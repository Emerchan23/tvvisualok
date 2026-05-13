const args = new Map(process.argv.slice(2).map((arg, index, all) => {
  if (!arg.startsWith("--")) return [arg, true];
  return [arg.replace(/^--/, ""), all[index + 1]];
}));

const token = args.get("token") || process.env.TV_TOKEN;
const server = args.get("server") || process.env.SERVER_URL || "ws://localhost:8787";

if (!token) {
  console.error("Use: npm.cmd run simulate:tv -- --token TOKEN_DA_TV");
  process.exit(1);
}

let config = null;
let reloads = 0;
let reconnects = 0;
let startedAt = Date.now();
let currentUrl = "about:blank";
let audioEnabled = true;

function connect() {
  const ws = new WebSocket(`${server}/ws/device?token=${encodeURIComponent(token)}`);

  ws.addEventListener("open", () => {
    console.log("TV simulada conectada");
  });

  ws.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.type === "registered" || message.type === "config") {
      config = message.config;
      currentUrl = config.currentUrl || currentUrl;
      audioEnabled = config.audioEnabled;
      console.log("Configuracao recebida:", config);
    }

    if (message.type === "command") {
      handleCommand(ws, message.command);
    }

    if (message.type === "error") {
      console.error("Erro do servidor:", message.error);
    }
  });

  ws.addEventListener("close", () => {
    reconnects += 1;
    console.log("Conexao perdida. Tentando novamente...");
    setTimeout(connect, 3000);
  });

  const sendHeartbeat = () => {
    if (ws.readyState !== WebSocket.OPEN) {
      return;
    }
    const memoryMb = Math.round(90 + Math.random() * 90);
    ws.send(JSON.stringify({
      type: "heartbeat",
      telemetry: {
        status: "online",
        currentUrl,
        playerStatus: "playing",
        audioEnabled,
        memoryMb,
        reloads,
        reconnects,
        uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
        lastError: "",
        appVersion: "sim-0.1.0"
      }
    }));
  };

  const heartbeat = setInterval(() => {
    if (ws.readyState !== WebSocket.OPEN) {
      clearInterval(heartbeat);
      return;
    }
    sendHeartbeat();
  }, (config?.heartbeatIntervalSeconds || 15) * 1000);

  setTimeout(sendHeartbeat, 500);
}

function handleCommand(ws, command) {
  console.log("Comando recebido:", command.command);
  if (command.command === "reload_page") reloads += 1;
  if (command.command === "recreate_webview") reloads += 1;
  if (command.command === "restart_app") startedAt = Date.now();
  if (command.command === "set_audio") audioEnabled = command.payload.audioEnabled;
  if (command.command === "set_url") currentUrl = command.payload.currentUrl || currentUrl;
  ws.send(JSON.stringify({
    type: "command_ack",
    commandId: command.id,
    status: "ok",
    handledAt: new Date().toISOString()
  }));
}

connect();
