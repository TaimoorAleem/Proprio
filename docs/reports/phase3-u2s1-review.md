# Phase 3 · U2 session 1 — orchestrator review (in-sample first look)

> In-sample: R is fitted and scored on the same session, so every number is optimistic. The verdict needs U2 session 2 (`watch-val-v1`). Generated reports: [`phase3-u2s1-insample.md`](phase3-u2s1-insample.md) (method as specified) and [`phase3-u2s1-insample-lifts-only.md`](phase3-u2s1-insample-lifts-only.md) (R fitted on lifts only, M1/M4).

## The session
- `918aa36e`, 2026-10-26, 73 min, 49.88 Hz, **0 gaps**, drift 0.03 ms. 15 sets: bench 4, squat 3, row 3, OHP 3, deadlift 2; 13 `other` periods (used for rest between sets) plus 17 implicit rest gaps.
- Deviations from `U2_CHECKLIST.md`: 15 sets instead of 20; order bench → squat → row → OHP → deadlift; `load_kg` holds **pounds** (owner's note on set 2). Deadlift set 26 is 9.3 s for 6 reps (marks likely late). None of this affects classification; loads aren't model inputs.
- Not uploaded by the app (see "Side finding"); pulled over adb, packaged exactly like the phone's export (watch files byte-identical + `labels.json`), DVC-tracked, assigned to `watch-fit-v1`.

## Results
| | prior profile | fitted R (as specified: lifts + rest) | fitted R (lifts only) |
|---|---|---|---|
| Kabsch residual (gate < 0.15 g) | — | 0.337 g ✗ | **0.148 g** ✓ |
| M1 set accuracy (n=15, Wilson 95%) | 0.27 [0.11, 0.52] | 0.60 [0.36, 0.80] | **0.73 [0.48, 0.89]** |
| M4 (CNN) set accuracy | 0.27 | 0.60 | 0.67 |
| Rejection of rest/other (M1) | 0.70 | 0.90 ✓ | ✓ |
| Rep counter MAE | 4.6 | 4.5 | — (fails, as on MetaMotion) |

Per class (M1, lifts-only R, window recall): bench 1.00, squat 0.99, deadlift 0.92, row 0.63, **OHP 0.18** (all 3 OHP sets voted bench).

## What the data says
1. **The prior profile was wrong by ~176°.** The fitted R is a clean ~90° turn about the screen normal: the watch's **+x (3 o'clock, buttons) points down the forearm**, where the MetaMotion's +y does. That is exactly how a watch sits on a left wrist with the buttons toward the hand. The prior assumed +y (12 o'clock). Left wrist is confirmed (det +1, the unconstrained fit is also a proper rotation); the report's "left-wrist suspect" warning is a false alarm caused by item 2.
2. **Rest doesn't belong in the rotation fit (spec flaw, mine).** MetaMotion has rest from only 2 of 5 people and it's a narrow posture; the watch's rest gaps are walking and re-racking (|mean a| 0.55–1.0 g, i.e. not static). With 17 rest segments outweighing 15 lifts, rest dragged the fit to a 0.34 g residual. Lifts only: 0.148 g.
3. **The 0.15 g calibration gate sits at the noise floor.** MetaMotion's own participants differ from their pooled per-exercise gravity by **0.158 g RMS** (squat up to 0.48 g for one person). A single watch wearer can't be expected to match the pooled mean more closely than the training participants match each other.
4. **What transfers and what doesn't.** Squat, bench and deadlift transfer almost perfectly once R is right. OHP → bench is the failure: in MetaMotion both have nearly the same gravity vector (forearm vertical), so the models separate them by motion, and that cue doesn't survive the device change. Row is partial. Roll about the forearm is poorly determined because every lift's gravity lies near ±y (smallest singular value 0.033), so a residual roll error is likely part of it.
5. **Domain classifier AUC 0.88** (> 0.8), driven by temporal `acc_y`/`gyr` features → decision tree step 2 applies.
6. **Rep counter fails** (MAE 4.5, 0 % exact), consistent with it already failing on MetaMotion; its rework was planned.

## Recommendations (gate/spec changes need the owner's approval)
- **A. Fit R on lifts only** (exclude rest and other). Evaluation-method fix, motivated by session 1 and to be judged on session 2. → D-030.
- **B. Make the Kabsch gate relative:** pass if the residual is ≤ 1.5 × MetaMotion's between-participant RMS (≈ 0.24 g), keep det = +1 and the gyro check. Absolute 0.15 g is unreachable in principle for some people. *(PROJECT.md §6.5 change: owner decision.)*
- **C. Replace the prior with the physical left-wrist rotation** (90° about z, +x distal), so a new watch works before any fit.
- **D. Next model work (decision tree step 2):** OHP vs bench and row. Candidates: wider roll augmentation about the forearm axis, and orientation-robust features (gravity-projected vertical acceleration). Must pass at zero rotation on MetaMotion LOPO. Step 3 (mixing watch data) needs ≥ 30 watch sets; we have 15.
- **E. Session 2 runs the full checklist** (20 sets, squat-first order is fine either way), loads in kg or a note, and ends each set promptly.

## Side finding: the upload never left the phone
The phone kept the session in `UPLOADING` (4 attempts, no session row on the server). `UploadCore` sets `UPLOADING` before calling the server and leaves it there on a retryable error, without recording the error, so the UI shows "uploading" during backoff and the cause is invisible. Fix: show `WAITING_TO_RETRY` with the last error, and don't cap retries at 6 (a gym session should wait for the PC indefinitely).
