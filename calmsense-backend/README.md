# CalmSense Backend

FastAPI service that receives sensor readings from the Android app and serves
per-user model weights for panic-attack detection.

## Requirements

- Python 3.10+
- pip

## Quick start (Windows)

```bat
cd calmsense-backend

REM Create and activate a virtual env (one-time)
python -m venv .venv
.venv\Scripts\activate

REM Install dependencies
pip install -r requirements.txt

REM Run the server (auto-reloads on code changes)
python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

Then open: http://localhost:8000/docs (auto-generated Swagger UI).

`--host 0.0.0.0` is important if you want the Android emulator or a real
device on the same Wi-Fi to reach the server. From the emulator, the host
machine is at `http://10.0.2.2:8000`. From a real phone on the same Wi-Fi,
use the host machine's LAN IP (e.g. `http://192.168.x.x:8000`).

## Endpoints (current)

| Method | Path                       | Purpose                                     |
| ------ | -------------------------- | ------------------------------------------- |
| GET    | `/health`                  | Liveness check — returns `{"status":"ok"}`  |
| POST   | `/api/v1/sensor-data`      | Append a sensor reading for a given user    |
| GET    | `/api/v1/sensor-data?user_id=...` | Return model weights for that user   |

### POST /api/v1/sensor-data — request body

```json
{
  "user_id": "tair-001",
  "panic_attack_detection": false,
  "current_hr": 78.0,
  "current_hrv": 42.5,
  "current_motion_intensity": 0.1,
  "timestamp": null
}
```

`timestamp` is optional; the server auto-fills UTC ISO-8601 if omitted.

### GET /api/v1/sensor-data — response

```json
{
  "user_id": "tair-001",
  "weights": [0.0, 0.0, 0.0, 0.0, 0.0],
  "source": "default"
}
```

Weights are currently hardcoded zeros (see TODOs in `main.py`). They will be
replaced by trained logistic-regression coefficients once the ML pipeline is
in place.

## Storage

Sensor records are appended to `data/sensor_data.json` using an atomic
write (temp file + rename). Good enough for MVP / single-process dev; not
safe for concurrent writers from multiple processes.

## Smoke test

After starting the server in one terminal, in another terminal:

```bat
.venv\Scripts\activate
python smoke_test.py
```

This sends a few POSTs and a GET, prints results, and exits non-zero on any
failure. Useful as a sanity check before connecting the Android app.

## Project layout

```
calmsense-backend/
  main.py            # FastAPI app + endpoints
  models.py          # Pydantic schemas
  storage.py         # Atomic JSON file storage
  requirements.txt   # Runtime deps
  smoke_test.py      # Local verification script
  data/              # Runtime data dir (gitignored)
```
