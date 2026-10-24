# Tasks

Plan and phases: docs/standard-plan.md.

## עליך (only Alex can do these)

Phase 1, safety. Do these first:

1. ✅ (2026-10-16) **Back up the signing key that's in use now.** Upload `C:\Users\User\.android\debug.keystore`
   to Google Drive (a private folder, e.g. `Keys/CalmSense`). It is the only key that can
   update the installed phone and watch apps.
2. ✅ (2026-10-24, `.jks` on Drive) **Create the release key** (used from phase 2). In Git Bash:
   ```sh
   "/c/Program Files/Android/Android Studio/jbr/bin/keytool.exe" -genkeypair -v \
     -keystore ~/.keystores/calmsense-release.jks -alias calmsense \
     -keyalg RSA -keysize 4096 -validity 36500
   ```
   It asks for a password: save it in your password manager, then upload the `.jks` to
   the same Drive folder. Losing either one means every user reinstalls.
3. Secrets ✅ set 2026-10-24 (DB password unverified until the first run). Still: say "merge".
   **Turn on the daily database backup.** Needs repo secrets (you have write access):
   ```sh
   gh secret set SUPABASE_DB_URL      # paste: Supabase -> Connect -> Session pooler URI, password filled in
   gh secret set BACKUP_PASSPHRASE    # paste a long random string; save it in your password manager too
   ```
   Then say "merge" so `backup.yml` reaches `main` (scheduled workflows only run from
   `main`), and run it once: `gh workflow run backup.yml`.
4. **Test a restore once** with the first real backup (COMMANDS.md → "Restore a database
   backup") and tick it here.
5. ✅ (2026-10-24) Revoke the Sentry auth token pasted in chat on 2026-10-03.

Carried over:

- Install watch 1.4.1 (needs the watch's adb port) and check motion drops near 0 at rest.
- Supabase → Authentication → Emails → Reset password template: include `{{ .Token }}`.
- Custom SMTP for Supabase Auth (built-in mailer: 2 emails/hour).

## אצל Claude

- After the first real backup: check the run log and artifact size.
- Phase 2: release pipeline on tag, CHANGELOG.md, silent updates, min version, new key
  (one reinstall). Waits on items 2–3 above.

## Closed

- 2026-10-16: audit against the app standard, plan written (docs/standard-plan.md).
  Phone client path decided: Flutter, for iOS later.
- 2026-10-16: backup script + daily workflow written; dump → encrypt → decrypt → restore
  tested on a local Postgres built from `supabase_schema.sql` (rows, accounts, RLS and the
  `admin_user_stats()` revoke all survive; a wrong passphrase is refused).
