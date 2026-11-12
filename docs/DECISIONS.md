# Decision Log

Append-only. Newest at the bottom. Format: **ID · date · decision**, followed by the reason and the alternatives rejected.
Scope-affecting decisions must also be reflected in [`PROJECT.md`](PROJECT.md).

---

**D-001 · 2026-10-21 · Target watch: Samsung Galaxy Watch (Wear OS)**
The owner has one. Raw IMU comes from the standard Android `SensorManager`; Samsung's Health Sensor SDK isn't needed.

**D-002 · 2026-10-21 · Backend split: Java (Spring Boot) core-api + Python ML services**
Different runtimes justify a real service boundary, and the split covers both Java and Python. Rejected: all-Python, which means less Java/JUnit coverage.

**D-003 · 2026-10-21 · Cloud: IBM Cloud (free tier + trial credits)**
Code Engine, COS and Container Registry. Postgres runs on IBM Cloud Databases and RabbitMQ on IBM Cloud Messages for RabbitMQ (both paid, so use trial credits during the demo window). docker-compose for daily work.

**D-004 · 2026-10-21 · Timeline: 12+ weeks**
Stretch items (K8s, Granite LoRA, live on-watch counter) are included as optional.

**D-005 · 2026-10-21 · No web client**
The owner chose to skip it. Consequences: HTML/JS/Ajax is an acknowledged gap, Selenium/Puppeteer are dropped, and UI automation uses Espresso/Compose UI tests. The recording-review UI moves to the phone.

**D-006 · 2026-10-21 · Left wrist only**
The MetaMotion dataset is assumed to be recorded on the left wrist (the source doesn't document it), and users always wear the watch on the left wrist. No mirror transform. The Kabsch fit residual and det check verify the assumption, and the decision gets escalated if they contradict it.

**D-007 · 2026-10-21 · Product name: Proprio**
Android package id defaults to `dev.proprio.*`, pending owner override.

**D-008 · 2026-10-21 · Friends join validation collection (U3)**
Two people do 10 sets each, with written consent, to estimate between-user variance.

**D-009 · 2026-10-21 · Baseline cleanup committed to main; all work on `dev`**
Legacy exploratory scripts were removed and generated artifacts untracked (commit `ca6f57f`). All branches come off `dev` and merge back to `dev`. **Nothing goes to `main` until the owner says so.**

**D-010 · 2026-10-21 · Commit authorship: owner only**
No Claude co-author trailers on commits and no Claude attribution in PRs.

**D-011 · 2026-10-21 · Source of truth: `docs/PROJECT.md` in the repo**
Versioned with the code and readable by every future session. Rejected: a Claude Doc on claude.ai, which drifts and needs a connector to read.

**D-012 · 2026-10-21 · Raw sensor data is kept; conversion to CSF-1 happens server-side in shared Python code**
This avoids train/serve skew, and calibration can be fixed after the fact by reprocessing raw data. Rejected: converting on the watch or phone.

**D-013 · 2026-10-21 · Per-set inference for the MVP (not streaming)**
Sets have a median length of 19 s, so a result 1–2 s after the set is acceptable. A live on-watch counter is a stretch goal, checked against Python golden vectors.

**D-014 · 2026-10-21 · RabbitMQ over Kafka; Gradle over Maven; PostgreSQL over Cassandra**
RabbitMQ gives task-queue semantics, retries and a DLQ, with no need for replay or streaming. Android already requires Gradle. The data is relational at small scale, and pgvector gives a vector store in the same DB.

**D-015 · 2026-10-22 · Adopt MLflow for experiment tracking, model registry and LLM evaluation**
Owner request. Every training run is tracked (params, metrics, plots, DVC data hash, git SHA). The registry's `candidate`/`production` aliases drive the CI regression gate and what inference-svc serves, so promotion and rollback don't need a redeploy. The coach's golden-set evaluation runs through `mlflow.evaluate` with versioned prompts. Hosting: local server for development; in the cloud, an MLflow server on Code Engine with its backend DB on the existing IBM Postgres instance and artifacts in COS. Division of labor: DVC versions data, MLflow tracks runs and models. Rejected: a JSON model card in the repo as the registry (no promotion workflow, no run history).

**D-016 · 2026-10-22 · Ignore the 23 duplicate `*_1.4.41.csv` raw files**
They are value-identical re-exports of `*_1.4.4.csv` sets with timestamps shifted by exactly +24 h (all 23 pairs verified). The loader keys sets by file stem, keeps the `1.4.4` file and logs each ignored copy. The dataset is 82 unique sets. The old pipeline double-counted 11 sets plus one accelerometer-only file, which also leaked under its random split. The raw DVC dataset is left untouched (immutable raw data); deduplication happens in code.

**D-017 · 2026-10-22 · Phase 0 baseline accepted; set-level accuracy is the primary product metric**
LOPO best: NN on basic + derived + in-fold PCA + temporal features, window acc 0.963 ± 0.047, macro-F1 0.957 ± 0.054, set acc 0.963 ± 0.052. The top models are within fold noise of each other. Window macro-F1 stays as the diagnostic metric. Consequences: Phase 1 adds a rotation-sensitivity test (the model relies on absolute axes, and bench/OHP is the fragile pair); Phase 4 rep-counter rework is mandatory; dropping the FFT features is to be evaluated.

**D-018 · 2026-10-22 · Train with rotation augmentation (≤ 30°, K = 8); keep the 0.15 g calibration gate; amend the §7.3 rule**
The rotation-sensitivity study (`reports/phase1-rotation-sensitivity.md`) showed the Phase 0 model tolerates only 2° of misalignment before bench/OHP recall collapses (worst on the z axis). Results:
- magnitude-only features: rotation-proof, but fail the macro-F1 gate at zero rotation (0.848);
- augmentation ≤ 15° (K = 4): 5°;
- augmentation ≤ 30° (K = 4 or K = 8): 10°, at a cost of 0.5 pp macro-F1.

The pre-set rule would have picked magnitude-only features. It is amended: a remedy must itself pass the §6.5 gates at zero rotation, and the choice among passing remedies goes to the one that loses least at zero rotation. K = 8 is chosen over K = 4 for better OHP recall under large rotations.

Consequences:
- the Kabsch residual gate stays 0.15 g (≈ 8.6° < 10°);
- augmentation becomes the default training regime (classical pipeline now, CNN in Phase 4);
- Phase 3 evaluates baseline and augmented models on real watch data.

Rejected: magnitude-only features (fail a gate); tightening calibration to 2° (likely unachievable from human posture).

**D-019 · 2026-10-22 · Record continuous sessions with segments; the phone writes labels to a separate file**
The watch records the whole session continuously; sets, rest and other are time segments inside it. This captures rest and "other" motion as real sensor data (the training set has no negatives), lets set boundaries be corrected later without data loss, and means one transfer per session. The watch's raw files are immutable; the phone's `labels.json` holds labels and boundary overrides. Rejected: one file per set (loses rest/other data, can't fix boundary mistakes) and labels inside the watch's meta (mutates raw data).

**D-020 · 2026-10-22 · Rotation augmentation is the pipeline default; model selection re-runs with it**
`python -m workout_ml.pipeline` now trains every model on the originals plus 8 rotated copies (≤30°), inside each LOPO fold, with training participants only. `--no-augment` reproduces the Phase 0 baseline exactly; `phase0-baseline.md` stays frozen and the live report is `current-baseline.md`. With augmentation the best combination by macro-F1 is temporal/KNN (window acc 0.966, macro-F1 0.959, set acc 0.986), ahead of the NN (0.952). Because D-018's 10° tolerance was measured on the NN only, KNN/RF are being rotation-tested before any is treated as production. Runtime rises from 7 to 20 minutes, which is acceptable for an offline pipeline.

**D-021 · 2026-10-22 · Production candidate: temporal features + KNN, trained with rotation augmentation**
With augmentation, temporal/KNN has the best LOPO numbers (set acc 0.986, macro-F1 0.959) and the widest rotation tolerance measured (15°, vs NN 10°). It is the candidate for Phase 3 watch evaluation, alongside the NN as a comparison. RF/R2w8 scores θ_tol 0° on the rule but is behaviourally robust to about 20°: one set flips at 2° and accuracy then stays flat. That exposes a limit of the §7.3 rule: a 2 pp set-accuracy threshold is finer than one set in a small fold (a fold has 9 to 27 sets). Future tolerance rules should use pooled set counts or require the drop to persist across two consecutive angles. Not changed retroactively.

**D-022 · 2026-10-22 · Watch and phone apps share `applicationId` `dev.proprio`**
The Wearable Data Layer only connects apps with the same package name and signing key. The spec's separate ids (`dev.proprio.wear`, `dev.proprio.mobile`) meant the phone never found the watch, which unit tests with fake transports could not catch. Kotlin namespaces stay separate. The `package` strings inside recordings remain component labels.

**D-023 · 2026-10-22 · Samsung Freecess must be handled before any data collection**
On the Galaxy Watch5 Pro, Samsung's Freecess freezes the recorder process (`FZ … reason: LEV`) about 15–20 s after the screen turns off, and disables its partial wake lock, even with a `health` foreground service. The 300-event sensor FIFO (~6 s) then overflows and samples are lost. In the first real session, 7 gaps (up to 22.6 s) fell inside labeled sets. Adding the app to the battery allow-list (`deviceidle whitelist`) did not help (frozen for 133 s of a 152 s session) and was reverted. Data collection (U1, U2, U3) is blocked until a 5-minute screen-off session has zero gaps over 100 ms. Candidate fixes, tested on the device in this order: the Wear OS Ongoing Activity API, an ambient-mode recording screen, and a Health Services exercise session.

**D-024 · 2026-10-22 · Keep the dimmed recording screen on during sessions (Freecess fix)**
Device tests on the Galaxy Watch5 Pro showed Samsung Freecess freezes any non-TOP process, including a `health` foreground service (procState 4). The Wear OS Ongoing Activity API, a Health Services strength-training exercise (it runs in the Health Services process, not ours), and ambient mode (untestable with Always-on display off) all failed: each run froze about 5–12 s after screen-off. Holding `FLAG_KEEP_SCREEN_ON` with brightness 0.01 while recording keeps the activity TOP. Results: 0 freezes and 0 gaps over 180 s on the charger, and 289.7 s at 49.85 Hz with a maximum interval of 20.2 ms on the wrist. Cost: screen-on battery drain during sessions (not yet measured). The default strategy is `all` (including `screen_on`). Ambient mode with Always-on display remains a possible battery-friendlier alternative, to be tested later. Rejected: battery allow-list (no effect), and periodic broadcast keep-alives (unnecessary hack once `screen_on` works).

**D-025 · 2026-10-22 · Readable recording screen now; battery optimization deferred to pre-launch**
At brightness 0.01 with dark-gray text the recording screen was barely readable on the owner's watch. The brightness override is removed; the recording screen stays on (D-024) with a black background and readable light text. Battery tuning (brightness, ambient mode with Always-on display, measuring drain per session) is deferred until before launch, at the owner's request. Data collection needs correct, gap-free recordings, not long battery life.

**D-026 · 2026-10-23 · Interview notes are private: untracked and purged from history**
The owner added `docs/INTERVIEW_NOTES.md` to `.gitignore`. Ignoring doesn't untrack a committed file, and it had been pushed, so it was untracked and removed from every commit on `dev` and `main` with `git filter-repo`. The rewrite ran in a separate fresh clone so no local worktree was disturbed. The rewritten tips were verified identical to the originals apart from the file, then force-pushed with `--force-with-lease`. The lease caught an unexpected remote commit (the owner's README edit), which turned out to be already included. The file stays local (backup at `C:\Users\taimo\Documents\proprio-private\`). `BUILD_JOURNAL.md` remains public. Caveat: GitHub may still serve the old commits by SHA until its garbage collection runs; for certainty, ask GitHub Support to purge cached views.

**D-027 · 2026-10-23 · Inference service: one shared inference function, queue-level TTL retries, deterministic event ids**
- **One code path.** `workout_ml.watch.predict.analyze_session` is what both the Phase 3 harness verdict and the service run (D-012), and a test checks it gives the same per-set decisions as the harness. `train_bundle` trains M1 through the harness's own `ModelBank`, so the served model is the evaluated one.
- **Retries.** Transient errors go through three delay queues with *queue-level* TTL (5/15/45 s) that dead-letter back to the main queue. Per-message TTL in a single queue was rejected: RabbitMQ only expires the head, so a long delay would block a short one. Permanent errors (`hash_mismatch`, `invalid_format`, `unsupported_device`, `object_missing`) publish `failed` immediately. Exhausted messages go to the service's own DLQ **and** publish an `internal` `failed` event, so no session hangs in `UPLOADED`. The DLQ is not bound to core-api's fanout DLX, so the two never mix.
- **Idempotency.** The `session.analyzed` id is a UUIDv5 of the uploaded event id; analysis is deterministic, so redelivery is a no-op at core-api's dedupe.
- **Process model.** API and worker in one process (worker as a thread): one model in memory, one `/health`. A standalone worker entrypoint exists for scaling separately.
- **Known trade-off.** `session.analyzed` is published `mandatory`; if core-api's queue isn't declared yet the message is redelivered without a cap, chosen over losing results.
- **Model files** are joblib (pickle) bundles, treated as trusted artifacts mounted or fetched from MLflow, never committed (constraint: no pickles in git).

**D-028 · 2026-10-25 · Local stack: two S3 endpoints, synthetic model for CI, dev-only keys**
- **Presigning.** Services reach MinIO as `http://minio:9000`; presigned URLs are signed for `S3_PUBLIC_ENDPOINT` (default `http://localhost:9000`, a LAN address for a real phone), because a signature is bound to the host the client uses.
- **Model in CI.** The real model needs DVC data CI doesn't have, so a one-shot `model-init` job trains a bundle on synthetic data named `synthetic-fixture` (it can't be mistaken for a real model). A real bundle mounted via `MODEL_DIR` is never overwritten. The E2E asserts structure and the state machine, not exercise labels.
- **Keys.** core-api's `dev` profile generates an ephemeral RS256 key at startup; no private key is committed. A fixed key needs the `prod` profile.
- **MinIO.** Chainguard's distroless image (the community image left Docker Hub) has no shell, so it has no healthcheck; the `createbuckets` job gates dependents instead. Chainguard's free tier only publishes `:latest`, an accepted reproducibility gap for the local stack.
- **Deferred.** The MLflow tracking server in compose (not needed by the Phase 5 services).

**D-029 · 2026-10-25 · Phone ↔ backend: generated client, JVM-testable core, refresh token in a Tink vault**
- **Contract-first on the phone too.** The Kotlin client is generated at build time from `contracts/openapi/core-api.yaml` (OpenAPI Generator 7.14.0, same as core-api; `jvm-retrofit2` + kotlinx.serialization), never committed. A test asserts the generated methods equal the contract's 17 operations. Two generator workarounds (free-form `LabelsDocument` → `Map<String, JsonElement>`, `@JvmSuppressWildcards` patch) live in the module's build script.
- **Logic in a pure-JVM `:backend` module** (auth, upload, analysis mapping), so it's tested with MockWebServer without Android; `:mobile` only wires WorkManager, storage and UI.
- **Auth.** Access token in memory; refresh token encrypted with Tink (AES-256-GCM, keyset wrapped by an Android Keystore key; `security-crypto` is deprecated). One synchronized refresh per 401 burst, on a separate dispatcher to avoid deadlock; refusal signs out, a network failure doesn't. Known limit: a crash between server rotation and local save forces a re-login.
- **Upload idempotency.** create → PUT untouched zip → complete → labels; 409s resolve by reading server state; completion persisted per session so a finished session is never resent; labels only when their hash changes, with ETag/If-Match. WorkManager: network constraint, exponential backoff, 6 attempts, unique work per session.
- **OkHttp pinned at 5.1.0** because 5.5.1 needs compileSdk 37, which CI doesn't install.
- **Debug builds allow cleartext to any host** (network-security domain rules can't express LAN ranges); release builds reject `http://`.

**D-030 · 2026-10-26 · U2 session 1 is `watch-fit-v1`; first look says the rotation fit must exclude rest (proposal)**
- Session `918aa36e` (73 min, 15 sets) is the fit set. The phone's upload stalled, so it was pulled over adb and packaged exactly as the phone's export does (watch files byte-identical + `labels.json`). `load_kg` holds pounds per the owner's note; loads aren't model inputs.
- In-sample, as specified: Kabsch residual 0.337 g (fail), M1 set accuracy 0.60. Cause: 17 non-static rest gaps outweighed 15 lifts in the fit; MetaMotion rest comes from 2 of 5 people in one posture. Fitted on lifts only: residual 0.148 g, M1 0.73, and R is a clean ~90° turn about the screen normal (+x, the button side, points down the forearm on a left wrist), ~176° from the prior. The prior already had +x distal; its error was a 180° roll (the signs of MetaMotion's unknown x and z).
- MetaMotion's own between-participant spread of per-exercise gravity is 0.158 g RMS, so the absolute 0.15 g gate sits at the noise floor.
- **Proposed, pending owner approval:** (A) fit R on lifts only; (B) relative Kabsch gate ≤ 1.5× MetaMotion between-participant RMS; (C) replace the prior with the physical left-wrist rotation. These changes were motivated by session 1, so session 2 (`watch-val-v1`, frozen) is the only fair judge of them.

**D-031 · 2026-10-26 · Owner approved A, B, C from D-030**
(A) R is fitted on lifts only; (B) the Kabsch gate becomes relative: residual ≤ 1.5 × MetaMotion's between-participant RMS of per-exercise gravity (≈ 0.24 g), still with det = +1 and the gyro check; (C) new prior `galaxy-watch-prior-v1` = 90° about the screen normal (+x distal), owner-confirmed left wrist with the buttons toward the hand; v0 kept for reproducibility. All three were motivated by session 1; session 2 (`watch-val-v1`, frozen) judges them.

**D-032 · 2026-10-26 · Phone uploads retry forever and show why they're waiting**
Transient failures now retry without a cap (30 s doubling to WorkManager's 5 h maximum) in a visible `WAITING_TO_RETRY` state with the last error; `UPLOADING` means a request is in flight. App start and sign-in repair stale `UPLOADING` states and re-enqueue with `REPLACE` (the old `APPEND_OR_REPLACE` chained manual retries behind a job sleeping in backoff). The root cause of the stalled session-1 upload isn't proven; on inspection the phone was in deep Doze, which blocks app network access until the screen is on. Follow-ups: expedited work for user-initiated uploads; the `devices` find-then-insert race in core-api (HTTP 500 on concurrent first uploads from a new watch model, self-healing on retry).

**D-033 · 2026-10-27 · Live sensor preview; U2 session 1 is redone; loads entered in the user's unit**
- **Why:** the owner lost count on several sets and couldn't tell during the session whether data was good, so session 1 is redone with feedback. The redo replaces `918aa36e` in `watch-fit-v1`; the old zip stays in DVC (raw data is never deleted) but is no longer in any dataset.
- **Live preview:** the watch streams the samples it has already captured to the phone (binary batches every 200 ms on `/proprio/v1/live/samples`) only while the phone holds a 10 s lease that it renews every 4 s while the live view is on screen. The tap runs after the recording path, never back-pressures it (fixed 2048-sample ring, drops counted), and a test proves the recorded files are byte-identical with and without it. Display-only: never stored or uploaded. Health counters (rate, gaps > 100 ms, saturation at 7.8 g, recorder drops, write errors) separate real data loss from Bluetooth preview loss.
- **Weight unit:** kg | lb setting; `labels.json` `load_kg` stays canonical kg (lb × 0.45359237, 0.01 kg), so no schema change.

**D-034 · 2026-10-27 · Hands-free set start: a countdown owned by the watch**
The owner needs both hands on the bar before a set starts. "Start set" now runs a countdown on the watch (Off/3/5/10 s, default 5, set on the phone) with a tick each second and a long buzz when the set opens; the segment's start timestamp is the countdown's end, not the tap. Tap to cancel; ending the session cancels silently. Protocol changes are optional fields (`SegmentStartCommand.countdownMs`, `WatchStatus.countdownEndsInMs`) plus `/proprio/v1/segment/cancel`, so old payloads decode unchanged. Rejected: watch buttons (still need the other hand), voice (gym noise), automatic set detection (needs a model that works on the watch first; the continuous recording means boundaries can also be corrected later).

**D-035 · 2026-11-11 · Record four future roadmap ideas, outside the current scope**
The owner recorded four candidate features in `docs/ROADMAP_IDEAS.md`: (A) a prescription adherence ledger for clinicians and their clients, (B) wrist velocity-loss feedback, (C) a session truth feed for remote trainers, and (D) tempo and time-under-tension compliance. They give Proprio a reason to exist beyond classification, which is a crowded space. They change no phases, gates, priorities or code. A separate file keeps them apart from the source-of-truth spec; `PROJECT.md` only links to it. The file notes where the ideas conflict with fixed decisions (no web client D-005, left wrist only D-006, closed exercise enums in the contracts, user-only data access) and the cheap ways to keep the door open. Adopting any idea needs its own decision.

**D-036 · 2026-11-11 · Apple Watch + iPhone support after the Android product**
Proprio will also run on Apple Watch. Android (Galaxy Watch + Android phone) is built first because it's the hardware the owner can test on; the watchOS recorder and iPhone app follow once the Android product is done (new Phase 9). This amends D-001 (the Galaxy Watch is the *first* target, not the only one).
- **Already platform-neutral:** raw data is kept unconverted and converted server-side through device profiles (CSF-1), so an Apple Watch needs a new profile and a calibration run, not new models. Raw Format v1 is a documented format with JSON Schemas and committed golden sessions, so a Swift writer can be proven conformant. The backend contract is OpenAPI, so an iPhone client can be generated like the Android one.
- **Apple-specific work:** an Apple Watch only pairs with an iPhone, so this means two apps (watchOS + iOS), with WatchConnectivity instead of the Wearable Data Layer and a workout session for background recording. Core Motion reports acceleration in g and rotation in rad/s, with its own axis convention.
- **Keep the door open now (cheap):** no Android-only assumptions in contracts, schemas, event payloads or server code; device facts stay in profiles; the left-wrist rule stays (watchOS can report wrist and crown side, worth recording in `meta.json` when that app is built).

**D-037 · 2026-11-11 · Features A–D become committed scope: Phases 5A–5D**
The owner made the four features from D-035 commitments and placed them between the services MVP (Phase 5) and the LLM coach (Phase 6): 5A prescription adherence ledger, 5B wrist velocity-loss feedback, 5C verified session record for remote trainers, 5D tempo and time-under-tension compliance. `docs/ROADMAP_IDEAS.md` is renamed `docs/ROADMAP_FEATURES.md`. Letter suffixes keep every existing phase number, and every decision that cites one, valid.
- **Open decisions this creates (owner):** a clinician/trainer interface for 5A and 5C conflicts with D-005 (no web client); rehab clients on either wrist conflict with D-006 (left wrist only). Both must be decided before 5A starts.
- **Prerequisites:** automatic set detection (D-034 named it the long-term direction), the Phase 4 rep-counter rework, an open exercise vocabulary in the contracts (today a fixed enum of five lifts), a consented client → clinician sharing model, and a privacy and medical-device review for 5A. 5B is the highest-risk feature: it is validated against a reference before any cue reaches a user, and it is never described as reps in reserve or proximity to failure.

**D-038 · 2026-11-11 · Blog platform: Medium; diagrams in Mermaid**
The series is published on Medium. Diagrams are written as Mermaid in the repo drafts (readable on GitHub, editable as text). Medium renders neither Mermaid nor Markdown tables, so publishing a post means exporting each Mermaid diagram to an image and turning tables into lists or images. Generated images stay out of git (`docs/blog/assets/`, gitignored).
