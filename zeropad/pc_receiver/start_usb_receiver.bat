@echo off
setlocal

cd /d "%~dp0.."
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

if not exist "%ADB%" (
    echo ADB not found: %ADB%
    echo Install Android SDK Platform Tools or open Android Studio once.
    pause
    exit /b 1
)

echo Closing old ZeroPad receiver instances...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*zeropad_receiver.py*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>nul

echo Checking phone...
"%ADB%" kill-server >nul 2>nul
"%ADB%" start-server
"%ADB%" devices -l

echo.
echo Setting ADB reverse tcp:54546 -> tcp:54546
"%ADB%" reverse tcp:54546 tcp:54546
if errorlevel 1 (
    echo.
    echo ADB reverse failed.
    echo Make sure USB debugging is enabled and the phone shows "device" in adb devices.
    echo If it says no devices, reconnect USB and approve the phone prompt.
    pause
    exit /b 1
)

echo.
echo Starting ZeroPad receiver...
python .\pc_receiver\zeropad_receiver.py
