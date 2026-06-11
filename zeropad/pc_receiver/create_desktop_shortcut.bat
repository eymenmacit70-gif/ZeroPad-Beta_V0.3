@echo off
setlocal

cd /d "%~dp0.."

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$root=(Resolve-Path '.').Path; " ^
  "$desktop=[Environment]::GetFolderPath('Desktop'); " ^
  "$launcher=Join-Path $root 'pc_receiver\zeropad_launcher.vbs'; " ^
  "$icon=Join-Path $root 'pc_receiver\assets\zeropad.ico'; " ^
  "$shell=New-Object -ComObject WScript.Shell; " ^
  "foreach ($name in @('ZeroPad.lnk','ZeroPad Receiver.lnk')) { " ^
  "  $shortcutPath=Join-Path $desktop $name; " ^
  "  $shortcut=$shell.CreateShortcut($shortcutPath); " ^
  "  $shortcut.TargetPath=Join-Path $env:WINDIR 'System32\wscript.exe'; " ^
  "  $shortcut.Arguments='""' + $launcher + '""'; " ^
  "  $shortcut.WorkingDirectory=$root; " ^
  "  if (Test-Path $icon) { $shortcut.IconLocation=$icon + ',0' }; " ^
  "  $shortcut.Save(); " ^
  "  Write-Host 'Created:' $shortcutPath " ^
  "}"

if not "%ZEROPAD_NO_PAUSE%"=="1" pause
