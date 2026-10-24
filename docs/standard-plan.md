# CalmSense → the app standard

Audit of `main` @ 75b1bb0 (phone + watch 1.4.1, backend + admin 1.2.0) against the
house standard (make-an-app checklist), 2026-10-16. Status: **approved, in order; phase 1
in progress.**

✅ meets · ⚠️ partial · ❌ missing · ↔ different on purpose · ❓ needs your decision

## Defaults

| Topic | Standard | CalmSense today | |
|---|---|---|---|
| Client | Flutter: Android + web/iPad + Windows | Kotlin/Compose phone, Kotlin Wear OS watch (Samsung Health Sensor SDK `.aar`), React admin web | ❓ see "Client path" |
| Backend | Firebase Spark, rules are the backend | FastAPI on Cloud Run + Supabase Postgres, JWT checked on every route | ↔ keep: users, data and a hardened API already live there |
| Server work | Pi 5 when needed | Cloud Run (auto-retrain loop, admin API) | ↔ keep |
| Cost | Zero | Supabase free + Cloud Run free tier | ⚠️ Supabase free: 500 MB DB (sensor_data grows every 30 s per user), 2 auth emails/hour, project pauses after a week idle |
| Language | Hebrew RTL | English default, Hebrew translation (217/218 strings), per-app locale | ❓ academic/English audience vs. house Hebrew default |
| Theme | Light + dark, follows system | Light only: `Theme.kt` says "the calm palette doesn't translate to dark" | ↔ recorded decision; ❓ keep it? |
| Sign-in | Google + allowlist, fingerprint opt-in | Supabase email/password, open sign-up, forgot password, fingerprint toggle | ⚠️ (an allowlist doesn't fit a patient product: ↔) |
| Updates | Silent, background, GitHub Releases | Manual check in Settings → opens Android's installer; watch needs adb | ⚠️ |
| Release | Tag → Actions builds + publishes | `scripts/publish-phone-release.sh` on the PC; CI deploys backend on `main` | ⚠️ |
| Platforms | APK + web + Windows + widgets | Phone APK, watch via adb, admin web | ⚠️ |

## Checklist

| # | Area | | Gap |
|---|---|---|---|
| 1 | Versioning | ⚠️ | SemVer ✅, one source per component ✅, History table in VERSIONING.md instead of `CHANGELOG.md`; version not on the sign-in screen; `versionCode` = `M*10000+m*100+p` (works; switching to `M*1000000+…` is safe, it only rises) |
| 2 | Release pipeline | ⚠️ | CI is strong (tests, lint, pip/npm audit, Cloud Run deploy gated on `/health`). No `release.yml`, no `SHA256SUMS`, no secret scan of the APK; releases built by hand on the PC |
| 3 | Patient zero | ❌ | Releases go straight to "latest" |
| 4 | In-app updates | ⚠️ | Manual only, no checksum, no background download, no silent install; watch can't update itself (no INTERNET) |
| 5 | Min version / kill switch | ❌ | Nothing stops an old phone from posting an old payload shape |
| 6 | Sign-in | ⚠️ | Stays signed in ✅ (1.2.0), forgot password ✅ (template still needs `{{ .Token }}`). Missing: Google sign-in, switch account, last sign-in, shake-on-wrong-password |
| 7 | Fingerprint | ⚠️ | Toggle in Settings ✅. Not offered after first sign-in; no lock on reopen / after 5 min |
| 8 | Notifications | ⚠️ | Local panic alert + monitor notification ✅ (the core). No push: therapist events, new version, auto-retrain failure alert |
| 9 | Errors with codes | ❌ | Raw messages; no catalog. HTTP timeouts ✅ |
| 10 | Problem report | ❌ | Sentry catches crashes ✅; no user report |
| 11 | Diagnostics screen | ❌ | |
| 12 | Walkthrough | ❌ | Consent + questionnaire only |
| 13 | Settings set | ⚠️ | Has version, updates, language, fingerprint, monitoring, sign out. Missing: theme, diagnostics, report, export, walkthrough, switch account, last sign-in, security note |
| 14 | UI | ⚠️ | Colour tokens ✅, contrast pass ✅ (1.4.0). Missing: skeletons, pull-to-refresh, splash, home widgets, long-press menus |
| 15 | Data safety | ❌ | Last backup is a manual JSON dump from 2026-08-04; Supabase free has no downloadable backups. No in-app export. Offline upload queue ✅ |
| 16 | Security | ⚠️ | Backend hardening ✅. **Apps are signed with the PC's `~/.android/debug.keystore`, not backed up** — lose the PC and every install must be wiped. Sentry token pasted in chat on 2026-10-03: revoked? |
| 17 | Docs | ⚠️ | README, VERSIONING, DATABASE ✅. Missing: `CLAUDE.md`, `TASKS.md`, `CHANGELOG.md`, `COMMANDS.md`; README lacks Cost / Security / Releasing |

## Client path: **Flutter** (decided 2026-10-16)

Reason: so the phone app can run on iOS later. Phases run in order, safest first.
iOS facts to plan around when that day comes:

- Galaxy Watches (Wear OS) don't pair with iPhones. On iOS the sensor source is an
  Apple Watch, which needs its own watchOS app (Swift, HealthKit); Flutter covers the
  phone screens only.
- iOS needs the Apple Developer Program ($99/year) and a Mac or macOS CI runner, and
  apps can't update themselves from GitHub: TestFlight / App Store instead.

Facts that shaped the decision:

- The **watch stays Kotlin** either way: the Samsung Health Sensor SDK is a native `.aar`,
  and Flutter has no real Wear OS support.
- The phone's core is Android-native: a foreground service (`connectedDevice` + location),
  the Wearable Data Layer listener, Health Connect, `PackageInstaller`. In Flutter these
  stay Kotlin behind platform channels; the screens move.
- Web and Windows add little for a patient (no sensors there). A therapist view on the
  web would; the React admin app already runs there.
- Same `applicationId` (`com.calmsense.app`) is kept either way, so the watch keeps pairing.

## Phases

Each phase is one release. Phases 1–2 are the same whichever client path you choose.

**1. Safety (no app release)**
- Back up `~/.android/debug.keystore` to Drive now; it is the only key that can update the
  installed apps.
- Create the real release keystore in `~/.keystores`, back it up, put it in GitHub secrets.
  Switching keys means **one reinstall of phone + watch**: do it once, in phase 2. Local
  data at risk: unsynced reports in `PanicReportStore` and the upload queue → sync first.
- Daily Supabase backup: `pg_dump` from a GitHub Actions cron (free) → dated, encrypted
  artifact, 30 kept. Restore into local Postgres once and note it in TASKS.md.
- Revoke the Sentry token if not done. Add CLAUDE.md, TASKS.md, COMMANDS.md.
  (CHANGELOG.md moves to phase 2: the release script reads notes from VERSIONING.md
  today, and two sources would drift.)
- Verify: restore test passes; key backups listed in TASKS.md.
- Done 2026-10-16: `scripts/backup-db.sh` + `.github/workflows/backup.yml`, round trip
  tested locally; CLAUDE.md, TASKS.md, COMMANDS.md. Waiting on Alex: key backups, the
  two backup secrets, merge, first real run + restore (TASKS.md items 1–5).

**2. Release pipeline + updates (phone/watch 1.5.0)**
- `release.yml` on tag: version check, keystore in an isolated job, signed phone + watch
  APKs, secret scan, `SHA256SUMS`, prerelease with CHANGELOG notes.
- Updater: verify SHA-256, prerelease only for early users, background download on Wi-Fi,
  `PackageInstaller` silent install after the app leaves the screen, check on launch / every 6 h.
- `config` table in Supabase (`minVersion`, `earlyUsers`, `message`), public read;
  below `minVersion` → full-screen "update required", uploads stop.
- You notice: one reinstall (new key), then updates arrive by themselves.
- Risk: the key switch. Verify: install 1.5.0, ship 1.5.1 as a prerelease, see it install silently.
- Decided 2026-10-24 (Alex): updates **prompt** instead of installing silently (daily check
  → notification → Settings; Settings button stays) ↔. Min version / kill switch and the
  early-users channel **deferred** until test subjects join (single user now). Trust =
  release key (Android refuses another key) + SHA-256 checksum + HTTPS.
- Written 2026-10-24 on `standard-phase-2`: release.yml, CHANGELOG.md, UpdateNotifier,
  checksum check in AppUpdater, release signing config. Waits on: key secrets, "release".

**3. Errors, diagnostics, problem report (1.6.0)**
- Error catalog with letter codes + a test listing them; `showError` everywhere.
- Settings → System status: account, version, last update check, watch link, last upload,
  queue length, backend `/health`.
- Settings → Report a problem → backend `/report` (checks the Supabase JWT) → the shared
  Telegram bot. Sentry stays for crashes.
- Push for the auto-retrain failure alert (Telegram is enough; FCM can wait).

**4. Client path + UI standard (1.7.0 or 2.0.0)**
- Retrofit (Kotlin): splash, skeletons, pull-to-refresh, long-press menus, theme setting
  (if dark is wanted), lock on reopen, offer fingerprint after sign-in, walkthrough with
  `since:` steps, home widget (today's status / monitoring on-off).
- Or Migrate (Flutter, 2.0.0): app-shell screens at parity, Kotlin service + watch link
  kept as a plugin, same id and key. Old app keeps getting fixes until parity.

**5. The rest**
- Google sign-in, switch account, last sign-in. Data export (CSV + JSON). Therapist push
  notifications (FCM needs a server sender: Cloud Run can do it). DB retention /
  downsampling for the 500 MB limit. README Cost / Security / Releasing.

## Progress

- [x] 1 (restore test open) · [x] 2 (watch on 1.5.0 + notification seen still open) · [ ] 3 · [ ] 4 · [ ] 5
