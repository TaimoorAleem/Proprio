# Blog 1 — draft material (not the post)

> Holding file. Blog 1 ("The 99.9% that wasn't real") isn't outlined or written yet. This section was moved here from blog 0 on 2026-11-11 at the owner's request. Its figures were generated for blog 0 and still live in `assets/post-00/` (`make_figures_post00.py`); its references point to blog 0's list, so renumber them when blog 1 is written:
> - [2] Ebbelaar's VU Amsterdam report (sampling rates).

## What a wrist sensor actually measures

Everything in this series depends on understanding the raw signal, so this section goes slowly.

![Two rows of line charts for five exercises: acceleration in g on x, y, z on top, angular rate in deg/s below](assets/post-00/fig01-raw-signals.png)
*Figure 1. What one heavy set of each lift looks like in the raw data (participant A). Each lift has its own resting levels and repeating pattern.*


The MetaMotion band, and every smartwatch, contains a tiny chip called an **IMU** (inertial measurement unit). Two of its sensors matter here.

### The accelerometer

An accelerometer measures how hard the device is being pushed along three directions, called its **x, y and z axes**.

> **The idea in plain words: gravity counts as a push.**
>
> Picture a small ball resting on a tray. Tilt the tray and the ball rolls; jerk the tray forward and the ball lags behind. An accelerometer "feels" both kinds of effect, so it can't tell tilting apart from accelerating. A device lying perfectly still on a table does **not** read zero. It reads about **1 g** pointing up, because the table is pushing it up against gravity. (1 g is the strength of Earth's gravity, 9.80665 m/s².)
>
> So even when you aren't moving, the accelerometer tells you **which way is down, relative to the device**. That's enormously useful for exercise recognition. Your wrist points one way during a bench press (hand above the wrist, arm vertical) and another way during a deadlift (hand hanging below the wrist). Before the bar even moves, gravity has already given a clue.

During a lift, the accelerometer reads gravity plus the push from your movement, mixed together. Separating the two is a recurring theme.

![Six small bar charts of mean acceleration on x, y and z for each exercise; y is about +0.9 g for bench and overhead press and about -1.0 g for deadlift and row](assets/post-00/fig02-gravity-direction.png)
*Figure 2. Gravity tells you which way your hand points. Mean acceleration per axis over all sets of each exercise in the MetaMotion dataset.*


### The gyroscope

A gyroscope measures how fast the device is **rotating** around each axis: "you are turning at 40 degrees per second around your forearm". It knows nothing about gravity. If you hold still it reads roughly zero, whatever angle you're at.

The two sensors complement each other. The accelerometer is good at "where is down" and at sharp pushes. The gyroscope is good at twists and turns.

### Axes belong to the device, not the room

The x, y and z directions are fixed to the sensor's case. Turn your wrist and the axes turn with it. Wear the watch on slightly rotated on your wrist and every reading changes, even though you lifted exactly the same way.

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

![Two dot plots over the same two seconds: accelerometer samples every 80 ms and gyroscope samples every 40 ms](assets/post-00/fig03-sampling.png)
*Figure 3. A sensor takes snapshots: the MetaMotion accelerometer samples 12.5 times a second, the gyroscope 25 times.*
