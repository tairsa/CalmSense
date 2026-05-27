@echo off
REM Starts the CalmSense FastAPI backend. Double-click to launch.
REM Closes when you press Ctrl+C or close this window.

setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\activate.bat" (
    echo [ERROR] No venv at .venv\
    echo Create it first:  python -m venv .venv  ^&^&  .venv\Scripts\pip install -r requirements.txt
    pause
    exit /b 1
)

call ".venv\Scripts\activate.bat"

echo.
echo === CalmSense backend ===
echo Working dir : %CD%
echo Listening on: 0.0.0.0:8000
echo Reachable on this LAN at any of:
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do echo    http://%%a:8000  (paste into BACKEND_LAN_URL)
echo.
echo Health probe: http://127.0.0.1:8000/health
echo Stop with Ctrl+C.
echo.

uvicorn main:app --host 0.0.0.0 --port 8000 --reload

endlocal
