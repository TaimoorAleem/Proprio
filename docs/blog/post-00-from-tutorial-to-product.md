# From a YouTube tutorial to a real product: introducing Proprio

*Building Proprio: an intelligent smartwatch-based fitness tracker. Part 0.*

---

## 1. Where this started

In 2024 I found a YouTube series by Dave Ebbelaar called **"Full Machine Learning Project — Coding a Fitness Tracker with Python"** [1]. Over eight videos and about seven hours, it builds a program that watches the data from a sensor on your wrist and works out two things:

1. **which barbell exercise you are doing:** bench press, squat, deadlift, overhead press (OHP) or barbell row, or whether you are resting;
2. **how many repetitions you did.**

It is a complete beginner-to-intermediate machine learning project:
- reading raw sensor files;
- cleaning and plotting them;
- removing outliers;
- engineering features;
- training and comparing several models;
- writing a rep counter.

I followed it on and off. The code lives in the same repository I am using for this series, and the early commits from 2024 are still there.

When I got to the end, my best model scored **99.9% accuracy**.

That number is the reason this series exists. A model that is right 999 times out of 1,000 sounds finished. But I wanted to wear it, on my own watch, in my own gym, and I had a nagging feeling that a number that good deserved a hard second look before I built anything on top of it.

This post introduces the project: where the idea and the data come from, what the sensor actually measures, how the tutorial pipeline works, why I think it's worth turning into a real product, and the roadmap. It doesn't contain any results yet. Each later post follows the development as it happens.

## 2. The original work behind the tutorial

The tutorial stands on several layers of earlier work, and I want to credit them properly.

- **The dataset and the original study.** Ebbelaar collected the data himself, during real gym workouts, for a mini master project at the Vrije Universiteit Amsterdam [2]:
  - **Participants:** five people (himself included), wearing a **MetaMotion** wristband sensor from MbientLab [4].
  - **Exercises:** the five lifts from the *Starting Strength* program.
  - **Sets:** heavy sets of about 5 reps and medium sets of about 10.
  - **Weights:** chosen relative to each person's estimated one-rep maximum.
  - **Rest:** some resting periods were recorded too.

  The study's goal was to explore, in its own words, whether context-aware applications could "just like human personal trainers, track exercises, count repetitions, and detect improper form."
- **The method.** The pipeline follows the course *Machine Learning for the Quantified Self* by Mark Hoogendoorn, and the book of the same name by Hoogendoorn and Funk [3]. Several of the helper modules in the tutorial come straight from the book's public code (ML4QS).
- **The report's own results.** The report trained on the first two sets of each exercise and tested on the remaining sets. On that test, a random forest scored **98.51%**. It also tried harder tests, and the numbers dropped:
  - training on heavy sets and testing on medium sets (and the reverse) gave about 80%;
  - leaving one participant out at a time and testing on them averaged **85.43%** [2].

  The author concluded that "the models have some problems generalizing to new data." That sentence turned out to be the most important one for me.

So the honest starting point is this: a thoughtful student project, a clear and well-made tutorial built from it, and my own implementation of that tutorial reporting 99.9%.

## 3. What a wrist sensor actually measures

Everything in this series depends on understanding the raw signal, so this section goes slowly.

The MetaMotion band, and every smartwatch, contains a tiny chip called an **IMU** (inertial measurement unit). Two of its sensors matter here.

### The accelerometer

An accelerometer measures how hard the device is being pushed along three directions, called its **x, y and z axes**.

> **The idea in plain words: gravity counts as a push.**
>
> Picture a small ball resting on a tray. Tilt the tray and the ball rolls; jerk the tray forward and the ball lags behind. An accelerometer "feels" both kinds of effect, so it can't tell tilting apart from accelerating.
>
> The surprising part: a device lying perfectly still on a table does **not** read zero. It reads about **1 g** pointing up, because the table is pushing it up against gravity. (1 g is the strength of Earth's gravity, 9.80665 m/s².)
>
> So even when you aren't moving, the accelerometer tells you **which way is down, relative to the device**. That's enormously useful for exercise recognition. Your wrist points one way during a bench press (hand above the wrist, arm vertical) and another way during a deadlift (hand hanging below the wrist). Before the bar even moves, gravity has already given a clue.

During a lift, the accelerometer reads gravity plus the push from your movement, mixed together. Separating the two is a recurring theme.

### The gyroscope

A gyroscope measures how fast the device is **rotating** around each axis: "you are turning at 40 degrees per second around your forearm". It knows nothing about gravity. If you hold still it reads roughly zero, whatever angle you're at.

The two sensors complement each other. The accelerometer is good at "where is down" and at sharp pushes. The gyroscope is good at twists and turns.

### Axes belong to the device, not the room

The x, y and z directions are fixed to the sensor's case. Turn your wrist and the axes turn with it. Strap the band on slightly rotated and every reading changes, even though you lifted exactly the same way.

This matters for two reasons:
- **Documentation:** a manufacturer's documentation tells you how its axes are laid out, but not how the device sat on each person's wrist during data collection.
- **Changing devices:** if you swap one device for another, the axes almost certainly won't line up.

### Units are not universal

| Device | Acceleration | Rotation |
|---|---|---|
| MetaMotion (the dataset) | **g** (multiples of gravity) | **degrees per second** |
| An Android watch | **m/s²** (1 g = 9.80665 m/s²) | **radians per second** (1 rad ≈ 57.3°) |

A model trained on degrees per second and fed radians per second would see every rotation as about 57 times too small. Nothing would crash. It would just be quietly, badly wrong. Bugs like that don't announce themselves, and avoiding them is a big part of this project.

### Sampling rate

A sensor doesn't record continuously. It takes snapshots, a fixed number per second. That number is called the **sampling rate**, measured in hertz (Hz).
- **MetaMotion:** in this dataset, the accelerometer samples at **12.5 Hz** and the gyroscope at **25 Hz** [2].
- **Watches:** a smartwatch picks its own rates, and phones and watches don't always deliver samples at perfectly even intervals.

If you train on one rate and run on another, the features the model learned from can shift. A rolling average over "20 samples" means a different amount of time at a different rate.

Units, axes and rates are the three things that must match between the data a model learned from and the data it sees later. On a single device, in a single study, they match automatically. The moment you change the device, nothing guarantees it.

## 4. How the tutorial pipeline works

Here is the whole pipeline from raw files to a predicted exercise:

```
 raw CSV files (one per sensor per set; labels in the file name)
        │
        ▼
 merge + resample to 200 ms steps (5 per second)
        │
        ▼
 outlier handling (IQR · Chauvenet's criterion · local outlier factor)
        │
        ▼
 feature engineering
   · low-pass filter (smooth away jitter)
   · PCA (combine correlated signals)
   · magnitude  r = √(x² + y² + z²)  (ignores device orientation)
   · rolling mean / std over ~4 s windows
   · frequency features (Fourier transform over ~4 s)
   · cluster membership (k-means)
        │
        ▼
 models: neural network · random forest · k-nearest neighbours · decision tree · naive Bayes
        │
        ▼
 predicted exercise per 200 ms step        rep counter: smooth, then count peaks
```

A few ideas in that picture are worth explaining now.

- **Resampling.** The accelerometer and gyroscope tick at different rates. The tutorial puts them on a common clock by averaging everything that falls inside each 200 ms step. Each row of the table becomes one fifth of a second of data.
- **Features.** A single snapshot says very little: one reading could come from almost any exercise. A **feature** is a number computed to describe a short stretch of time, such as "the average y-acceleration over the last 4 seconds" or "how strongly the signal repeats every 2 seconds". Models learn from features rather than from raw snapshots.
- **Windows.** Many features are computed over a **rolling window**: the last few seconds, recomputed every 200 ms. Neighbouring rows therefore share most of their data. The tutorial deals with this by keeping only every other row. I'll come back to overlapping windows in the next post.
- **Models.** Five classic algorithms are compared, then tuned with a grid search (trying many settings and keeping the best).
- **Rep counting.** The rep counter isn't learned at all. It smooths the signal heavily and counts the peaks, with the smoothing tuned per exercise. The "true" rep counts it is compared against come from the set type (heavy = 5, medium = 10), not from someone counting each rep.

Everything in the pipeline is reasonable and standard for this kind of course project. Whether the final number means what it seems to mean is a separate question, and that's where the next post begins.

## 5. Why a notebook isn't a product

A tutorial is designed to teach. A product has to survive contact with real people. Between the two there is a long list of gaps:

- **A different sensor.** Nobody buys a MetaMotion research band to lift weights. People wear smartwatches, with different units, rates, axes and fit on the wrist (section 3).
- **New people.** A product only matters for people the model has never seen. The original report already found that results fall when the test person is new [2].
- **New conditions.** Different gyms, bars and grips; walking between sets; checking your phone mid-workout. All of this lands in the data.
- **No app, no backend, no history.** A Python notebook doesn't record a workout, label it, store it, or show you anything.
- **An unchecked number.** My 99.9% came out of one evaluation, run once. Before I trust it, I want to know exactly how the training and test data were separated, and whether anything about the test made it easier than real life.

That last point is the first job. If the number doesn't hold up, everything built on top of it inherits the problem.

## 6. Why it's worth building: the research

Before investing months, I asked whether the world needs another workout tracker.

### The consumer space is crowded, and still frustrating

Automatic exercise and rep tracking already ships, or is attempted, on the big platforms:
- **Samsung Galaxy Watch:** recognises some gym exercises, and its community forums contain recurring complaints that rep counting is unreliable for lifts like squats and bench press [10].
- **Garmin:** its strength mode has drawn criticism in reviews [11], and a 2023 study of four Garmin wrist devices found that none met the authors' validity criteria for counting reps [9].
- **Dedicated apps:** some, such as Train Fitness (now Motra) on the Apple Watch, are built entirely around automatic tracking [13].
- **WHOOP:** added automatic estimates of muscular load from wrist motion in early 2026 [12].

So "recognise the exercise and count the reps" is not, on its own, a reason for Proprio to exist. It is the necessary foundation, and the competition shows it's still not solved well. But it isn't enough of a reason by itself.

### The unsolved problem: did the prescribed exercise actually happen?

The more interesting gap is **unsupervised, prescribed strength work**: the exercises a physiotherapist or coach tells you to do at home.

- **Non-adherence is common.** A 2021 paper summarises earlier research: "patient non-adherence with rehabilitation programs is 50%-70%" [5].
- **Self-reports overstate it.** In a 2018 study, people with knee pain kept an exercise diary while an accelerometer hidden in their ankle weight counted what they actually did. The diaries recorded a median of **220** exercises; the hidden sensor counted **176** [6]. People didn't lie badly, but the diary was consistently optimistic.
- **Clinicians lack tools.** A 2023 survey of 149 clinicians found that the most common barrier to measuring adherence was not having a tool to do it [7].
- **The approach works in research.** Researchers in Toronto (Sunnybrook and the Holland Centre) showed that smartwatch inertial data could recognise shoulder physiotherapy exercises [14]. They then designed a study in which data labelled in the clinic is used to classify patients' home sessions [15]. Wrist accelerometers also appear as adherence measures in clinical trials [8].
- **Wrists can measure more than rep counts.** In a 2023 study, a wrist-worn Apple Watch estimated mean barbell squat velocity about as well as sensors mounted on the bar [16]. There is early work on estimating effort, too, though only as a preprint so far [18]. Reviews of commercial training sensors also warn that validation is often weak [17], which is a good reason to measure carefully rather than trust marketing.

That's the opening: research has shown the idea works, but there's no product a clinician can simply buy. The roadmap at the end of this post comes back to it.

## 7. Meet Proprio

**Proprio** (short for *proprioception*, the body's sense of its own position and movement) is my attempt to turn the tutorial into something real:

- **A Samsung Galaxy Watch** records the raw motion of a whole workout, worn on the left wrist.
- **An Android phone app** lets you label sets, fix mistakes, and see your history.
- **Backend services** store the raw recordings, convert them, recognise exercises, count reps, and send the results back to the phone.
- **An AI coach** explains your training in plain language, grounded in your own recorded history rather than generic advice.

Two principles run through all of it:

1. **Keep the raw data.** The watch records exactly what its sensors measured, unconverted. Every conversion happens later, in one shared piece of code, so it can be fixed and re-run if it turns out to be wrong.
2. **Measure before claiming.** Every accuracy number in this series will come from a test designed to resemble real use, with its limitations stated next to it.

## 8. The roadmap: what I'm building now

These are the phases, in order. Each one will produce at least one post.

| Phase | Goal, in plain words |
|---|---|
| 0 | **Check the baseline honestly.** Make the tutorial code reproducible, rerun it, and find out whether 99.9% survives a fair test. |
| 1 | **Teach it to read another sensor.** One standard format that any device's data is converted into before a model sees it, and a simulated watch to test that. |
| 2 | **Build the recorder.** A Galaxy Watch app and a phone app that record a whole workout with clearly marked sets. |
| 3 | **Calibrate and test on real gym data.** Check the watch's units and axes physically, record real sessions, and judge the model against pass/fail rules written in advance. |
| 4 | **Better models.** A small deep-learning model and a reworked rep counter, if the classic models fall short on the watch. |
| 5 | **Services.** A backend that receives recordings from the phone, analyses them, and returns results reliably. |
| 6 | **The coach.** A language model (IBM Granite) that answers questions about your training and cites where each answer came from. |
| 7 | **Ship it.** Automated testing, a security review, and deployment to the cloud. |
| 8 | **Personalisation.** Use each user's corrections to improve the model for that user. |

## 9. The roadmap: where it could go

These are **ideas, not commitments**. They answer the question from section 6: what could a wrist-worn strength tracker do that the crowded market doesn't?

- **A. Prescription adherence ledger.**
  - **Prescription:** a physiotherapist, kinesiologist or coach prescribes exercises (sets, reps, rest, tempo, days per week) and records a short demo set of each on the client's watch.
  - **At home:** the client just starts and stops a session. The system detects the sets, matches each one to a prescribed exercise (or "unknown"), and counts reps, rest and rep duration.
  - **Result:** both sides see a weekly ledger of what was done against what was prescribed. Any AI summary may only restate computed facts, and must point to the sessions they came from.
- **B. Wrist velocity-loss feedback** (the riskiest idea).
  - **Idea:** estimate how much the lift slows down within a set, from the wrist alone, and buzz at a chosen point (for example a 20–25% drop).
  - **Why:** it offers lifters some of what a dedicated bar sensor does.
  - **What it must never claim:** to tell you how close you are to failure. Velocity loss only roughly tracks that.
- **C. A verified session record for remote trainers.**
  - **The gap:** online coaching platforms show what clients *say* they did.
  - **Idea:** a watch-verified record of sets, rest and session length, compared with the logged workout. For example: "logged 4 sets, watch saw 2" or "rest averaged 45 s vs 2 min prescribed".
- **D. Tempo and time-under-tension compliance.**
  - **Idea:** split each rep into its lifting and lowering phases and compare them with a prescribed tempo, as used in tendon rehab and tempo training.
  - **Order:** after the session first, real-time buzzes later.

Ideas A and C would put Proprio close to healthcare. That brings rules on medical software and health data, in Canada [19] and in the US [20]. That's another reason they're ideas for later, built on a foundation that has to work first.

## 10. Ground rules for this series

- **Real numbers, including failures.** If something doesn't work, the post says so, with the evidence.
- **Optimistic numbers are labelled.** If a result was measured on the same data used to tune the system, it is marked as optimistic.
- **Every technology has a genuine job.** No tool is added just to say it was used. If something is a weak fit, I'll say that too.
- **Posts follow development.** I don't know how this ends. Each post is written when its part of the work is done.

## 11. What's next

The first job is the one from section 1. My model scored 99.9%. In the next post I take the tutorial code apart, make it reproducible, and ask a simple question:

**If you give this model a person it has never seen, how often is it right?**

## 12. References

1. Ebbelaar, D. *Full Machine Learning Project — Coding a Fitness Tracker with Python* (video series, 8 parts). https://youtube.com/playlist?list=PL-Y17yukoyy0sT2hoSQxn1TdV0J7-MX4K
2. Ebbelaar, D. *Exploring the Possibilities of Context-Aware Applications for Strength Training.* Mini Master Project, Vrije Universiteit Amsterdam. (Linked from the tutorial's source material.)
3. Hoogendoorn, M. & Funk, B. (2018). *Machine Learning for the Quantified Self: On the Art of Learning from Sensory Data.* Springer. https://link.springer.com/book/10.1007/978-3-319-66308-1 · Code: https://github.com/mhoogen/ML4QS/
4. MbientLab. MetaMotion sensor. https://mbientlab.com/metamotions/
5. Picha, K. et al. (2021). Physical therapists' assessment of patient self-efficacy for home exercise programs. *International Journal of Sports Physical Therapy*, 16(1), 184–194. https://doi.org/10.26603/001c.18957 (the 50–70% figure is from the paper's introduction, summarising earlier studies)
6. Nicolson, P. J. A. et al. (2018). Self-reported home exercise adherence: a validity and reliability study using concealed accelerometers. *Journal of Orthopaedic & Sports Physical Therapy*, 48(12), 943–950. https://doi.org/10.2519/jospt.2018.8275
7. McArthur, C. et al. (2023). Older adults, clinicians, and researchers' preferences for measuring adherence to resistance and balance exercises. *BMC Geriatrics*, 23, 530. https://doi.org/10.1186/s12877-023-04237-x
8. ClinicalTrials.gov. *Adherence in Upper Extremity Home-based Rehabilitation* (NCT05032638). https://clinicaltrials.gov/study/NCT05032638
9. Ziegler, W. F. et al. (2023). Repetition count concurrent validity of various Garmin wrist watches during light circuit resistance training. *International Journal of Exercise Science: Conference Proceedings*, 14(3) (conference abstract). https://digitalcommons.wku.edu/ijesab/vol14/iss3/143
10. Samsung Community. "Galaxy Watch 4 Classic rep tracking is not very accurate" (user forum thread). https://eu.community.samsung.com/t5/wearables/galaxy-watch-4-classic-rep-tracking-is-not-a-very-accurate/td-p/4955247
11. TechRadar. "Why Garmin's strength training mode needs to be improved – or scrapped." https://techradar.com/features/why-garmins-strength-training-mode-needs-to-be-improved-or-scrapped
12. the5krunner (2026). "New WHOOP Strength Trainer update: Passive MSK explained." https://the5krunner.com/2026/02/28/new-whoop-strength-trainer-update/
13. Motra (formerly Train Fitness), App Store listing. https://apps.apple.com/us/app/motra-formerly-train-fitness/id1548577496
14. Burns, D. M. et al. (2018). Shoulder physiotherapy exercise recognition: machine learning the inertial signals from a smartwatch. arXiv:1802.01489. https://arxiv.org/abs/1802.01489
15. Burns, D. M. et al. (2020). Adherence tracking with smart watches for shoulder physiotherapy in rotator cuff pathology: protocol for a longitudinal cohort study. *JMIR Research Protocols*, 9(7), e17841. https://doi.org/10.2196/17841
16. Achermann, B. et al. (2023). Velocity-based strength training: the validity and personal monitoring of barbell velocity with the Apple Watch. *Sports*, 11(7), 125. https://www.mdpi.com/2075-4663/11/7/125
17. Weakley, J. et al. (2021). The validity and reliability of commercially available resistance training monitoring devices: a systematic review. *Sports Medicine*, 51, 443–502. https://doi.org/10.1007/s40279-020-01382-w
18. Thomas, J. & Wahlström, J. (2025). Estimation of resistance training RPE using inertial sensors and electromyography. arXiv:2510.03197 (preprint). https://arxiv.org/abs/2510.03197
19. Health Canada (2019). *Guidance Document: Software as a Medical Device (SaMD): Definition and Classification.* https://www.canada.ca/en/health-canada/services/drugs-health-products/medical-devices/application-information/guidance-documents/software-medical-device-guidance-document.html
20. U.S. Food and Drug Administration (2026). *General Wellness: Policy for Low Risk Devices* (updated guidance).
