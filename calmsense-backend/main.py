import hmac
import os
import random
import string
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from typing import Optional
from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

import auto_retrain
import model_service
from admin_routes import router as admin_router
from models import (
    ConsentCodeRequest,
    ConsentCodeResponse,
    PanicFeedback,
    PanicReport,
    Profile,
    RedeemConsentRequest,
    SensorData,
)
from storage import (
    append_feedback,
    append_record,
    append_report,
    create_consent_code,
    create_therapist_patient_link,
    find_consent_code,
    get_profile,
    get_reports_for_patient,
    get_sensor_data_for_patient,
    is_link_active,
    list_patients_for_therapist,
    mark_consent_code_used,
    read_all_records,
    storage_backend,
    upsert_profile,
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
# Therapist mode: profiles, consent codes, therapist views.
#
# Auth is trust-based right now (server accepts whatever user_id the client
# claims). Fine for the class demo; documented as "Future Work" to verify
# the Supabase JWT server-side before shipping to real patients.
# ---------------------------------------------------------------------------

# --- Profile ---------------------------------------------------------------

@app.post("/api/v1/profile")
def set_profile(data: Profile):
    """Create or update a user's role (patient/therapist) + display name."""
    record = data.model_dump()
    try:
        upsert_profile(record)
        return {"success": True, "profile": record}
    except Exception as e:
        return JSONResponse(
            status_code=500,
            content={"success": False, "message": f"Failed to save profile: {e}"},
        )


@app.get("/api/v1/profile")
def read_profile(user_id: str = Query(..., description="Look up this user's profile")):
    profile = get_profile(user_id)
    return {"profile": profile}


# --- Consent codes ---------------------------------------------------------

CONSENT_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   # skip O/0, I/1, l
CONSENT_CODE_TTL_MINUTES = 30


def _generate_consent_code() -> str:
    """Short human-friendly code like 'A7K-Q2M'. Ambiguous chars removed."""
    body = "".join(random.choices(CONSENT_CODE_ALPHABET, k=6))
    return f"{body[:3]}-{body[3:]}"


@app.post("/api/v1/consent-codes", response_model=ConsentCodeResponse)
def generate_consent_code(data: ConsentCodeRequest):
    """Therapist generates a code to hand to a client (in person / message)."""
    now = datetime.now(timezone.utc)
    expires = now + timedelta(minutes=CONSENT_CODE_TTL_MINUTES)
    code = _generate_consent_code()
    record = {
        "code": code,
        "therapist_id": data.therapist_id,
        "created_at": now.isoformat(),
        "expires_at": expires.isoformat(),
        "used_at": None,
        "used_by": None,
    }
    try:
        create_consent_code(record)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"failed to create code: {e}")
    return ConsentCodeResponse(code=code, expires_at=expires.isoformat())


@app.post("/api/v1/consent-codes/redeem")
def redeem_consent_code(data: RedeemConsentRequest):
    """Patient submits a code. If valid + unused + unexpired, creates the link."""
    row = find_consent_code(data.code)
    if row is None:
        raise HTTPException(status_code=404, detail="unknown code")
    if row.get("used_at"):
        raise HTTPException(status_code=409, detail="code already used")

    now = datetime.now(timezone.utc)
    expires_iso = row.get("expires_at")
    try:
        expires = datetime.fromisoformat(expires_iso.replace("Z", "+00:00"))
    except Exception:
        raise HTTPException(status_code=500, detail="corrupt expires_at on code")
    if now > expires:
        raise HTTPException(status_code=410, detail="code expired")

    therapist_id = row["therapist_id"]

    mark_consent_code_used(data.code, data.patient_id, now.isoformat())
    create_therapist_patient_link({
        "therapist_id": therapist_id,
        "patient_id": data.patient_id,
        "created_at": now.isoformat(),
    })
    return {
        "success": True,
        "therapist_id": therapist_id,
        "patient_id": data.patient_id,
    }


# --- Therapist read views --------------------------------------------------

@app.get("/api/v1/therapist/{therapist_id}/patients")
def therapist_patients(therapist_id: str):
    """List of patient user_ids the therapist has been granted access to."""
    ids = list_patients_for_therapist(therapist_id)
    patients = []
    for pid in ids:
        profile = get_profile(pid) or {}
        patients.append({
            "user_id": pid,
            "display_name": profile.get("display_name"),
        })
    return {"therapist_id": therapist_id, "patients": patients}


@app.get("/api/v1/therapist/{therapist_id}/patients/{patient_id}/reports")
def therapist_patient_reports(therapist_id: str, patient_id: str):
    """Panic reports for a client. Requires an active consent link."""
    if not is_link_active(therapist_id, patient_id):
        raise HTTPException(status_code=403, detail="no consent link for this patient")
    return {"reports": get_reports_for_patient(patient_id)}


@app.get("/api/v1/therapist/{therapist_id}/patients/{patient_id}/sensor-data")
def therapist_patient_sensor(therapist_id: str, patient_id: str):
    """Sensor stream for a client. Requires an active consent link."""
    if not is_link_active(therapist_id, patient_id):
        raise HTTPException(status_code=403, detail="no consent link for this patient")
    return {"sensor_data": get_sensor_data_for_patient(patient_id)}


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
        if full_path.startswith("api/"):
            raise HTTPException(status_code=404, detail="Not found")
        candidate = os.path.realpath(os.path.join(_ADMIN_DIST, full_path))
        if candidate.startswith(_ADMIN_DIST + os.sep) and os.path.isfile(candidate):
            return FileResponse(candidate)
        return FileResponse(os.path.join(_ADMIN_DIST, "index.html"))
