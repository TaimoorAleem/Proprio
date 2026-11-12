"""Figures for blog post 0 ("From a YouTube tutorial to a real product").

Generates seven PNG (2400 px wide) + SVG figures from the raw MetaMotion data only.
No Proprio evaluation numbers appear here: the post is set at the project's start.

    python docs/blog/make_figures_post00.py [--raw data/raw/MetaMotion] [--out docs/blog/assets/post-00]

Deterministic choices (also printed): participant A for the one-participant figures
(first set per exercise by sorted file stem), participant E for the bench
heavy-vs-medium comparison (A has no medium bench set).
"""

from __future__ import annotations

import argparse
import logging
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch
from workout_ml import config
from workout_ml.data.preprocessor import DataLoader
from workout_ml.features.DataTransformation import LowPassFilter
from workout_ml.reps.counter import add_magnitudes, count_peaks
from workout_ml.sensors import to_csf

REPO = Path(__file__).resolve().parents[2]

# ---- design tokens (fixed across all figures) ---------------------------------------
INK = "#2b2b2b"
MUTED = "#6b6b6b"
GRID = "#e6e6e6"
AXIS_COLOR = {
    "x": "#0072B2",
    "y": "#D55E00",
    "z": "#009E73",
}  # Okabe-Ito blue / vermillion / green
EXERCISES = ["bench", "squat", "dead", "ohp", "row"]
EX_NAME = {
    "bench": "Bench press",
    "squat": "Squat",
    "dead": "Deadlift",
    "ohp": "Overhead press",
    "row": "Barbell row",
    "rest": "Rest",
}
EX_COLOR = {
    "bench": "#CC79A7",
    "squat": "#E69F00",
    "dead": "#56B4E9",
    "ohp": "#7B4EA3",
    "row": "#8C6D31",
    "rest": "#8a8a8a",
}
ACCENT = "#2A6F97"
LW = 1.6
DPI = 200

plt.rcParams.update(
    {
        "font.family": "DejaVu Sans",
        "font.size": 10,
        "text.color": INK,
        "axes.edgecolor": "#b5b5b5",
        "axes.labelcolor": INK,
        "axes.titlecolor": INK,
        "xtick.color": MUTED,
        "ytick.color": MUTED,
        "axes.linewidth": 0.8,
        "axes.grid": True,
        "grid.color": GRID,
        "grid.linewidth": 0.7,
        "axes.axisbelow": True,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "legend.frameon": False,
        "svg.fonttype": "none",
        "figure.facecolor": "white",
        "axes.facecolor": "white",
        "savefig.facecolor": "white",
    }
)

# Gravity table from docs/PROJECT.md section 6.1 (mean of per-set means, g).
GRAVITY_TABLE = {
    "bench": (-0.11, 0.94, -0.17),
    "dead": (0.04, -1.02, -0.02),
    "ohp": (-0.24, 0.92, -0.12),
    "rest": (0.52, -0.50, 0.30),
    "row": (0.05, -1.02, 0.04),
    "squat": (0.17, 0.66, 0.66),
}

CAPTIONS: list[tuple[str, str, str]] = []


def title(fig, main: str, sub: str | None = None, top: float = 0.97):
    fig.text(
        0.015, top, main, fontsize=15, fontweight="bold", ha="left", va="top", color=INK
    )
    if sub:
        fig.text(
            0.015,
            top - 0.065,
            sub,
            fontsize=10,
            ha="left",
            va="top",
            color=MUTED,
            wrap=True,
        )


def save(fig, out: Path, name: str, caption: str, alt: str):
    for ext in ("png", "svg"):
        fig.savefig(out / f"{name}.{ext}", dpi=DPI)
    plt.close(fig)
    CAPTIONS.append((name, caption, alt))


def pick_set(sets, prefix: str):
    """First set (sorted by stem) whose stem starts with prefix."""
    cands = sorted(
        (s for s in sets if s.meta["stem"].startswith(prefix)),
        key=lambda s: s.meta["stem"],
    )
    if not cands:
        raise SystemExit(f"no set with stem prefix {prefix!r}")
    return cands[0]


def style_ax(ax, exercise: str | None = None):
    ax.tick_params(length=3, labelsize=8.5)
    if exercise:
        ax.spines["top"].set_visible(True)
        ax.spines["top"].set_color(EX_COLOR[exercise])
        ax.spines["top"].set_linewidth(3)


def lowpass(sig, fs, cutoff, order):
    tmp = pd.DataFrame({"x": np.asarray(sig, dtype=float)})
    return (
        LowPassFilter()
        .low_pass_filter(tmp, "x", fs, cutoff, order=order)["x_lowpass"]
        .to_numpy()
    )


def resampled(set_, profile):
    """workout_ml's own 200 ms CSF-1 conversion; returns (frame, seconds-from-raw-start array)."""
    df = to_csf(set_, profile)
    raw_t0 = pd.to_datetime(set_.acc.t_ns[0], unit="ns")
    t = (df.index - raw_t0).total_seconds().to_numpy()
    return df, t


# ---- figures ------------------------------------------------------------------------
def fig01(sets, out):
    chosen = {ex: pick_set(sets, f"A-{ex}-heavy_") for ex in EXERCISES}
    fig, axes = plt.subplots(2, 5, figsize=(12, 6.2), sharey="row")
    for j, ex in enumerate(EXERCISES):
        s = chosen[ex]
        rows = ((s.acc, "Acceleration (g)"), (s.gyr, "Angular rate (deg/s)"))
        for i, (stream, ylabel) in enumerate(rows):
            ax = axes[i, j]
            t = (stream.t_ns - stream.t_ns[0]) / 1e9
            for k, a in enumerate("xyz"):
                ax.plot(
                    t, stream.xyz[:, k], color=AXIS_COLOR[a], lw=1.1, label=f"{a} axis"
                )
            style_ax(ax, ex if i == 0 else None)
            if j == 0:
                ax.set_ylabel(ylabel)
            if i == 0:
                ax.set_title(EX_NAME[ex], fontsize=10.5, fontweight="bold", pad=8)
            if i == 1:
                ax.set_xlabel("Time (s)")
    axes[0, 0].legend(
        loc="lower left", fontsize=8, ncol=3, handlelength=1.2, columnspacing=0.8
    )
    title(
        fig,
        "What one heavy set looks like",
        "Raw MetaMotion samples, participant A, one heavy set per exercise. "
        "Accelerometer 12.5 Hz, gyroscope 25 Hz.",
    )
    fig.tight_layout(rect=(0, 0, 1, 0.88))
    save(
        fig,
        out,
        "fig01-raw-signals",
        "Raw accelerometer and gyroscope traces of one heavy set of each of the five lifts "
        "(participant A).",
        "Two rows by five columns of line charts. Top row: acceleration in g on x, y and z for "
        "bench, squat, deadlift, overhead press and row. Bottom row: angular rate in degrees per "
        "second. Each lift has a different resting level and a different pattern of repeating "
        "bumps.",
    )
    return {ex: chosen[ex].meta["stem"][:24] for ex in EXERCISES}


def fig02(sets, out):
    per_set: dict[str, list] = {}
    for s in sets:
        per_set.setdefault(s.meta["label"], []).append(s.acc.xyz.mean(axis=0))
    means = {k: np.mean(v, axis=0) for k, v in per_set.items()}
    for k, expected in GRAVITY_TABLE.items():
        got = np.round(means[k], 2)
        assert np.allclose(got, expected, atol=0.011), (k, got, expected)
    order = ["bench", "ohp", "squat", "dead", "row", "rest"]
    fig, axes = plt.subplots(1, 6, figsize=(12, 4.6), sharey=True)
    for ax, ex in zip(axes, order):
        vals = means[ex]
        bars = ax.bar(
            ["x", "y", "z"], vals, color=[AXIS_COLOR[a] for a in "xyz"], width=0.65
        )
        for b, v in zip(bars, vals):
            va, off = ("bottom", 0.04) if v >= 0 else ("top", -0.04)
            ax.text(
                b.get_x() + b.get_width() / 2,
                v + off,
                f"{v:+.2f}",
                ha="center",
                va=va,
                fontsize=8.5,
                color=INK,
            )
        ax.axhline(0, color=INK, lw=1.4, zorder=3)
        ax.set_ylim(-1.35, 1.3)
        ax.set_title(EX_NAME[ex], fontsize=10, fontweight="bold", pad=8)
        ax.grid(axis="x", visible=False)
        style_ax(ax, ex)
        ax.tick_params(axis="x", labelsize=9.5)
    axes[0].set_ylabel("Mean acceleration (g)")
    fig.supxlabel("Sensor axis", fontsize=10, color=INK)
    title(
        fig,
        "Gravity tells you which way your hand points",
        "Mean acceleration per axis, averaged over all sets of each exercise. "
        "Hand above the wrist (bench) gives +y, hand below (deadlift) gives -y.",
    )
    fig.tight_layout(rect=(0, 0, 1, 0.86))
    save(
        fig,
        out,
        "fig02-gravity-direction",
        "Average acceleration on each axis for each exercise: gravity's reading differs by lift.",
        "Six small bar charts, one per exercise, each with three bars for the x, y and z axis in "
        "g. Bench and overhead press have y near +0.9 g, deadlift and row have y near -1.0 g, "
        "squat splits gravity between y and z, rest is in between.",
    )
    return {
        k: tuple(float(x) for x in np.round(v, 2)) for k, v in sorted(means.items())
    }


def fig03(sets, out):
    s = pick_set(sets, "A-bench-heavy_")
    t_a = (s.acc.t_ns - s.acc.t_ns[0]) / 1e9
    t_g = (s.gyr.t_ns - s.acc.t_ns[0]) / 1e9
    # busiest 2 s window of the gyroscope y axis (deterministic)
    best, start = -1.0, 0.0
    for st in np.arange(2.0, t_g[-1] - 4.0, 0.2):
        m = (t_g >= st) & (t_g < st + 2.0)
        v = s.gyr.xyz[m, 1].std() if m.sum() > 5 else 0
        if v > best:
            best, start = v, st
    w = (start, start + 2.0)
    fig, axes = plt.subplots(2, 1, figsize=(12, 5.6), sharex=True)
    spec = [
        (
            axes[0],
            t_a,
            s.acc.xyz[:, 1],
            "Accelerometer y (g)",
            "Accelerometer, 12.5 Hz",
            80,
        ),
        (axes[1], t_g, s.gyr.xyz[:, 1], "Gyroscope y (deg/s)", "Gyroscope, 25 Hz", 40),
    ]
    for ax, t, y, label, note, dt_ms in spec:
        m = (t >= w[0] - 0.05) & (t <= w[1] + 0.05)
        idx = np.flatnonzero(m)
        ym = y[m]
        span = np.ptp(ym)
        ax.plot(t[m], ym, color=AXIS_COLOR["y"], lw=0.9, alpha=0.35)
        ax.vlines(t[m], ym.min() - 0.25 * span, ym, color=GRID, lw=0.8, zorder=1)
        ax.plot(t[m], ym, "o", color=AXIS_COLOR["y"], ms=5.5, zorder=3)
        ax.set_xlim(*w)
        ax.set_ylabel(label)
        ax.set_title(
            f"{note}: one snapshot every {dt_ms} ms",
            loc="left",
            fontsize=10,
            color=INK,
            pad=6,
        )
        style_ax(ax)
        i0 = idx[len(idx) // 2]
        ytop = ym.max() + 0.15 * span
        ax.annotate(
            "",
            xy=(t[i0 + 1], ytop),
            xytext=(t[i0], ytop),
            arrowprops={
                "arrowstyle": "<->",
                "color": MUTED,
                "lw": 1.0,
                "shrinkA": 0,
                "shrinkB": 0,
            },
        )
        ax.text(
            (t[i0] + t[i0 + 1]) / 2,
            ytop + 0.03 * span,
            f"{dt_ms} ms",
            ha="center",
            va="bottom",
            fontsize=8.5,
            color=MUTED,
        )
        ax.set_ylim(ym.min() - 0.3 * span, ytop + 0.2 * span)
    axes[1].set_xlabel("Time since start of set (s)")
    title(
        fig,
        "A sensor takes snapshots",
        "Two seconds of one bench-press set (participant A). Each dot is one sample. "
        f"Window: {w[0]:.1f} to {w[1]:.1f} s into the set.",
    )
    fig.tight_layout(rect=(0, 0, 1, 0.87))
    save(
        fig,
        out,
        "fig03-sampling",
        "The accelerometer takes 12.5 samples per second and the gyroscope 25: two seconds of "
        "one set as dots.",
        "Two stacked dot plots over the same two seconds. The top panel, accelerometer y, has a "
        "dot every 80 milliseconds. The bottom panel, gyroscope y, has a dot every 40 "
        "milliseconds, twice as dense.",
    )
    return f"{s.meta['stem'][:24]}, window {w[0]:.1f}-{w[1]:.1f} s"


def box(
    ax, x, y, w, h, text, fc="#f4f4f4", ec="#9a9a9a", fs=10, bold=False, left=False
):
    ax.add_patch(
        FancyBboxPatch(
            (x, y),
            w,
            h,
            boxstyle="round,pad=0.0,rounding_size=0.12",
            fc=fc,
            ec=ec,
            lw=1.2,
        )
    )
    tx = x + 0.25 if left else x + w / 2
    ax.text(
        tx,
        y + h / 2,
        text,
        ha="left" if left else "center",
        va="center",
        fontsize=fs,
        color=INK,
        fontweight="bold" if bold else "normal",
        linespacing=1.45,
    )


def arrow(ax, p0, p1, color="#7a7a7a"):
    ax.add_patch(
        FancyArrowPatch(
            p0,
            p1,
            arrowstyle="-|>",
            mutation_scale=14,
            lw=1.4,
            color=color,
            shrinkA=0,
            shrinkB=0,
        )
    )


def fig04(out):
    fig, ax = plt.subplots(figsize=(12, 7.6))
    ax.set_xlim(0, 12)
    ax.set_ylim(0, 7.6)
    ax.axis("off")
    title(
        fig,
        "The tutorial pipeline, end to end",
        "From raw sensor files to a predicted exercise and a rep count.",
        top=0.985,
    )
    bw, bx = 6.2, 0.3
    cx = bx + bw / 2
    box(
        ax,
        bx,
        6.45,
        bw,
        0.8,
        "Raw CSV files\none per sensor per set, labels in the file name",
        fc="#fff",
    )
    box(ax, bx, 5.35, bw, 0.8, "Merge and resample\nto 200 ms steps (5 per second)")
    box(
        ax,
        bx,
        4.25,
        bw,
        0.8,
        "Outlier handling\nIQR, Chauvenet's criterion, local outlier factor",
    )
    feat = (
        "Feature engineering\n"
        "-  low-pass filter (smooth away jitter)\n"
        "-  PCA (combine correlated signals)\n"
        "-  magnitude r = sqrt(x² + y² + z²)\n"
        "-  rolling mean and std over ~4 s windows\n"
        "-  frequency features (Fourier transform, ~4 s)\n"
        "-  cluster membership (k-means)"
    )
    box(ax, bx, 1.75, bw, 2.1, feat, left=True, fs=9.5)
    box(
        ax,
        bx,
        0.15,
        bw,
        0.95,
        "Five models\nneural network, random forest, k-nearest neighbours,\n"
        "decision tree, naive Bayes",
        fs=9.5,
    )
    arrow(ax, (cx, 6.45), (cx, 6.15))
    arrow(ax, (cx, 5.35), (cx, 5.05))
    arrow(ax, (cx, 4.25), (cx, 3.95))
    arrow(ax, (cx, 1.75), (cx, 1.1))
    box(
        ax,
        8.0,
        0.27,
        3.7,
        0.8,
        "Predicted exercise\nper 200 ms step",
        fc="#e3edf3",
        ec=ACCENT,
        bold=True,
    )
    arrow(ax, (bx + bw, 0.62), (8.0, 0.67), color=ACCENT)
    box(ax, 8.0, 4.95, 3.7, 0.9, "Rep counter (no learning)\nsmooth the signal heavily")
    box(ax, 8.0, 3.85, 3.7, 0.7, "Count the peaks")
    box(ax, 8.0, 2.75, 3.7, 0.6, "Number of reps", fc="#e3edf3", ec=ACCENT, bold=True)
    # the tutorial counts reps on the resampled data, before outlier handling and features
    arrow(ax, (bx + bw, 5.75), (8.0, 5.4))
    arrow(ax, (9.85, 4.95), (9.85, 4.55))
    arrow(ax, (9.85, 3.85), (9.85, 3.35), color=ACCENT)
    fig.subplots_adjust(left=0.01, right=0.99, top=0.88, bottom=0.01)
    save(
        fig,
        out,
        "fig04-pipeline",
        "The tutorial's pipeline: files to resampled data, cleaned, turned into features and fed to "
        "five models; a separate, non-learned counter finds the reps.",
        "Flow diagram. Left column top to bottom: raw CSV files, merge and resample to 200 "
        "milliseconds, outlier handling, feature engineering with a list of six feature types, "
        "five models, then predicted exercise. A branch from merge and resample goes to a rep "
        "counter that smooths the signal, then counts peaks to give a number of reps.",
    )


def fig05(sets, profile, out):
    s = pick_set(sets, "A-bench-heavy_")
    df, t5 = resampled(s, profile)
    sig5 = df["acc_y"].to_numpy()
    cutoff, order = config.FEATURE_CONFIG["lowpass_cutoff"], 5
    filt = lowpass(sig5, config.FEATURE_CONFIG["sampling_frequency"], cutoff, order)
    t_raw = (s.acc.t_ns - s.acc.t_ns[0]) / 1e9
    lo = 4.0
    hi = min(lo + 10.0, t5[-1])
    fig, ax = plt.subplots(figsize=(12, 5.0))
    m = (t_raw >= lo) & (t_raw <= hi)
    ax.plot(
        t_raw[m],
        s.acc.xyz[m, 1],
        "o",
        color="#9a9a9a",
        ms=4.5,
        zorder=2,
        label="Raw samples (12.5 Hz)",
    )
    m5 = (t5 >= lo) & (t5 <= hi)
    ax.plot(
        t5[m5],
        sig5[m5],
        "-",
        color=INK,
        lw=1.2,
        zorder=3,
        label="Resampled, 200 ms steps (5 Hz)",
    )
    ax.plot(
        t5[m5],
        filt[m5],
        "-",
        color=AXIS_COLOR["y"],
        lw=2.6,
        zorder=4,
        label=f"Low-pass filtered ({cutoff} Hz Butterworth)",
    )
    ax.set_xlim(lo, hi)
    ax.set_xlabel("Time since start of set (s)")
    ax.set_ylabel("Acceleration y (g)")
    style_ax(ax)
    ax.legend(loc="lower right", fontsize=9.5)
    title(
        fig,
        "Smoothing removes jitter",
        f"Bench press, participant A, accelerometer y. Butterworth low-pass, cutoff {cutoff} Hz, "
        f"order {order}, zero-phase, applied at 5 Hz (the setting used for features).",
    )
    fig.tight_layout(rect=(0, 0, 1, 0.88))
    save(
        fig,
        out,
        "fig05-resample-filter",
        f"Raw samples, the 200 ms resampled series and the {cutoff} Hz low-pass version of one "
        "bench set.",
        "A line chart over eight seconds of acceleration y. Grey dots are raw samples, a thin dark "
        "line is the resampled signal, and a thick orange line is the smoothed signal that follows "
        "the slow rep pattern without the small wiggles.",
    )
    return (
        f"{s.meta['stem'][:24]}, {lo:.0f}-{hi:.0f} s, cutoff {cutoff} Hz, order {order}"
    )


def fig06(sets, profile, out):
    ex = "bench"
    cfg = config.REP_CONFIG
    col, cutoff = cfg["exercises"][ex]["column"], cfg["exercises"][ex]["cutoff"]
    fig, axes = plt.subplots(2, 1, figsize=(12, 6.6), sharey=True)
    used = []
    for ax, (cat, prefix) in zip(
        axes, (("heavy", "E-bench-heavy_"), ("medium", "E-bench-medium_"))
    ):
        s = pick_set(sets, prefix)
        df, _ = resampled(s, profile)
        sig = add_magnitudes(df)[col].to_numpy()
        t = np.arange(len(sig)) * 0.2
        filt = lowpass(sig, cfg["sampling_frequency"], cutoff, cfg["order"])
        pk = count_peaks(sig, cfg["sampling_frequency"], cutoff, cfg["order"])
        ax.plot(
            t,
            filt,
            color=INK,
            lw=LW,
            label=f"Low-pass filtered acceleration magnitude ({cutoff} Hz)",
        )
        ax.plot(
            t[pk],
            filt[pk],
            "o",
            color=EX_COLOR[ex],
            ms=9,
            mec="white",
            mew=1.2,
            zorder=4,
            label="Detected peak",
        )
        for n, i in enumerate(pk, start=1):
            ax.annotate(
                str(n),
                (t[i], filt[i]),
                xytext=(0, 9),
                textcoords="offset points",
                ha="center",
                fontsize=9,
                color=INK,
            )
        exp = cfg["expected_reps"][cat]
        ax.set_title(
            f"{cat.capitalize()} set: expected {exp} reps, {len(pk)} peaks marked",
            loc="left",
            fontsize=10.5,
            fontweight="bold",
            pad=8,
        )
        ax.set_ylabel("Acceleration magnitude (g)")
        ax.margins(y=0.3)
        style_ax(ax, ex)
        used.append(f"{s.meta['stem'][:24]}: expected {exp}, peaks {len(pk)}")
    axes[1].set_xlabel("Time since start of set (s)")
    axes[0].legend(loc="upper right", fontsize=9, ncol=2)
    title(
        fig,
        "Counting reps by counting peaks",
        f"Bench press, participant E. Butterworth low-pass at {cutoff} Hz (order {cfg['order']}), "
        "local maxima marked.\n'Expected' reps are inferred from the heavy/medium label, not counted by hand.",
    )
    fig.tight_layout(rect=(0, 0, 1, 0.84))
    save(
        fig,
        out,
        "fig06-rep-peaks",
        "A heavily smoothed signal with each local maximum marked and numbered: the tutorial's "
        "rep counter.",
        "Two stacked line charts of smoothed acceleration magnitude for a heavy and a medium "
        "bench set, each with numbered pink dots on the peaks.",
    )
    return used


def fig07(out):
    fig, ax = plt.subplots(figsize=(12, 5.4))
    labels = ["Exercise diary\n(self-report)", "Hidden accelerometer\n(sensor count)"]
    vals = [220, 176]
    bars = ax.bar(labels, vals, color=["#b8b8b8", ACCENT], width=0.4)
    for b, v in zip(bars, vals):
        ax.text(
            b.get_x() + b.get_width() / 2,
            v + 5,
            str(v),
            ha="center",
            va="bottom",
            fontsize=16,
            fontweight="bold",
            color=INK,
        )
    ax.set_ylim(0, 255)
    ax.set_ylabel("Median exercises recorded")
    ax.grid(axis="x", visible=False)
    style_ax(ax)
    ax.tick_params(axis="x", labelsize=10.5)
    title(
        fig,
        "Diaries overstate what people actually do",
        "Median home exercises recorded: the diary versus an accelerometer hidden in an ankle weight.",
    )
    fig.text(
        0.015,
        0.015,
        "Nicolson et al., JOSPT 2018 (accelerometer hidden in an ankle weight)",
        fontsize=8.5,
        color=MUTED,
        ha="left",
        va="bottom",
    )
    fig.tight_layout(rect=(0, 0.04, 1, 0.86))
    save(
        fig,
        out,
        "fig07-diary-vs-sensor",
        "People with knee pain logged a median of 220 exercises in a diary; a hidden sensor "
        "counted 176 (Nicolson et al., 2018).",
        "Two bars. The exercise diary bar is labelled 220, the hidden accelerometer bar is "
        "labelled 176.",
    )


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--raw", default=str(REPO / "data" / "raw" / "MetaMotion"))
    ap.add_argument("--out", default=str(REPO / "docs" / "blog" / "assets" / "post-00"))
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    logging.disable(logging.WARNING)

    loader = DataLoader(args.raw)
    sets = loader.load_sets()
    print(
        f"Loaded {len(sets)} MetaMotion sets from {args.raw} (duplicate *_1.4.41.csv ignored)"
    )

    print("fig01 sets:", fig01(sets, out))
    print("fig02 mean acc per exercise (g):", fig02(sets, out))
    print("fig03:", fig03(sets, out))
    fig04(out)
    print("fig05:", fig05(sets, loader.profile, out))
    print("fig06:", fig06(sets, loader.profile, out))
    fig07(out)

    print("\nCaptions and alt text")
    for name, cap, alt in CAPTIONS:
        print(f"{name}\n  caption: {cap}\n  alt: {alt}")
    print(f"\nWrote {len(CAPTIONS)} figures (PNG + SVG) to {out}")


if __name__ == "__main__":
    main()
