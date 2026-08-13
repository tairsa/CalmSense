import json
import os
import tempfile

DATA_DIR = os.path.join(os.path.dirname(__file__), "data")
DATA_FILE = os.path.join(DATA_DIR, "sensor_data.json")
FEEDBACK_FILE = os.path.join(DATA_DIR, "panic_feedback.json")
REPORTS_FILE = os.path.join(DATA_DIR, "panic_reports.json")
PROFILES_FILE = os.path.join(DATA_DIR, "profiles.json")
CONSENT_CODES_FILE = os.path.join(DATA_DIR, "consent_codes.json")
LINKS_FILE = os.path.join(DATA_DIR, "therapist_patients.json")
TABLE_NAME = "sensor_data"
FEEDBACK_TABLE_NAME = "panic_feedback"
REPORTS_TABLE_NAME = "panic_reports"
PROFILES_TABLE_NAME = "profiles"
CONSENT_CODES_TABLE_NAME = "consent_codes"
LINKS_TABLE_NAME = "therapist_patients"

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


def _init_supabase():
    global _supabase_error
    url = os.environ.get("SUPABASE_URL")
    key = os.environ.get("SUPABASE_KEY")
    if not url or not key:
        _supabase_error = "SUPABASE_URL / SUPABASE_KEY not set"
        return None
    try:
        from supabase import create_client

        client = create_client(url, key)
        print("[storage] Supabase client initialized")
        return client
    except Exception as e:  # package missing or bad credentials/URL
        _supabase_error = f"supabase init failed: {e}"
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
            print(f"[storage] Supabase insert failed ({e}); writing JSON fallback")

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
            print(f"[storage] Supabase feedback insert failed ({e}); writing JSON fallback")

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
            print(f"[storage] Supabase report insert failed ({e}); writing JSON fallback")

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


# ---------------------------------------------------------------------------
# Profiles / consent / therapist-patient links
#
# These power the therapist mode. Same Supabase-first, JSON-fallback pattern
# as the sensor tables above so a demo without cloud connectivity still works
# end-to-end.
# ---------------------------------------------------------------------------

def _json_replace_by_key(path: str, key: str, record: dict) -> None:
    """Insert or replace a single row identified by [key] in a JSON file."""
    os.makedirs(DATA_DIR, exist_ok=True)
    rows = _json_read_from(path)
    rows = [r for r in rows if r.get(key) != record.get(key)]
    rows.append(record)
    fd, tmp_path = tempfile.mkstemp(dir=DATA_DIR, suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(rows, f, indent=2)
        os.replace(tmp_path, path)
    except Exception:
        os.unlink(tmp_path)
        raise


# --- Profiles --------------------------------------------------------------

def upsert_profile(record: dict) -> None:
    """Set or update a profile row (keyed by user_id)."""
    if _supabase is not None:
        try:
            _supabase.table(PROFILES_TABLE_NAME).upsert(record, on_conflict="user_id").execute()
            return
        except Exception as e:
            print(f"[storage] Supabase profile upsert failed ({e}); writing JSON fallback")
    _json_replace_by_key(PROFILES_FILE, "user_id", record)


def get_profile(user_id: str) -> dict | None:
    """Fetch a single profile by user_id, or None if missing."""
    if _supabase is not None:
        try:
            resp = (
                _supabase.table(PROFILES_TABLE_NAME)
                .select("*")
                .eq("user_id", user_id)
                .execute()
            )
            data = resp.data or []
            return data[0] if data else None
        except Exception as e:
            print(f"[storage] Supabase profile read failed ({e}); reading JSON fallback")
    rows = _json_read_from(PROFILES_FILE)
    return next((r for r in rows if r.get("user_id") == user_id), None)


# --- Consent codes ---------------------------------------------------------

def create_consent_code(record: dict) -> None:
    """Store a new consent code (keyed by code)."""
    if _supabase is not None:
        try:
            _supabase.table(CONSENT_CODES_TABLE_NAME).insert(record).execute()
            return
        except Exception as e:
            print(f"[storage] Supabase consent-code insert failed ({e}); writing JSON fallback")
    _json_append_to(CONSENT_CODES_FILE, record)


def find_consent_code(code: str) -> dict | None:
    """Return the code row if it exists (whether used or not)."""
    if _supabase is not None:
        try:
            resp = (
                _supabase.table(CONSENT_CODES_TABLE_NAME)
                .select("*")
                .eq("code", code)
                .execute()
            )
            data = resp.data or []
            return data[0] if data else None
        except Exception as e:
            print(f"[storage] Supabase consent-code read failed ({e}); reading JSON fallback")
    rows = _json_read_from(CONSENT_CODES_FILE)
    return next((r for r in rows if r.get("code") == code), None)


def mark_consent_code_used(code: str, patient_id: str, used_at_iso: str) -> None:
    """Set used_at/used_by on the code so it can't be redeemed twice."""
    if _supabase is not None:
        try:
            _supabase.table(CONSENT_CODES_TABLE_NAME).update({
                "used_at": used_at_iso,
                "used_by": patient_id,
            }).eq("code", code).execute()
            return
        except Exception as e:
            print(f"[storage] Supabase consent-code update failed ({e}); updating JSON fallback")
    rows = _json_read_from(CONSENT_CODES_FILE)
    for r in rows:
        if r.get("code") == code:
            r["used_at"] = used_at_iso
            r["used_by"] = patient_id
    _json_replace_by_key(CONSENT_CODES_FILE, "code", next(r for r in rows if r.get("code") == code))


# --- Therapist-patient links ----------------------------------------------

def create_therapist_patient_link(record: dict) -> None:
    """Create the (therapist_id, patient_id) link. No-op if it already exists."""
    if _supabase is not None:
        try:
            _supabase.table(LINKS_TABLE_NAME).insert(record).execute()
            return
        except Exception as e:
            # Unique-constraint violation is fine; caller cares only about "link exists after this".
            print(f"[storage] Supabase link insert note ({e})")
            return
    # JSON: enforce uniqueness on (therapist_id, patient_id) manually.
    rows = _json_read_from(LINKS_FILE)
    exists = any(
        r.get("therapist_id") == record.get("therapist_id")
        and r.get("patient_id") == record.get("patient_id")
        for r in rows
    )
    if not exists:
        _json_append_to(LINKS_FILE, record)


def list_patients_for_therapist(therapist_id: str) -> list[str]:
    """Return the patient_ids linked to this therapist."""
    if _supabase is not None:
        try:
            resp = (
                _supabase.table(LINKS_TABLE_NAME)
                .select("patient_id")
                .eq("therapist_id", therapist_id)
                .execute()
            )
            return [r["patient_id"] for r in (resp.data or []) if "patient_id" in r]
        except Exception as e:
            print(f"[storage] Supabase link read failed ({e}); reading JSON fallback")
    rows = _json_read_from(LINKS_FILE)
    return [r["patient_id"] for r in rows if r.get("therapist_id") == therapist_id]


def is_link_active(therapist_id: str, patient_id: str) -> bool:
    """Guard used by therapist-scoped read endpoints."""
    return patient_id in list_patients_for_therapist(therapist_id)


def get_reports_for_patient(patient_id: str) -> list:
    """All panic_reports rows for a given patient (used by therapist view)."""
    if _supabase is not None:
        try:
            resp = (
                _supabase.table(REPORTS_TABLE_NAME)
                .select("*")
                .eq("user_id", patient_id)
                .order("id")
                .execute()
            )
            return resp.data or []
        except Exception as e:
            print(f"[storage] Supabase per-patient reports read failed ({e}); reading JSON fallback")
    return [r for r in _json_read_from(REPORTS_FILE) if r.get("user_id") == patient_id]


def get_sensor_data_for_patient(patient_id: str) -> list:
    """All sensor_data rows for a given patient."""
    if _supabase is not None:
        try:
            resp = (
                _supabase.table(TABLE_NAME)
                .select("*")
                .eq("user_id", patient_id)
                .order("id")
                .execute()
            )
            return resp.data or []
        except Exception as e:
            print(f"[storage] Supabase per-patient sensor read failed ({e}); reading JSON fallback")
    return [r for r in _json_read_from(DATA_FILE) if r.get("user_id") == patient_id]
