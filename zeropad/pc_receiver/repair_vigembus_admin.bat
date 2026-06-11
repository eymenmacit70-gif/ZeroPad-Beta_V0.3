@echo off
setlocal

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0repair_vigembus_admin.ps1"
