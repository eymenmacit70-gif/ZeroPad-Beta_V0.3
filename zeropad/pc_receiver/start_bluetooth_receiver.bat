@echo off
setlocal

cd /d "%~dp0.."

echo Closing old ZeroPad receiver instances...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*zeropad_receiver.py*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>nul

for /f "usebackq delims=" %%P in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$base='HKLM:\SYSTEM\CurrentControlSet\Enum\BTHENUM'; $ports=Get-ChildItem $base -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '00001101' -and $_.Name -match 'LOCALMFG' } | ForEach-Object { (Get-ItemProperty -Path $_.PSPath -ErrorAction SilentlyContinue).PortName } | Where-Object { $_ } | Select-Object -Unique; $ports | Select-Object -First 1"`) do set "BT_COM=%%P"

if "%BT_COM%"=="" (
    echo Could not auto-detect Bluetooth incoming COM port.
    echo Run this to list ports:
    echo python .\pc_receiver\zeropad_receiver.py --list-serial
    echo.
    echo Then start manually, example:
    echo python .\pc_receiver\zeropad_receiver.py --bluetooth-serial COM6
    pause
    exit /b 1
)

echo Using Bluetooth incoming port: %BT_COM%
echo Starting ZeroPad receiver...
python .\pc_receiver\zeropad_receiver.py --bluetooth-serial %BT_COM%
