import json
import os
from datetime import datetime, timezone
from fastapi import FastAPI, Query
from fastapi.responses import JSONResponse

from models import PanicFeedback, SensorData
from storage import append_feedback, append_record, read_all_records, storage_backend

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
