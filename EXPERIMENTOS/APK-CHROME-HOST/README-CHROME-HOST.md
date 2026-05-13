# APK Chrome Host (Experimento Isolado)

Este experimento fica isolado em:

- EXPERIMENTOS/APK-CHROME-HOST

Objetivo:

- Abrir o painel no aplicativo Google Chrome (quando existir no dispositivo).
- Nao altera nenhum projeto principal ja em producao.

Comportamento do app:

1. Tenta abrir URL no pacote `com.android.chrome`.
2. Se Chrome nao existir, tenta navegador padrao do Android.
3. Se nao houver navegador externo, usa WebView interno como ultimo fallback.

URL usada:

- Primeiro tenta `Intent extra: panelUrl`
- Depois tenta `Intent dataString` (deep link)
- Se nada vier, usa URL salva em SharedPreferences (`chrome_host_prefs.panelUrl`)
- Fallback padrao: `https://sigss.chapadaodoceu.go.gov.br/unique-panel/`

Build:

```bat
cd /d "EXPERIMENTOS\APK-CHROME-HOST"
gradlew.bat assembleDebug
```

Saida esperada:

- app/build/outputs/apk/debug/app-debug.apk

Observacao importante:

- Em Fire TV, o Chrome normalmente nao vem instalado. Nesse caso ele vai abrir navegador disponivel (ex.: Silk) ou cair no fallback WebView.
