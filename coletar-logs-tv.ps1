# ============================================================
#  COLETOR DE LOGS - PAINEL TV
#  Conecta na TV via WiFi e captura logs por 15 minutos
# ============================================================

$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$MINUTOS = 15
$SAIDA = "$PSScriptRoot\logs-painel-tv-$(Get-Date -Format 'yyyyMMdd-HHmmss').txt"

Write-Host ""
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "  COLETOR DE LOGS - PAINEL TV" -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host ""

# Pede IP da TV
$IP = Read-Host "Digite o IP da TV (ex: 192.168.68.50)"
$IP = $IP.Trim()

if (-not $IP) {
    Write-Host "IP nao informado. Saindo." -ForegroundColor Red
    exit 1
}

# Conecta ADB
Write-Host ""
Write-Host "Conectando ADB na TV $IP`:5555 ..." -ForegroundColor Yellow
& $ADB connect "${IP}:5555" 2>&1

Start-Sleep -Seconds 2

# Verifica dispositivo
$devices = & $ADB devices 2>&1
Write-Host ""
Write-Host "Dispositivos conectados:" -ForegroundColor Yellow
Write-Host $devices

if ($devices -notmatch $IP) {
    Write-Host ""
    Write-Host "ATENCAO: TV nao apareceu na lista." -ForegroundColor Red
    Write-Host "Certifique-se de que:" -ForegroundColor Yellow
    Write-Host "  1. Na TV: Configuracoes > Sistema > Opcoes do Desenvolvedor > Depuracao ADB = ATIVADO" -ForegroundColor White
    Write-Host "     (Fire TV: Configuracoes > Meu Fire TV > Opcoes do Desenvolvedor > Depuracao ADB)" -ForegroundColor White
    Write-Host "  2. TV e PC estao na mesma rede WiFi" -ForegroundColor White
    Write-Host "  3. Ao conectar, aceite a autorizacao que aparece na TV" -ForegroundColor White
    Write-Host ""
    $continue = Read-Host "Tentar mesmo assim? (s/n)"
    if ($continue -ne "s") { exit 1 }
}

# Limpa log anterior
Write-Host ""
Write-Host "Limpando buffer de logs antigos..." -ForegroundColor Yellow
& $ADB -s "${IP}:5555" logcat -c 2>&1 | Out-Null

Write-Host ""
Write-Host "======================================================" -ForegroundColor Green
Write-Host "  CAPTURANDO LOGS POR $MINUTOS MINUTOS" -ForegroundColor Green
Write-Host "  Arquivo: $SAIDA" -ForegroundColor Green
Write-Host "  DEIXE O PAINEL RODANDO NA TV AGORA" -ForegroundColor Green
Write-Host "  Chame pacientes normalmente durante esse tempo" -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Green
Write-Host ""

$inicio = Get-Date
$fim = $inicio.AddMinutes($MINUTOS)

# Cabecalho do arquivo
@"
LOGS PAINEL TV - $(Get-Date -Format 'dd/MM/yyyy HH:mm:ss')
TV: $IP
Duracao: $MINUTOS minutos
======================================================
"@ | Set-Content $SAIDA -Encoding UTF8

# Captura logcat em processo separado, filtrando pela tag PAINEL_TV
# Tambem captura erros do sistema Android que possam afetar TTS
$proc = Start-Process -FilePath $ADB -ArgumentList "-s `"${IP}:5555`" logcat -v time PAINEL_TV:D AndroidRuntime:E TTS:D TextToSpeech:D *:S" -RedirectStandardOutput $SAIDA -NoNewWindow -PassThru

Write-Host "Capturando... (pressione Ctrl+C para parar antes do tempo)" -ForegroundColor Cyan
Write-Host ""

# Mostra progresso a cada 30 segundos
while ((Get-Date) -lt $fim) {
    $restante = [int]($fim - (Get-Date)).TotalSeconds
    Write-Host "$(Get-Date -Format 'HH:mm:ss') - Faltam $restante segundos... (arquivo: $SAIDA)" -ForegroundColor Gray
    Start-Sleep -Seconds 30
}

# Para a captura
if (-not $proc.HasExited) {
    $proc.Kill()
}

Write-Host ""
Write-Host "======================================================" -ForegroundColor Green
Write-Host "  CAPTURA CONCLUIDA!" -ForegroundColor Green
Write-Host "  Arquivo salvo: $SAIDA" -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Green
Write-Host ""
Write-Host "Linhas capturadas: $((Get-Content $SAIDA | Measure-Object -Line).Lines)" -ForegroundColor Cyan
Write-Host ""
Write-Host "Envie o arquivo '$SAIDA' para analise." -ForegroundColor Yellow
Write-Host ""

# Abre o arquivo no bloco de notas
$abrir = Read-Host "Abrir arquivo agora para visualizar? (s/n)"
if ($abrir -eq "s") {
    notepad $SAIDA
}
