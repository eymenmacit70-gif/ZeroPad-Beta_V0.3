@echo off
setlocal

cd /d "%~dp0.."
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

echo === ADB devices ===
if exist "%ADB%" (
    "%ADB%" devices -l
    echo.
    echo === ADB reverse list ===
    "%ADB%" reverse --list
) else (
    echo ADB not found: %ADB%
)

echo.
echo === Bluetooth / serial ports ===
python .\pc_receiver\zeropad_receiver.py --list-serial

echo.
echo === USB TCP receiver port 54546 ===
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-NetTCPConnection -LocalPort 54546 -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,State,OwningProcess | Format-Table -AutoSize"

echo.
echo === Recent ZeroPad receiver log ===
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-Content .\pc_receiver\zeropad_receiver.log -Tail 30 -ErrorAction SilentlyContinue"

echo.
pause
