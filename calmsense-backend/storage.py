import json
import os
import tempfile

DATA_DIR = os.path.join(os.path.dirname(__file__), "data")
DATA_FILE = os.path.join(DATA_DIR, "sensor_data.json")
TABLE_NAME = "sensor_data"

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

def _json_append(record: dict) -> None:
    os.makedirs(DATA_DIR, exist_ok=True)

    if os.path.exists(DATA_FILE):
        with open(DATA_FILE, "r", encoding="utf-8") as f:
            records = json.load(f)
    else:
        records = []

    records.append(record)

    # Write atomically: temp file in same directory, then rename.
    fd, tmp_path = tempfile.mkstemp(dir=DATA_DIR, suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(records, f, indent=2)
        os.replace(tmp_path, DATA_FILE)
    except Exception:
        os.unlink(tmp_path)
        raise


def _json_read_all() -> list:
    if not os.path.exists(DATA_FILE):
        return []
    with open(DATA_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


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
