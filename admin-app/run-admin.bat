@echo off
REM Starts the CalmSense admin web app (Vite dev server). Double-click to launch.
REM The backend (calmsense-backend\run-server.bat) must be running too -- this
REM app proxies /api to it. Closes when you press Ctrl+C or close this window.

setlocal
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Node.js was not found on PATH.
    echo Install Node 18+ from https://nodejs.org/ then run this again.
    pause
    exit /b 1
)

if not exist "node_modules\" (
    echo Installing dependencies ^(first run only^)...
    call npm install
    if errorlevel 1 (
        echo [ERROR] npm install failed.
        pause
        exit /b 1
    )
)

echo.
echo === CalmSense admin app ===
echo Working dir : %CD%
echo Opening on  : http://localhost:5173
echo Backend     : expecting http://localhost:8000 ^(start run-server.bat first^)
echo Stop with Ctrl+C.
echo.

call npm run dev

endlocal
