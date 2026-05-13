# Cliente Android/Fire TV

O projeto em `apps/android-tv-client` e uma base para abrir no Android Studio e evoluir para APK real.

## Requisitos do cliente

- Kotlin nativo.
- WebView interna, sem depender de Chrome externo.
- Tela cheia.
- Wake lock para operacao continua.
- Boot receiver para iniciar apos reboot.
- WebSocket persistente com reconexao.
- Heartbeat a cada 15 segundos por padrao.
- Watchdog de saude do player.
- Kiosk/lock task mode quando o dispositivo permitir.
- Compatibilidade com D-pad em Fire TV.

## Pontos de atencao para Fire TV

- O APK pode ser instalado por sideload durante piloto.
- Homologar por modelo e versao do Fire OS.
- Validar memoria em video continuo por 24h, 48h e 7 dias.
- Evitar depender de recursos especificos do Chrome completo.

## Evolucao tecnica

1. Adicionar cliente WebSocket Android, por exemplo OkHttp.
2. Persistir token, ultima URL valida e configuracoes em DataStore/SharedPreferences.
3. Implementar comandos:
   - `reload_page`: `webView.reload()`
   - `recreate_webview`: destruir e recriar WebView
   - `clear_cache`: limpar cache controladamente
   - `restart_app`: reiniciar Activity/processo
   - `set_audio`: aplicar mute/unmute em camada de audio
4. Implementar lock task mode em dispositivos homologados.
5. Assinar APK e criar procedimento de instalacao assistida.
