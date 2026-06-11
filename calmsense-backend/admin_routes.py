"""Admin web-app API. All routes are under /api/v1/admin and (except login)
require a valid admin JWT. Backs the React admin app.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

import auto_retrain
import model_service
import storage
from auth import (
    create_access_token,
    get_current_admin,
    hash_password,
    verify_password,
)
from models import (
    AdminLoginRequest,
    AdminRegisterRequest,
    RetrainRequest,
    RollbackRequest,
    TokenResponse,
)

router = APIRouter(prefix="/api/v1/admin", tags=["admin"])


def _public(admin: dict) -> dict:
    return {"id": admin["id"], "email": admin["email"], "name": admin.get("name")}


# --- Auth ------------------------------------------------------------------

@router.post("/auth/login", response_model=TokenResponse)
def login(body: AdminLoginRequest):
    admin = storage.get_admin_by_email(body.email)
    if admin is None or not verify_password(body.password, admin["password_hash"]):
        raise HTTPException(status_code=401, detail="Invalid email or password")
    if not admin.get("is_active", True):
        raise HTTPException(status_code=403, detail="Account disabled")
    token = create_access_token(admin)
    return {"access_token": token, "token_type": "bearer", "admin": _public(admin)}


@router.post("/auth/register", response_model=TokenResponse)
def register(body: AdminRegisterRequest, _: dict = Depends(get_current_admin)):
    """Create a new admin. Requires an existing admin (bootstrap the first one
    with seed_admin.py)."""
    if storage.get_admin_by_email(body.email) is not None:
        raise HTTPException(status_code=409, detail="An admin with that email already exists")
    admin = storage.create_admin(body.email, hash_password(body.password), body.name)
    token = create_access_token(admin)
    return {"access_token": token, "token_type": "bearer", "admin": _public(admin)}


@router.get("/auth/me")
def me(admin: dict = Depends(get_current_admin)):
    return admin


@router.get("/admins")
def list_admins(admin: dict = Depends(get_current_admin)):
    """All admin accounts (no password hashes). Used by the Admins page."""
    return {"admins": storage.list_admins()}


# --- Users -----------------------------------------------------------------

@router.get("/users")
def list_users(admin: dict = Depends(get_current_admin)):
    """Distinct app users (by user_id) with per-source row counts."""
    sensors = storage.read_all_records()
    feedback = storage.read_all_feedback()
    reports = storage.read_all_reports()

    users: dict[str, dict] = {}

    def bump(uid, key):
        if not uid:
            return
        u = users.setdefault(uid, {
            "user_id": uid, "sensor_count": 0, "feedback_count": 0,
            "report_count": 0, "last_seen": None,
        })
        u[key] += 1

    def touch_last_seen(uid, ts):
        if not uid or not ts:
            return
        u = users.get(uid)
        if u and (u["last_seen"] is None or ts > u["last_seen"]):
            u["last_seen"] = ts

    for r in sensors:
        bump(r.get("user_id"), "sensor_count")
        touch_last_seen(r.get("user_id"), r.get("timestamp") or r.get("created_at"))
    for r in feedback:
        bump(r.get("user_id"), "feedback_count")
        touch_last_seen(r.get("user_id"), r.get("timestamp") or r.get("created_at"))
    for r in reports:
        bump(r.get("user_id"), "report_count")
        touch_last_seen(r.get("user_id"), r.get("timestamp") or r.get("created_at"))

    # Annotate each user with the source of their currently-served model.
    for uid, u in users.items():
        try:
            u["model_source"] = model_service.get_active_weights(uid)["source"]
        except Exception:
            u["model_source"] = None

    return {"users": sorted(users.values(), key=lambda u: u["user_id"])}


@router.get("/users/{user_id}")
def user_detail(user_id: str, admin: dict = Depends(get_current_admin)):
    return {
        "user_id": user_id,
        "metrics": model_service.compute_user_metrics(user_id),
        "model": model_service.get_model_state_view(user_id),
    }


@router.get("/users/{user_id}/reports")
def user_reports(user_id: str, admin: dict = Depends(get_current_admin)):
    rows = [r for r in storage.read_all_reports() if r.get("user_id") == user_id]
    rows.sort(key=lambda r: r.get("timestamp") or r.get("created_at") or "", reverse=True)
    return {"reports": rows}


@router.get("/users/{user_id}/feedback")
def user_feedback(user_id: str, admin: dict = Depends(get_current_admin)):
    rows = [r for r in storage.read_all_feedback() if r.get("user_id") == user_id]
    rows.sort(key=lambda r: r.get("timestamp") or r.get("created_at") or "", reverse=True)
    return {"feedback": rows}


@router.get("/users/{user_id}/sensor-data")
def user_sensor_data(user_id: str, limit: int = 500, admin: dict = Depends(get_current_admin)):
    rows = [r for r in storage.read_all_records() if r.get("user_id") == user_id]
    rows.sort(key=lambda r: r.get("timestamp") or r.get("created_at") or "", reverse=True)
    return {"sensor_data": rows[: max(1, min(limit, 5000))], "total": len(rows)}


@router.get("/users/{user_id}/metrics")
def user_metrics(user_id: str, admin: dict = Depends(get_current_admin)):
    return model_service.compute_user_metrics(user_id)


# --- Model management ------------------------------------------------------

@router.get("/users/{user_id}/model")
def model_state(user_id: str, admin: dict = Depends(get_current_admin)):
    return model_service.get_model_state_view(user_id)


@router.get("/users/{user_id}/model/history")
def model_history(user_id: str, admin: dict = Depends(get_current_admin)):
    snapshots = storage.list_model_snapshots(user_id)
    state = storage.get_user_model_state(user_id)
    active_id = state.get("active_weights_id") if state else None
    for s in snapshots:
        s["is_active"] = (s.get("id") == active_id)
    return {"snapshots": snapshots, "active_weights_id": active_id,
            "training_cutoff": state.get("training_cutoff") if state else None}


@router.post("/users/{user_id}/model/retrain")
def model_retrain(user_id: str, body: RetrainRequest | None = None,
                  admin: dict = Depends(get_current_admin)):
    body = body or RetrainRequest()
    cutoff = body.cutoff
    if cutoff is None and body.respect_cutoff:
        state = storage.get_user_model_state(user_id)
        cutoff = state.get("training_cutoff") if state else None
    try:
        snap = model_service.retrain_user_model(user_id, cutoff)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except ImportError:
        raise HTTPException(status_code=500,
                            detail="Training libraries (scikit-learn) not installed on the server.")
    return {"success": True, "snapshot": snap,
            "model": model_service.get_model_state_view(user_id)}


@router.post("/users/{user_id}/model/rollback")
def model_rollback(user_id: str, body: RollbackRequest,
                   admin: dict = Depends(get_current_admin)):
    try:
        target = model_service.rollback_user_model(user_id, body.cutoff)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return {"success": True, "activated_snapshot": target,
            "model": model_service.get_model_state_view(user_id)}


@router.post("/users/{user_id}/model/reset")
def model_reset(user_id: str, admin: dict = Depends(get_current_admin)):
    snap = model_service.reset_user_model(user_id)
    return {"success": True, "snapshot": snap,
            "model": model_service.get_model_state_view(user_id)}


# --- Auto-retraining ---------------------------------------------------------

@router.get("/auto-retrain/status")
def auto_retrain_status(admin: dict = Depends(get_current_admin)):
    """Loop configuration + the outcome of the most recent pass."""
    return auto_retrain.status()


@router.post("/auto-retrain/run")
def auto_retrain_run(admin: dict = Depends(get_current_admin)):
    """Run one retraining pass over all users right now."""
    return auto_retrain.run_pass()


# --- Global dashboard ------------------------------------------------------

@router.get("/metrics/global")
def global_metrics(admin: dict = Depends(get_current_admin)):
    sensors = storage.read_all_records()
    feedback = storage.read_all_feedback()
    reports = storage.read_all_reports()
    user_ids = {r.get("user_id") for r in sensors + feedback + reports if r.get("user_id")}

    tp = fp = fn = 0
    for r in feedback:
        detected = bool(r.get("detected_by_model"))
        was_panic = bool(r.get("was_panic"))
        if detected and was_panic:
            tp += 1
        elif detected and not was_panic:
            fp += 1
        elif not detected and was_panic:
            fn += 1

    return {
        "user_count": len(user_ids),
        "sensor_count": len(sensors),
        "feedback_count": len(feedback),
        "report_count": len(reports),
        "confusion": {"true_positive": tp, "false_positive": fp, "false_negative": fn},
        "precision": tp / (tp + fp) if (tp + fp) else None,
        "recall": tp / (tp + fn) if (tp + fn) else None,
        "feedback_by_day": model_service._counts_by_day(feedback),
    }
