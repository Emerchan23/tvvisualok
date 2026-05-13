$ErrorActionPreference = "SilentlyContinue"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$stateFile = Join-Path $root "apps\server\data\state.json"
$wasDown = $false

function Get-TelegramConfig {
  if (!(Test-Path $stateFile)) { return $null }
  try {
    $state = Get-Content $stateFile -Raw | ConvertFrom-Json
    return $state.settings.telegram
  } catch {
    return $null
  }
}

function Send-Telegram($message) {
  $cfg = Get-TelegramConfig
  if (!$cfg -or !$cfg.enabled -or !$cfg.botToken -or !$cfg.chatId) { return }
  try {
    $body = @{
      chat_id = $cfg.chatId
      text = $message
      parse_mode = "HTML"
      disable_web_page_preview = $true
    } | ConvertTo-Json
    Invoke-RestMethod -Uri "https://api.telegram.org/bot$($cfg.botToken)/sendMessage" -Method Post -ContentType "application/json" -Body $body | Out-Null
  } catch {
  }
}

function Start-PainelServer {
  $vbs = Join-Path $root "start-hidden.vbs"
  Start-Process -FilePath "wscript.exe" -ArgumentList "`"$vbs`"" -WorkingDirectory $root
}

while ($true) {
  $ok = $false
  try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:8787/api/health" -TimeoutSec 8
    $ok = [bool]$health.ok
  } catch {
    $ok = $false
  }

  if (!$ok) {
    if (!$wasDown) {
      Send-Telegram "🚨 <b>Painel TV parou de responder</b>`nTentando reiniciar automaticamente.`nHorario: $(Get-Date -Format 'dd/MM/yyyy HH:mm:ss')"
    }
    $wasDown = $true
    Start-PainelServer
  } elseif ($wasDown) {
    Send-Telegram "✅ <b>Painel TV voltou a responder</b>`nHorario: $(Get-Date -Format 'dd/MM/yyyy HH:mm:ss')"
    $wasDown = $false
  }

  Start-Sleep -Seconds 60
}
