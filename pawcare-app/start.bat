@echo off
rem Starts PawCare. Double-click, or run from a Command Prompt.
rem The first start asks for your Continuum API key and pipeline name and keeps
rem them in pawcare.local (git-ignored). Delete that file to enter new ones.
setlocal
cd /d "%~dp0"
set PYTHONIOENCODING=utf-8

where py >nul 2>nul
if %errorlevel%==0 (
  py -3 server.py
  goto end
)
where python >nul 2>nul
if %errorlevel%==0 (
  python server.py
  goto end
)
echo Python 3 was not found. Install it from https://www.python.org/downloads/
echo (tick "Add python.exe to PATH") and run this again.

:end
echo.
pause
