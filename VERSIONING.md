# Versioning

All CalmSense components use [semantic versioning](https://semver.org):
`MAJOR.MINOR.PATCH`. Everything starts at **1.0.0** (2026-09-07).

## When to bump

**Every build that ships gets a version** - installed on Alex's devices or
deployed to production. Alex is patient zero: he runs every build himself, so
shipping to him *is* the release, and a version is what lets a bug report,
a Sentry event or a `/health` response name exactly which build it came from.
(Until 2026-10-03 a build was only numbered after it had been confirmed on a
device, which left the build under test indistinguishable from the last one.)

Bump in the same commit that ships, never after. Work that has not shipped yet
does not get a number - it is just the current commit.

| Bump  | For |
|-------|-----|
| PATCH | A fix or small change. The default. |
| MINOR | New user-visible capability, backwards compatible. |
| MAJOR | A break: an API contract change, or a phone build that no longer works with an existing watch build. |

## Where the number lives

Each deployable owns exactly one source of truth. Bump the file, nothing else.

| Component | Source of truth | Shown to the user |
|-----------|-----------------|-------------------|
| Phone + watch | `android-app/gradle.properties` → `calmsense.versionName` | Settings, foot of the list / watch status face |
| Admin web app | `admin-app/package.json` → `version` | Top bar, beside the brand |
| Backend | `calmsense-backend/main.py` → `APP_VERSION` | `GET /health` → `"version"` |

The phone and watch deliberately share one number. They have the same
`applicationId` and pair over the Data Layer, so letting them drift would make
"which build are you on?" ambiguous exactly when a pairing bug makes it matter.

`versionCode` is **derived**, never edited: `android-app/build.gradle.kts`
computes `MAJOR*10000 + MINOR*100 + PATCH` (so 1.0.0 → 10000). The build fails
if the version is not three numeric parts, or if minor or patch reaches 100 —
past that the derived code would stop increasing, which Play rejects.

Phone builds ship as GitHub Releases: `scripts/publish-phone-release.sh` uploads
the APK, and Settings > App updates on the phone installs it. The watch still
needs adb.

The three components version independently. They start aligned; they are not
expected to stay that way.

## History

| Version | Date | Components | What shipped |
|---------|------|------------|--------------|
| 1.4.1 | 2026-10-05 | phone + watch | Watch: motion measured against the wrist's learned resting \|a\| instead of a nominal 9.81. On the raw accelerometer a few percent of sensor offset read as constant motion (0.8-1.1 m/s² while still), so the status said Active at rest, the panic model discounted resting readings as exercise, and the HRV baseline (which learns only below 0.5) rarely learned. Phone: no changes, renumbered to match. |
| 1.4.0 | 2026-10-05 | phone + watch | Forgot password: Sign in > Forgot password? emails a 6-digit code, then a new password signs you in (needs {{ .Token }} in Supabase's Reset password email template). UI pass: secondary text and severity badges now meet 4.5:1 contrast (were 2.0-3.5:1); every theme colour role set, so nothing falls back to Material's default purple; selected report filter clearly marked; breathing screen fully opaque; watch status no longer cut short; 19 everyday strings translated to Hebrew; an unused duplicate questionnaire screen removed. Watch: no changes, renumbered to match. |
| 1.3.1 | 2026-10-05 | phone + watch | Monitoring restarts by itself after a reboot or an app update. Android 14+ refuses to restart a location service from the background, so it stopped until the app was opened (25 h without uploads after the 1.3.0 update); it now runs on the watch link and adds GPS back when the app is opened. Watch: no changes, renumbered to match. |
| 1.3.0 | 2026-10-04 | phone + watch | The phone updates itself: Settings > App updates checks GitHub Releases, downloads the new build and opens Android's installer (the first time, allow CalmSense to install apps). First build on the phone since 1.1.1, so it also brings everything in 1.2.0. Watch: no changes, renumbered to match. |
| 1.2.0 | 2026-10-03 | phone + watch | Fingerprint sign-in, turned on by hand in Settings (password confirmed once, then kept locked by the fingerprint; the sign-in screen offers it). Staying signed in: signing out on one device no longer ends every other session (Supabase signs out globally by default), a rate limit or timeout no longer counts as a dead session, the rotated refresh token is written before it is used, a refresh whose reply was lost is retried inside Supabase's 10 s reuse window, and monitoring started at boot has the session. Watch: no changes, renumbered to match. |
| 1.2.0 | 2026-10-03 | backend, admin | Admin Users and user pages show each person's name and email (names from profiles, emails from Supabase Auth). A name saved in the app is also put on the Supabase Auth account. |
| 1.1.1 | 2026-10-03 | backend | Admin Users page and dashboard count in the database (`admin_user_stats()`, migration in supabase_schema.sql) instead of downloading the sensor table: 60 s and then a 500 at ~300k rows, now ~50 ms. The per-user sensor tab fetches only the newest page. |
| 1.1.1 | 2026-10-03 | phone + watch | Watch: real HRV no longer drops out with the screen off (the Samsung SDK's batch is flushed on every send), and switching HRV source restarts the HRV window instead of reporting leftover values under the new label. |
| 1.1.0 | 2026-10-03 | phone + watch, backend, admin | Faster watch-to-phone delivery; HRV judged against each user's own baseline; motion in m/s²; background detection on every watch sample; crash reporting (Sentry); HRV source on the watch face and "Real HRV" in admin; consent-code and login hardening; CI/CD. Model weights keep their 5-slot shape, but slot 3 now carries `hrv_rel` - phones before 1.1.0 ignore it and run a much less sensitive model, so phone and backend should move together. |
