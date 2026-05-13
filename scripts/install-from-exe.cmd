@echo off
setlocal
set "TARGET=%LOCALAPPDATA%\PainelTV"
echo Instalando Painel TV em "%TARGET%"...
if not exist "%TARGET%" mkdir "%TARGET%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Test-Path -LiteralPath $env:TARGET) { Get-ChildItem -LiteralPath $env:TARGET -Force | Remove-Item -Recurse -Force }; Expand-Archive -LiteralPath '%~dp0INSTALADOR-PAINEL-TV-ATUALIZADO.zip' -DestinationPath $env:TARGET -Force"
if errorlevel 1 (
  echo Falha ao extrair pacote.
  pause
  exit /b 1
)
cd /d "%TARGET%"
call "%TARGET%\INSTALAR-PAINEL-WINDOWS.bat"
endlocal
