# Aplicativo TV Android / Fire TV

Este e o app instalavel na TV. Ele fica isolado desta forma:

```text
APLICATIVO-TV-ANDROID/
```

## O que ele faz

- Tela de pareamento com endereco do servidor e token da TV.
- WebView interna em tela cheia, sem depender do Chrome externo.
- Conexao WebSocket com o backend.
- Heartbeat com URL atual, memoria, uptime, estado do player, audio e versao.
- Reconexao automatica.
- Comandos remotos:
  - `reload_page`
  - `recreate_webview`
  - `restart_app`
  - `clear_cache`
  - `set_audio`
  - `set_url`
  - `apply_config`
  - `exit_fallback`
- Boot automatico apos ligar o dispositivo.
- Tela sempre ligada.
- Modo imersivo e tentativa de `lockTask`.
- Fallback apos falhas repetidas de carregamento.

## Gerar APK debug

Nesta maquina, use:

```bat
build-apk.bat
```

O APK fica em:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Instalar via ADB

Com a TV/TV Box conectada por USB ou ADB de rede:

```bat
install-apk-adb.bat
```

## Como testar

1. No computador, inicie o painel:

```powershell
cd "C:\Users\Adm\Desktop\REMODO MELHOR RDP\PROGRAMA REMOTO PAINEL"
npm.cmd start
```

2. No painel, cadastre uma TV e copie o token.
3. Instale o APK na TV/TV Box/Fire TV.
4. Na tela do app, informe o servidor.

Se a TV estiver na mesma rede, use o IP do computador, por exemplo:

```text
http://192.168.0.100:8787
```

Nao use `localhost` na TV, porque `localhost` seria a propria TV.

5. Informe o token e clique em `Conectar`.

## Observacao sobre kiosk real

O app ja usa tela cheia imersiva e tenta ativar `lockTask`, mas o kiosk completo depende do dispositivo estar provisionado como dedicado/device owner ou permitir fixacao/lock task. Para piloto, isso ja reduz bastante interferencia; para producao, faca homologacao por modelo.
