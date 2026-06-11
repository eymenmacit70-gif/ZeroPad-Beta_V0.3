@echo off
setlocal

cd /d "%~dp0android"

call gradlew.bat --stop

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "ANDROID_SDK=%LOCALAPPDATA%\Android\Sdk"

if exist "%ANDROID_SDK%" (
    > local.properties echo sdk.dir=%ANDROID_SDK:\=\\%
)

call gradlew.bat --no-daemon assembleDebug
if errorlevel 1 (
    echo.
    echo Build failed. If Android Studio or OneDrive is syncing, close/pause them and try again.
    pause
    exit /b 1
)

echo.
echo APK ready:
echo %~dp0dist\ZeroPad-debug.apk
pause
