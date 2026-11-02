@echo off
rem Fills your Continuum account with demo activity. Double-click, or run from
rem a Command Prompt:  seed-demo-data.bat you@example.com
rem It only adds; nothing is deleted. See seed-demo-data.ps1 for options.
setlocal
cd /d "%~dp0"
set PYTHONIOENCODING=utf-8
set "EMAILARG="
if not "%~1"=="" set "EMAILARG=--email %~1"

where py >nul 2>nul
if %errorlevel%==0 (
  py -3 seed_demo_data.py %EMAILARG%
  goto end
)
where python >nul 2>nul
if %errorlevel%==0 (
  python seed_demo_data.py %EMAILARG%
  goto end
)
echo Python 3 was not found. Install it from https://www.python.org/downloads/
echo (tick "Add python.exe to PATH") and run this again.

:end
echo.
pause
