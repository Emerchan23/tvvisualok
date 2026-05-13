# API e WebSocket

## Autenticacao administrativa

Use `X-Admin-Token` ou query string `?token=...`.

Padrao local:

```text
admin123
```

## REST

### `GET /api/health`

Retorna status do servidor.

### `GET /api/devices`

Lista TVs cadastradas sem expor token de ativacao.

### `POST /api/devices`

Cria TV e retorna token de ativacao.

```json
{
  "name": "TV Caixa 01",
  "location": "Loja Centro",
  "group": "Caixas",
  "currentUrl": "https://exemplo.com/painel",
  "fallbackUrl": "about:blank",
  "audioEnabled": true
}
```

### `PATCH /api/devices/:id`

Atualiza nome, local, grupo, URL, fallback e audio.

### `POST /api/devices/:id/commands`

Envia comando remoto.

```json
{
  "command": "reload_page",
  "payload": {}
}
```

Comandos previstos:

- `reload_page`
- `recreate_webview`
- `restart_app`
- `clear_cache`
- `set_audio`
- `set_url`
- `apply_config`
- `exit_fallback`

### `GET /api/events`

Lista eventos recentes.

### `GET /api/settings`

Retorna configuracoes gerais.

### `PATCH /api/settings`

Atualiza configuracoes gerais.

## WebSocket administrativo

Endpoint:

```text
/ws/admin?token=TOKEN_ADMIN
```

Mensagens recebidas:

- `snapshot`: lista completa de dispositivos, eventos e configuracoes.
- `event`: evento incremental.

## WebSocket do cliente TV

Endpoint:

```text
/ws/device?token=TOKEN_DE_ATIVACAO
```

### Servidor -> cliente

```json
{
  "type": "registered",
  "config": {
    "deviceId": "tv_...",
    "name": "TV Caixa 01",
    "currentUrl": "https://exemplo.com/painel",
    "fallbackUrl": "about:blank",
    "audioEnabled": true,
    "heartbeatIntervalSeconds": 15
  }
}
```

```json
{
  "type": "command",
  "command": {
    "id": "cmd_...",
    "command": "reload_page",
    "payload": {},
    "createdAt": "2026-04-21T12:00:00.000Z"
  }
}
```

### Cliente -> servidor

```json
{
  "type": "heartbeat",
  "telemetry": {
    "status": "online",
    "currentUrl": "https://exemplo.com/painel",
    "playerStatus": "playing",
    "audioEnabled": true,
    "memoryMb": 180,
    "reloads": 2,
    "reconnects": 0,
    "uptimeSeconds": 3600,
    "lastError": "",
    "appVersion": "1.0.0"
  }
}
```

```json
{
  "type": "command_ack",
  "commandId": "cmd_...",
  "status": "ok",
  "handledAt": "2026-04-21T12:00:02.000Z"
}
```
