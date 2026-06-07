import os
from datetime import datetime, timezone
from fastapi import FastAPI, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

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

app = FastAPI(title="CalmSense API", version="1.0.0")

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
