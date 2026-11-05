"""Kabsch rotation fit (spec section 4): recovery, conditioning warning, mirror flag, profile."""

import numpy as np
import pandas as pd
import pytest
from scipy.spatial.transform import Rotation
from watch_synth import GRAVITY, known_rotation

from workout_ml.sensors import load_profile
from workout_ml.watch import metrics as M
from workout_ml.watch.calibrate import (
    FITTED_PROFILE_ID,
    PRIOR_PROFILE_ID,
    RESIDUAL_SPREAD_FACTOR,
    between_participant_spread,
    fit_rotation,
    fitted_profile,
    identity_profile,
    kabsch,
    residual_threshold,
    residual_within_threshold,
    write_fitted_profile,
)
from workout_ml.watch.evaluate import calibration_status


def _frame(vectors: dict, rng, noise=0.0, n_sets=3, rows=30) -> pd.DataFrame:
    parts, sid = [], 0
    for label, v in vectors.items():
        for _ in range(n_sets):
            sid += 1
            acc = np.asarray(v) + rng.normal(0, noise, (rows, 3))
            df = pd.DataFrame(acc, columns=["acc_x", "acc_y", "acc_z"])
            gyr = rng.normal(0, 10, (rows, 3)) * (1 + len(label))
            for i, c in enumerate(["gyr_x", "gyr_y", "gyr_z"]):
                df[c] = gyr[:, i]
            df["label"], df["set"], df["kind"] = label, sid, "rest" if label == "rest" else "set"
            parts.append(df)
    return pd.concat(parts, ignore_index=True)


def _angle(Ra, Rb):
    return np.degrees(Rotation.from_matrix(Ra.T @ Rb).magnitude())


def test_kabsch_recovers_known_rotation_with_noise():
    rng = np.random.default_rng(0)
    R = known_rotation(55.0)
    mm = _frame(GRAVITY, rng, noise=0.02)
    watch = _frame({k: R.T @ np.array(v) for k, v in GRAVITY.items()}, rng, noise=0.02)
    fit = fit_rotation(mm, watch, prior_R=np.eye(3))
    assert _angle(fit.R, R) < 1.0
    assert fit.residual_rms_g < 0.03
    assert np.linalg.det(fit.R) == pytest.approx(1.0)
    assert not fit.flags["mirror_preferred"]
    assert not any("poorly conditioned" in w for w in fit.warnings)
    assert fit.exercises == sorted(k for k in GRAVITY if k != "rest")  # lifts only
    assert fit.weights["bench"] == 3.0


def test_near_collinear_gravity_warns():
    rng = np.random.default_rng(1)
    vecs = {
        "bench": (0.01, 0.95, 0.0),
        "ohp": (0.0, 0.92, 0.01),
        "dead": (0.01, -1.0, 0.0),
        "row": (0.0, -1.02, -0.01),
    }
    R = known_rotation(30.0)
    mm = _frame(vecs, rng)
    watch = _frame({k: R.T @ np.array(v) for k, v in vecs.items()}, rng)
    fit = fit_rotation(mm, watch, prior_R=np.eye(3))
    assert any("poorly conditioned" in w for w in fit.warnings)
    assert fit.lever_min_g < 0.15


def test_mirrored_truth_flags_det_minus_one():
    rng = np.random.default_rng(2)
    T = known_rotation(20.0) @ np.diag([1.0, 1.0, -1.0])  # det = -1: a mirrored watch
    assert np.linalg.det(T) < 0
    mm = _frame(GRAVITY, rng, noise=0.005)
    watch = _frame({k: T.T @ np.array(v) for k, v in GRAVITY.items()}, rng, noise=0.005)
    fit = fit_rotation(mm, watch, prior_R=np.eye(3))
    assert fit.flags["mirror_preferred"]
    assert fit.unconstrained["det"] < 0
    assert fit.unconstrained["residual_rms_g"] < fit.residual_rms_g
    assert any("ESCALATE" in w for w in fit.warnings)
    assert np.linalg.det(fit.R) == pytest.approx(1.0)


def test_fit_needs_two_labels():
    rng = np.random.default_rng(3)
    mm = _frame({"bench": GRAVITY["bench"]}, rng)
    with pytest.raises(ValueError, match="at least 2"):
        fit_rotation(mm, mm.copy(), prior_R=np.eye(3))


def test_kabsch_weights_pull_towards_heavy_vectors():
    A = np.array([[1.0, 0, 0], [0, 1, 0], [0, 0, 1], [1, 1, 0]])
    R_true = known_rotation(25.0)
    B = A @ R_true.T
    B[3] += [0.1, -0.1, 0.0]  # one inconsistent pair
    R_w, _, _ = kabsch(A, B, [1, 1, 1, 100])
    R_u, _, _ = kabsch(A, B, [1, 1, 1, 0.01])
    err = lambda R: np.linalg.norm(A[3] @ R.T - B[3])  # noqa: E731
    assert err(R_w) < err(R_u)


def test_profile_roundtrip(tmp_path):
    rng = np.random.default_rng(4)
    R = known_rotation(35.0)
    mm = _frame(GRAVITY, rng, noise=0.01)
    watch = _frame({k: R.T @ np.array(v) for k, v in GRAVITY.items()}, rng, noise=0.01)
    fit = fit_rotation(mm, watch)
    path = write_fitted_profile(fit, ["sess-1"], path=tmp_path / f"{FITTED_PROFILE_ID}.yaml")
    loaded = load_profile(path)
    assert loaded.rotation_status == "fitted"
    assert loaded.device["model"] == "SM-R920"
    assert loaded.calibration["fit_sessions"] == ["sess-1"]
    assert loaded.calibration["residual_g"] == pytest.approx(fit.residual_rms_g, abs=1e-4)
    np.testing.assert_allclose(loaded.rotation, fit.R, atol=1e-12)
    assert loaded.transform_id != load_profile("galaxy-watch-prior-v0").transform_id
    mem = fitted_profile(fit, ["sess-1"])
    assert mem.transform_id == loaded.transform_id  # same numbers -> same transform id
    np.testing.assert_allclose(identity_profile().rotation, np.eye(3))


def test_fit_ignores_rest_and_other():
    rng = np.random.default_rng(5)
    R = known_rotation(40.0)
    lifts = {k: v for k, v in GRAVITY.items() if k != "rest"}
    mm = _frame(lifts, rng, noise=0.01)
    watch = _frame({k: R.T @ np.array(v) for k, v in lifts.items()}, rng, noise=0.01)
    base = fit_rotation(mm, watch, prior_R=np.eye(3))
    # wildly off rest/other segments (and a rest label on both sides) must not move R
    bad = _frame({"rest": (0.9, 0.9, -0.9), "other": (-0.8, 0.1, 0.9)}, rng, n_sets=20)
    bad.loc[bad["label"] == "other", "kind"] = "other"
    mm_bad = _frame({"rest": (-0.9, 0.2, 0.7)}, rng, n_sets=20)
    fit = fit_rotation(pd.concat([mm, mm_bad]), pd.concat([watch, bad]), prior_R=np.eye(3))
    np.testing.assert_allclose(fit.R, base.R, atol=1e-9)
    assert "rest" not in fit.exercises and "other" not in fit.exercises
    assert fit.exercises == sorted(lifts)


def _mm_participants(offsets):
    rows = []
    for label, base in {"bench": (0.0, 1.0, 0.0), "squat": (0.0, 0.0, 1.0)}.items():
        for p, off in offsets.items():
            rows.append(
                {
                    "label": label,
                    "participant": p,
                    "acc_x": base[0] + off[0],
                    "acc_y": base[1] + off[1],
                    "acc_z": base[2] + off[2],
                }
            )
    return pd.DataFrame(rows)


def test_between_participant_spread_known_value():
    # two participants offset +-0.1 g along x: each deviates 0.1 g from the pooled mean
    df = _mm_participants({"A": (0.1, 0, 0), "B": (-0.1, 0, 0)})
    assert between_participant_spread(df) == pytest.approx(0.1)
    # rest rows are ignored
    rest = df.assign(label="rest", acc_x=df["acc_x"] * 50)
    assert between_participant_spread(pd.concat([df, rest])) == pytest.approx(0.1)
    assert residual_threshold(0.1) == pytest.approx(RESIDUAL_SPREAD_FACTOR * 0.1)


def test_residual_gate_around_threshold():
    thr = residual_threshold(0.158)
    assert residual_within_threshold(thr - 1e-6, thr) is True
    assert residual_within_threshold(thr, thr) is True
    assert residual_within_threshold(thr + 1e-6, thr) is False
    calib = {"sessions": [{}], "passed": True}
    assert calibration_status(calib, thr - 0.01, thr) == M.PASS
    assert calibration_status(calib, thr + 0.01, thr) == M.FAIL
    assert calibration_status({**calib, "passed": False}, 0.0, thr) == M.FAIL
    assert calibration_status(None, 0.1, thr) == M.NA


def test_fit_reports_spread_and_threshold():
    rng = np.random.default_rng(6)
    lifts = {k: v for k, v in GRAVITY.items() if k != "rest"}
    mm = _frame(lifts, rng, noise=0.02)
    mm["participant"] = np.where(mm["set"] % 2 == 0, "A", "B")
    watch = _frame(lifts, rng, noise=0.02)
    fit = fit_rotation(mm, watch, prior_R=np.eye(3))
    assert fit.mm_spread_g is not None
    assert fit.residual_threshold_g == pytest.approx(RESIDUAL_SPREAD_FACTOR * fit.mm_spread_g)
    s = fit.summary()
    assert s["residual_gate_pass"] is True and s["mm_spread_g"] == fit.mm_spread_g


def test_prior_v1_profile():
    p = load_profile("galaxy-watch-prior-v1")
    assert p.rotation_status == "prior"
    assert np.linalg.det(p.rotation) == pytest.approx(1.0)
    np.testing.assert_allclose(p.rotation @ np.array([1.0, 0, 0]), [0, 1, 0])  # watch +x -> +y
    np.testing.assert_allclose(p.rotation @ np.array([0, 0, 1.0]), [0, 0, 1])
    assert PRIOR_PROFILE_ID == "galaxy-watch-prior-v1"
    assert p.transform_id != load_profile("galaxy-watch-prior-v0").transform_id
