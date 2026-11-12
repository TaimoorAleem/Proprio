# Future roadmap ideas

> **Not scope.** Nothing here changes the current phases, gates, priorities or code in [`PROJECT.md`](PROJECT.md). These are candidate directions recorded so that current design choices can keep the door open where that is cheap. Adopting any of them is a scope change: it needs the owner's approval and a `DECISIONS.md` entry (D-035 records only that the ideas exist).
> Added 2026-11-11 from the owner's notes.

## Why these ideas

Exercise classification and rep counting on their own are a crowded space: Apple, Garmin, Whoop and Train Fitness ship them. Proprio needs a stronger reason to exist.

The owner's research points at a different problem: **verifying unsupervised, prescribed strength work.**
- **Low adherence:** non-adherence to home rehab exercise is cited at 50–70%.
- **Diaries overstate adherence:** a 2018 JOSPT study found a median of 220 exercises in self-reported diaries, against 176 recorded by a hidden accelerometer.
- **It works in research, but there's no product:** a Toronto group (SPARS, Holland Centre) has shown that smartwatch IMU data labelled in clinic can classify home physio sessions. No product exists that clinicians can buy.

These figures come from the owner's research notes and haven't been re-checked here. Verify the citations before using them publicly (README, blog, pitch).

## The ideas

### A. Prescription adherence ledger
- **For:** physiotherapists, kinesiologists and coaches who prescribe unsupervised strength work, and their clients.
- **Idea:**
  - **Prescription:** the clinician creates a prescription: exercise, sets, reps, rest, tempo and days per week.
  - **Template:** the clinician records a short demo set of each exercise on the client's watch.
  - **At home:** the client only starts and stops a session.
  - **Backend analysis:** the backend detects the sets and matches each one to a prescribed exercise. Matching is open-set, so a set can also be "unknown". It then counts reps and measures rest and rep duration.
  - **Output:** a weekly ledger of what was done vs what was prescribed.
- **LLM rule:** a Granite summary may only restate computed facts, and it must cite session ids.

### B. Wrist velocity-loss feedback (highest risk)
- **For:** barbell lifters who want autoregulation without a $329 bar sensor.
- **Idea:** estimate how much the lifting velocity drops within a set, measured at the wrist, and buzz when it reaches a target such as a 20–25% loss.
- **Guardrail:** never market it as reps in reserve or proximity to failure. Velocity loss maps only loosely to failure.

### C. Session truth feed for remote trainers
- **For:** online trainers with 10–50 remote clients. Their platforms (TrueCoach, Trainerize, Everfit) show compliance based on what clients *log*, not what they did.
- **Idea:** a verified session record (sets detected, rest times, rep duration, session length), compared with the logged or prescribed workout, with flags such as:
  - "logged 4 sets, watch saw 2";
  - "rest averaged 45 s vs 2 min prescribed".

### D. Tempo and time-under-tension compliance
- **For:** clinicians prescribing tempo protocols (e.g. tendon rehab) and lifters on tempo programs.
- **Idea:** split each rep into its lifting and lowering phases, then report seconds per phase against the prescribed tempo. First as a report after the session, later as real-time haptics.

## Rejected ideas and guardrails
- **Velocity loss sold as "reps in reserve" or "proximity to failure"** (B): rejected. The mapping is too loose to support the claim.
- **Classification and rep counting alone as Proprio's differentiator:** deprioritised as a *reason to exist*, because the market already has it. It stays the current technical foundation.
- *Other rejected ideas:* the owner's notes mention a rejected list, but it wasn't included in the text these ideas came from. Add it here.

## Reconciliation with the current design

What already fits, what conflicts, and what would be needed. Nothing in this section changes anything now.

| Topic | Current state | Relevance to A–D |
|---|---|---|
| Raw data kept at native rate, immutable (CSF-1 conversion server-side) | Raw zips keep 50 Hz watch timestamps; models use 5 Hz CSF-1 bins | **Door already open.** B and D need sub-rep timing; 200 ms bins are too coarse, so they would work from raw 50 Hz data or a new canonical variant. |
| Continuous sessions with segments; rest as implicit gaps (D-019) | Segment start and end times are in `labels.json` and in the `session.analyzed` event | **Door already open.** Rest durations (A, C) can be derived today. |
| "Not a lift" rejection (τ) and the `other` class | In scope; τ tuned on the fit set | Basis for A's open-set "unknown" match. |
| **Exercise vocabulary closed to 5 lifts** | Enums in `contracts/openapi/core-api.yaml`, `contracts/events/session-analyzed.schema.json` and `protocol/schema/labels.schema.json`; the classifier is trained on MetaMotion barbell data only | **Door partly closed.** A needs arbitrary rehab exercises defined by a demo set (template matching or per-user heads; Phase 8 personalization is a stepping stone). At the next schema version bump, prefer an open exercise id plus a registry over a fixed enum. No change now. |
| Set detection | Sets are marked by the user, with the countdown start (D-034) | A and C need automatic set detection. D-034 already lists it as the long-term direction, gated on a model that works on the watch. |
| Rep counter | Fails its gates; rework planned in Phase 4 | Prerequisite for A and C (rep counts) and D (rep phases). |
| **No web client (D-005)** | Phone app only | **Conflict.** A and C are clinician- and trainer-facing. Options: a clinician mode in the Android app, exports (PDF/CSV), or integrations into existing trainer platforms (C). A clinician web dashboard would reverse D-005; that is a future owner decision. |
| **Left wrist only (D-006)** | The fixed constraint; CSF-1 rotation assumes it | **Conflict for A.** Rehab clients may need either wrist. Per-client demo templates reduce, but don't remove, the dependency on the left-wrist assumption. |
| User scoping: foreign ids return 404 (IDOR tests) | Data visible only to its owner | A and C need explicit, revocable client → clinician/trainer sharing with consent. That is a new authorization model, not a loosening of today's checks. |
| Health data and claims (PROJECT.md §9) | Consent flag, export and delete; "no medical diagnoses" | A sits close to clinical use. Expect privacy-law review (e.g. PHIPA for Ontario clinicians) and medical-device positioning questions. Keep outputs descriptive (done vs prescribed), never diagnostic. |
| LLM coach: answers cite sources (Phase 6 gate) | Grounding via tools and RAG | Consistent with A's rule that summaries only restate computed facts and cite session ids. |
| Accelerometer range ±8 g; saturation counted (live preview) | Measured on device | B (fast concentric phases) should check saturation rates before relying on integration. |

## Cheap ways to keep the door open (when the relevant code is touched anyway)
- Keep raw native-rate data and watch timestamps exactly as today. This is the single most important enabler for B and D.
- Keep segment timing in every result event, as `session.analyzed` already does.
- On the next contract or schema version, replace closed exercise enums with an open id.
- Keep set detection, exercise matching and rep counting as separate stages with separate outputs, rather than one "classify the set" step, so A, C and D can reuse each stage.
