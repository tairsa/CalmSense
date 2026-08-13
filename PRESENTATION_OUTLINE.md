# CalmSense — Presentation Outline (for Gamma.app)

This file is structured so you can paste it directly into **Gamma → Create new → Paste in text → Generate**. Each `##` heading becomes one slide. Image hints in italics let Gamma's AI pick or generate appropriate visuals. Speaker notes are at the bottom of each slide section — copy them into Gamma's per-slide notes after generation.

**Tone:** academic, 10 slides, includes the engineering deep-dive on the Galaxy Watch firmware bypass.

**Suggested Gamma settings**
- Card / slide format: Standard
- Image style: Photorealistic with calm/medical tones (light palette to match the app)
- Tone: Professional, with a touch of accessibility
- Audience: Academic — university course

---

# CalmSense
Real-time panic-attack detection on a smartwatch

*[image: a calm person looking at a smartwatch on their wrist, soft daylight, blue–green tones]*

---

## The problem we set out to solve

- Panic attacks affect ~11% of adults in any given year; ~3% live with panic disorder
- They arrive without warning, often last 5–20 minutes, and feel physiologically indistinguishable from a heart attack
- Existing tools (mood journals, generic HR alerts) detect **after the fact** or fire constant false positives
- Goal: catch the attack early — using just a smartwatch — and intervene with a calming exercise *and* build a record patients can review with a therapist

**Speaker notes:** This sets the clinical and personal stakes. We're not a medical device; we're a self-management tool grounded in physiological signals.

---

## Our approach in one sentence

A **Galaxy Watch** streams heart rate and motion in real time to an **Android phone**, which runs a tiny on-device classifier and immediately starts a breathing exercise + journaling flow. A **FastAPI backend** retrains the model from labeled feedback.

*[image: clean three-tier architecture diagram — watch on the left, phone in the middle, server on the right, with arrows]*

**Speaker notes:** Three tiers because each has a different constraint: the watch has sensors but limited compute; the phone is the only thing always with the user; the server is the only place where retraining is feasible.

---

## System architecture

- **Wearable (Wear OS 5):** `HrMonitoringService` — foreground service reading raw `Sensor.TYPE_HEART_RATE` (1 Hz) and `TYPE_LINEAR_ACCELERATION` (≈16 Hz → motion RMS over a 5-second window). Streams `<bpm>,<motion>` to the phone over `MessageClient` every 2 seconds.
- **Phone (Android):** `MonitorService` (foreground) ingests the watch stream, runs an on-device sigmoid classifier in milliseconds, fires a breathing overlay + notification on detection. `MainActivity` provides the dashboard, simulations, and journal UI.
- **Backend (FastAPI + Supabase):** Stores sensor records, labeled feedback, and journaled reports. Trains a logistic-regression panic classifier and serves the latest weights via `GET /api/v1/sensor-data`.

*[image: layered architecture box-and-arrow diagram with HR icon, phone icon, server icon, labelled arrows for MessageClient / HTTP]*

**Speaker notes:** Inference is local for two reasons: panic attacks happen in places without signal, and we need millisecond response. The server's only job is retraining.

---

## The engineering wall: Galaxy Watch firmware

Both documented paths to heart-rate data on Wear OS 5 **fail silently on the Galaxy Watch 5** with current One UI Watch firmware:

- **Health Services `PassiveMonitoringClient`** — registration succeeds, dispatch throws `SecurityException` at `WHS_PermissionPolicy.bdk.m(PG:116)` despite a valid runtime grant
- **`MeasureClient`** — same wall, just at registration time
- **Health Connect Jetpack client** — `UnsupportedOperationException: SDK version too low or running in a profile` (Health Connect's Jetpack client isn't supported on Wear OS at all)

**Our bypass:** read directly from `android.hardware.SensorManager` (`TYPE_HEART_RATE`), inside a foreground service of type `health` with `BODY_SENSORS` + `ACTIVITY_RECOGNITION` + `health.READ_HEART_RATE` permissions. The HR sensor itself gates on `health.READ_HEART_RATE` per `dumpsys sensorservice`, but unlike Health Services it accepts the standard runtime grant.

*[image: a developer console screenshot with a red "Permission denied" log on the left and a green "HR sample received" log on the right]*

**Speaker notes:** This was the single hardest part of the build — three different sanctioned APIs all failed on this specific watch model. The raw SensorManager path is what actually delivers data. We documented all of this in the repo so others don't lose the same week.

---

## The machine-learning loop

- **Model:** Logistic regression on 4 features → `(HR, HRV, motion intensity, bias)`, with a sigmoid for `p(panic)`. Currently trained on 5,000 synthetic samples generated from physiological priors (test accuracy 0.992).
- **On-device inference:** Phone caches the weights to SharedPreferences so the classifier still runs **offline** — panic attacks happen in places without signal.
- **Feedback loop:** Every detection asks the user *"Was that a panic attack?"* Yes → severity 1–10 → labeled hit. Severity 0 → labeled miss. Manual "I'm having a panic attack" button covers false negatives.
- **Hit/miss tracking:** Labeled signals land in the backend's `panic_feedback` table; one query produces a confusion matrix per user.

*[image: a simple sigmoid curve plot in the same calm color palette]*

**Speaker notes:** Logistic regression was the deliberate choice — interpretable, tiny model file, easy to retrain server-side, and the synthetic training distribution is grounded in published panic-physiology priors. Bigger models would be premature.

---

## The journaling and pattern-review flow

After every confirmed panic, the user can fill an optional questionnaire:

- **What were you feeling?** (free text)
- **Symptoms** (multi-select chips: shortness of breath, racing heart, chest tightness, cold sweats, trembling, dizziness, nausea, numbness, choking, unreality, fear of losing control, hot flashes)
- **What were you doing before?** (free text)
- **What helped you snap out of it?** (free text)
- **Duration**, plus an auto-captured **GPS pin**

Reports tab filters by 24h / 7d / 30d / All. Tap a card to see the full entry and an "Open in Google Maps" button — built so the user can review patterns with their therapist.

*[image: three phone-screen mockups side by side: dashboard with HR monitor, severity slider, reports list with a colored severity badge]*

**Speaker notes:** Free-text was a deliberate choice over preset categories for activity/what-helped — therapy works on narrative, not taxonomy. Symptoms stay as chips because they aggregate well.

---

## Privacy and design constraints

- **Inference is local.** Sensor data is the only thing that leaves the phone, and only when the backend is reachable.
- **Reports stay on-device by default.** They're mirrored to the backend for cross-device sync; deletion is local-only so the labeled training signal isn't disturbed.
- **GPS is captured only at panic moments** — not as a passive tracker — and only when the user grants location permission.
- **Light-mode locked** — the calm palette is the brand; dark mode breaks the perception of the app.

*[image: a stylized shield icon with a small heart in the center, signaling on-device privacy]*

**Speaker notes:** A panic-management app earns trust by being conservative about data. We chose mirroring over hard sync because the user's local view is the source of truth.

---

## What's working today

- End-to-end pipeline live on **Galaxy Watch 5 + Galaxy S23** + uvicorn backend
- HR + motion streaming continuously; on-device classifier firing in tens of milliseconds
- Trained model (`test_accuracy = 0.992`) loading from server and persisting locally for offline use
- Hit/miss feedback collected via the in-app severity slider and stored to the backend
- Bottom-nav UI with a working Reports tab, journal questionnaire, and Google Maps pin

*[image: a screen recording still of the phone showing live HR, the watch on a wrist, and the model status chip saying "model loaded: trained_global (acc=0.99)"]*

**Speaker notes:** I'll demo this live if there's time — it's about 30 seconds to trigger the simulation, see the breathing overlay, rate severity, and watch the report show up in the Reports tab.

---

## Next steps

- **Server-side retraining pipeline** — scheduled job over collected hit/miss + report data, replacing the synthetic-trained baseline
- **Per-user models** — currently global; the API already keys on `user_id` so this is a training change only
- **JWT authentication** — currently `user_id` is trusted from the phone
- **Therapist-facing dashboard** — let the user share a read-only view of their reports timeline
- **HRV** — currently null from the watch path; either compute it on-device from beat-to-beat intervals or pull it from Samsung Health via Health Connect
- **iOS port** — same backend, new HealthKit-based wearable client

*[image: a roadmap diagram with milestones laid out left to right on a horizontal axis]*

**Speaker notes:** Each of these is an independent track; we ordered them by impact-to-effort. Per-user models is the highest-value once we have a few hundred labeled rows per user.

---

## What we learned

- Vendor-specific firmware quirks can invalidate "official" APIs — **always have a primary-sources fallback** when building on someone else's hardware
- Keep the model tiny and interpretable until you have real labeled data — synthetic priors get you to a working prototype but lie about real-world distribution
- Treat the on-device cache as the source of truth; let the server be eventually-consistent
- A health app earns adoption by making the *easy* moment to log feel as low-friction as the *hard* moment to recover

*[image: a small notebook page with handwritten "lessons learned" headers]*

**Speaker notes:** I'll close on the lessons learned because they generalize past CalmSense — the firmware-quirk insight in particular applies to any wearable project on consumer hardware.

---

## Thanks — questions?

- Repo: `github.com/tairsa/CalmSense`
- Architecture document: `ARCHITECTURE.docx` (in the repo)
- Demo: live on phone

*[image: a simple "Thank you" card in the calm palette]*

**Speaker notes:** Pause here. Likely questions: privacy posture, false-positive rate so far, why logistic regression vs neural net, generalization across users.
