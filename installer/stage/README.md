# Sistema de Paineis Inteligentes para TVs

MVP inicial para substituir RDP por uma arquitetura propria de digital signage operacional:

- Servidor Node.js sem dependencias externas para REST, WebSocket, persistencia local e monitoramento.
- Painel de operacao web/desktop servido localmente pelo backend.
- Simulador de cliente TV para testar cadastro, heartbeat, troca de URL e comandos.
- Aplicativo Android/Fire TV isolado em `APLICATIVO-TV-ANDROID`, com APK debug gerado.
- Aplicativo Android/Fire TV com pareamento por QR em `APLICATIVO-TV-ANDROID-QR`.

## Como rodar agora

No PowerShell, dentro desta pasta:

```powershell
npm.cmd run check
npm.cmd start
```

Ou use os atalhos:

```text
verificar-projeto.bat
start-painel.bat
```

Abra:

```text
http://localhost:8787
```

O token administrativo padrao para API/WS e:

```text
admin123
```

Esse token e usado pelo painel, pelo app gestor e pela pagina mobile de pareamento para autorizar operacoes sensiveis:

- cadastrar TV;
- editar TV;
- excluir TV;
- aprovar pareamento por QR;
- enviar comandos remotos.

Para producao, troque usando variavel de ambiente antes de iniciar:

```powershell
$env:ADMIN_TOKEN="um-token-forte"; npm.cmd start
```

O campo `Token admin` no painel apenas informa qual token o painel deve usar para falar com o servidor. Ele nao cria usuario ainda. Na V1 de producao, o recomendado e criar login de Administrador/Operador com senha e sessao.

## Testar com uma TV simulada

1. Abra o painel.
2. Clique em `Nova TV`.
3. Copie o token gerado.
4. Em outro terminal:

```powershell
npm.cmd run simulate:tv -- --token TOKEN_DA_TV
```

Depois disso, a TV aparece online, envia heartbeat e recebe comandos de reload, recriacao de WebView, limpeza de cache e troca de audio.

## Instalar em uma TV/TV Box/Fire TV

O APK simples foi gerado aqui:

```text
APLICATIVO-TV-ANDROID\app\build\outputs\apk\debug\app-debug.apk
```

O APK recomendado, com QR Code, foi gerado aqui:

```text
APLICATIVO-TV-ANDROID-QR\app\build\outputs\apk\debug\app-debug.apk
```

Para recompilar:

```bat
APLICATIVO-TV-ANDROID\build-apk.bat
APLICATIVO-TV-ANDROID-QR\build-apk.bat
```

Para instalar via ADB:

```bat
APLICATIVO-TV-ANDROID\install-apk-adb.bat
APLICATIVO-TV-ANDROID-QR\install-apk-adb.bat
```

No app da TV, informe o endereco do computador na rede, nao `localhost`. Exemplo:

```text
http://192.168.68.112:8787
```

Depois informe o token gerado no cadastro da TV pelo painel.

Na versao QR, nao precisa digitar o token grande na TV:

1. Informe o servidor no app.
2. Clique em `Gerar QR Code de pareamento`.
3. Escaneie com o celular.
4. Aprove a TV na pagina mobile.
5. A TV recebe o token automaticamente.

Para testar o narrador nativo do APK QR, aplique esta URL em uma TV:

```text
http://192.168.68.112:8787/tts-test.html
```

## Estrutura

```text
apps/
  server/             Backend, API, WebSocket e painel estatico
  tv-simulator/       Cliente simulado para validar o protocolo
  android-tv-client/  Base Android/Kotlin para o APK real
APLICATIVO-TV-ANDROID/ App Android/Fire TV isolado e compilavel
APLICATIVO-TV-ANDROID-QR/ App Android/Fire TV com pareamento por QR
docs/
  ARCHITECTURE.md     Decisoes tecnicas e fluxos
  API.md              Contratos REST e WebSocket
  ANDROID_CLIENT.md   Guia do cliente TV real
  TEST_PLAN.md        Plano de estabilidade e homologacao
```

## O que ja esta implementado no MVP

- Cadastro de TVs com token unico.
- URL principal e URL fallback por TV.
- Status online/offline com heartbeat.
- Telemetria basica: memoria, uptime, audio, player, reloads, versao.
- Comandos remotos auditados.
- Logs por dispositivo.
- Alertas visuais no painel.
- WebSocket para painel, simulador e app Android.
- Persistencia em `apps/server/data/state.json`.

## Proximos passos recomendados

1. Transformar o painel web em Electron para empacotar como app Windows.
2. Instalar `APLICATIVO-TV-ANDROID\app\build\outputs\apk\debug\app-debug.apk` em uma TV de teste.
3. Homologar pelo menos 1 Android TV e 1 Fire TV Stick por 24h, 48h e 7 dias.
4. Trocar persistencia JSON por SQLite/PostgreSQL quando entrar em producao com mais unidades.
5. Ativar TLS/WSS na rede final ou colocar o backend atras de proxy seguro.
