# Makes the daily-check token and gives the same value to GitHub (secret) and
# Cloud Run (env var). The token is never printed. Run again to rotate it.
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\set-cron-token.ps1
$ErrorActionPreference = "Stop"
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$token = -join ($bytes | ForEach-Object { $_.ToString("x2") })

$token | gh secret set CALMSENSE_CRON_TOKEN
if ($LASTEXITCODE -ne 0) { throw "gh secret set failed" }

# --update-env-vars keeps every other env var on the service.
gcloud run services update calmsense --region europe-west1 --update-env-vars "CALMSENSE_CRON_TOKEN=$token" --quiet
if ($LASTEXITCODE -ne 0) { throw "gcloud update failed (GitHub already has the new token: run this again)" }

Write-Host "Done: GitHub secret and Cloud Run env var set."
