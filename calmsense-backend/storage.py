import json
import os
import tempfile
from datetime import datetime, timezone


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()

DATA_DIR = os.path.join(os.path.dirname(__file__), "data")
DATA_FILE = os.path.join(DATA_DIR, "sensor_data.json")
FEEDBACK_FILE = os.path.join(DATA_DIR, "panic_feedback.json")
REPORTS_FILE = os.path.join(DATA_DIR, "panic_reports.json")
ADMIN_USERS_FILE = os.path.join(DATA_DIR, "admin_users.json")
MODEL_WEIGHTS_FILE = os.path.join(DATA_DIR, "model_weights.json")
USER_MODEL_STATE_FILE = os.path.join(DATA_DIR, "user_model_state.json")
TABLE_NAME = "sensor_data"
FEEDBACK_TABLE_NAME = "panic_feedback"
REPORTS_TABLE_NAME = "panic_reports"
ADMIN_USERS_TABLE_NAME = "admin_users"
MODEL_WEIGHTS_TABLE_NAME = "model_weights"
USER_MODEL_STATE_TABLE_NAME = "user_model_state"

# ---------------------------------------------------------------------------
# Storage backend selection
#
# Primary store is Supabase (Postgres via the supabase-py client), configured
# through env vars SUPABASE_URL and SUPABASE_KEY (loaded from a local .env if
# python-dotenv is installed). If those vars are missing, the supabase package
# isn't installed, or a Supabase call fails at runtime, we transparently fall
# back to the local JSON file so a reading is never lost and the API keeps
# running offline (handy for the demo and CI).
#
# Public API is unchanged: append_record(record) and read_all_records().
# main.py and the rest of the app don't need to know which backend is active.
# ---------------------------------------------------------------------------

# Load .env if available (no-op if python-dotenv isn't installed or no file).
try:
    from dotenv import load_dotenv

    load_dotenv(os.path.join(os.path.dirname(__file__), ".env"))
except Exception:
    pass

_supabase = None          # the client, or None when running on JSON
_supabase_error = None    # last init/runtime note, for logging

# Silently degrading to the JSON files is the right behaviour on the Pi, where
# they are on a persistent volume. In a container it is actively dangerous: the
# filesystem is ephemeral, so a "fallback" write is discarded at the next cold
# start with no error anywhere. Setting CALMSENSE_REQUIRE_SUPABASE makes Supabase
# mandatory — bad credentials fail startup (so a broken revision never takes
# traffic) and write errors surface as 5xx instead of vanishing.
_REQUIRE_SUPABASE = os.environ.get("CALMSENSE_REQUIRE_SUPABASE", "").strip().lower() in ("1", "true", "yes")


def _write_fallback_or_raise(what: str, e: Exception) -> None:
    """Either log-and-fall-through to the JSON write, or raise when Supabase is
    mandatory. Callers must invoke this *before* their JSON write."""
    if _REQUIRE_SUPABASE:
        raise RuntimeError(
            f"Supabase {what} failed and CALMSENSE_REQUIRE_SUPABASE is set; "
            f"refusing to write to the ephemeral JSON store: {e}"
        ) from e
    print(f"[storage] Supabase {what} failed ({e}); writing JSON fallback")


def _init_supabase():
    global _supabase_error
    url = os.environ.get("SUPABASE_URL")
    key = os.environ.get("SUPABASE_KEY")
    if not url or not key:
        _supabase_error = "SUPABASE_URL / SUPABASE_KEY not set"
        if _REQUIRE_SUPABASE:
            raise RuntimeError(
                "CALMSENSE_REQUIRE_SUPABASE is set but SUPABASE_URL / SUPABASE_KEY "
                "are missing — refusing to start on the ephemeral JSON store."
            )
        return None
    try:
        from supabase import create_client

        client = create_client(url, key)
        print("[storage] Supabase client initialized")
        return client
    except Exception as e:  # package missing or bad credentials/URL
        _supabase_error = f"supabase init failed: {e}"
        if _REQUIRE_SUPABASE:
            # Fail the container start rather than come up quietly on JSON.
            raise
        print(f"[storage] {_supabase_error} -> using JSON fallback")
        return None


_supabase = _init_supabase()


def storage_backend() -> str:
    """Return 'supabase' or 'json' - useful for /health and logging."""
    return "supabase" if _supabase is not None else "json"


# --- JSON fallback (original atomic-write implementation) ------------------

def _json_append_to(path: str, record: dict) -> None:
    os.makedirs(DATA_DIR, exist_ok=True)

    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            records = json.load(f)
    else:
        records = []

    records.append(record)

    # Write atomically: temp file in same directory, then rename.
    fd, tmp_path = tempfile.mkstemp(dir=DATA_DIR, suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(records, f, indent=2)
        os.replace(tmp_path, path)
    except Exception:
        os.unlink(tmp_path)
        raise


def _json_read_from(path: str) -> list:
    if not os.path.exists(path):
        return []
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _json_write_all(path: str, records: list) -> None:
    """Atomically replace the whole file contents with `records`."""
    os.makedirs(DATA_DIR, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(dir=DATA_DIR, suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(records, f, indent=2)
        os.replace(tmp_path, path)
    except Exception:
        os.unlink(tmp_path)
        raise


def _json_next_id(records: list) -> int:
    """Next surrogate id for JSON-mode rows (Supabase assigns ids itself)."""
    return (max((r.get("id", 0) for r in records), default=0) or 0) + 1


def _json_append(record: dict) -> None:
    _json_append_to(DATA_FILE, record)


def _json_read_all() -> list:
    return _json_read_from(DATA_FILE)


# --- Public API ------------------------------------------------------------

def append_record(record: dict) -> None:
    """Append a single sensor record.

    Inserts into Supabase when configured; on any Supabase error, writes to
    the local JSON file instead so the reading is never lost.
    """
    if _supabase is not None:
        try:
            _supabase.table(TABLE_NAME).insert(record).execute()
            return
        except Exception as e:
            _write_fallback_or_raise("insert", e)

    _json_append(record)


def read_all_records() -> list:
    """Return all stored records (Supabase if configured, else JSON)."""
    if _supabase is not None:
        try:
            resp = (
                _supabase.table(TABLE_NAME)
                .select("*")
                .order("id")
                .execute()
            )
            return resp.data or []
        except Exception as e:
            print(f"[storage] Supabase read failed ({e}); reading JSON fallback")

    return _json_read_all()


def append_feedback(record: dict) -> None:
    """Append a labeled panic-feedback record (training signal).

    Same Supabase-with-JSON-fallback semantics as append_record.
    """
    if _supabase is not None:
        try:
            _supabase.table(FEEDBACK_TABLE_NAME).insert(record).execute()
            return
        except Exception as e:
            _write_fallback_or_raise("feedback insert", e)

    _json_append_to(FEEDBACK_FILE, record)


def read_all_feedback() -> list:
    if _supabase is not None:
        try:
            resp = (
                _supabase.table(FEEDBACK_TABLE_NAME)
                .select("*")
                .order("id")
                .execute()
            )
            return resp.data or []
        except Exception as e:
            print(f"[storage] Supabase feedback read failed ({e}); reading JSON fallback")

    return _json_read_from(FEEDBACK_FILE)


def append_report(record: dict) -> None:
    """Append a journaled panic report. Same Supabase-with-JSON-fallback."""
    if _supabase is not None:
        try:
            _supabase.table(REPORTS_TABLE_NAME).insert(record).execute()
            return
        except Exception as e:
            _write_fallback_or_raise("report insert", e)

    _json_append_to(REPORTS_FILE, record)


def read_all_reports() -> list:
    if _supabase is not None:
        try:
            resp = (
                _supabase.table(REPORTS_TABLE_NAME)
                .select("*")
                .order("id")
                .execute()
            )
            return resp.data or []
        except Exception as e:
            print(f"[storage] Supabase report read failed ({e}); reading JSON fallback")

    return _json_read_from(REPORTS_FILE)


# --- Admin users -----------------------------------------------------------

def get_admin_by_email(email: str) -> dict | None:
    """Return the admin row for this email, or None."""
    email = email.strip().lower()
    if _supabase is not None:
        try:
            resp = (
                _supabase.table(ADMIN_USERS_TABLE_NAME)
                .select("*")
                .eq("email", email)
                .limit(1)
                .execute()
            )
            rows = resp.data or []
            return rows[0] if rows else None
        except Exception as e:
            print(f"[storage] Supabase admin read failed ({e}); reading JSON fallback")

    for row in _json_read_from(ADMIN_USERS_FILE):
        if row.get("email") == email:
            return row
    return None


def list_admins() -> list:
    """All admin accounts (without password hashes)."""
    if _supabase is not None:
        try:
            resp = (
                _supabase.table(ADMIN_USERS_TABLE_NAME)
                .select("id, email, name, is_active, created_at")
                .order("id")
                .execute()
            )
            return resp.data or []
        except Exception as e:
            print(f"[storage] Supabase admin list failed ({e}); reading JSON fallback")

    rows = _json_read_from(ADMIN_USERS_FILE)
    return [
        {k: r.get(k) for k in ("id", "email", "name", "is_active", "created_at")}
        for r in rows
    ]


def create_admin(email: str, password_hash: str, name: str | None) -> dict:
    """Insert a new admin and return the stored row."""
    email = email.strip().lower()
    record = {
        "email": email,
        "name": name,
        "password_hash": password_hash,
        "is_active": True,
    }
    if _supabase is not None:
        try:
            resp = _supabase.table(ADMIN_USERS_TABLE_NAME).insert(record).execute()
            return (resp.data or [record])[0]
        except Exception as e:
            _write_fallback_or_raise("admin insert", e)

    rows = _json_read_from(ADMIN_USERS_FILE)
    record["id"] = _json_next_id(rows)
    record["created_at"] = _now_iso()
    rows.append(record)
    _json_write_all(ADMIN_USERS_FILE, rows)
    return record


# --- Versioned model weights ----------------------------------------------

def insert_model_snapshot(record: dict) -> dict:
    """Insert a model-weights snapshot and return the stored row (with id)."""
    if _supabase is not None:
        try:
            resp = _supabase.table(MODEL_WEIGHTS_TABLE_NAME).insert(record).execute()
            return (resp.data or [record])[0]
        except Exception as e:
            _write_fallback_or_raise("snapshot insert", e)

    rows = _json_read_from(MODEL_WEIGHTS_FILE)
    stored = dict(record)
    stored["id"] = _json_next_id(rows)
    stored.setdefault("created_at", _now_iso())
    rows.append(stored)
    _json_write_all(MODEL_WEIGHTS_FILE, rows)
    return stored


def list_model_snapshots(user_id: str) -> list:
    """All snapshots for a user, newest first."""
    if _supabase is not None:
        try:
            resp = (
                _supabase.table(MODEL_WEIGHTS_TABLE_NAME)
                .select("*")
                .eq("user_id", user_id)
                .order("created_at", desc=True)
                .execute()
            )
            return resp.data or []
        except Exception as e:
            print(f"[storage] Supabase snapshot list failed ({e}); reading JSON fallback")

    rows = [r for r in _json_read_from(MODEL_WEIGHTS_FILE) if r.get("user_id") == user_id]
    rows.sort(key=lambda r: r.get("created_at") or "", reverse=True)
    return rows


def get_model_snapshot(snapshot_id: int) -> dict | None:
    if _supabase is not None:
        try:
            resp = (
                _supabase.table(MODEL_WEIGHTS_TABLE_NAME)
                .select("*")
                .eq("id", snapshot_id)
                .limit(1)
                .execute()
            )
            rows = resp.data or []
            return rows[0] if rows else None
        except Exception as e:
            print(f"[storage] Supabase snapshot get failed ({e}); reading JSON fallback")

    for row in _json_read_from(MODEL_WEIGHTS_FILE):
        if row.get("id") == snapshot_id:
            return row
    return None


# --- Per-user model state (active snapshot + training cutoff) --------------

def get_user_model_state(user_id: str) -> dict | None:
    if _supabase is not None:
        try:
            resp = (
                _supabase.table(USER_MODEL_STATE_TABLE_NAME)
                .select("*")
                .eq("user_id", user_id)
                .limit(1)
                .execute()
            )
            rows = resp.data or []
            return rows[0] if rows else None
        except Exception as e:
            print(f"[storage] Supabase state get failed ({e}); reading JSON fallback")

    for row in _json_read_from(USER_MODEL_STATE_FILE):
        if row.get("user_id") == user_id:
            return row
    return None


def upsert_user_model_state(user_id: str, active_weights_id: int | None,
                            training_cutoff: str | None) -> dict:
    """Create or update a user's model state row."""
    record = {
        "user_id": user_id,
        "active_weights_id": active_weights_id,
        "training_cutoff": training_cutoff,
        "updated_at": _now_iso(),
    }
    if _supabase is not None:
        try:
            resp = (
                _supabase.table(USER_MODEL_STATE_TABLE_NAME)
                .upsert(record, on_conflict="user_id")
                .execute()
            )
            return (resp.data or [record])[0]
        except Exception as e:
            _write_fallback_or_raise("state upsert", e)

    rows = _json_read_from(USER_MODEL_STATE_FILE)
    rows = [r for r in rows if r.get("user_id") != user_id]
    rows.append(record)
    _json_write_all(USER_MODEL_STATE_FILE, rows)
    return record
