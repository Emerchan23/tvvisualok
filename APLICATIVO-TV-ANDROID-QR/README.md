# Aplicativo TV Android / Fire TV com QR Code

Este e o app instalavel na TV com pareamento por QR Code. Ele fica isolado desta forma:

```text
APLICATIVO-TV-ANDROID-QR/
```

## O que ele faz

- Tela de pareamento com endereco do servidor.
- Gera QR Code para o gestor aprovar pelo celular.
- Recebe automaticamente o token depois da aprovacao.
- Ainda permite conectar por token manual se necessario.
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

2. Instale o APK QR na TV/TV Box/Fire TV.
3. Na tela do app, informe o servidor.

Se a TV estiver na mesma rede, use o IP do computador, por exemplo:

```text
http://192.168.68.112:8787
```

Nao use `localhost` na TV, porque `localhost` seria a propria TV.

4. Clique em `Gerar QR Code de pareamento`.
5. Aponte a camera do celular para o QR.
6. No celular, informe o token admin do painel e aprove a TV.
7. A TV recebe o token automaticamente e entra em operacao.

## Token admin

O token admin padrao do painel local e:

```text
admin123
```

Depois, em producao, troque esse token com a variavel `ADMIN_TOKEN`.

## Teste rapido de narrador

Para testar a voz sem depender do sistema de chamados, coloque esta URL na TV pelo painel:

```text
http://192.168.68.112:8787/tts-test.html
```

Se estiver tudo certo, a TV fala uma frase de teste automaticamente.

## Observacao sobre kiosk real

O app ja usa tela cheia imersiva e tenta ativar `lockTask`, mas o kiosk completo depende do dispositivo estar provisionado como dedicado/device owner ou permitir fixacao/lock task. Para piloto, isso ja reduz bastante interferencia; para producao, faca homologacao por modelo.
