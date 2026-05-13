@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install-windows-shortcuts.ps1"
echo.
echo Instalacao concluida.
echo O painel vai iniciar automaticamente com o Windows em segundo plano.
echo Use o atalho "Painel TV" na area de trabalho para abrir o navegador.
pause
endlocal
