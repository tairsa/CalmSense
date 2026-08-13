import json
import os
import random
import string
from datetime import datetime, timedelta, timezone
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import JSONResponse

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

app = FastAPI(title="CalmSense API", version="1.0.0")

# Try to load trained model weights at startup. If the file is missing
# (e.g. ml/train_model.py hasn't been run yet), fall back to zeros so the
# API still works and existing clients keep their behavior.
DEFAULT_WEIGHTS = [0.0, 0.0, 0.0, 0.0, 0.0]  # [w_hr, w_hrv, w_motion, w_reserved, bias]
MODEL_WEIGHTS_FILE = os.path.join(os.path.dirname(__file__), "ml", "model_weights.json")


def _load_global_model():
    """Returns (weights_list, source_label, metadata_dict_or_None)."""
    try:
        with open(MODEL_WEIGHTS_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        weights = data.get("weights")
        if not isinstance(weights, list) or len(weights) != 5:
            return DEFAULT_WEIGHTS, "default", None
        meta = {k: data.get(k) for k in ("model_type", "trained_at", "test_accuracy", "training_samples")}
        return [float(w) for w in weights], "trained_global", meta
    except FileNotFoundError:
        return DEFAULT_WEIGHTS, "default", None
    except (json.JSONDecodeError, ValueError, KeyError):
        return DEFAULT_WEIGHTS, "default", None


GLOBAL_WEIGHTS, GLOBAL_SOURCE, GLOBAL_META = _load_global_model()


@app.get("/health")
def health_check():
    return {"status": "ok", "storage": storage_backend()}


@app.post("/api/v1/sensor-data")
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


@app.post("/api/v1/panic-feedback")
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


@app.post("/api/v1/panic-reports")
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


@app.get("/api/v1/sensor-data")
def get_weights_for_user(user_id: str = Query(..., description="The user whose weights to retrieve")):
    """Return the model weights for the given user_id.

    Currently returns the global model trained by ml/train_model.py
    (or zeros if the model file does not exist). Per-user retraining on
    stored sensor records is the next step; the user_id query is preserved
    so the API contract does not change when that lands.
    """
    response = {
        "user_id": user_id,
        "weights": GLOBAL_WEIGHTS,
        "source": GLOBAL_SOURCE,
    }
    if GLOBAL_META:
        response["model_meta"] = GLOBAL_META
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

    # Expiry check (guard against clock skew by comparing UTC ISO strings).
    now = datetime.now(timezone.utc)
    expires_iso = row.get("expires_at")
    try:
        expires = datetime.fromisoformat(expires_iso.replace("Z", "+00:00"))
    except Exception:
        raise HTTPException(status_code=500, detail="corrupt expires_at on code")
    if now > expires:
        raise HTTPException(status_code=410, detail="code expired")

    therapist_id = row["therapist_id"]

    # Two writes: mark code used, then create the link. If the link write
    # fails after the code is marked used, the therapist can regenerate.
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
    # Enrich with display_name from profiles when available.
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
