$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$shell = New-Object -ComObject WScript.Shell

$desktop = [Environment]::GetFolderPath("Desktop")
$startup = [Environment]::GetFolderPath("Startup")

$openShortcut = $shell.CreateShortcut((Join-Path $desktop "Painel TV.lnk"))
$openShortcut.TargetPath = Join-Path $root "abrir-painel.bat"
$openShortcut.WorkingDirectory = $root
$openShortcut.IconLocation = "$env:SystemRoot\System32\shell32.dll,220"
$openShortcut.Save()

$serverShortcut = $shell.CreateShortcut((Join-Path $startup "Painel TV Servidor.lnk"))
$serverShortcut.TargetPath = Join-Path $root "start-hidden.vbs"
$serverShortcut.WorkingDirectory = $root
$serverShortcut.IconLocation = "$env:SystemRoot\System32\shell32.dll,220"
$serverShortcut.Save()

$watchdogShortcut = $shell.CreateShortcut((Join-Path $startup "Painel TV Watchdog.lnk"))
$watchdogShortcut.TargetPath = Join-Path $root "start-watchdog.vbs"
$watchdogShortcut.WorkingDirectory = $root
$watchdogShortcut.IconLocation = "$env:SystemRoot\System32\shell32.dll,78"
$watchdogShortcut.Save()

$stopShortcut = $shell.CreateShortcut((Join-Path $desktop "Parar Painel TV.lnk"))
$stopShortcut.TargetPath = Join-Path $root "parar-painel.bat"
$stopShortcut.WorkingDirectory = $root
$stopShortcut.IconLocation = "$env:SystemRoot\System32\shell32.dll,131"
$stopShortcut.Save()

Write-Host "Atalhos criados:"
Write-Host "- Area de trabalho: Painel TV"
Write-Host "- Area de trabalho: Parar Painel TV"
Write-Host "- Inicializacao do Windows: Painel TV Servidor"
Write-Host "- Inicializacao do Windows: Painel TV Watchdog"
