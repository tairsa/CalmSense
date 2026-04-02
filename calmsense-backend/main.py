from datetime import datetime, timezone
from fastapi import FastAPI, Query
from fastapi.responses import JSONResponse

from models import SensorData
from storage import append_record, read_all_records

app = FastAPI(title="CalmSense API", version="1.0.0")


@app.get("/health")
def health_check():
    return {"status": "ok"}


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


@app.get("/api/v1/sensor-data")
def get_weights_for_user(user_id: str = Query(..., description="The user whose weights to retrieve")):
    """Return the model weights for the given user_id.

    If no data exists yet for this user, returns hardcoded default weights.
    TODO: once DB is in place, compute weights from stored sensor records via
          logistic regression instead of returning hardcoded defaults.
    """
    # TODO: replace these hardcoded defaults with weights trained on the user's
    #       stored sensor data once the DB and training pipeline are ready.
    DEFAULT_WEIGHTS = [0.0, 0.0, 0.0, 0.0, 0.0]  # one per feature: HR, HRV, motion, panic, bias

    all_records = read_all_records()
    user_records = [r for r in all_records if r["user_id"] == user_id]

    if not user_records:
        return {
            "user_id": user_id,
            "weights": DEFAULT_WEIGHTS,
            "source": "default",
        }

    # TODO: train logistic regression on user_records and return learned weights.
    #       For now, still returns defaults even when data exists.
    return {
        "user_id": user_id,
        "weights": DEFAULT_WEIGHTS,
        "source": "default",
    }
