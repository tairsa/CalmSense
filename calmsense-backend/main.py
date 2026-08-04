import hmac
import os
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Optional
from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

import auto_retrain
import model_service
from admin_routes import router as admin_router
from models import PanicFeedback, PanicReport, SensorData
from storage import (
    append_feedback,
    append_record,
    append_report,
    read_all_records,
    storage_backend,
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    retrain_task = auto_retrain.start()
    yield
    auto_retrain.stop(retrain_task)


app = FastAPI(title="CalmSense API", version="1.0.0", lifespan=lifespan)

# CORS for the admin web app (Vite dev server + any configured origins).
# Set ADMIN_CORS_ORIGINS in .env as a comma-separated list for production.
_default_origins = "http://localhost:5173,http://127.0.0.1:5173"
_origins = [o.strip() for o in os.environ.get("ADMIN_CORS_ORIGINS", _default_origins).split(",") if o.strip()]
app.add_middleware(
    CORSMiddleware,
    allow_origins=_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(admin_router)


# ---------------------------------------------------------------------------
# Device authentication
#
# The phone endpoints below were historically unauthenticated, which was
# tolerable while the API was only reachable over the private tailnet. Once it
# is published on the internet it is not: these routes accept and return heart
# rate, panic history and GPS coordinates.
#
# CALMSENSE_API_KEY is a shared secret the phone sends as X-API-Key. When the
# variable is UNSET the check is skipped entirely, so existing local and
# Raspberry Pi deployments keep working exactly as before; set it in the cloud
# deployment to require the header. This is not per-user auth — a stolen key
# grants full access — but it stops anonymous readers and junk writes. Proper
# JWT auth remains the intended next step.
# ---------------------------------------------------------------------------
_API_KEY = os.environ.get("CALMSENSE_API_KEY", "").strip()


def require_api_key(x_api_key: Optional[str] = Header(default=None, alias="X-API-Key")) -> None:
    if not _API_KEY:
        return
    # Constant-time compare so the key can't be recovered by timing the response.
    if not x_api_key or not hmac.compare_digest(x_api_key, _API_KEY):
        raise HTTPException(status_code=401, detail="Invalid or missing API key")


_device_auth = [Depends(require_api_key)]


@app.get("/health")
def health_check():
    """Unauthenticated on purpose: it is the container liveness probe and the
    phone's server-status chip, and it exposes no user data."""
    return {"status": "ok", "storage": storage_backend()}


@app.post("/api/v1/sensor-data", dependencies=_device_auth)
def receive_sensor_data(data: SensorData):
    record = data.model_dump()

    # Auto-fill timestamp if the mobile device did not provide one
    if record["timestamp"] is None:
        record["timestamp"] = datetime.now(timezone.utc).isoformat()

    try:
        append_record(record)
        return {"success": True, "message": "Data saved successfully."}
    except Exception as e:
        return JSONResponse(
            status_code=500,
            content={"success": False, "message": f"Failed to save data: {e}"},
        )


@app.post("/api/v1/panic-feedback", dependencies=_device_auth)
def receive_panic_feedback(data: PanicFeedback):
    """Record a labeled training signal from the user.

    Used to retrain the panic classifier and to keep a model hit/miss log.
    `was_panic=true` with severity (1-10) is a positive label; `was_panic=false`
    is a labeled false positive. `detected_by_model=false` means the user
    logged it manually because the model missed it.
    """
    record = data.model_dump()
    if record["timestamp"] is None:
        record["timestamp"] = datetime.now(timezone.utc).isoformat()
    try:
        append_feedback(record)
        return {"success": True, "message": "Feedback saved."}
    except Exception as e:
        return JSONResponse(
            status_code=500,
            content={"success": False, "message": f"Failed to save feedback: {e}"},
        )


@app.post("/api/v1/panic-reports", dependencies=_device_auth)
def receive_panic_report(data: PanicReport):
    """Mirror a journaled panic-attack report to the server.

    Phone is the source of truth (Room DB) — the server copy enables future
    cross-device sync and bulk export for a therapist. Free-text fields and
    GPS are sensitive; consider that before exposing this data widely.
    """
    record = data.model_dump()
    if record["timestamp"] is None:
        record["timestamp"] = datetime.now(timezone.utc).isoformat()
    try:
        append_report(record)
        return {"success": True, "message": "Report saved."}
    except Exception as e:
        return JSONResponse(
            status_code=500,
            content={"success": False, "message": f"Failed to save report: {e}"},
        )


@app.get("/api/v1/sensor-data", dependencies=_device_auth)
def get_weights_for_user(user_id: str = Query(..., description="The user whose weights to retrieve")):
    """Return the active model weights for the given user_id.

    Serves the user's active snapshot (from retrain / rollback / reset via the
    admin app). Falls back to the shipped synthetic baseline when the user has
    no snapshot yet, so the phone contract is unchanged for new users.
    """
    active = model_service.get_active_weights(user_id)
    response = {
        "user_id": user_id,
        "weights": active["weights"],
        "source": active["source"],
    }
    if active.get("model_meta"):
        response["model_meta"] = active["model_meta"]
    return response


# ---------------------------------------------------------------------------
# Admin dashboard (single-service deployment)
#
# When a built copy of the React admin app is present at ./admin_dist, serve it
# from this same process. That is what the cloud image does: one container and
# one URL for API + dashboard, which keeps registry/runtime cost down and makes
# CORS irrelevant because the two share an origin. The docker-compose setup on
# the Pi has no admin_dist and is unaffected — nginx keeps serving it there.
#
# Registered last so every API route above wins the match.
# ---------------------------------------------------------------------------
_ADMIN_DIST = os.path.realpath(os.path.join(os.path.dirname(__file__), "admin_dist"))

if os.path.isdir(_ADMIN_DIST):
    _assets = os.path.join(_ADMIN_DIST, "assets")
    if os.path.isdir(_assets):
        app.mount("/assets", StaticFiles(directory=_assets), name="admin-assets")

    @app.get("/{full_path:path}", include_in_schema=False)
    def serve_admin_spa(full_path: str):
        """Serve the built file when it exists, else index.html so React Router
        can handle the route client-side (the try_files rule nginx applies)."""
        # Never swallow unmatched API paths — they must still 404 as JSON rather
        # than silently returning the dashboard HTML.
        if full_path.startswith("api/"):
            raise HTTPException(status_code=404, detail="Not found")
        candidate = os.path.realpath(os.path.join(_ADMIN_DIST, full_path))
        # realpath + prefix check keeps "../" out of the served tree.
        if candidate.startswith(_ADMIN_DIST + os.sep) and os.path.isfile(candidate):
            return FileResponse(candidate)
        return FileResponse(os.path.join(_ADMIN_DIST, "index.html"))
