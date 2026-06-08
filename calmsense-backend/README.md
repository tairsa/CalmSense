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

## Run with Docker

The image runs on both `linux/amd64` (GCP/AWS/Azure) and `linux/arm64`
(Raspberry Pi 5). All deps, including scikit-learn, ship prebuilt wheels, so no
compiler is needed.

```bash
# Optional: configure Supabase creds + a stable JWT secret (else JSON fallback
# + a per-restart secret are used). At minimum set ADMIN_JWT_SECRET.
cp .env.example .env   # then edit

# Build + run (compose handles the data volume and env file):
docker compose up -d --build
docker compose logs -f

# Create your first admin (one-time):
docker compose exec backend python seed_admin.py --email you@example.com --name "Alex"
```

The API is then on `http://<host>:8000` (Swagger at `/docs`). The JSON fallback
store persists in the `calmsense-data` Docker volume.

Without compose:

```bash
docker build -t calmsense-backend .
docker run -d -p 8000:8000 -v calmsense-data:/app/data \
  -e ADMIN_JWT_SECRET=$(python -c "import secrets;print(secrets.token_urlsafe(48))") \
  --name calmsense-backend calmsense-backend
```

### On the Raspberry Pi 5

Build natively on the Pi (simplest):

```bash
git clone https://github.com/tairsa/CalmSense.git
cd CalmSense/calmsense-backend
docker compose up -d --build
```

Or cross-build from an x86 machine and push to a registry:

```bash
docker buildx build --platform linux/arm64 -t <registry>/calmsense-backend:arm64 --push .
```

### Reaching it remotely (Tailscale)

Because the container binds `0.0.0.0`, the server is reachable on the Pi's
**Tailscale** IP (`100.x.y.z`) or MagicDNS name (e.g. `http://pi5:8000`) from
**any device that's also on your tailnet** — including the phone, if it has the
Tailscale app installed and signed in. No port forwarding needed. To expose it
to the public internet instead, use Tailscale Funnel (opt-in, adds HTTPS), and
add that origin to `ADMIN_CORS_ORIGINS`.

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
