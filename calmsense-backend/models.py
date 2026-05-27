from pydantic import BaseModel, Field
from typing import Optional


class SensorData(BaseModel):
    user_id: str
    panic_attack_detection: bool
    current_hr: float                  # Heart Rate in BPM
    current_hrv: float                 # Heart Rate Variability in ms
    current_motion_intensity: float    # Motion intensity (e.g. 0.0–1.0)
    timestamp: Optional[str] = None   # ISO 8601; auto-filled server-side if omitted


class PanicFeedback(BaseModel):
    """Labeled training signal from the user.

    Three intended sources:
      - detected_confirmed:   model fired, user said \"yes, that was a panic\"
      - detected_rejected:    model fired, user said \"no, false alarm\"
      - manual:               user logged a panic themselves (model missed it)
    """

    user_id: str
    was_panic: bool                              # ground truth from the user
    severity: Optional[int] = Field(None, ge=1, le=10)  # only when was_panic=true
    detected_by_model: bool                      # true = detection path, false = manual
    current_hr: Optional[float] = None
    current_hrv: Optional[float] = None
    current_motion_intensity: Optional[float] = None
    model_probability: Optional[float] = None    # p(panic) the on-device model emitted
    timestamp: Optional[str] = None             # ISO 8601; auto-filled server-side if omitted
