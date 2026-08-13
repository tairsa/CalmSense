from pydantic import BaseModel, Field
from typing import List, Optional, Literal


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


class PanicReport(BaseModel):
    """Journaled panic-attack entry.

    Captured at event start: GPS, timestamp, severity. Free-text fields and
    symptom list are filled in by the user via the post-event questionnaire
    and can all be skipped. Used by the user to track patterns over time and
    review them with a therapist; mirrored to backend for cross-device sync.
    """

    user_id: str
    timestamp: Optional[str] = None             # ISO 8601 of event start
    severity: int = Field(..., ge=1, le=10)
    detected_by_model: bool

    # Optional questionnaire fields — null when the user skipped them.
    feeling: Optional[str] = None
    symptoms: List[str] = Field(default_factory=list)
    activity_before: Optional[str] = None
    what_helped: Optional[str] = None
    duration_minutes: Optional[int] = Field(None, ge=0, le=1440)

    # Captured from FusedLocationProviderClient on the phone; null if perm denied.
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    location_accuracy_m: Optional[float] = None

    # Vitals at event start for therapist context.
    current_hr: Optional[float] = None
    current_hrv: Optional[float] = None
    current_motion_intensity: Optional[float] = None


# ---------------------------------------------------------------------------
# Therapist mode: profiles, consent codes, therapist-patient links.
# ---------------------------------------------------------------------------

class Profile(BaseModel):
    """One row per authenticated user. Chosen once at first login."""
    user_id: str
    role: Literal["patient", "therapist"]
    display_name: Optional[str] = None


class ConsentCodeRequest(BaseModel):
    """Therapist asks the server for a short, redeemable code to hand out."""
    therapist_id: str


class ConsentCodeResponse(BaseModel):
    code: str
    expires_at: str  # ISO 8601, UTC


class RedeemConsentRequest(BaseModel):
    """Patient submits a code they received from a therapist."""
    code: str
    patient_id: str


# ---------------------------------------------------------------------------
# Admin web-app models (auth + model management). These back the JWT-protected
# /api/v1/admin endpoints used by the React admin app — not the phone.
# ---------------------------------------------------------------------------


class AdminLoginRequest(BaseModel):
    email: str
    password: str


class AdminRegisterRequest(BaseModel):
    email: str
    password: str = Field(..., min_length=8)
    name: Optional[str] = None


class AdminPublic(BaseModel):
    id: int
    email: str
    name: Optional[str] = None


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    admin: AdminPublic


class RollbackRequest(BaseModel):
    """Roll a user's model back to the snapshot active as of `cutoff`.

    No refitting: the most recent snapshot at or before `cutoff` is re-activated,
    and `cutoff` is stored as the user's training cutoff so future retrains
    ignore data after it (until cleared).
    """

    cutoff: str  # ISO 8601 date/datetime


class RetrainRequest(BaseModel):
    """Retrain a user's model from their labeled feedback.

    If `respect_cutoff` is true and the user has a training cutoff set, feedback
    after the cutoff is excluded. Pass an explicit `cutoff` to override.
    """

    respect_cutoff: bool = True
    cutoff: Optional[str] = None
