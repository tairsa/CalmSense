from pydantic import BaseModel
from typing import Optional


class SensorData(BaseModel):
    user_id: str
    panic_attack_detection: bool
    current_hr: float                  # Heart Rate in BPM
    current_hrv: float                 # Heart Rate Variability in ms
    current_motion_intensity: float    # Motion intensity (e.g. 0.0–1.0)
    timestamp: Optional[str] = None   # ISO 8601; auto-filled server-side if omitted
