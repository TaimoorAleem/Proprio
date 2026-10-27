# Proprio phone app (`:mobile`)

## Labeling conventions

- Labels are written to `labels.json` (Raw Format v1) keyed by the watch's segment id; the watch's raw files are never modified.
- **Discarded segments.** `labels.schema.json` has no "discarded" kind. A segment the user marks "Discard (accidental)" in the stop prompt (offered by default when a segment was shorter than 3.0 s, measured on the phone from the STARTED/STOPPED event arrival times) is stored as kind `other` with the exact note `"discarded"`; exercise, load, category, reps and RPE are cleared. Readers must treat `note == "discarded"` as "ignore this segment": `workout_ml.sensors.android.Session.segments()` skips such segments unless `include_discarded=True`.
- **Unconfirmed labels.** The Review screen marks a `set` without `reps_user` as "unconfirmed" (chip in the list, orange bar on the plot). A watch-started set only gets `reps_user` when the user confirms exercise and reps in the stop prompt, so a missing value means the exercise/load were only the phone's guess at press time. No file-format change.
- **Starting a session from the phone.** The phone first opens Proprio on the watch with a `RemoteActivityHelper` intent (`ACTION_VIEW`, `proprio://session/start?kind=GYM&participant=P01`) so the watch app is visible and may start its foreground service, then sends the usual `session/start` message as a fallback. If both fail the phone shows "Open Proprio on your watch, then try again."

## Live sensor preview

While the watch records, the Session tab shows a **Live** card (Expand opens it full screen): acceleration (g) and angular rate (deg/s) as two stacked rolling 10 s charts (x, y, z in fixed order, three colors per theme, drawn on a Compose `Canvas` with a primitive ring buffer and a ~25 fps redraw cap; no chart library) and a health row. The data is display-only: `WearWatchLink.onLiveSamples` decodes `LiveCodec` batches into `LiveModel` (unit conversion m/s^2 -> g and rad/s -> deg/s happens only there), and nothing is written to the session store or the upload queue.

- **Lease.** `LivePreviewController.runVisible` runs inside `repeatOnLifecycle(STARTED)`: it sends `live/subscribe` (10 s lease) immediately and every 4 s, and `live/unsubscribe` when the panel leaves the screen or the app goes to the background. If that message is lost the watch's lease expires by itself.
- **Reading the health row** (every item has a text label and a glyph, not just a color):
  - Link: LIVE (batch in the last 1 s), STALE (older than 1 s), NO DATA (none for 5 s or yet). Only about the phone link.
  - ACC / GYR rate, measured on the watch: OK for 45..55 Hz, otherwise CHECK (the target is 50 Hz).
  - Gaps >100 ms: whole-session count of inter-sample gaps on the watch. Should stay 0; anything else is BAD (samples were not delivered or recorded in time).
  - Saturation: acc samples with any |axis| >= 7.8 g (the sensor range is about 8 g); CHECK when above 0 (clipped data).
  - Watch recorder: OK, "N dropped" (the recorder queue overflowed) or WRITE ERROR. Both are real data loss.
  - Preview lost: batches missing in transit (sequence gaps). **Display only**, the recording is not affected.
  - Segment: "SET #12 recording", "OTHER #n recording", "Calibration step #n" or "Rest"; the footer shows samples written per sensor and the anti-freeze strategy.
- Bad recording = gaps > 0, a rate outside 45..55 Hz, watch recorder not OK, or the "Written" counters not growing. NO DATA/STALE just means the phone link; check again once LIVE.

## Weight unit

Settings > Weight unit (kg | lb, default kg, persisted in `settings.json`) controls how loads are entered and shown: the set-labeling field and -/+ buttons (2.5 kg or 5 lb), the rep prompt, the Review screen, and the analysis/history cards (`LocalWeightUnit`, `LoadField`, `domain/WeightUnit.kt`). Storage never changes: `labels.json` `load_kg` is always kilograms, with lb input stored as lb x 0.45359237 rounded to 0.01 kg, and lb shown rounded to 0.1, so typical plate loads (45, 135, 225, 315 lb) come back exactly. Existing sessions are untouched; the Review editor keeps the stored kg value unless the load field was edited.

## Backend (Phase 5, task V4)

The phone talks to `services/core-api` through a client **generated from `contracts/openapi/core-api.yaml` at build time** (`:api-client`, OpenAPI Generator `kotlin` / `jvm-retrofit2` + kotlinx.serialization; nothing generated is committed). `:backend` (pure JVM) holds what is built on top of it: `AuthManager` and the OkHttp `Authenticator`, `UploadCore`, `AnalysisRepository`, `HistoryRepository`. `:mobile` adds the Android parts: `TinkSecretStore`, the WorkManager job (`data/Uploads.kt`) and the screens (`ui/BackendUi.kt`).

- **Auth.** Sign in or create an account from Settings, the session detail or History. The access token (15 min) lives in memory; the rotating refresh token is stored AES-256-GCM encrypted by a Tink keyset wrapped in the Android Keystore (`androidx.security:security-crypto` is deprecated). On a 401 the `Authenticator` refreshes once under a lock (concurrent 401s share one rotation), retries, and signs the user out if the server rejects the refresh token (expired, revoked, or reuse detected). Tokens are never logged. A new account starts with no training consent; the register screen's switch (and later Settings changes) are pushed with `PUT /v1/me/consent`.
- **Server address.** Settings > Account. Blank uses `BuildConfig.DEFAULT_API_BASE_URL_*` (debug `http://10.0.2.2:8080`, the host PC from the emulator; release `https://api.proprio.invalid`, a placeholder). Override at build time with `-Pproprio.apiBaseUrl=http://<pc-lan-ip>:8080`. Debug builds allow cleartext through `src/debug` (`network_security_config.xml`); release builds only accept `https://`.
- **Upload.** When a session zip arrives from the watch (and when labels change, on sign-in and on app start) a unique WorkManager job per session runs `UploadCore`: `POST /v1/sessions` (idempotent per session UUID; a 409 means an earlier attempt got further, so the session is fetched and finished) -> `PUT` of the untouched zip to the presigned URL with exactly the ticket's headers (no bearer token) -> `POST /complete` -> labels (`GET` for the ETag, `PUT` with `If-Match`, re-read on 412). Constraints: network connected; exponential backoff from 30 s, 6 attempts, then the session shows "Upload failed" with a retry button. Progress is persisted in `upload.json` next to the zip; a completed session is never uploaded again, and unchanged labels are not re-sent.
- **Analysis.** The session detail polls (2 s growing 1.5x to 30 s) while the server is analysing and shows, per set, your label against the model's prediction, confidence, and logged against predicted reps; a failed analysis shows its error code. The History tab lists `GET /v1/workouts` for the last 90 days.

Point a real phone at a local stack: run `docker compose -f deploy/compose.yaml up --build -d --wait` with `S3_PUBLIC_ENDPOINT=http://<pc-lan-ip>:9000` in `deploy/.env` (the presigned URL is handed to the phone), allow ports 8080 and 9000 through the PC firewall, then enter `http://<pc-lan-ip>:8080` under Settings > Account on the debug build.

Tests: `./gradlew :api-client:test :backend:test :mobile:testDebugUnitTest`. The live-stack E2E (`StackE2ETest`, registers, uploads the golden session through the same `UploadCore`, waits for ANALYZED) is skipped unless the stack is up and `PROPRIO_E2E_BASE_URL=http://localhost:8080` is set: `PROPRIO_E2E_BASE_URL=http://localhost:8080 ./gradlew :backend:test --tests '*StackE2ETest*'`.
