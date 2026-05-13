@echo off
setlocal
cd /d "%~dp0"
call APLICATIVO-FIRE-TV-STICK\build-release-apk.bat
call APLICATIVO-TV-ANDROID-QR\build-release-apk.bat
call APLICATIVO-GESTOR-ANDROID\build-release-apk.bat
endlocal
