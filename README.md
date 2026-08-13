# CalmSense

Real-time panic-attack detection on a smartwatch.

A Galaxy Watch streams heart rate and motion to an Android phone, which runs a
tiny on-device classifier and immediately starts a breathing exercise and
journaling flow. A FastAPI backend stores labeled feedback and serves the
trained model weights.

## Why three tiers

Each tier has a different constraint:

- **Watch** — has the sensors, but limited compute.
- **Phone** — the only device always with the user; runs inference locally so it
  works offline (panic attacks happen in places without signal) and responds in
  milliseconds.
- **Backend** — the only place retraining is feasible.

## Repository layout

```
CalmSense/
├── PRESENTATION_OUTLINE.md     — Gamma-ready outline for the academic talk
├── scripts/                    — doc/asset generation helpers
├── calmsense-backend/          — FastAPI service (see its own README)
│   ├── main.py                 — routes
│   ├── models.py               — Pydantic schemas
│   ├── storage.py              — Supabase + local-JSON fallback
│   └── ml/                     — logistic-regression training + weights
└── android-app/
    ├── app/                    — phone module (com.example.app)
    └── wear/                   — watch module (com.example.app.wear)
```

## Components

### Wearable (Wear OS 5)
`HrMonitoringService` is a foreground service that reads raw
`Sensor.TYPE_HEART_RATE` (~1 Hz) and `TYPE_LINEAR_ACCELERATION` (~16 Hz, motion
RMS over a 5-second window), and streams `<bpm>,<motion>` to the phone over
`MessageClient` every 2 seconds. We read raw sensors directly because the
sanctioned Wear Health Services and Health Connect paths fail on the Galaxy
Watch 5 firmware.

### Phone (Android)
`MonitorService` (foreground) ingests the watch stream, runs an on-device
sigmoid classifier, and fires a breathing overlay plus notification on
detection. Weights are cached to SharedPreferences so the classifier still runs
offline. `MainActivity` provides the dashboard, simulations, journaling
questionnaire, and a Reports tab. The app is light-mode locked by design.

### Backend (FastAPI + Supabase)
Stores sensor records, labeled hit/miss feedback, and journaled reports, and
serves the trained logistic-regression weights. Falls back to local JSON files
when Supabase credentials aren't configured. See
[`calmsense-backend/README.md`](calmsense-backend/README.md) for setup and the
endpoint reference.

## Quick start (backend)

```bat
cd calmsense-backend
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

Then open http://localhost:8000/docs.

## The machine-learning loop

Logistic regression on `(HR, HRV, motion intensity, bias)` with a sigmoid for
`p(panic)`. Every detection asks the user "Was that a panic attack?" — yes plus
a 1–10 severity is a labeled hit, severity 0 is a labeled miss, and a manual
"I'm having a panic attack" button covers false negatives. Those labels feed the
backend's training data.

## Roadmap

- Server-side retraining over collected hit/miss + report data
- Per-user models (the API already keys on `user_id`)
- JWT authentication (currently `user_id` is trusted from the phone)
- On-device HRV from beat-to-beat intervals
- Therapist-facing read-only report view
