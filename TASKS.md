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

6. ✅ (2026-10-24) **Give GitHub the release key** (cmd, in the CalmSense folder):
   ```bat
   cd /d "C:\claude projects\Calm-Sense\CalmSense"
   powershell -NoProfile -Command "[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $env:USERPROFILE '.keystores\calmsense-release.jks'))) | gh secret set CALMSENSE_KEYSTORE_BASE64"
   gh secret set CALMSENSE_KEYSTORE_PASSWORD
   ```
   The second one asks for the key's password (from your password manager).
7. ✅ 1.5.0 released 2026-10-24 (both APKs signed CN=Alex, cert SHA-256 ad61e014…). Now the one-time switch: open CalmSense online (queued data
   uploads), uninstall phone + watch apps, install 1.5.0 from the release page (phone)
   and adb (watch). **Don't use the in-app updater for 1.5.0**: 1.4.1 would offer it, but
   Android refuses it (different key).
   Phone ✅ 1.5.0 installed 2026-10-24 (was blocked by an old 1.4.0 in the phone's
   "MyGuest" profile, removed). **Watch still 1.4.1 → its data can't reach the phone
   until it gets 1.5.0** (send the watch's adb IP:port).

Phase 3 (phone 1.6.0, backend + admin 1.3.0, branch `standard-phase-3`):

8. ✅ (2026-11-01) **Create the `problem_reports` table** (created after backend 1.4.0 deployed). Supabase → SQL
   Editor → paste the block under `"Report a problem" from the phone's error screen` at the
   end of `calmsense-backend/supabase_schema.sql` → Run. Safe to run twice.
9. Then say "merge" (deploys backend 1.3.0 + admin) and "release" (tags v1.6.0).

Dissociation + flashback (phone 1.7.0, backend 1.4.0, branch `trauma-events`, on top of phase 3):

10. ✅ (2026-11-01) **Create the two tables** before this backend deploys. Supabase → SQL Editor → paste the
    block under `Manual dissociation and flashback reports` at the end of
    `calmsense-backend/supabase_schema.sql` → Run. Safe to run twice.
11. Try it on the phone: home → "I'm dissociating" → grounding → questionnaire → Reports →
    Dissociation filter shows it; same for flashback.

Carried over:

- Install watch 1.4.1 (needs the watch's adb port) and check motion drops near 0 at rest.
- Supabase → Authentication → Emails → Reset password template: include `{{ .Token }}`.
- Custom SMTP for Supabase Auth (built-in mailer: 2 emails/hour).

## אצל Claude

- Watch → 1.5.0 when Alex sends its adb IP:port.
- Next real release: confirm the daily notification appears (1.5.1 was installed from
  Settings before the 24 h check, so the notification path is still unseen).
- Phase 3, step 1 written 2026-10-25 on `standard-phase-3` (not merged, not released):
  error codes E-1…E-14 (`ErrorCatalog.kt`), one error screen (`ErrorDialog.kt`), `AppLog`
  (500 lines, saved on crash/error, no HR/tokens/emails), reports via `UploadQueue` →
  `POST /api/v1/problem-reports` → admin Problems page; Telegram hook
  `notify_problem_report()` is a no-op until Alex adds the bot.
  Before release: try it on the phone (airplane mode → an error → Send report → back
  online → it shows on Problems).
- 2026-11-01: `main` = 104a8f1 (phase 3 + dissociation/flashback). Backend 1.4.0 deployed by hand
  (gcloud, image tag 104a8f1, revision calmsense-00015-mar; rollback: calmsense-00013-tih).
  CI's deploy job is skipped: repo variable `GCP_PROJECT_ID` and the WIF secrets were never set.
  Phone 1.7.0 not released yet (needs "release" after an on-phone test).
- Dissociation + flashback written 2026-11-01 on `trauma-events`, merged to main:
  `EventReport.kt` (EventType enum, one local file + one endpoint per type, drafts not
  posted until the questionnaire ends), `GroundingOverlay.kt`, `EventQuestionnaireScreen.kt`,
  `EventChips.kt`, `EventReportDetailScreen.kt`, category filter in `ReportsScreen.kt`;
  backend routes + tables. Vitals stored, not used yet. Not in admin app / therapist view.
- Phase 3 next: System status screen, then the retrain-failure alert (Build checkpoint each).

## Closed

- 2026-10-24: 1.5.0 + 1.5.1 released by release.yml; 1.5.1 installed on the phone through
  Settings > App updates (download, checksum, install) ✅.
- 2026-10-24: first real backup (run 40083557239, 1.59 MB encrypted).

- 2026-10-16: audit against the app standard, plan written (docs/standard-plan.md).
  Phone client path decided: Flutter, for iOS later.
- 2026-10-16: backup script + daily workflow written; dump → encrypt → decrypt → restore
  tested on a local Postgres built from `supabase_schema.sql` (rows, accounts, RLS and the
  `admin_user_stats()` revoke all survive; a wrong passphrase is refused).
