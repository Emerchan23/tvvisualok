# Aplicativo Painel TV Fire Stick

Este projeto e uma variante isolada do app da TV, otimizada para Amazon Fire TV Stick / Fire OS.

Pasta:

```text
APLICATIVO-FIRE-TV-STICK/
```

## Diferencas desta versao

- Pacote separado: `br.com.paineltv.firetv`.
- Nome exibido: `Painel TV Fire`.
- Launcher com categoria `LEANBACK_LAUNCHER`, para aparecer melhor em interface de TV.
- Nao exige touchscreen.
- Mantem tela acordada com `FLAG_KEEP_SCREEN_ON` e wake lock explicito.
- Botao Voltar do controle remoto nao fecha o app acidentalmente.
- Continua com WebView interna, sem depender do Chrome externo.
- Continua com QR Code de pareamento, narrador nativo, heartbeat, watchdog, reload preventivo, recriacao de WebView, fallback e reconexao automatica.

## Gerar APK release

Use:

```bat
build-release-apk.bat
```

APK gerado:

```text
app\build\outputs\apk\release\app-release.apk
```

## Instalar no Fire TV Stick

Opcoes comuns:

1. Instalar por ADB na rede:

```bat
adb connect IP_DO_FIRE_STICK:5555
adb install -r app\build\outputs\apk\release\app-release.apk
```

2. Copiar o APK para o Fire Stick e instalar com app de sideload.

No Fire TV, habilite as opcoes de desenvolvedor quando necessario:

- ADB Debugging.
- Apps from Unknown Sources / Install unknown apps.

## Uso

1. Abra o `Painel TV Fire`.
2. Informe o IP e porta do servidor, por exemplo:

```text
http://192.168.68.112:8787
```

3. Gere o QR Code.
4. Aprove pelo painel ou pelo app gestor.
5. Defina URL, fallback, audio e horario de monitoramento.

## Observacoes para 24 horas

Mesmo com tela sempre ligada no app, alguns modelos de Fire Stick podem ter economia de energia agressiva. Para producao, homologue por modelo:

- testar 24h;
- testar 48h;
- testar 7 dias;
- validar audio do narrador;
- validar memoria e recuperacoes no painel.

No painel, use `Saude 24/7` para ajustar reload preventivo, limite de memoria e fallback.
