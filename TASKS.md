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
3. ✅ (2026-10-24) Daily backup on. First real run 40083557239: db 1.58 MB + auth 2.6 KB,
   encrypted. (An earlier run leaked the old DB password: run deleted, password reset,
   script now masks it.)
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

Phase 2 (1.5.0):

6. **Give GitHub the release key** (cmd, in the CalmSense folder):
   ```bat
   cd /d "C:\claude projects\Calm-Sense\CalmSense"
   powershell -NoProfile -Command "[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $env:USERPROFILE '.keystores\calmsense-release.jks'))) | gh secret set CALMSENSE_KEYSTORE_BASE64"
   gh secret set CALMSENSE_KEYSTORE_PASSWORD
   ```
   The second one asks for the key's password (from your password manager).
7. Say "release" for 1.5.0. Then the one-time switch: open CalmSense online (queued data
   uploads), uninstall phone + watch apps, install 1.5.0 from the release page (phone)
   and adb (watch). **Don't use the in-app updater for 1.5.0**: 1.4.1 would offer it, but
   Android refuses it (different key).

Carried over:

- Install watch 1.4.1 (needs the watch's adb port) and check motion drops near 0 at rest.
- Supabase → Authentication → Emails → Reset password template: include `{{ .Token }}`.
- Custom SMTP for Supabase Auth (built-in mailer: 2 emails/hour).

## אצל Claude

- Phase 2 code on branch `standard-phase-2` (unpushed). After 1.5.0 is installed: ship
  1.5.1 and check the daily notification → Settings → install works end to end.

## Closed

- 2026-10-24: first real backup (run 40083557239, 1.59 MB encrypted).

- 2026-10-16: audit against the app standard, plan written (docs/standard-plan.md).
  Phone client path decided: Flutter, for iOS later.
- 2026-10-16: backup script + daily workflow written; dump → encrypt → decrypt → restore
  tested on a local Postgres built from `supabase_schema.sql` (rows, accounts, RLS and the
  `admin_user_stats()` revoke all survive; a wrong passphrase is refused).
