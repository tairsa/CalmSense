# Changelog

Every shipped build, newest first. Versioning rules: VERSIONING.md.
A release takes its notes from the section whose heading starts with its version
and names the phone (`.github/workflows/release.yml`).

## 1.7.0 · 2026-11-01 · phone + watch

Dissociation and flashback reports. Two new buttons on the home screen ("I'm dissociating", "I'm having a flashback") open a grounding exercise instead of breathing: "find around you" tasks (4 green things, 3 sounds…) or guided tense-and-release by body part, with "Done" always available. Then a questionnaire of its own for each, without a 1-10 rating: emotions first, then what you experienced (dissociation: detachment, unreality, memory gaps, whether you remember it clearly; flashback: senses, how much it felt like now, trigger, body reaction), what helped and how long it lasted. Heart rate, HRV and motion at the moment of the report are kept for later (not when simulated). These reports never reach the anxiety model's training. Reports: a new category filter (All / Anxiety / Dissociation / Flashback). Watch: no changes, renumbered to match.

Also includes 1.6.0, which was not released on its own: error codes, one error screen with a fix button, and "Send report".

## 1.4.0 · 2026-11-01 · backend

`POST /api/v1/dissociation-reports` and `POST /api/v1/flashback-reports`, each in its own table (needs the `dissociation_reports` and `flashback_reports` tables, migration at the end of supabase_schema.sql). They never write to `panic_feedback`, so they can't become training labels.

## 1.6.0 · 2026-10-25 · phone + watch (not released; shipped in 1.7.0)

Errors have codes. Every failure the app shows now opens one error screen: a code (`E-2`, or `E-2 | 503` when the server answered with an HTTP status), what happened, why, what you can do, and a button to the place that fixes it (sign in again, App updates, Reload model, Internet settings). "Send report" sends the code, app and Android version, phone model, an optional note and the app's recent log to the CalmSense team; offline, it waits on the phone and goes with the next upload. The log keeps the last 500 lines and is saved on a crash or an error, never with heart rate, tokens or emails. Watch: no changes, renumbered to match.

## 1.3.0 · 2026-10-25 · backend, admin

`POST /api/v1/problem-reports` stores reports from the phone's error screen (needs the `problem_reports` table, migration in supabase_schema.sql); the admin app lists them on a new Problems page with each report's log. A hook for a Telegram notification is in place, not yet connected.

## 1.5.1 · 2026-10-24 · phone + watch

No changes for users: the first update delivered by the daily update notification and checked against its SHA-256 checksum, to prove that path works end to end.

## 1.5.0 · 2026-10-24 · phone + watch

The phone checks for a new version once a day and shows a notification; tapping it opens Settings > App updates. Every download is checked against the release's SHA-256 checksums before Android's installer opens. Releases are now built and signed by GitHub Actions with the CalmSense release key instead of a PC's debug key, so this version needs one uninstall and reinstall of the phone and watch apps; open the app online first so queued readings and reports upload. Watch: signed with the same new key, no other changes.

## 1.4.1 · 2026-10-05 · phone + watch

Watch: motion measured against the wrist's learned resting |a| instead of a nominal 9.81. On the raw accelerometer a few percent of sensor offset read as constant motion (0.8-1.1 m/s² while still), so the status said Active at rest, the panic model discounted resting readings as exercise, and the HRV baseline (which learns only below 0.5) rarely learned. Phone: no changes, renumbered to match.

## 1.4.0 · 2026-10-05 · phone + watch

Forgot password: Sign in > Forgot password? emails a 6-digit code, then a new password signs you in (needs {{ .Token }} in Supabase's Reset password email template). UI pass: secondary text and severity badges now meet 4.5:1 contrast (were 2.0-3.5:1); every theme colour role set, so nothing falls back to Material's default purple; selected report filter clearly marked; breathing screen fully opaque; watch status no longer cut short; 19 everyday strings translated to Hebrew; an unused duplicate questionnaire screen removed. Watch: no changes, renumbered to match.

## 1.3.1 · 2026-10-05 · phone + watch

Monitoring restarts by itself after a reboot or an app update. Android 14+ refuses to restart a location service from the background, so it stopped until the app was opened (25 h without uploads after the 1.3.0 update); it now runs on the watch link and adds GPS back when the app is opened. Watch: no changes, renumbered to match.

## 1.3.0 · 2026-10-04 · phone + watch

The phone updates itself: Settings > App updates checks GitHub Releases, downloads the new build and opens Android's installer (the first time, allow CalmSense to install apps). First build on the phone since 1.1.1, so it also brings everything in 1.2.0. Watch: no changes, renumbered to match.

## 1.2.0 · 2026-10-03 · phone + watch

Fingerprint sign-in, turned on by hand in Settings (password confirmed once, then kept locked by the fingerprint; the sign-in screen offers it). Staying signed in: signing out on one device no longer ends every other session (Supabase signs out globally by default), a rate limit or timeout no longer counts as a dead session, the rotated refresh token is written before it is used, a refresh whose reply was lost is retried inside Supabase's 10 s reuse window, and monitoring started at boot has the session. Watch: no changes, renumbered to match.

## 1.2.0 · 2026-10-03 · backend, admin

Admin Users and user pages show each person's name and email (names from profiles, emails from Supabase Auth). A name saved in the app is also put on the Supabase Auth account.

## 1.1.1 · 2026-10-03 · backend

Admin Users page and dashboard count in the database (`admin_user_stats()`, migration in supabase_schema.sql) instead of downloading the sensor table: 60 s and then a 500 at ~300k rows, now ~50 ms. The per-user sensor tab fetches only the newest page.

## 1.1.1 · 2026-10-03 · phone + watch

Watch: real HRV no longer drops out with the screen off (the Samsung SDK's batch is flushed on every send), and switching HRV source restarts the HRV window instead of reporting leftover values under the new label.

## 1.1.0 · 2026-10-03 · phone + watch, backend, admin

Faster watch-to-phone delivery; HRV judged against each user's own baseline; motion in m/s²; background detection on every watch sample; crash reporting (Sentry); HRV source on the watch face and "Real HRV" in admin; consent-code and login hardening; CI/CD. Model weights keep their 5-slot shape, but slot 3 now carries `hrv_rel` - phones before 1.1.0 ignore it and run a much less sensitive model, so phone and backend should move together.
