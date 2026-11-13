# Blog series plan

> Plan only; no posts are written yet. Source material: [`BUILD_JOURNAL.md`](BUILD_JOURNAL.md) (the chronological record), [`DECISIONS.md`](DECISIONS.md), [`docs/reports/`](reports/). The series is published after launch.

## The series

**Working title:** "Building Proprio: an intelligent smartwatch-based fitness tracker".

**Audience:** beginner ML students and early-career engineers. Each post teaches one idea through a real problem, with real numbers, including the failures.

**Format per post:**
- About 1,500–2,500 words.
- A hook in the first paragraph.
- 2–4 "idea in plain words" boxes, reused from the journal.
- One or two charts or diagrams.
- A "what I'd tell my past self" ending.
- Links into the repo for evidence: one link per claim, to the exact report or decision.

## Posts

| # | Working title | Lesson for the reader | Journal source | Visuals | Status |
|---|---|---|---|---|---|
| 0 | From a tutorial notebook to a product | Where the baseline came from (see [Origins](#origins-of-the-baseline)); how to audit an old project; what an accelerometer and gyroscope measure | Part 1 | Sensor axes on a wrist; gravity during bench vs deadlift | Journal draft ready; needs the origins section |
| 1 | **The 99.9% that wasn't real** (flagship) | Data leakage, why random splits over windows lie, leave-one-participant-out | Part 2 | Bar chart 99.9% → 96.3%; LOPO fold diagram; overlapping-windows diagram; "three numbers for one question" (see Origins) | Journal draft ready |
| 2 | Your model has never seen this sensor | Domain shift; units, sample rates, aliasing; one canonical format | Part 3, first half | Old sensor vs Galaxy Watch table; resampling diagram | Journal draft ready |
| 3 | 2° of misalignment broke my model | Rotation sensitivity, rotation matrices, augmentation by random rotation | Part 3, second half | Accuracy vs rotation angle, before and after augmentation | Journal draft ready; chart needed |
| 4 | Design the data collection before you have data | Sessions and segments, immutable raw data, cross-language conformance | Part 4 | Session timeline with sets, rest and "other" | Journal draft ready |
| 5 | What only real hardware could tell me | Samsung Freecess, package ids, why fakes can't test platform rules, physics as a test | Parts 5 + 6 | Recording gaps before/after; calibration poses | Journal draft ready |
| 6 | Decide how you'll judge before you see results | Gates, confidence intervals, in-sample vs held-out, the rejection threshold | Part 7 | Wilson intervals at n = 15; fit vs validation diagram | Journal draft ready |
| 7 | Deep learning didn't win, and that's fine | 1D-CNN vs KNN, early stopping on a held-out participant, ONNX, fair comparisons | Part 8 | Model comparison with fold spread | Journal draft ready |
| 8 | From scripts to services | Contract-first APIs, failure-first messaging, idempotency, presigned uploads | Part 9 | Architecture diagram; retry and dead-letter flow | Journal draft ready |
| 9 | First gym session: fix the data, not the model | Debugging the stage before the model; noise floors; questioning your own gates | Part 10 | 27 / 60 / 73% bars (in-sample); watch vs training gravity; rep-signal plot | Journal draft ready (in-sample, labelled) |
| 10 | **The verdict** | Held-out evaluation; do the session-1 changes generalise? | Not written | Results with intervals | **Blocked:** session-1 redo and session 2 |

**Later posts** (written as the work happens): OHP vs bench, the rep-counter rework, the Granite coach (RAG over training history, with its evaluation), shipping to IBM Cloud, a retrospective.

## Order and timing
- **Lead with post 1.** "The 99.9% that wasn't real" is the strongest hook and the most useful lesson; post 0 is background it links to. Then 0, 2, 3 and onward in order.
- **Weekly after launch.** Posts 0–9 and 11 can be finished before launch; post 10 slots in once session 2 exists.
- **Where:** one canonical home for full posts, with a short social summary per post linking back. Every post links to the public repo and the report behind its numbers.

## Origins of the baseline

Research done 2026-11-06 from the owner's reference playlist. These facts are needed for accurate credit and for post 0 and post 1.

### Sources
- **Video series:** Dave Ebbelaar, "Full Machine Learning Project — Coding a Fitness Tracker with Python", 8 videos, about 7 hours in total: [playlist](https://youtube.com/playlist?list=PL-Y17yukoyy0sT2hoSQxn1TdV0J7-MX4K). The parts are:
  1. Introduction
  2. Processing raw data
  3. Visualization
  4. Outliers
  5. Feature engineering: 5a (low-pass filter and PCA) and 5b (Fourier and clustering)
  6. Predictive modelling
  7. Counting repetitions
- **Per-video source material** (Craft pages linked in each description, e.g. [part 1](https://docs.datalumina.io/xLAtq6PNUsMcfG)):
  - the dataset (`MetaMotion.zip`);
  - the starter scripts: `make_dataset.py`, `visualize.py`, `remove_outliers.py`, `build_features.py`, `train_model.py`, `count_repetitions.py`;
  - the ML4QS helper modules (`DataTransformation.py`, `TemporalAbstraction.py`, `FrequencyAbstraction.py`, `LearningAlgorithms.py`);
  - the original report (below).
- **Original report:** Dave Ebbelaar, *Exploring the Possibilities of Context-Aware Applications for Strength Training*, Mini Master Project, Vrije Universiteit Amsterdam (Machine Learning for the Quantified Self course, Mark Hoogendoorn). It is linked from the part 2 material.
- **Method and code lineage:**
  - Hoogendoorn & Funk, *Machine Learning for the Quantified Self* (Springer, 2018);
  - [ML4QS code](https://github.com/mhoogen/ML4QS/);
  - [ml4qs.org](https://ml4qs.org).
- **Data:** collected by Ebbelaar during gym workouts with 5 participants, including himself, wearing a MetaMotion wristband (accelerometer 12.5 Hz, gyroscope 25 Hz).

### How the baseline was built (as taught)
- **Raw data:**
  - per-sensor CSVs are merged;
  - labels (participant, exercise, heavy/medium, set) come from file names;
  - the data is resampled to 200 ms (5 Hz), mean for numbers and mode for labels.
- **Outliers:**
  - three methods compared: IQR, Chauvenet's criterion (C = 2, assumes normality) and Local Outlier Factor;
  - outliers are replaced, then interpolated.
- **Features:**
  - Butterworth low-pass filter, with the cutoff chosen by eye (1.3 Hz in the report);
  - PCA with 3 components, picked by the elbow;
  - magnitudes `r = sqrt(x² + y² + z²)`, which are orientation-independent;
  - rolling mean and standard deviation over a 4 s window;
  - Fourier features over 4 s (max frequency, weighted frequency, power spectral entropy);
  - k-means clusters (k = 4);
  - a set "duration" computed per set, introduced as preparation for the filter.
- **Overlap:** the tutorial keeps every other row ("50% overlap allowed") to reduce near-duplicate rows.
- **Modelling:**
  - forward feature selection with a decision tree, evaluated on training data (the video says so);
  - grid search with 5-fold CV;
  - NN, RF, KNN, DT and NB compared;
  - a random forest picked (min_samples_leaf 2, 100 trees, gini).
- **Reps:**
  - peak counting on the low-passed acceleration magnitude, with the cutoff tuned per exercise (0.4 Hz for bench; minima for deadlift and OHP);
  - ground truth inferred from heavy = 5 and medium = 10, not counted;
  - MAE about 1 rep in the video, and "about 5% miscount" in the report.

### Three numbers for one question (material for post 1)
"How well does it work on someone it hasn't seen?" got three very different answers:

| Source | Evaluation | Result |
|---|---|---|
| Video, part 6 | Random 75/25 stratified split over rows (`random_state=42`) | ≈ 99% test accuracy. The presenter notes this is "still kind of cheating" because of overlapping windows |
| Video, part 6 | Train on B–E, test on participant A only, same leaky features | 99.4% |
| Report | Train on the first two sets per exercise/weight/participant, test on the rest | 98.51% |
| Report | Leave-one-participant-out, averaged | **85.43%** |
| Report | Train heavy, test medium (and the reverse) | 79.97% / 79.51% |
| Proprio Phase 0 | Leak-fixed LOPO over all 5 participants, set-level vote | **96.3%** |

**The lesson:** the evaluation design decides the number. The 99.9% claim came from the split, not the model.

### Discrepancies to handle carefully
- **Set count:** the report describes 150 sets (5 × 5 × 6). The distributed data has 94 accelerometer files with 23 duplicates, so 82 unique sets (D-016). Say "the public dataset contains 82 unique sets"; don't claim the report is wrong.
- **Pipeline differences:** the report's LOPO 85.43% isn't directly comparable to Proprio's 96.3%. The pipelines differ: features, leak fixes, set-level vote, augmentation off in Phase 0. Present both as context, not as a contest.
- **The 80/20 vs 75/25 split:**
  - the owner's implementation used `test_size=0.2` (commit `8ac2fe0`, 2026-02-07);
  - the video uses 25%.

  Both are random row-level splits; describe them separately.
- **"From 2019":**
  - the *data* was recorded in January 2019 (file timestamps);
  - the owner's code was written in early 2026 by following this tutorial (git history from "Project Setup").

  The journal's "2019 proof of concept" wording conflates the two and should be corrected when post 0 is written.
- **Credit:** the README credits ML4QS but not the tutorial or report. Before publishing, add credit to the README and to post 0: Dave Ebbelaar's series, report and dataset; ML4QS.

### Access notes
- **Transcripts:** auto-captions were read for parts 1, 3, 4, 5a, 5b, 6 and 7. Part 2's captions were rate-limited by YouTube; its description and source page were read instead. Nothing was watched as video.
- **Craft pages:** they need JavaScript, so their content was read through Craft's public share API.
- **Not used:** the freelancing and marketing links in the descriptions.

## Work before publishing
1. **Visuals:** about 12 charts and diagrams, generated from reports and MLflow. Keep them outside git per the project rules (e.g. a `blog-assets` folder or the platform itself).
2. **Turn journal parts into posts:**
   - cut internal detail (decision ids, file paths);
   - keep one evidence link per claim;
   - add the hook and the ending.
3. **Check every post:**
   - numbers match their report;
   - in-sample results are labelled;
   - credit to the original work is included;
   - no personal identifiers (IP addresses, device serials, emails, private notes).
4. **Get feedback:** one beginner reads posts 1 and 6 and marks where they got lost.

## Open decisions (owner)
- **Platform:** own site, dev.to, Medium or other.
- **Post 11:** publish the AI-workflow post or not (recommended: yes).
- **Timing:** hold everything for launch, or release posts 1–3 earlier (pure ML, independent of the app).
