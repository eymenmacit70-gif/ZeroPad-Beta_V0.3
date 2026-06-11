@echo off
setlocal

cd /d "%~dp0.."

set "VENV=%USERPROFILE%\.zeropad-venv311"

where py >nul 2>nul
if errorlevel 1 (
    echo Python Launcher was not found. Install Python 3.11 or newer first.
    if not "%ZEROPAD_NO_PAUSE%"=="1" pause
    exit /b 1
)

echo Creating Python environment:
echo %VENV%
py -3.11 -m venv "%VENV%"
if errorlevel 1 (
    echo Python 3.11 was not found. Install Python 3.11, then run this again.
    if not "%ZEROPAD_NO_PAUSE%"=="1" pause
    exit /b 1
)

"%VENV%\Scripts\python.exe" -m pip install --upgrade pip
"%VENV%\Scripts\python.exe" -m pip install -r ".\pc_receiver\requirements.txt"

echo.
echo ZeroPad PC environment is ready.
if not "%ZEROPAD_NO_PAUSE%"=="1" pause
