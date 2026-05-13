# APK Chrome Managed (Isolado)

Pasta do experimento:

- EXPERIMENTOS/APK-CHROME-MANAGED

Objetivo:

- Manter controle via gerenciador desktop (pareamento, heartbeat, comandos)
- Abrir conteudo no navegador Chrome (ou navegador padrao como fallback)
- Nao mexer no projeto principal em producao

Comportamento:

1. O app continua conectado ao servidor e recebendo comandos (set_url, apply_config, etc.).
2. Quando recebe URL valida, abre no Chrome (`com.android.chrome`).
3. Se Chrome nao existir, abre no navegador padrao.
4. O app nao fica forçando refresh/recreate de WebView no modo Chrome Managed.

APK debug gerado:

- APKS/Painel-TV-Chrome-Managed-debug.apk
