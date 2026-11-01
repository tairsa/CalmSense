from pydantic import BaseModel, Field
from typing import List, Optional, Literal


# How an HRV number was obtained. Null means the client did not say - which is
# NOT the same as "none": rows written before the phone reported provenance are
# genuinely unknown and must not be assumed to be real measurements.
#   real_ibi     inter-beat intervals from the sensor (Samsung SDK, HealthKit)
#   bpm_derived  approximated from smoothed BPM - a proxy, an order of
#                magnitude smaller than a true RMSSD, and not comparable to it
#   none         no HRV was available
HrvSourceValue = Literal["real_ibi", "bpm_derived", "none"]


class SensorData(BaseModel):
    user_id: str
    panic_attack_detection: bool
    current_hr: float                  # Heart Rate in BPM
    current_hrv: float                 # Heart Rate Variability in ms
    current_motion_intensity: float    # Motion intensity (e.g. 0.0–1.0)
    hrv_source: Optional[HrvSourceValue] = None
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
    # Provenance of current_hrv. These rows are the model's training labels, so
    # mixing a real RMSSD with a bpm-derived proxy in one column would quietly
    # train on two different quantities.
    hrv_source: Optional[HrvSourceValue] = None
    # The phone's resting-HRV baseline for this hrv_source when the label was
    # captured. Training uses ln(current_hrv / hrv_baseline), the drop from the
    # user's own normal, rather than raw HRV - see ml/generate_data.py.
    hrv_baseline: Optional[float] = Field(None, gt=0)
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
    hrv_source: Optional[HrvSourceValue] = None


class _ManualEventReport(BaseModel):
    """Fields shared by the manual dissociation and flashback reports.

    These are journal entries only: they go to their own tables and never to
    panic_feedback, so they can't become training labels for the anxiety
    model. No user_id: the reporter comes from the verified token. Chip lists
    hold stable English keys, not display text. Vitals are kept for later
    analysis and not used yet.
    """

    timestamp: Optional[str] = None             # ISO 8601 of the report
    emotions: List[str] = Field(default_factory=list, max_length=30)
    feeling: Optional[str] = Field(None, max_length=4000)
    activity_before: Optional[str] = Field(None, max_length=2000)
    what_helped: Optional[str] = Field(None, max_length=2000)
    duration_minutes: Optional[int] = Field(None, ge=0, le=1440)
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    location_accuracy_m: Optional[float] = None
    current_hr: Optional[float] = None
    current_hrv: Optional[float] = None
    current_motion_intensity: Optional[float] = None
    hrv_source: Optional[HrvSourceValue] = None
    during_sleep: Optional[bool] = None


class DissociationReport(_ManualEventReport):
    experiences: List[str] = Field(default_factory=list, max_length=30)
    memory_clarity: Optional[Literal["clear", "partial", "none"]] = None


class FlashbackReport(_ManualEventReport):
    senses: List[str] = Field(default_factory=list, max_length=30)
    # How much it felt like it was happening now: the defining flashback feature.
    nowness: Optional[Literal["memory", "partial", "now"]] = None
    triggers: List[str] = Field(default_factory=list, max_length=30)
    body_reactions: List[str] = Field(default_factory=list, max_length=30)


class ProblemReport(BaseModel):
    """"Report a problem" from the phone's error screen.

    No user_id: the reporter comes from the verified token. `code` is the
    app's own E-<n> catalog number; `http_status` is set only when a server
    answer was involved. The phone keeps heart rate, tokens and emails out of
    `logs`; the size caps stop a broken client from filling the table.
    """

    code: int = Field(..., ge=1)
    http_status: Optional[int] = Field(None, ge=100, le=599)
    app_version: Optional[str] = Field(None, max_length=40)
    os_version: Optional[str] = Field(None, max_length=40)
    device: Optional[str] = Field(None, max_length=100)
    note: Optional[str] = Field(None, max_length=2000)
    logs: str = Field("", max_length=200_000)
    timestamp: Optional[str] = None             # ISO 8601 of when the error happened


# ---------------------------------------------------------------------------
# Therapist mode: profiles, consent codes, therapist-patient links.
# ---------------------------------------------------------------------------

class DisplayNameRequest(BaseModel):
    """Rename yourself. Deliberately carries no user_id and no role.

    The user comes from the verified token, and the role is read from the
    existing row server-side, so renaming can never change who you are or
    what you are - upsert_profile replaces the whole row, and a client that
    sent a stale or empty role would otherwise silently demote itself.
    """

    display_name: Optional[str] = None


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
