@echo off
REM Creates an admin login for the dashboard. Double-click and follow the prompts.
REM Run once before the first sign-in; add more admins later from the Admins page
REM inside the app.

setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\activate.bat" (
    echo [ERROR] No venv at .venv\
    echo Create it first:  python -m venv .venv  ^&^&  .venv\Scripts\pip install -r requirements.txt
    pause
    exit /b 1
)

call ".venv\Scripts\activate.bat"

set /p ADMIN_EMAIL="Admin email: "
set /p ADMIN_NAME="Display name (optional): "

echo.
python seed_admin.py --email "%ADMIN_EMAIL%" --name "%ADMIN_NAME%"

echo.
pause
endlocal
