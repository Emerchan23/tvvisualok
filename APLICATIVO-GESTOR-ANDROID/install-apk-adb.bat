@echo off
setlocal
cd /d "%~dp0"
set "ADB=C:\Users\Adm\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "APK=%~dp0app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
  echo APK nao encontrado. Rode build-apk.bat primeiro.
  exit /b 1
)
"%ADB%" install -r "%APK%"
endlocal
