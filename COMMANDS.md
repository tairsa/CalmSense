# Commands

Run from the repo root unless the line says otherwise.

## Test

| What | Command |
|---|---|
| Backend tests | `cd calmsense-backend && python -m pytest tests -q && python test_storage_guards.py && python test_auto_retrain.py` |
| Backend lint | `cd calmsense-backend && ruff check .` |
| Admin build | `cd admin-app && npm ci && npm run build` |
| Phone + watch tests, lint | `cd android-app && ./gradlew :app:testDebugUnitTest :wear:testDebugUnitTest :app:lintDebug :wear:lintDebug` |

## Run locally

| What | Command |
|---|---|
| Backend + admin dashboard | `run-admin-dashboard.bat` (first time: `calmsense-backend\seed-admin.bat`) |
| Backend only | `cd calmsense-backend && python -m uvicorn main:app --port 8000 --reload` → http://localhost:8000/docs |

## Ship (only after Alex says so)

| What | Command |
|---|---|
| Phone release (on Alex's PC only) | `bash scripts/publish-phone-release.sh` (needs a VERSIONING.md History row) |
| Backend + admin image | `gcloud builds submit --config cloudbuild.yaml --substitutions=_REGION=europe-west1` |
| Database backup now | `gh workflow run backup.yml` |

## Restore a database backup

1. Download: `gh run download <run-id> -n db-backup-<run-number>` (or from the run page).
2. Decrypt each file (asks for `BACKUP_PASSPHRASE`):
   `gpg -d -o calmsense-db-<date>.dump calmsense-db-<date>.dump.gpg`, same for `calmsense-auth-…`.
3. Restore into an **empty** Supabase project (Connect → Session pooler URI as `$URL`):
   ```sh
   pg_restore --no-owner -d "$URL" calmsense-db-<date>.dump
   pg_restore --data-only -d "$URL" calmsense-auth-<date>.dump
   ```
   One ignored error ("schema public already exists") is normal.
   Local test instead: any Postgres 16+, with `create role anon; create role authenticated;
   create role service_role; create schema auth; create table auth.users(id uuid primary key);`
   first (the auth restore then fails on missing columns; the app tables are what to check).

## Do NOT run

- `publish-phone-release.sh` on any PC but Alex's: a different key forces an uninstall.
- `git push` to `main` casually: it deploys the backend.
- `seed_demo_data.py`, `migrate_json_to_supabase.py` or `seed_admin.py` with production `.env` values.
- Uninstalling CalmSense from the phone or watch: it wipes local reports and the upload queue.
