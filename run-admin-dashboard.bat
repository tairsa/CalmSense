@echo off
REM One-click launcher for the CalmSense admin dashboard.
REM Opens the backend and the admin web app in their own windows, then opens
REM the browser. First time? Run calmsense-backend\seed-admin.bat once to create
REM your login.

setlocal
cd /d "%~dp0"

echo Starting CalmSense backend...
start "CalmSense Backend" "%~dp0calmsense-backend\run-server.bat"

echo Starting CalmSense admin app...
start "CalmSense Admin" "%~dp0admin-app\run-admin.bat"

echo Waiting for the dev server to come up...
timeout /t 6 /nobreak >nul
start "" http://localhost:5173

echo.
echo Two windows opened (backend + admin app). Browser pointed at
echo http://localhost:5173. Close those windows or press Ctrl+C in them to stop.
echo You can close THIS window now.
echo.
pause

endlocal
