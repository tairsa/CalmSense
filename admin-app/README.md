# CalmSense Admin

Internal admin web app (React + Vite) for CalmSense. Lets you and Tair browse
app users and their data, view model metrics, and manage each user's panic
classifier — retrain, roll back to an earlier snapshot, or reset to the
synthetic baseline. It talks to the JWT-protected `/api/v1/admin` endpoints on
the FastAPI backend.

## Prerequisites

- Node 18+
- The CalmSense backend running (see `../calmsense-backend/README.md`)

## First-time setup

1. **Create an admin login** (one-time, from the backend folder):

   ```bat
   cd ../calmsense-backend
   .venv\Scripts\activate
   python seed_admin.py --email you@example.com --name "Alex"
   ```

   You'll be prompted for a password (min 8 chars). Run again with a different
   email to add Tair, or add her later from inside the app (Register requires an
   existing admin).

2. **Apply the DB schema** if you're on Supabase: run
   `../calmsense-backend/supabase_schema.sql` in the Supabase SQL editor. It
   covers all six tables, admin ones included, and is the single source of
   truth. (An older `admin_schema.sql` used to live alongside it and declared
   the same tables with `generated always as identity`, which silently blocks
   migrating rows that carry their own ids — it has been removed.) On the
   local JSON fallback there's nothing to do.

## Run (dev)

```bat
npm install
npm run dev
```

Open http://localhost:5173. The dev server proxies `/api` and `/health` to the
backend at `http://localhost:8000` (override with `VITE_BACKEND_URL`; see
`.env.example`). Start the backend first:

```bat
cd ../calmsense-backend && .venv\Scripts\activate && python -m uvicorn main:app --reload
```

## Build

```bat
npm run build      # outputs to dist/
npm run preview    # serve the built app locally
```

## What it does

- **Dashboard** — global counts and a detection-quality (confusion) summary.
- **Users** — every `user_id` that has sent data, with row counts and last-seen.
- **User → Overview** — per-user precision/recall, confusion matrix, detections
  by day, and the active model's source + training cutoff.
- **User → Model** — retrain from feedback, roll back to a date (re-activates the
  most recent snapshot at/before it and sets a training cutoff — no refitting),
  reset to the synthetic baseline, and a full snapshot history.
- **User → Reports / Feedback / Sensor** — the raw journaled reports (with a
  Google Maps link), labeled feedback, and recent sensor readings.

## Notes

- Auth is a JWT bearer token stored in `localStorage`; it expires after 12h
  (configurable via `ADMIN_JWT_EXPIRE_HOURS` on the backend).
- Retraining needs `scikit-learn` installed on the backend. The minimum number
  of labeled rows is 10 by default — lower it for demos with
  `CALMSENSE_MIN_TRAIN_SAMPLES` on the backend.
