# Proprio build journal

> The full story of building Proprio, in order. It is the draft of a public, multi-part blog series, written in my voice.
> Every part covers: **what I did, why, what I rejected, what happened (with numbers), and what I learned.**
> Numbers come from reproducible runs. Evidence lives in `docs/reports/`, MLflow and git history.
> Kept current alongside [`PROGRESS.md`](PROGRESS.md) (state) and [`DECISIONS.md`](DECISIONS.md) (decision log, cited below as D-001 to D-034).

## Who this is for

You know a little Python and high-school maths. You have heard that machine learning means "training a model", but you have not met cross-validation, data leakage, accelerometers or rotation matrices. You do not need to know them yet. I explain each idea the first time it matters in the project, then keep using it.

## How to read it

- **The story comes first.** Each part is "what I did, why, what I rejected, results, lessons".
- **Boxed notes teach.** Every quoted block that starts with **"The idea in plain words"** explains one concept: intuition first, then a tiny example or formula, then why it mattered in this project. If you already know the idea, skip the box. If a later part uses a word you have forgotten, look it up in the [glossary](#glossary) at the end.
- **Numbers are real.** Every figure comes from a report, a decision record or the progress log in this repository. Where a number is optimistic, in-sample or unverified, I say so next to it.
- **How the work was done.** I am the owner and the only author of the commits. The planning, specs and reviews were done with an AI orchestrator (a stronger model, Opus), and the code was written by smaller AI agents (Sonnet) working in isolated git worktrees on tightly scoped tasks, each reviewed before merging. Where a section says "I", I mean the owner-plus-orchestrator side of that arrangement: I set direction and approved decisions, and the orchestrator analysed, wrote specs and reviewed results.
- **The machine learning is the focus.** The Android and backend parts get short plain-language explanations, because the ML only matters if the data gets from a wrist to a model.

## Series outline

| Part | Working title | Covers | Status |
|---|---|---|---|
| 1 | From notebook to product plan | Auditing the 2019 proof of concept, sensors and units, the product vision, honest technology choices, the working setup | written |
| 2 | The 99.9% that wasn't real | Packaging, reproducibility, the first end-to-end run, data leakage (five leaks), cross-validation, duplicate data, the honest baseline | written |
| 3 | Teaching a model trained on one sensor to read another | Domain shift, the canonical sensor format, resampling and aliasing, the fake-watch test, rotation sensitivity and augmentation | written |
| 4 | Designing a recorder around the data you'll need | Sessions and segments, the recording format, clocks, cross-language conformance, the watch and phone apps | written |
| 5 | What only real hardware could tell me | Data Layer package ids, the Samsung Freecess saga, remote launch, UX from real use | written |
| 6 | Proving the sensor assumptions | The calibration protocol and the physics-based checks | written |
| 7 | Getting ready for real gym data | Writing the evaluation criteria before the data exists: rotation fit (Kabsch), gates, confidence intervals, the harness | written |
| 8 | A PyTorch model that ties the classical best | 1D-CNN, augmentation on the fly, ONNX | written |
| 9 | From scripts to services | Contract-first Spring Boot core, FastAPI inference, RabbitMQ, Docker on Red Hat UBI, the phone client | written |
| 10 | The first real gym session | In-sample results, debugging the fit instead of the model, questioning my own gate, the live preview and countdown | written (session 2 has not happened) |
| 11+ | See [Upcoming parts](#upcoming-parts-to-be-written-as-they-happen) | The real verdict on session 2, OHP vs bench, rep counter, the LLM coach, shipping | upcoming |

The part numbers follow the order things happened, not the phase numbers in `PROJECT.md`.

---

## Part 1 — From notebook to product plan (2026-10-21)

### Where it started

- **The proof of concept, from 2019.** A Python project that classified five barbell exercises (bench press, squat, deadlift, overhead press, row) and counted reps. Its data came from a single MetaMotion wrist sensor worn by five people.
- **How the pipeline worked:**
  - load the accelerometer and gyroscope CSV files;
  - resample to 5 Hz;
  - remove outliers;
  - engineer features (low-pass filter, rolling statistics, FFT, PCA);
  - compare five classifiers.
- **The claim:** 99.9% accuracy.

> **The idea in plain words: what is a wrist sensor measuring?**
>
> A small chip called an **IMU** (inertial measurement unit) sits inside the watch or the MetaMotion band. It has two sensors that matter here.
>
> - The **accelerometer** measures how hard the device is being pushed along three axes (x, y, z). Think of a ball on a flat tray: tilt the tray and the ball rolls, accelerate the tray and the ball lags behind. An accelerometer feels both. Crucially, **gravity counts as a push**. A device lying still on a table reads about 1 g pointing "up" (the force that stops it falling), so even when you are not moving, the accelerometer tells you which way is down relative to the device. That is why it can tell a wrist held vertically (bench press) from one hanging down (deadlift).
> - The **gyroscope** measures how fast the device is *rotating* about each axis. It knows nothing about gravity. It says "you are spinning at 40 degrees per second about the forearm".
>
> **Units matter, and they differ between devices.** The MetaMotion reports acceleration in **g** (1 g is the pull of Earth's gravity, 9.80665 m/s²) and rotation in **degrees per second**. An Android watch reports **m/s²** and **radians per second** (one radian is about 57.3 degrees). Feed a model rad/s when it was trained on deg/s and every rotation looks 57 times too small. Much of this series is about not making that kind of mistake.
>
> **Axes are tied to the device, not the room.** The sensor's own x, y, z directions turn with your wrist. I found the MetaMotion's +y axis points **distally**, along the forearm toward the hand. Its x and z directions are not documented anywhere. This will matter a lot in Part 3 and Part 10.

### What I did

1. **Audited the codebase before planning anything.**
   - I read every module.
   - I measured the raw data myself instead of trusting the README. That covered units, gravity, sampling regularity and per-exercise gravity vectors.
   - I found that the sensor's +y axis points along the forearm, from physics alone: the mean y reading is +0.94 g during bench press (hand above wrist, so +y points up) and −1.02 g during deadlift (hand below wrist, so +y points down).
2. **Named the gaps up front:**
   - a random train/test split over overlapping time windows;
   - a "duration" feature that knows the future;
   - filters running across set boundaries;
   - no tests or CI (continuous integration: automatic checks on every push);
   - pickles (serialized Python objects) committed to git.
3. **Wrote a product vision:** "Proprio". You lift with a Galaxy Watch on; it recognizes the exercise and counts reps; a phone app logs it; an LLM coach explains your training.
4. **Mapped every technology to a real job, with a priority.** The honest calls:
   - Kafka is overkill, so RabbitMQ (D-014).
   - Cassandra is a poor fit, so PostgreSQL + pgvector.
   - Android forces Gradle, so no Maven.
   - The LLM must not classify sensor data. It explains and coaches.
   - Dropping the web client (an owner decision, D-005) leaves HTML/JS/Ajax uncovered, and the docs say so.
5. **Planned the hardest risk first: sensor domain shift.** A model trained on a 2019 research sensor has to work on a consumer smartwatch. I put a calibration and validation phase *before* any model-serving work, with pass/fail gates defined in advance.

### Decisions made with the owner

- Samsung Galaxy Watch (Wear OS) (D-001).
- Java Spring Boot core plus Python ML services (D-002).
- IBM Cloud (free tier plus trial credits) (D-003).
- A 12+ week timeline (D-004).
- No web client (D-005).
- Left wrist only (D-006). The dataset is assumed to be left-wrist too, because the source does not say.
- The name "Proprio" (D-007), short for proprioception, the body's sense of its own position and movement.
- Friends help with validation data (D-008).

### How I set up to work

- **Documents as memory:**
  - `PROJECT.md` is the source of truth for scope;
  - `PROGRESS.md` holds the current state;
  - `DECISIONS.md` is an append-only log with the reason for every decision;
  - `CLAUDE.md` tells any new AI session how to resume.
- **Git flow:**
  - a stable `main`;
  - all work on feature branches off `dev`;
  - `main` only moves on explicit approval;
- **AI workflow:**
  - a stronger model (Opus) orchestrates: analysis, architecture, specs, review;
  - smaller models (Sonnet) implement tightly scoped tasks in isolated git worktrees;
  - every result is reviewed before merging.

### Lessons

- Measure the data before you plan around it.
- Decide what "good enough" means before you see the results.
- Writing down why you rejected an option is as valuable as writing down what you chose.

---

## Part 2 — The 99.9% that wasn't real (2026-10-21 to 22)

### Cleaning up and making it reproducible

> **The idea in plain words: reproducibility**
>
> A result you cannot rerun is an anecdote. Four small habits make a result rerunnable.
>
> - **Locked dependencies.** A "lock file" lists the exact version of every library, so next year's install is the same as today's.
> - **Versioned data.** Git is bad at large data files. **DVC** (Data Version Control) stores a small text file containing a hash (a fingerprint) of the dataset, in git, while the data itself lives elsewhere. Change one byte of the data and the fingerprint changes.
> - **Tracked runs.** **MLflow** records, for every training run, the settings, the scores, the plots, the data fingerprint and the git commit. DVC versions data; MLflow tracks runs and models (D-015).
> - **Seeds.** Many ML steps use random numbers (shuffling, random initial weights, random rotations). A fixed **seed** makes the "random" sequence the same each time.
>
> In this project: every pipeline run records the data hash and git commit, the augmentation seed is fixed, and a rerun on a clean checkout reproduced the earlier metrics exactly.

- **Removed legacy scripts** that duplicated the class-based pipeline, and stopped tracking generated artifacts.
- **Packaged the code** as an installable Python package (`workout_ml`) on Python 3.11, with locked dependencies.
- **Versioned the raw dataset with DVC.** Every run records the data hash and git commit.
- **Added GitHub Actions CI** (lint, format, tests).
  - I caught that the lock files were Windows-only (`pywin32` pinned with no platform condition).
  - I switched to `uv` universal locks and verified them in a Linux Docker container before pushing.
- **Proved the refactor changed no logic.** I compared the parsed code structure (the abstract syntax tree) of every module before and after, and it was identical except config and pipeline wiring. Behavior-preserving changes deserve proof, not trust.

### The first end-to-end run: four latent bugs

The class-based pipeline had never completed a full run. The first one found:

1. **pandas 3** stores text as Arrow-backed strings, which scikit-learn couldn't index.
2. **A 6.5-hour hang.** I diagnosed it by watching CPU time: completely flat, so the process was waiting, not working. A `plt.show()` was waiting for a window nobody would close.
3. **NaN rows** (not-a-number: missing values) from incomplete rolling windows. I checked the blast radius first: exactly 10 rows.
4. **A model-name mismatch** between two functions.

I also built a watcher for long runs that alerts on stage changes, errors, flat CPU and exit.

### How the original pipeline turned sensor data into predictions

Before the leaks make sense, you need the pipeline in your head.

> **The idea in plain words: sampling, resampling and aliasing**
>
> A digital sensor does not record a smooth curve. It takes **samples**: one reading every so often. The **sampling rate** is how many per second. The MetaMotion accelerometer samples at 12.5 Hz (12.5 readings per second, exactly one every 80 ms) and its gyroscope at 25 Hz (one every 40 ms). The two streams have different rates, so to put them in one table the original pipeline **resampled** both to 5 Hz: one row every 200 ms, each row the mean of the samples that fall in that 200 ms slot.
>
> There is a trap called **aliasing**. A sensor sampled at 12.5 Hz can only represent wiggles slower than half that, 6.25 Hz (the *Nyquist frequency*). A faster wiggle does not vanish: it folds back and shows up as a fake slower wiggle, like a wagon wheel appearing to spin backwards in a film. The cure is to remove the too-fast content *before* reducing the rate. That is a **low-pass filter**: it lets slow changes through and blocks fast ones. Part 3 uses this when converting watch data to the MetaMotion's rates.
>
> Two related terms: **jitter** is irregularity in the time between samples (the MetaMotion has none; the Android watch has a little), and a **gap** is a stretch with samples missing.

> **The idea in plain words: windows and features**
>
> A classifier cannot look at one raw reading and know "that is a bench press". One reading is a single instant. What distinguishes exercises is how the signals behave over a second or two. So for every 200 ms row, the pipeline adds **features**: numbers that summarize the recent past. Examples used here:
>
> - **rolling statistics**: the mean, standard deviation, minimum and maximum of each axis over the last 5 rows (1 s) and the last 10 rows (2 s);
> - **FFT features**: the strength of different rhythms in the signal (the **Fast Fourier Transform** splits a signal into slow and fast components);
> - **derived** features: the length of the acceleration vector and of the rotation vector (`acc_r`, `gyr_r`), which do not depend on which way the axes point;
> - **PCA** (principal component analysis): three numbers that capture the main directions in which the six raw axes vary together.
>
> In this project, a **window** means one 200 ms row together with its features. The 82 sets yield 7360 windows in all (about 90 per set on average), and the classifier labels each window. The feature sets I compare get bigger step by step: `basic` (6 raw columns), `basic+derived+pca` (11), `+temporal` (59) and `all` (107, with frequency features).

> **The idea in plain words: the classifiers I compare**
>
> A **classifier** takes a row of features and outputs a class name. Five kinds are used, all from scikit-learn:
>
> - **Decision tree (DT):** a flowchart of yes/no questions on feature values ("is mean acc_y above 0.5 g?"), learned from the data. Easy to read, easy to overfit.
> - **Random forest (RF):** many decision trees, each trained on a slightly different sample, voting together. More stable than one tree.
> - **K-nearest neighbours (KNN):** no training in the usual sense. To label a new window it finds the most similar windows in the training data and copies their majority label.
> - **Naive Bayes (NB):** treats each feature as independent and multiplies probabilities. Simple and fast, and its independence assumption is usually false.
> - **Neural network (NN):** here a small multilayer perceptron: layers of weighted sums with non-linear squashing, whose weights are adjusted by gradient descent until predictions match the labels. It is the ancestor of the 1D-CNN in Part 8.

### Finding the leaks

I audited how the 99.9% was produced and found five ways test data leaked into training.

> **The idea in plain words: train/test split and data leakage**
>
> A model can memorize. If you test it on the same data it learned from, a perfect score only proves it has a good memory. So we hold some data back, the **test set**, train on the rest (the **training set**), and score only on the held-back part. The score estimates how the model does on data it has never seen, which is what a product needs.
>
> **Data leakage** is any way information from the test set sneaks into training, so the test is no longer a fair surprise. It gives you a higher score and a false sense of safety. The scary part is that nothing crashes. The number just looks great.
>
> A good question to ask of any ML result: *what exactly does the model get to see at training time, and how would that differ from what it will see on a new person tomorrow?*

The five leaks, concretely:

1. **Random 80/20 split over overlapping 200 ms windows.** The pipeline shuffled all rows and put 80% in training and 20% in test. But rows from the same set are near-copies of each other: two neighbouring 200 ms rows share almost the same rolling features, because their windows overlap. So for nearly every test row, an almost identical twin was sitting in the training set. Like testing a student with an exam that has the practice questions' answers with one number changed.
2. **The `duration` feature,** computed over the whole set. It was selected second-best by forward selection (a feature-picking procedure). Heavy sets are 5 reps and medium sets 10, so the length of a set nearly gives the label away. It also uses information (the set's end) that is not known while the set is still going.
3. **Filters and rolling windows ran across set boundaries.** A low-pass filter or a rolling mean that runs over the whole table, instead of per set, mixes the last seconds of one set (a different exercise, maybe a different person) into the first rows of the next. The first rows of each set carried the previous set.
4. **PCA and outlier statistics were fitted on all data,** test participants included. "Fitting" means learning numbers from data (PCA's directions, the mean and spread used to flag outliers). If the test participants' data helped choose those numbers, the training pipeline already knew something about them.
5. **Duplicate files.** 23 raw files were byte-identical re-exports of other sets, with timestamps shifted by exactly +24 hours. The "94 vs 93 files" mismatch was the clue. With a random split, a copy of a set could land in the test set while the original sat in training. My own first check got it wrong because it ignored timestamps, and I caught that too. The dataset is **82 unique sets**; the old pipeline double-counted 11 of them plus one accelerometer-only file (D-016). The raw DVC dataset is left untouched, since raw data is immutable, and deduplication happens in code.

> **The idea in plain words: cross-validation and why leave-one-participant-out**
>
> One train/test split gives one score, and that score depends on which rows happened to land in test. **Cross-validation** repeats the split several ways and averages: cut the data into parts ("folds"), and in turn hold each part out as the test set while training on the others.
>
> The important design question is *what you cut by*. Cutting by random row is the leak above. Cutting by **set** is better but still unrealistic: the product will meet **new people**, and one person's style (how they hold the bar, their arm length) is shared across all of their sets. If the same person is in training and test, the model can partly learn the person rather than the exercise.
>
> So I use **leave-one-participant-out (LOPO)**: there are five participants, A to E, so five folds. Each fold trains on four people and tests on the fifth.
>
> ```
>  fold 0:  test A | train B C D E
>  fold 1:  test B | train A C D E
>  fold 2:  test C | train A B D E
>  fold 3:  test D | train A B C E
>  fold 4:  test E | train A B C D
> ```
>
> The rule of thumb: **group your cross-validation by the unit you will deploy to** (a person), not by the sample (a row).
>
> The cost is that folds are small and uneven: A has 27 sets, B 9, C 14, D 9, E 23, and some people lack some exercises (B has no deadlift, row or rest). A fold's score is noisy, which matters when reading results.

### The fix, written as a spec first

- **Three rules** (the design principles in `docs/specs/phase0-leakage-fix.md`):
  - anything computed at inference must work on one set without its label (so per-set filtering is fine, per-label processing is not);
  - anything fitted happens inside the training fold only, using scikit-learn Pipelines;
  - evaluation, including any inner tuning, is **leave-one-participant-out (LOPO)**.
- **Outliers:** the old outlier step (Chauvenet's criterion, a rule that flags values too far from the mean) *dropped* rows, which leaves holes in time that the filters assume are not there. The new one runs per set, marks outliers as NaN and interpolates across them, so no rows are lost.
- **A tripwire set in advance:** if the "fixed" score still came out at 0.99 or higher, assume there's still a leak. I wrote down an expected range (0.85 to 0.97 for the best model) before running.
- **MLflow tracks every run:** a parent run per pipeline invocation, a child run per model × feature set, and the best model logged.

### Result

> **The idea in plain words: accuracy, macro-F1 and per-class recall**
>
> **Accuracy** is the share of predictions that are right. It can mislead when classes are unbalanced: if 90% of the data is one class, always guessing that class scores 90%.
>
> **Recall for a class** answers "of all the windows that really were bench press, how many did we call bench press?" Looking at recall per class shows *where* a model is weak.
>
> **Macro-F1** averages a per-class score (F1 combines precision, "when I say bench, how often is it?", and recall) with every class counting equally, so a rare class cannot hide.
>
> There are two levels of scoring here. **Window level** scores every 200 ms row. **Set level** gives each set *one* answer by **majority vote**: take the label most of its windows voted for. If a set has 100 windows and 70 say bench and 30 say overhead press, the set is bench. Voting smooths out the odd wrong window, which is why set accuracy is usually higher than window accuracy. A tie goes to the first class in alphabetical order, so results are deterministic. The product reports exercises per set (D-013), so **set accuracy is the primary product metric** (D-017) and window macro-F1 is the diagnostic.

| | Before | After |
|---|---|---|
| Accuracy | 0.9994 (leaky; best was a decision tree on 10 selected features) | **window 0.963 ± 0.047, macro-F1 0.957 ± 0.054, set accuracy 0.963 ± 0.052** (LOPO) |
| Dataset | "94 sets" | **82 unique sets** |
| Runtime | ~35 min (about 25 of them forward selection) | 292 s |

The "±" is the standard deviation across the five folds. The best model was the neural network on `basic+derived+pca+temporal` features.

- **The top four rows of the leaderboard are within noise of each other** (fold standard deviation about 0.05, up to 0.21 for decision trees and naive Bayes). Picking the neural network was a tiebreak, not a finding.
- **The errors cluster where physics predicts: bench vs overhead press.** Pooled over all folds, 116 bench windows were predicted as overhead press and 38 overhead-press windows as bench; almost every other cell of the confusion matrix is zero. Both lifts hold the forearm near vertical, so their average gravity vectors are nearly the same: bench (−0.11, 0.94, −0.17) g and overhead press (−0.24, 0.92, −0.12) g, differing by about 0.13 g on one axis. Bench recall was 0.715 for participant B and 0.574 for participant C.
- **Temporal features add about 7 points of macro-F1** over raw readings (0.861 to 0.957 for the best models). **Frequency features add nothing:** `all` (107 features) scores 0.953 for the neural network, against 0.957 for the 59-feature set.
- **The model leans on absolute orientation.** The temporal statistics are computed on the raw axes, so the classifier is orientation-dependent by construction. Fine on one fixed device, but the main risk on a different watch. This sets up Part 3.
- **The spec's leak tripwire did not trip**, and the errors concentrated where the physics says they should, which is further evidence the remaining number is real. It is still a *source-domain* number: five people, one sensor.
- **The rep counter fails every gate,** even on its own tuning data. Over 78 non-rest sets, it is off by 1.05 reps on average (MAE, mean absolute error), exact on 40% of sets and within one rep on 81%. Overhead press is worst (MAE 1.90, 20% exact). It also got worse after cleaning (MAE 0.89 to 1.05), mostly because the removed duplicate sets happened to be easy ones. These numbers are optimistic twice over: the counter's cutoffs were tuned on this same data, and the "expected" rep counts are assumed from the load category (heavy = 5, medium = 10), not counted by anyone. I reported that rather than hiding it.

> **The idea in plain words: why counting reps is hard**
>
> Counting reps sounds easy: count the peaks in a wavy signal. But the signal depends on the exercise, the person's tempo and range of motion, and where the sensor sits. A slow grinding rep has a different shape from a fast one. Unracking the bar, walking out and re-racking also make waves that are not reps. And without someone counting aloud, you do not know the true answer to score against. The original counter used a low-pass filter plus peak finding, with hand-tuned cutoffs per exercise. That is exactly the kind of thing that works on the data you tuned it on and nowhere else, so the rework is mandatory (Phase 4).

### What I rejected

- Trusting 0.9994. A number that high on five people and a handful of sets should be a question.
- Keeping `duration` "because it helps". It helps by cheating.
- Dropping outlier rows. It breaks the evenly spaced time axis that the filters assume.

### Lessons

- An impressive number is a question, not an answer. Ask how it was produced.
- Group your cross-validation by the unit you'll deploy to (a person), not by the sample.
- Pre-register your red flags.
- With five participants, treat model rankings as noise unless they clear the fold spread.

---

## Part 3 — Teaching a model trained on one sensor to read another (2026-10-22)

> **The idea in plain words: domain shift**
>
> A model learns patterns from its training data. If the data it meets later comes from a *different source*, with different units, noise, sampling or orientation, the patterns may not transfer, even though the activity is the same. This is **domain shift**. The training domain is the 2019 MetaMotion band on five people. The deployment domain is a Galaxy Watch on whoever buys the app.
>
> The dangerous version is silent: the model still outputs confident answers, they are just wrong. So the plan is to (1) remove every *avoidable* difference with exact conversion code, (2) test that conversion on a simulated watch, and (3) measure what is left on real watch data against pass/fail gates written in advance. Parts 3, 6, 7 and 10 follow those three steps.

### The gap, measured

| Property | MetaMotion (training) | Android watch |
|---|---|---|
| Accelerometer unit | g, gravity included | m/s², gravity included |
| Gyroscope unit | deg/s | rad/s |
| Rate | acc 12.5 Hz, gyro 25 Hz, zero jitter | requested 50 Hz, irregular timing, separate unsynchronized streams |
| Axes | +y along the forearm, x and z unknown | Android's own: +x toward screen-right, +y toward screen-top, +z out of the screen |
| Wrist | undocumented (assumed left, D-006) | left, always |

### The design: one canonical format (CSF-1)

CSF-1 stands for Canonical Sensor Frame, version 1. The idea is that **one Python module** converts any device's raw data into exactly the format the model was trained on. Training and serving use the same code, so there is no **train/serve skew** (the model seeing differently prepared data in production than in training).

- **Raw data is always kept** (D-012), so the calibration can improve later and old data can be reprocessed. Nothing on the watch or phone converts anything.
- **The conversion steps, per sensor and in this order:**
  1. **Units:** multiply by a scale (m/s² to g is divide by 9.80665; rad/s to deg/s is multiply by 180/π).
  2. **Rotation:** turn the watch's axes into the MetaMotion's axes with a 3×3 rotation matrix. The same matrix applies to acceleration and rotation rate.
  3. **Optional clipping** (off by default).
  4. **Source-rate emulation:** cut the stream at gaps longer than 500 ms, resample onto an even grid, low-pass filter, then sample at the MetaMotion's rates (12.5 Hz and 25 Hz). The filter is a 4th-order Butterworth, run forwards and backwards so it adds no delay, with cutoff at 0.8 times half the target rate.
  5. **Bin** into 200 ms means, keeping a bin only when both sensors have a sample in it.
- **Why emulate the MetaMotion's rates, not just its units?** A model trained on 12.5 Hz data has seen a certain amount of fast jitter and a certain smoothness. Handing it raw 50 Hz data with the same units still looks different "spectrally". Filtering down to 12.5 Hz and 25 Hz first makes the data match in frequency content too.
- **Per-device YAML profiles** hold the unit scales, rotation and emulation settings. Each profile gets a `transform_id`, a hash of its numeric content, so two converted datasets can only be compared if they used the same transform. Changing any number requires a new profile id.
- **Rewiring proof:** I saved the old loader's output before deleting it, and the new path matched it **bit for bit**. The leaderboard was identical.
- **The tests corrected the spec once.** I'd asked for 20 dB of filter attenuation at 6 Hz, but an order-4 Butterworth filter run forward and backward with a 5 Hz cutoff can only reach about 14.5 dB there. We fixed the spec (at least 14 dB at 6 Hz, at least 20 dB at 7 Hz) rather than weaken the test. The suite stood at 126 tests by the end of the phase.

> **The idea in plain words: coordinate frames and rotation matrices**
>
> Every sensor measures along its *own* three axes, and those axes are glued to the device. Put the same watch on your wrist in two orientations and the numbers for the same motion differ. To compare data from two devices we must say how one device's axes relate to the other's. That relationship is a **rotation matrix** R: a 3×3 table of numbers that turns a vector written in one frame into the same vector written in another.
>
> A tiny 2D example. Rotating the vector (1, 0) by 90 degrees counter-clockwise gives (0, 1). The matrix that does it is `[[0, -1], [1, 0]]`. In 3D, R times a vector gives the rotated vector. We write `canonical = R · device`.
>
> Two properties define a proper rotation. It must keep lengths unchanged (a 1 g reading stays 1 g), which makes the matrix **orthonormal**. And its **determinant** must be **+1**. A determinant of **-1** is not a rotation but a *mirror image*: it swaps left and right handedness. That matters here. Swapping a watch from the left to the right wrist is a mirror, not a rotation. So a conversion with determinant -1 would silently "fix" a wrong-wrist assumption and hide it. The profile loader therefore rejects det = -1, and a fit that prefers it would be a warning that the left-wrist assumption is wrong (D-006).
>
> Because a rotation does not change lengths, **magnitudes** like `acc_r` (the length of the acceleration vector) are unaffected by it. That fact comes back in the rotation-sensitivity study below.

### The fake watch

> **The idea in plain words: build the simulator before the hardware arrives**
>
> I did not yet have watch data, but I could *manufacture* it: take the MetaMotion data and run the conversion in reverse. Then convert it forward again, and see whether the model still works. If it does not, my conversion code has a bug. If it does, then a later gap on *real* watch data is real domain shift, not a conversion bug. This isolates one source of error at a time.

- **Before owning any watch data,** I simulated a Galaxy Watch from the training data: 50 Hz, Android units, timing jitter of 3 ms, noise, unsynchronized streams, and a random orientation per set. A stress variant tripled the noise, doubled the jitter to 6 ms and cut a 1-second gap into every set.
- **Result:** after converting back, the model lost **0.0 points of set accuracy and 0.27 points of macro-F1** (the rule: within 1 point). Bench-versus-overhead-press confusion went from 154 to 161 windows (+4.5%, limit plus or minus 20%). The stress variant landed in the same band.
- **Signal-level error** on real sets: mean 0.015 g and 1.75 deg/s; worst set 0.081 g and 9.8 deg/s. The worst cases come from fast motion combined with the unsynchronized stream offsets and the low-pass filter. They did not move any model metric, so I accepted them. If Phase 3 showed a gap concentrated in fast exercises, this is the first place to look.
- So later real-watch gaps would mean real domain differences, not conversion bugs.

### Rotation sensitivity, and a rule with a gap

The Part 2 review said the model leans on absolute axes. How much does a small misalignment between the watch's axes and the training axes cost? I measured it before collecting any watch data.

- **The experiment.** Rotate the held-out participant's data by a known angle (0, 2, 5, 10, 15, 20, 30 degrees) about each of six signed axes plus 10 random axes, and re-score. I defined **θ_tol**, the *tolerance*, as the largest angle at which, on the worst axis, set accuracy drops by at most 2 points and bench and overhead-press recall each drop by at most 5 points.
- **The baseline model tolerated only 2 degrees.** At 5 degrees about the z axis, overhead-press recall fell from 0.98 to 0.80; at 10 degrees, bench recall fell to 0.44. The two classes trade places depending on the sign of the rotation, which is exactly the mechanism predicted in Part 2. The z axis was the worst: rotating about z mixes x and y, the plane where bench and overhead press differ.
- **Why this matters for calibration.** If the fitted watch-to-training rotation is off by θ degrees, the gravity vectors disagree by about `2·sin(θ/2)` g. At 8.6 degrees that is 0.15 g, which is where my first calibration gate came from.

> **The idea in plain words: data augmentation by random rotation**
>
> If a model is brittle to something that will vary in real life, show it that variation while training. **Data augmentation** adds modified copies of the training data. Here, each copy of a training set is rotated by a random angle (up to 30 degrees) about a random axis, the same rotation applied to acceleration and rotation rate, before the features are computed. The label does not change: a rotated bench press is still a bench press. The model learns that the exercise is the pattern, not the exact wrist angle.
>
> Rules: augment **training participants only**, inside each fold. The test data is never augmented, because it should look like the real world.

- **My pre-set rule had a gap.** The decision rule said: if the tolerance is below 5 degrees, adopt rotation augmentation, "or magnitude-only features if augmentation can't hold 10 degrees". Magnitude-only features (R1: lengths of vectors and their statistics) are rotation-proof by construction, because rotation does not change length. But they scored 0.848 macro-F1 with **no rotation at all**, failing my own 0.85 gate.
- **One more controlled experiment instead.** Training on originals plus K = 4 copies rotated by up to 15 degrees (R2) held 5 degrees. Raising the range to 30 degrees (R2w, K = 4) held **10 degrees** for only 0.5 points of macro-F1 (0.957 to 0.952). K = 8 copies (R2w8) held the same 10 degrees and kept overhead press better under large rotations (minimum recall 0.929 vs 0.906 at 15 degrees). I amended the rule: a remedy must itself pass the gates at zero rotation (D-018).

| Variant (neural network) | Set acc / macro-F1 at 0 degrees | Tolerance |
|---|---|---|
| Baseline | 0.963 / 0.957 | 2 degrees |
| R1, magnitude-only | 0.921 / **0.848** | 30 degrees (but fails a gate at 0) |
| R2, up to 15 degrees, K = 4 | 0.963 / 0.941 | 5 degrees |
| R2w, up to 30 degrees, K = 4 | 0.963 / 0.952 | 10 degrees |
| **R2w8, up to 30 degrees, K = 8** | **0.963 / 0.952** | **10 degrees** |

The cost of augmentation is training time: 9 times the training rows, so the pipeline went from 7 to 20 minutes, which is acceptable for an offline pipeline.

- **Checked it per model (D-020, D-021).** With augmentation, KNN became the best model: window accuracy 0.966, macro-F1 0.959, **set accuracy 0.986**, ahead of the neural network (0.952). Because the 10 degrees was measured on the neural network, I reran the study for KNN and the random forest. KNN with augmentation tolerated **15 degrees** (KNN without it, 5). Augmentation became the pipeline default, and `--no-augment` reproduces the Phase 0 baseline exactly.
  - The random forest scored a tolerance of "0 degrees" only because **one set flipped at 2 degrees** and then accuracy stayed roughly flat up to 20 degrees (0.949 at 2 to 15 degrees, 0.963 at 20, versus 0.971 at zero). That exposed that my threshold (2 points of set accuracy) is finer than one set in a small fold (a fold has 9 to 27 sets). A better rule would pool set counts or require the drop to persist across two angles. I did not change it retroactively.
- **Scope of the decision.** The calibration gate stays at 0.15 g (about 8.6 degrees, inside the 10-degree tolerance), because calibrating to the baseline's 2 degrees (0.035 g) would probably never be achievable from human posture.

### What I rejected

- Magnitude-only features (they fail the macro-F1 gate before any rotation).
- Tightening calibration to 2 degrees.
- Following my own rule literally into a model that fails a gate.

### Caveats I wrote down

- The rotations are synthetic and global (one rotation for a whole set). A real watch can also shift mid-set or sit differently on the wrist.
- Single seeds, five participants: differences between R2w and R2w8 at small angles are within fold noise. The choice rests on the large-angle behaviour.
- Some MLflow runs carry `git_dirty=True` because they were generated before their code was committed. The code versions are recorded and the numbers are reproducible, but future analysis scripts should commit first.

### Lessons

- Build the simulator before the hardware arrives.
- When a pre-registered rule turns out to have a gap, fix it openly. Don't game it or follow it blindly.
- Re-validate when the winning model changes.

---

## Part 4 — Designing a recorder around the data you'll need (2026-10-22)

### Why the recorder is an ML decision

The training data was recorded one set per file, with no examples of *not lifting*. A workout logger must not log a walk to the water fountain as a squat. So the recorder has to be designed around the evaluation I will need later, including the negatives.

### Key design choices

- **Continuous sessions with segments** (D-019), not one file per set.
  - The watch records the whole session, and sets, rest and "other" are time intervals inside it. This captures "not lifting" data for free, and set boundaries can be fixed later without losing samples.
  - Cost: about 60 minutes of two sensors at 50 Hz is roughly 360 thousand rows, around 10 MB as CSV and 3 MB zipped. Acceptable.
  - Rejected: one file per set (loses rest and "other" data, cannot fix a boundary mistake) and labels stored inside the watch's metadata (mutates raw data).
- **Immutable raw data.** The watch writes samples, device metadata and clock anchors; the phone writes labels into a separate file, `labels.json`, with optional start and end overrides. The watch files are never changed after the session ends.
- **Explicit clocks.** Timestamps stay exactly as Android delivers them: nanoseconds since the device last booted (a clock that never jumps), not wall-clock time. Anchor pairs at the start and end of the session link that clock to real time. Converting is then explicit, and drift (how much the two clocks disagree over the session) is measurable instead of hidden.
- **Crash safety.** Files are flushed every 2 seconds, so a session cut short by a crash or a dead battery is still readable and transferred, with `"complete": false` in its metadata.
- **One shared message contract** (message paths and payloads) in a library both apps depend on. I wrote it myself before the two apps were built in parallel.

> **The idea in plain words: contracts and cross-language conformance**
>
> Two programs written in different languages must agree on a file format down to the last comma. If each team (or each AI agent) reads the spec its own way, the programs disagree quietly. Two tools prevent that.
>
> - A **schema** (here, JSON Schema for the metadata and labels files) is a machine-checkable description of what a valid file looks like. Both the Kotlin and the Python code validate against the same schema files.
> - **Golden files** are example outputs generated once by a deterministic program and committed. Both languages must read them and produce identical results. If someone changes the writer without updating the golden files, the build fails. That is a **conformance test**.
>
> The same pattern appears again in Part 9 for the web API.

> **The idea in plain words: checksums**
>
> A **SHA-256 hash** is a fingerprint of a file: change any byte and the fingerprint changes completely. The watch sends a session to the phone, and the phone replies with the hash it computed. Only when the two match does the watch delete its copy. A broken transfer can never cause silent data loss.

### What got built

- **A Gradle project with three modules.**
  - `:protocol`, a pure-Kotlin library for the file format (writer, reader, zip, clock resolver, stats).
  - `:wear`, the Wear OS recorder:
    - the sensor thread never blocks (events go to a queue; a writer drains it);
    - explicit hardware sensor selection;
    - a guided calibration mode;
    - hash-verified transfer, as above;
    - recovery of orphaned sessions.
  - `:mobile`, the phone app: session control, a rep picker, a review plot, label editing and export.
- **A Python reader** (`read_session`) feeding the CSF-1 layer, plus `recording_stats` (rate, gaps, drift) so every session reports its own quality.
- **JSON Schemas,** validated in both languages.
- **Android CI** (tests, lint, both APKs), verified in a Linux container before its first GitHub run, which passed.
- **Test counts at the end of the phase:** 24 Kotlin and 22 Python tests for the protocol, 46 JVM tests for the watch app, 29 for the phone app.

### Lessons

- Design data collection around the evaluation you'll need later.
- Contracts shared across parallel work should be written first, by one person.

---

## Part 5 — What only real hardware could tell me (2026-10-22)

### The devices

- **A Galaxy Watch5 Pro** (SM-R920, Wear OS 5) with an **STMicro LSM6DSO** IMU, at 6.25–100 Hz.
- **A Galaxy S25 Ultra** (Android 17).
- **A trap:** two sensors answer to "gyroscope", the real chip and a software "Corrected Gyroscope". Selection had to be explicit and logged.

> **The idea in plain words: why fakes can't test platform rules**
>
> Unit tests with fake sensors and fake transports check *my* logic. They cannot check the *platform's* rules: which apps may talk to each other, when the operating system freezes a process, what a foreground service is allowed to do. Those rules only show up on the real device. In this part, every serious bug came from a platform rule that no fake could have contained.

### Bug 1: the apps couldn't see each other

- The phone never showed "Connected".
- The Wear OS Data Layer (the Bluetooth link between watch and phone apps) only connects apps with the **same package name and signing key**, and my spec had given them different ids.
- Unit tests with fake transports could never catch that. Fix: both apps share `applicationId` `dev.proprio` while Kotlin namespaces stay separate (D-022). The spec was corrected.

### Bug 2: the Samsung Freecess saga

> **The idea in plain words: foreground services, wake locks and aggressive power management**
>
> Phones and watches try to save battery by freezing background apps. Android gives an app that must keep working, such as a recorder, three tools: a **foreground service** (an app task with a visible notification that the system treats as important), a **wake lock** (a request to keep the CPU awake) and a declared **service type** (here `health`). Samsung adds its own layer on top, called **Freecess**, which freezes processes that are not the app on screen. If the recorder is frozen, it stops reading the sensor. The chip keeps a small buffer (a FIFO of 300 events, about 6 seconds) and when that overflows, samples are lost.

1. **The first real session had 7 gaps,** up to 22.6 s, inside labeled sets.
2. **Lining up gap times with the watch's system log** showed Samsung's Freecess power manager freezing our process (`FZ … reason: LEV`) about 15–20 s after the screen turned off, and disabling its wake lock, despite a health foreground service.
3. **First hypothesis: battery optimization.** Allow-listing the app made it *worse*: frozen 133 s out of 152. Disproved, reverted.
4. **Blocked all data collection** rather than collect bad data (D-023). The gate: a 5-minute screen-off session with zero gaps over 100 ms.
5. **Built a debug-only adb hook** to run tests unattended, then tested four fixes as a matrix: the Ongoing Activity API, ambient mode, a Health Services exercise session, and keeping the screen on.
   - The process-state dumps showed the pattern: Samsung freezes anything that isn't the top app, *including* foreground services.
   - The exercise session didn't help because it runs in Samsung's process, not ours.
   - Ambient mode could not be properly tested with the always-on display off. Each failed variant froze 5–12 s after the screen went off.
6. **Keeping the recording screen on keeps the app on top** (D-024). Results: 0 freezes and 0 gaps over 180 s on the charger, and on the wrist **289.7 s at 49.85 Hz, zero gaps, maximum interval 20.2 ms.** Cost: screen-on battery drain, not yet measured.
   - Rejected: the battery allow-list (no effect) and periodic keep-alive broadcasts (an unnecessary hack once the screen-on strategy works).

### More from real use

- **A phone-started session failed when the watch app wasn't open,** because Android blocks background apps from starting foreground services. Fix: the phone opens the watch app remotely, then starts the session. Verified with the app closed, 0 gaps over 148 s.
- **Sets started from the watch inherited the phone's exercise selection,** so my squat got logged as OHP. The phone now asks for confirmation.
- **Accidental double taps** created 0.7 s segments. The fix is a 1 s debounce, plus "Discard (accidental)" offered for anything under 3 s.
- **"The screen isn't dimmed".** The logs showed the dimming *was* applied, but the room was so dark auto-brightness was already at minimum. When dimming did show, the text was barely readable. Readability won; battery tuning moved to pre-launch (D-025).
- **Real timing was far better than simulated:** 49.8 Hz with about 0.03 ms jitter, and acceleration and gyroscope streams offset by only 0 to 20 ms. The fake watch had assumed 100 times worse jitter.

### Lessons

- Test on real hardware early. Fakes can't validate platform rules.
- Correlate symptoms with system logs, and treat every hypothesis as something to disprove.
- Stop collecting data rather than collect bad data.

---

## Part 6 — Proving the sensor assumptions (2026-10-23)

### What I did

I called this recording **U1** (the first of the owner-collected datasets). It is a 5-minute guided calibration: 2 minutes with the watch flat on a table, six static poses (face up, face down, buttons up, buttons down, 12 o'clock up, 12 o'clock down), and slow 90-degree rotations about each axis. It was recorded on 2026-10-23, 275 s, with the watch held in the hand.

> **The idea in plain words: physics as a test oracle**
>
> When you do not have labelled "right answers", look for things physics guarantees.
>
> - **Gravity has a fixed length.** A still device must read a vector of length 1 g, however it is oriented. If it reads 1.2 g, the units are wrong. If it reads 0.1 g, something divided by ten.
> - **Pointing a known face up puts gravity on a known axis.** Face up should put about +1 g on +z, face down -1 g on z, and so on. Six poses test all three axes and both signs, which also confirms the frame is right-handed as Android documents.
> - **Integrating rotation must reproduce the change in gravity direction.** If you turn the watch 90 degrees, adding up (integrating) the gyroscope readings over the turn tells you how far it rotated. The accelerometer independently shows how far the gravity vector moved. These two independent measurements must agree. If I flip the sign of one gyro axis in the calculation, they stop agreeing. If the gyro were in degrees and I assumed radians, they would disagree by a factor of 57.
>
> This catches errors that a model's accuracy could never reveal, because a model can partly compensate for a sign error and then fail silently on someone else.

### The checks, and what they said

| Check | Result |
|---|---|
| Timing | 49.88 Hz, interval p99 20.11 ms, 0 gaps, acc-gyro offset 0.00 ms, clock drift -0.17 ms |
| Units | \|a\| at rest = 0.989 g (gate 1.00 ± 0.03) |
| Axes | all six poses put gravity on the expected signed axis, so the frame is right-handed and as Android documents |
| Gyro sign and units | integrating the gyroscope reproduces the gravity change the accelerometer measured, within 2.0° (x rotation) and 2.7° (y rotation) median error. Flipping any single axis gives 16–74° error, and a unit error would be off by 57 times |
| Gyro bias | 0.11 deg/s (gate under 1) |
| Noise | accelerometer 3.2–3.5 mg per axis |

All calibration gates passed. The raw data is versioned in DVC as `watch-calib-v1`.

- **A minor finding:** a -0.03 g accelerometer offset on z (face down read 1.031 g, face up 0.970 g). That is under 2° of tilt, inside the production candidate's 15° tolerance, so it's noted rather than corrected. It could become a per-axis bias in the device profile later.
- **What U1 cannot do:** it cannot give the rotation R between the watch and the MetaMotion frames. That needs exercise data, so it waits for the gym sessions.

### A human error, handled with data

The owner's rotation step about the x axis turned out to be mostly a rotation about z, an instruction misread. Instead of re-recording, I checked whether the x-axis sign was still identifiable from the motion that *was* recorded. It was: 2.0° error as-is vs 66.9° with x flipped. The spin on a flat table (rot_z) cannot test the gyro sign at all, because gravity does not change. That is inconclusive by design.

### Lessons

- Physics makes a great test oracle.
- Before throwing away imperfect data, check whether it still answers the question.

---

## Part 7 — Getting ready for real gym data (2026-10-23)

The next step was collecting real gym data on the watch. Before recording a single set, I wrote down how I would judge it. This part is about that: the evaluation design, and the harness that implements it.

> **The idea in plain words: decide how you will judge it before you see the result**
>
> If you look at the results first and choose the pass mark afterwards, it is very easy, without meaning to, to choose a mark your model passes. Writing the criteria first (what is measured, what counts as pass, which data may be used for what) removes that temptation. It also forces you to think about which data plays which role.
>
> There are three roles, and one dataset cannot play two of them:
>
> - **fit data**: used to choose settings (here, the watch-to-training rotation and a rejection threshold);
> - **validation data**: held back, never touched until the verdict (here, a second gym session, `watch-val-v1`, "frozen");
> - **training data**: what the model learns from (MetaMotion only so far).
>
> Evaluating on the same data you fitted on is **in-sample** evaluation. It is useful as a first look, but it is optimistic, because the settings were chosen to suit exactly that data. Evaluating on data that played no part in any choice is **held-out** evaluation, and only that can be called a verdict. This distinction becomes central in Part 10.

### What I did before the first gym session

- **Wrote the evaluation criteria before collecting the data.** The Phase 3 spec (`docs/specs/phase3-domain-shift.md`) defines:
  - how a watch session becomes a verdict (raw zip, then the same reader, conversion and feature code that production will use);
  - how the watch-to-training axis rotation is fitted;
  - the pass/fail gates with confidence intervals;
  - a "not a lift" threshold tuned only on session 1;
  - an explicitly labeled in-sample mode for a first look the same evening.
- **Named the datasets and their roles:**

| Name | Contents | Role |
|---|---|---|
| `watch-calib-v1` | the U1 calibration session | calibration gates |
| `watch-fit-v1` | gym session 1 | fitting the rotation; choosing the rejection threshold; never used for the final verdict |
| `watch-val-v1` | gym session 2 | frozen held-out validation: the verdict |
| `watch-u3-v1` | two friends, 10 sets each, with written consent (D-008) | between-user variance |

- **Wrote a printable gym protocol (U2):**
  - 20 sets per session: 5 exercises x (2 heavy sets of 5 reps and 2 medium sets of 10 reps), in exercise blocks, with the order swapped in session 2;
  - 60 to 120 s of rest between sets;
  - five 30-second "other" periods per session, as negative examples;
  - reps counted aloud (the training data's rep counts were only assumed);
  - left wrist, at least 2 days between sessions.

> **The idea in plain words: Kabsch alignment**
>
> I need the rotation R that turns the watch's axes into the MetaMotion's axes. I cannot measure it directly, because I cannot hold the 2019 band next to the watch. But I know something about both: **for each exercise, the average direction of gravity as seen by the sensor**. In MetaMotion data, bench press averages gravity at (-0.11, 0.94, -0.17) g and squat at (0.17, 0.66, 0.66) g, for example. If I record the same exercises on the watch, I get five more average gravity vectors, but written in the watch's axes.
>
> So I have pairs: "this exercise looks like *this* vector to the watch and *that* vector to the MetaMotion". The **Kabsch algorithm** finds the single rotation that maps the watch vectors onto the MetaMotion vectors as closely as possible. In plain words, it slides and spins the watch's set of arrows until they line up with the MetaMotion's set of arrows and tells you how much it spun. It is a standard linear-algebra recipe (singular value decomposition) and it can be told to return only proper rotations (determinant +1).
>
> Three things come with the answer, and I use each as a check:
>
> - the **residual**, how far the arrows still miss after the best rotation, in g. Small means "these two devices agree on where gravity points for every exercise";
> - the **determinant check**: if the best unconstrained fit prefers determinant -1 (a mirror), the left-wrist assumption is suspect;
> - the **conditioning**: if all the arrows point nearly along one line, rotation *about* that line is poorly determined, because spinning an arrow about its own direction does not move it. This turns out to matter in Part 10.
>
> Each exercise is weighted by how many watch sets I have of it.

> **The idea in plain words: confidence intervals and why 15 sets is small**
>
> Suppose a model gets 11 of 15 sets right. You might say "73%". But if you recorded a different 15 sets you might get 9 or 13. A **confidence interval** is a range that reflects that uncertainty. For a proportion, the **Wilson interval** is a well-behaved choice that works for small samples. For 11 of 15 correct, the 95% Wilson interval is roughly 0.48 to 0.89: the honest statement is "somewhere between about half and nearly nine in ten".
>
> One set is 6.7 points of accuracy when you have 15 sets. With about 40 sets (two sessions), the intervals are still around plus or minus 9 points. So I wrote a rule: a pass whose lower bound sits more than 10 points below the threshold is labelled **provisional**, not a clean pass. The same logic applies to the 5 participants in the MetaMotion data: a difference smaller than the spread across folds is not a finding.

> **The idea in plain words: a "not a lift" threshold**
>
> The training data contains no "other" movements, but a real user will do plenty of them (walking, adjusting the bar). A classifier forced to choose among five exercises will always choose one. To reject non-lifts, each set gets a vote: the share of its windows that agree on the winning class. If the winner is `rest`, or its share is below a threshold **τ** (tau, 0.6 by default), the set is **rejected** ("not logged as a lift"). τ is chosen on `watch-fit-v1` only: the largest value that keeps fit-set accuracy within 2 points of having no threshold. Then it is frozen for validation.

### The gates, fixed in advance

The pass/fail gates (`PROJECT.md` section 6.5) are:

| Gate | Pass |
|---|---|
| Calibration | static \|a\| 1.00 ± 0.03 g; rest gyro bias under 1 deg/s; rotation-fit residual within a limit (first 0.15 g, later made relative, see Part 10) |
| Set-level exercise accuracy | at least 90% **and** at most 5 points below the MetaMotion LOPO score for the same model |
| Window macro-F1 | at least 0.85, and every class recall at least 0.75 |
| Other/rest rejection | at least 80% not logged as a lift |
| Rep count | MAE at most 1.0 overall and 1.5 per exercise; at least 70% exact; at least 90% within plus or minus 1 |
| Domain-classifier AUC | a diagnostic: above 0.8 triggers feature investigation |

Four models are evaluated, each trained on all MetaMotion data: **M1**, the production candidate (temporal features + KNN with rotation augmentation); **M2**, the same with the neural network; **M3**, M1 *without* augmentation, to see what augmentation buys on real data; and **M4**, the CNN from Part 8. Each is run with a **fitted** and a **prior** watch profile.

> **The idea in plain words: PSI and the domain classifier**
>
> Two diagnostics ask "how different does the watch data *look* from the training data?", without needing to know the right answer.
>
> - **PSI (population stability index)** compares one feature at a time. Cut the training values into ten equal-sized buckets, count what share of watch values lands in each bucket, and sum up how far those shares are from the expected 10% each (weighted by a logarithm so big mismatches count more). Zero means identical distributions; larger means more different. It tells you *which* features moved.
> - A **domain classifier** is a small model trained to tell "MetaMotion window" from "watch window". Score it with **AUC** (area under the ROC curve): 0.5 means it cannot tell them apart (the domains look alike), 1.0 means it separates them perfectly. If a simple model can easily spot which device produced a window, the exercise model is probably seeing device differences, not exercise differences. I set 0.8 as the line that triggers an investigation.

### Building the harness

- **`workout_ml.watch`**: ingest, calibration check, the weighted Kabsch fit, the evaluator with all the metrics, a generated report and an MLflow experiment (`proprio-domain-shift`). `dvc repro` runs the calibration check, profile fit and evaluation stages.
- **Verification:** it recovered a known synthetic rotation within 0.07 degrees, and it reproduces the U1 calibration numbers exactly.
- **The spec was wrong once, and the implementer caught it.** I'd specified a "smallest singular value" conditioning warning, but real gravity data legitimately has a small singular value (every lift holds the forearm near the vertical, so each exercise's gravity points near +y or -y, see the Kabsch box). It was replaced with a physically meaningful "weakest-axis lever" check.
- **The rep counter and the CNN plug in** through the same path, so the verdict uses exactly the code the production service will use (Part 9).
- **Versioned the calibration data in DVC** as `watch-calib-v1`.

### What I rejected

- Choosing the pass marks after looking at the data.
- Using the fit session for the verdict. It can only ever give an in-sample first look.
- Treating the first gym session as final: I planned it from the start as the fit set.

### Lessons

- Write the evaluation before the data exists, including which data may be used for what.
- Label optimistic numbers as optimistic in the output itself, not just in the prose.
- Review the spec as hard as the code: the spec was wrong once and the implementer caught it.

---

## Part 8 — A PyTorch model that ties the classical best (2026-10-23)

> **The idea in plain words: a 1D convolutional neural network**
>
> The classical models in Part 2 need me to design features by hand (rolling means, FFT, PCA). A **neural network** can learn its own features. A **1D-CNN** (one-dimensional convolutional neural network) is built for signals over time.
>
> A **convolution** slides a small filter (here, 5 time steps wide) along the signal and records how strongly each stretch matches the filter's pattern. Stack several such layers and the first ones detect simple shapes (a rise, a dip), the later ones detect combinations (a rhythmic up-down). Then a pooling step summarizes over time, and a final layer outputs the class.
>
> **Training** shows the network labelled examples, measures how wrong it is (the loss), and nudges all its weights slightly in the direction that reduces the loss (gradient descent), over many passes called epochs. **Early stopping** halts when a held-out check stops improving, to avoid memorizing the training set.
>
> **ONNX** is a standard file format for trained networks. An exported ONNX model can be run by a small runtime without installing PyTorch, which keeps a production service light.

- **The model:** a small 1D-CNN. Input windows are 20 bins of 200 ms (4 seconds), 8 channels (the 6 raw axes plus the acceleration and rotation magnitudes). Three conv blocks (32, 64, 64 channels), max-pooling after the first two, global average pooling, dropout 0.3 and a linear layer: 32,870 parameters.
- **Training:** AdamW optimizer, class-weighted loss (so rare classes count), up to 40 epochs. Each sample gets a **new random rotation every epoch** (on-the-fly augmentation, drawn from the same distribution as D-018), with early stopping on a held-out *participant*, never a random split.
- **A subtle bug, caught before reporting:** the first early-stopping rule sometimes held out the only other participant with `rest` data, so the model never saw that class (macro-F1 fell to 0.83). The fix: the validation participant may never be the sole holder of a class.
- **LOPO result:** set accuracy **0.986 ± 0.032**, window macro-F1 0.959 ± 0.058. That ties the augmented KNN (0.986 / 0.959). With augmentation switched off, the CNN scored 0.963 set accuracy and 0.957 macro-F1. The fold spread (about 0.05) says the CNN and KNN are indistinguishable on this data, and a +0.002 difference from augmentation is not to be over-read.
- **Bench versus overhead press** is still the weak spot: 89 bench windows predicted as overhead press and 50 the other way (augmented), against 103 and 59 for the classical best.
- **ONNX export:** matches PyTorch to 1.3e-6 in the largest logit difference. Preprocessing (channel order, normalization, class names) ships as JSON next to the model, so serving does not need PyTorch.
- **Kept CI light:** PyTorch lives in an optional extra with its own CPU-only lock, and tests skip cleanly without it. Training all five folds takes about 103 seconds on CPU.
- **An honest comparison caveat:** the CNN needs 4 seconds of history, so it cannot predict the first 19 bins (3.8 s) of a set, and its window-level metrics cover fewer windows than the classical models'. Set-level numbers are the fair comparison.
- **It is not "better".** It ties. The reasons to have it are that learned features may transfer differently to the watch (the harness evaluates it as M4), and that PyTorch and ONNX are part of the production design.

### Lessons

- A new model is not progress unless it beats, or at least differently fails, the old one. Report ties as ties.
- A validation scheme can have bugs too: check that every class appears in every training set.
- Know the comparison caveats (here, the CNN's missing first 3.8 seconds) and put them next to the numbers.

---

## Part 9 — From scripts to services (2026-10-23 to 25)

So far everything was a Python script on my laptop. A product needs services: something the phone talks to, something that runs the model, and a way to hold users and data. This part is the first end-to-end slice, and the focus is on design that survives failure.

> **The idea in plain words: contract-first design, REST and OpenAPI**
>
> Services talk to each other over **HTTP**, using **REST**: you send a request such as `POST /v1/sessions` with a **JSON** body, and get a response with a status code (200 OK, 401 not logged in, 404 not found). **OpenAPI** is a file format for describing every such operation precisely: URL, inputs, outputs, errors.
>
> **Contract-first** means writing that description *before* any code, then generating the server interfaces and the phone's client from it. Two teams, or two AI agents in two languages, cannot drift apart, because both are produced from the same file. It is the same idea as the golden files in Part 4.

### Contracts first, written by me before any service code

- an OpenAPI spec with 17 operations;
- JSON Schemas for the two RabbitMQ events (`session.uploaded`, `session.analyzed`);
- the database design.

### The Java service: core-api

core-api (Java 21, Spring Boot) owns users, sessions, labels and history.

> **The idea in plain words: presigned URLs and the transactional outbox**
>
> *Presigned URL.* A raw session is a few megabytes. Sending it through the API server wastes its bandwidth. Instead the API asks object storage (an S3-compatible service) for a special one-time upload address with a signature baked in, valid for 15 minutes, and hands it to the phone. The phone uploads straight to storage. The signature only matches the host name it was signed for, so the API must sign for the address the *phone* uses, not the internal container name (D-028).
>
> *Transactional outbox.* When an upload completes, two things must happen: the database row changes to `UPLOADED`, and an event is published to the message queue telling the analyzer to start. If the server crashes between the two, either the row says uploaded but nobody is told, or the event goes out for something never saved. The fix is to write the event into an "outbox" table **in the same database transaction** as the row change; a separate poller then publishes from the outbox. The two can no longer disagree.

> **The idea in plain words: login, tokens and password hashing**
>
> Passwords are never stored. They are stored as a **hash** from a deliberately slow function (**Argon2id** here), so a stolen database does not hand out passwords. After login the server issues a short-lived **access token** (a signed **JWT**, valid 15 minutes; **RS256** means a private key signs and a public key verifies) and a long-lived **refresh token**. Each refresh token is **rotated**: using it gives a new one and invalidates the old. If an old one is ever replayed (a sign of theft) the whole family is revoked.

- **Upload design:** phone, then a presigned upload straight to object storage, then a "complete" call, then the **transactional outbox**, so a crash can't lose or duplicate the upload event.
- **Security details:**
  - Argon2id passwords, with a dummy-hash comparison so login timing doesn't reveal which emails exist;
  - RS256 access tokens plus **rotating refresh tokens with reuse detection**;
  - RFC 9457 errors (a standard shape for API error responses);
  - another user's data returns **404**, not 403, so a response never confirms that someone else's id exists. This was proven with IDOR tests (insecure direct object reference: trying to read another user's resource by guessing its id).
- **Tests:** 61, 35 of them against real PostgreSQL, RabbitMQ and MinIO containers via Testcontainers (a library that starts disposable Docker containers inside the test run). The image is built on Red Hat UBI, runs as non-root, and starts healthy.
- **A real-world snag:** the `minio/minio` image had disappeared from Docker Hub, and the replacement (Chainguard's distroless image, which has no shell and therefore no healthcheck) needed different handling. A one-shot bucket-creation job gates the services that depend on it (D-028).

### The Python inference service (V2)

> **The idea in plain words: message queues, retries and idempotency**
>
> Analysis can take seconds and can fail. The API should not wait for it. A **message queue** (RabbitMQ) is a post office between services: core-api drops an `session.uploaded` message, the inference worker picks it up when ready and drops a `session.analyzed` reply.
>
> What if the worker fails? Some failures are **permanent** (the file's hash does not match, the zip is corrupt): retrying never helps, so report failure at once. Others are **transient** (a network blip): retry later. A **dead-letter queue (DLQ)** is where a message goes after retries run out, so it is parked for a human instead of lost or looping forever.
>
> **Idempotent** means doing something twice has the same effect as once. Queues usually deliver a message *at least* once, so a message can arrive twice. If processing is idempotent, a duplicate is harmless.

- **No train/serve skew by construction:** the service doesn't re-implement inference. It calls `analyze_session`, the same function the Phase 3 verdict uses, and a test proves the two agree segment by segment.
- **Failure design before happy path:** a permanent error (bad hash, bad zip, unknown watch, missing object) becomes a `failed` event straight away; anything else retries through 5 s / 15 s / 45 s delay queues and then lands in a dead-letter queue *and* emits `failed`, so the phone never waits forever.
- **A RabbitMQ subtlety:** per-message expiry only applies at the head of a queue, so mixed delays in one queue would block each other. One queue per delay avoids it (D-027).
- **Idempotent by design:** the result event's id is derived from the upload event's id (a UUIDv5, deterministic), so a redelivered message produces an identical event that core-api ignores.
- **A known trade-off:** `session.analyzed` is published `mandatory`; if core-api's queue isn't declared yet the message is redelivered without a cap. I chose that over losing results.
- **Model files** are joblib bundles (a pickle format), treated as trusted artifacts mounted or fetched from MLflow, never committed, as the "no pickles in git" rule requires.
- **Tests:** 36, four of them against real RabbitMQ and MinIO containers, including actual TTL retries ending in the DLQ. The UBI image starts healthy and reports the loaded model's version (git SHA + training-data hash).

### The whole stack on one command (V3)

- `docker compose up --build --wait` brings up PostgreSQL (with pgvector for the coach later), RabbitMQ, MinIO, core-api and inference, all healthy, with one-shot jobs for the bucket and the model.
- **An end-to-end suite in Java (REST Assured)** plays the phone: register, log in, create a session, upload the golden recording straight to object storage, complete it, wait for the analysis to come back over RabbitMQ, edit labels with optimistic locking, rotate refresh tokens, prove another user gets 404, delete the account. 10 tests, about 7 s.
- **A subtle networking point:** see the presigned-URL box above. The backend signs for the address the *phone* uses.
- **CI without the private data:** the training data lives in DVC, so CI trains a deliberately labeled synthetic model (named `synthetic-fixture`, so it can't be mistaken for a real one) just to exercise the plumbing. The end-to-end test asserts structure and the state machine, not exercise labels.
- **Keys:** core-api's `dev` profile generates an ephemeral signing key at startup. No private key is committed.

### Closing the loop on the phone (V4)

- **The phone speaks the same contract:** its API client is generated from the OpenAPI file at build time, and a test fails if the contract and client ever drift (D-029).
- **Uploads that survive real life:** a background job (Android WorkManager) uploads each session after the watch hands it over, through Wi-Fi drops and app kills, and never sends a finished session twice. The raw file goes byte for byte; labels go separately and only when they change.
- **Token handling done carefully:** one refresh no matter how many requests fail at once, sign-out only when the server actually refuses (not on a network failure), and the refresh token encrypted (Tink, AES-256-GCM) under a key wrapped by the Android Keystore. A known limit: a crash between the server rotating the token and the phone saving it forces a re-login.
- **First full loop:** against the local stack, the client's own upload code took the golden recording from registration to ANALYZED. With a synthetic model the predictions are meaningless; the point was the plumbing.
- **Still to do by hand:** the same flow on the real phone over Wi-Fi (the on-device check is open).

### What I rejected

- Kafka, for a task queue that needs retries and a DLQ but no replay (D-014).
- Per-message TTL retries in one queue (blocks).
- Re-implementing inference in the service: one code path serves training, the verdict and production.

### Lessons

- Design the failure paths first. A queue without a dead-letter plan is a data-loss plan.
- A signature is bound to the host the client uses, so a "working" dev setup can fail on a real phone.
- Contract-first plus generated clients turns "the two sides disagree" into a build failure.

---

## Part 10 — The first real gym session (2026-10-26 to 27)

This is the part where the plan from Part 7 meets real data. Read every number below with one label attached: **it is in-sample**. The rotation was fitted on this one session and then the models were scored on the same session, so every result is optimistic. The real verdict needs a second session (`watch-val-v1`), and that has not happened yet.

### The session

- Session `918aa36e`, 2026-10-26. **73 minutes, 15 sets** (bench 4, squat 3, row 3, overhead press 3, deadlift 2), 13 "other" periods that I used for the rest between sets, plus 17 implicit rest gaps.
- **The recording was flawless:** 49.88 Hz, zero gaps, 0.03 ms of clock drift. The Freecess work from Part 5 paid off.
- **Deviations from the checklist:** 15 sets instead of 20; order bench, squat, row, overhead press, deadlift; the `load_kg` field holds **pounds** (my note on set 2). Loads are not model inputs, so none of this affects classification.
- **The upload wasn't flawless:** the phone sat on "uploading" because the PC was off during the workout and the app hid the retry state. I pulled the file over adb and packaged it byte-for-byte the way the phone's export does (watch files unchanged plus `labels.json`), tracked it in DVC and assigned it to `watch-fit-v1`.

### First look, honestly labeled optimistic

| | Default watch profile (prior v0) | Fitted rotation, rest included (as specified) | Fitted rotation, lifts only |
|---|---|---|---|
| Rotation-fit residual (gate 0.15 g) | not applicable | 0.337 g (fail) | **0.148 g** (pass) |
| Production candidate (M1) set accuracy, n = 15, 95% Wilson interval | 0.27 [0.11, 0.52] | 0.60 [0.36, 0.80] | **0.73 [0.48, 0.89]** |
| CNN (M4) set accuracy | 0.27 | 0.60 | 0.67 |
| Rest/other rejection (M1) | 0.70 | 0.90 | passes |
| Rep counter MAE | 4.6 | 4.5 | fails, as on MetaMotion |

With the default profile, 27% of sets were right (4 of 15). Fitting the rotation took it to 60%, and the fit itself failed its quality gate.

### Debugging the fit instead of the model

> **The idea in plain words: when a result is bad, find which stage broke**
>
> A bad accuracy number has many possible causes: the data, the conversion, the alignment, the features, the model. Retraining a fancier model is the most tempting reaction and often the wrong one. The decision tree I had written (`PROJECT.md` section 6.6) says: check the *earliest* stage first. A model cannot fix wrong axes, and if you retrain to cover for an axes bug you will bake the bug in.

- **The bug was in my spec, not the model.** The fit used all exercises, including `rest`. But the "rest" periods between sets were walking and re-racking weights (mean acceleration between 0.55 and 1.0 g, which is not a still posture), and the 17 rest segments outnumbered the 15 lifts. MetaMotion's rest class comes from only 2 of 5 people, in one posture. Gravity during rest depends on how you hold your arm, not on the sensor's frame, so it has no business in a frame-alignment fit. Fitting on lifts only dropped the residual from 0.337 g to 0.148 g, and set accuracy rose to 73%.
- **The physics checked out.** The fitted rotation is a clean 90-degree turn about the screen normal. On a left wrist with the buttons toward the hand, the watch's +x axis (the 3 o'clock, button side) points down the forearm, exactly where the MetaMotion's +y axis does. The determinant of the fit is +1, and the unconstrained fit is also a proper rotation, so the left-wrist assumption holds. (The report's "left-wrist suspect" warning was a false alarm caused by the rest problem above.)
- **My earlier guess had the main axis right but the roll wrong.** The default profile already mapped the watch's +x to the MetaMotion's +y. What it got wrong was the signs of the two axes the old sensor never documented (x and z), which amounts to a **180-degree roll about the forearm**. The fitted rotation is about 176 degrees from that old prior. (An earlier draft of the review said the prior had assumed +y distal; the review was corrected on 2026-10-26, and D-030 records it.)

### Questioning my own gate

> **The idea in plain words: the noise floor**
>
> You cannot ask a measurement to be more precise than the natural variation it is measured against. If five different people doing the same exercise already differ from each other by some amount, then asking one new person to match the group average more closely than that is asking for the impossible.

- MetaMotion's own participants differ from the pooled average gravity vector, per exercise, by **0.158 g RMS** (squat by up to 0.48 g for one person). My absolute gate of 0.15 g sat at that noise floor. A single watch wearer can't be expected to match the pooled mean more closely than the training participants match each other.
- I proposed three changes, and the owner approved them (D-030, D-031):
  - **A.** fit the rotation on lifts only;
  - **B.** make the gate relative: the residual must be at most 1.5 times the training participants' own spread, which is 1.5 x 0.158 = **0.237 g**. The fit's 0.148 g passes. Determinant +1 and the gyro check still apply;
  - **C.** replace the default profile with the physical left-wrist rotation (90 degrees about the screen normal, +x distal), as `galaxy-watch-prior-v1`. The old v0 stays in the repo so old results can be reproduced.
- **A rule I wrote next to the change:** these three changes were motivated by looking at session 1, so **session 1 cannot be used to judge them**. Only session 2 can.

### The numbers after the changes (still in-sample)

- With the new default profile, a brand-new watch scores the same as a fitted one (73% in-sample, 11 of 15) instead of 27%. The prior and the fitted rotation now differ by 4.6 degrees. That is the point of change C: before any fit, a new watch works.
- Other models on the same session: the augmented neural network 0.60, the unaugmented KNN 0.60 to 0.67, the CNN 0.67. The differences are not meaningful at n = 15.
- Rejection of rest and other (M1): 28 of 30 rejected (0.93), passes its 0.80 gate in-sample.
- **Every set-accuracy, macro-F1 and rep gate still fails**, even in-sample. The calibration and rejection gates pass in-sample.

### What transfers and what doesn't

- **Squat, bench and deadlift transfer almost perfectly** once the rotation is right. Window-level recall for the production candidate: bench 1.00, squat 0.99, deadlift 0.92.
- **Row is partial (0.63), and overhead press is the failure (0.18):** all three overhead-press sets were voted bench. The mechanism is the one the Part 2 review predicted. In the MetaMotion data both lifts hold the forearm vertical, so their gravity vectors are almost the same, and the models separate them by *motion*. That motion cue does not survive the change of device. The CNN did a bit better on overhead-press windows (recall 0.42), but its set accuracy (0.67) was no better than the production candidate's.
- **The roll is weakly determined.** Every lift's gravity points near +y or -y, so the smallest singular value of the fit is 0.033 and rotation about the forearm is poorly pinned down. A residual roll error is likely part of the row and overhead-press problem. I have not tested that.
- **The domain classifier says the domains still look different:** AUC 0.88 in the first report and 0.90 after the changes, above the 0.8 line, driven by the temporal accelerometer and gyroscope features. The ten most-shifted features have population-stability scores between 1.66 and 2.25. By the decision tree, the next step is the model side (step 2): wider roll augmentation about the forearm axis and orientation-robust features such as gravity-projected vertical acceleration. Any such change must pass the gates at zero rotation on MetaMotion first. Step 3 (mixing watch data into training) needs at least 30 watch sets; I have 15.
- **One number I can't yet explain:** the gyroscope-confirmation correlation for the lifts-only fit is 0.739, below its 0.8 line (the rest-included fit had 0.848). No decision or review addresses it yet.

- **The rep counter fails on the watch too:** MAE about 4.5 reps overall, 0 of 15 sets exact, 2 of 15 within one rep. It was already failing on MetaMotion data (Part 2), so this is the same weakness, not a new domain-shift finding, and the rework is planned. My own logged counts were also uncertain, so I checked them next.

### Checking my own labels

I'd lost count on a few sets. I used the raw 50 Hz signal (band-passed first principal component of the acceleration, with peaks marked) to flag suspicious counts for a human look, not to overwrite them. The counter can't separate reps from unracking, walk-out and re-racking.

- **Consistent with the log:** bench 55 (12 reps), bench 95 (8), bench 115 (2, slow grinding cycles), squat 65 (8), squat 75 (6), row 60 (10), row 80 (8).
- **Possibly miscounted:** bench 75 (logged 12, 13 regular cycles), squat 55 (logged 8, about 10 large cycles), row 50 (logged 10, 10 to 11 cycles).
- **Can't tell from this signal:** overhead press 30, 40, 50 and deadlift 95.
- **Segment boundaries wrong:** deadlift 75 is 9.3 s with one movement in it; its 6 reps happened outside the marked set. It should be re-marked or excluded from rep evaluation.

### The upload, properly fixed

- The phone kept the session in `UPLOADING` (4 attempts, no session on the server). The upload code set `UPLOADING` before calling the server and left it there on a retryable error without recording the error, so the screen said "uploading" during backoff and hid the cause.
- The fix (D-032): a visible `WAITING_TO_RETRY` state that shows the last error, no cap on retries (30 s doubling to WorkManager's 5 h maximum), repair of stale states on app start and sign-in, and re-enqueue with `REPLACE`.
- **The likely culprit is Android's Doze,** a deep-sleep mode that cuts an app's network access while the screen is off. On inspection the phone was in deep Doze. I haven't proven it is the root cause. Follow-ups: expedited work for user-initiated uploads, and a race in core-api where two concurrent first uploads from a new watch model give an HTTP 500 that heals on retry.

### Redoing the session, this time with eyes on the data

- I couldn't trust my own rep counts from session 1, and nothing told me during the workout whether the watch was recording well. So before redoing it, the phone got a **live view**: acceleration and rotation graphs streamed from the watch, plus health counters (sampling rate, gaps over 100 ms, sensor saturation at 7.8 g, recorder drops). The redo will replace `918aa36e` in `watch-fit-v1`; the old zip stays in DVC (raw data is never deleted) but is no longer in any dataset (D-033).
- **Design rule:** the preview must never touch the recording. It reads samples after they're queued for writing, drops itself when it can't keep up (a fixed ring of 2048 samples), and only runs while the phone screen asks for it (a renewable 10 s lease, renewed every 4 s), so a forgotten phone can't drain the watch. A test proves the recorded files are byte-identical with it on or off.
- **Small data-quality fix:** I enter gym weights in pounds, but the format stores kilograms. The app now has a unit switch and converts on save (pounds x 0.45359237, rounded to 0.01 kg), with plate loads like 135 lb round-tripping exactly. No schema change.
- **Hands on the bar first** (D-034): a set now starts after a countdown (off, 3, 5 or 10 s, default 5) with a haptic tick each second and a long buzz when the set opens. The watch owns the countdown, and the set's timestamp is the end of the countdown, so walking up to the bar isn't inside the set. Protocol changes are optional fields, so old payloads still decode.
  - Rejected: watch buttons (still need a free hand), voice (gym noise) and automatic set detection (needs a model that works on the watch first; continuous recording means boundaries can be corrected later anyway).
- **Status:** the live preview was verified on the device. The countdown has been merged but still needs installing and an on-wrist check. **The redo itself has not happened.**

### Where things stand, and what is not verified

| Claim | Status |
|---|---|
| The recorder produces gap-free, well-timed data | Verified on the wrist (U1 and session 1) |
| The watch frame and units match Android's documentation | Verified by physics checks (Part 6) |
| The watch-to-training rotation is a clean left-wrist turn (determinant +1) | Fitted on one session; **in-sample** |
| 73% set accuracy for the production candidate | **In-sample, n = 15, interval 0.48 to 0.89**, not a verdict |
| The three changes (lifts-only fit, relative gate, new default profile) generalize | **Untested**; only session 2 can judge them |
| Overhead press vs bench is solved | **No.** Overhead press fails on the watch |
| Rep counting works | **No.** Fails on MetaMotion and on the watch |
| The phone-to-backend flow works on a real phone | Verified against the local stack only; the on-device check is open |

### What I rejected

- Fitting rest into the rotation (shown to hurt).
- Keeping the 0.15 g gate because I had already written it down.
- Retraining a model to cover for a rotation problem.
- Treating the first look as a verdict.

### Lessons

- Debug the stage before the model. The failure was in the alignment spec I wrote, not in the classifier.
- Question your own gate: ask what it would take for a normal training participant to pass it.
- Every change motivated by one session can only be judged on another.
- Make the failure visible to the user (the upload state, the live preview), because the person in the gym cannot see your logs.

---

## Housekeeping worth telling

- **Renamed the GitHub repo to Proprio** and rewrote the README around the product.
- **Made the interview notes private** (D-026). That meant learning the hard way that `.gitignore` doesn't untrack a file that is already committed.
  - It had been pushed, so I untracked it and removed it from every commit on `dev` and `main` with `git filter-repo`.
  - I did the rewrite in a separate fresh clone so in-progress work wasn't disturbed.
  - I verified the rewritten branch tips matched the originals except for the file.
  - I force-pushed with leases. The lease caught an unexpected remote commit, a README edit of mine, which turned out to be already included, and I checked that before anything was overwritten.
  - Caveat: GitHub may still serve the old commits by their hash until its garbage collection runs.

## Upcoming parts (to be written as they happen)

The numbering and titles below are provisional. The content comes from the next steps in `PROGRESS.md` and the phase plan in `PROJECT.md`.

- **Part 11, the real verdict.** Redo gym session 1 with the live preview, then record session 2 and freeze it as `watch-val-v1`. Run the pre-set gates once on held-out data, with Wilson intervals and bench versus overhead press as its own line. This is the first number I can call a result for the watch. Also the two friends' sessions for between-user variance.
- **Part 12, fixing overhead press and counting reps.** Decision-tree step 2: wider roll augmentation about the forearm and orientation-robust features, accepted only if they pass the gates at zero rotation on MetaMotion. The rep-counter rework (mandatory), the question of dropping the FFT features, a DVC pipeline with a model-regression CI job, model selection and a model card, and the MLflow registry with `candidate` and `production` aliases.
- **Part 13, finishing the services.** The on-device phone-to-backend check, the remaining phone upload, results and corrections screens with UI tests, and an integration review.
- **Part 14, an LLM coach that cites its sources.** Granite on watsonx.ai, retrieval over pgvector, tool calls into the user's own history, and evaluation in MLflow against a golden set.
- **Part 15, shipping it.** CI/CD to IBM Cloud, a security review, battery tuning (ambient mode, brightness), and launch.
- **Later:** per-user personalization from corrections via RabbitMQ jobs, and the stretch items (a live on-watch rep counter in Kotlin, Helm on Kubernetes, Granite LoRA).

---

## Glossary

- **Accelerometer:** a sensor that measures acceleration along three axes, including the constant pull of gravity.
- **Accuracy:** the share of predictions that are right. Misleading on unbalanced classes.
- **Aliasing:** a fast signal sampled too slowly shows up as a fake slow one. Prevented by low-pass filtering before reducing the sampling rate.
- **AUC:** area under the ROC curve. 0.5 means a classifier cannot separate two groups, 1.0 means it separates them perfectly.
- **Augmentation (rotation):** adding randomly rotated copies of training data so the model tolerates orientation differences.
- **Binning:** averaging samples that fall in a fixed time slot (here 200 ms).
- **CNN (1D):** a neural network that slides small filters along a time signal to learn features.
- **Classifier:** a model that outputs a class name from input features.
- **Confidence interval (Wilson):** a range of plausible true values for a proportion, reliable for small samples.
- **Confusion matrix:** a table of true class against predicted class, showing which classes get mixed up.
- **Contract-first:** writing the machine-readable interface description before the code, then generating code from it.
- **Cross-validation:** repeatedly holding out part of the data for testing and averaging the results.
- **CSF-1:** Canonical Sensor Frame, version 1: the one format all devices are converted into before the model sees them.
- **Dead-letter queue (DLQ):** where a message goes when retries run out.
- **Determinant:** a number computed from a matrix. +1 for a proper rotation, -1 for a mirror.
- **Domain classifier:** a model trained to tell two data sources apart, used as a diagnostic for domain shift.
- **Domain shift:** the data seen in use differs from the data seen in training.
- **DVC:** Data Version Control. Keeps a fingerprint of the data in git while the data lives elsewhere.
- **Feature:** a number computed from raw signals that summarizes something useful to the model.
- **FFT:** Fast Fourier Transform, which splits a signal into slow and fast rhythms.
- **Fold:** one train/test split inside cross-validation.
- **Foreground service:** an Android task the system treats as important enough to keep running.
- **Freecess:** Samsung's power manager that freezes apps which are not on screen.
- **g:** the acceleration of Earth's gravity, 9.80665 m/s².
- **Golden file:** a committed, deterministically generated example file that all implementations must read identically.
- **Gyroscope:** a sensor that measures rotation rate about three axes.
- **Idempotent:** doing it twice has the same effect as doing it once.
- **IMU:** inertial measurement unit: accelerometer plus gyroscope in one chip.
- **In-sample evaluation:** scoring on the same data that was used to choose settings. Optimistic.
- **Jitter:** irregularity in the time between samples.
- **Kabsch algorithm:** finds the rotation that best aligns one set of vectors to another.
- **KNN:** k-nearest neighbours: label a point by the labels of the most similar training points.
- **Leakage:** information from test data influencing training, which inflates scores.
- **LOPO:** leave-one-participant-out cross-validation: each person is the test set once.
- **Low-pass filter:** removes fast changes and keeps slow ones.
- **MAE:** mean absolute error: the average size of the miss, here in reps.
- **Macro-F1:** the average of per-class F1 scores, each class counting equally.
- **Majority vote:** the set's label is the label most of its windows chose.
- **MLflow:** a tool that records every training run's settings, metrics and models.
- **ONNX:** a portable file format for trained networks.
- **Outbox (transactional):** saving an outgoing message in the same database transaction as the change it announces.
- **PCA:** principal component analysis: compresses correlated columns into a few summary columns.
- **Presigned URL:** a time-limited, signed address that lets a client upload straight to storage.
- **PSI:** population stability index: how different one feature's distribution is between two datasets.
- **Random forest:** many decision trees voting together.
- **Recall (per class):** of the windows that truly belong to a class, the share the model found.
- **Residual (Kabsch):** how far the aligned vectors still miss, in g.
- **Rotation matrix:** a 3×3 table that turns vectors from one coordinate frame into another.
- **Sampling rate:** readings per second, in hertz (Hz).
- **Seed:** a fixed starting value that makes random steps repeatable.
- **Set-level accuracy:** the share of whole sets labelled correctly after majority vote.
- **SHA-256:** a fingerprint of a file's contents.
- **τ (tau):** the vote-share threshold below which a set is rejected as "not a lift".
- **θ_tol:** rotation tolerance: the largest misalignment before accuracy drops past the allowed amount.
- **Train/serve skew:** data prepared differently in training and in production.
- **Window:** one 200 ms row together with the features that summarize the recent past.

## Further reading

Only sources already cited in this repository:

- Hoogendoorn, M. and Funk, B., [*Machine Learning for the Quantified Self*](https://link.springer.com/book/10.1007/978-3-319-66308-1), Springer, 2018. The source of the sensor-data feature engineering ideas (filtering, temporal and frequency features) used in the first pipeline.
- The book's companion code, [ML4QS](https://github.com/mhoogen/ML4QS), from which the feature-engineering code was adapted.
- The [MetaMotion](https://mbientlab.com/metamotions/) sensor used for the training data (the dataset download is linked from the repository README).
- Inside this repository: `docs/PROJECT.md` (scope, sensor spec, gates, decision tree), `docs/DECISIONS.md` (every decision with its reasons), `docs/sensor_spec.md` (CSF-1) and `docs/reports/` (the generated and hand-written reports behind every number above).
