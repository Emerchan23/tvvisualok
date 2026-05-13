const fs = require("fs");
const path = require("path");

const PANEL_ID = "dedba94c-6ec7-4e60-9028-2167bccdf108";
const PANEL_URL = `https://sigss.chapadaodoceu.go.gov.br/unique-panel/panel-screen/${PANEL_ID}`;
const HISTORY_URL = `https://sigss.chapadaodoceu.go.gov.br/unique-panel/api/call/history?panelId=${PANEL_ID}&limit=5&sort=updatedAt:desc&select=id,personal,local,attempts,updatedAt`;
const CHROME_PORT = Number(process.env.CHROME_DEBUG_PORT || 9222);
const WEBVIEW_PORT = Number(process.env.WEBVIEW_DEBUG_PORT || 9223);
const DURATION_MS = Number(process.env.MONITOR_MINUTES || 120) * 60 * 1000;
const INTERVAL_MS = Number(process.env.MONITOR_INTERVAL_MS || 2000);
const outDir = path.resolve(__dirname, "..", "diagnostics");
fs.mkdirSync(outDir, { recursive: true });
const stamp = new Date().toISOString().replace(/[:.]/g, "-");
const logFile = path.join(outDir, `real-panel-monitor-${stamp}.jsonl`);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function norm(value) {
  return String(value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/\s+/g, " ")
    .trim();
}

function mainPatient(text) {
  const lines = String(text || "")
    .split(/\n+/)
    .map((line) => line.trim())
    .filter(Boolean);
  for (let i = 0; i < lines.length - 1; i += 1) {
    if (norm(lines[i]) === "paciente") return lines[i + 1] || "";
  }
  return "";
}

async function getJson(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${url} HTTP ${response.status}`);
  return response.json();
}

async function cdpTargets(port) {
  return getJson(`http://127.0.0.1:${port}/json/list`);
}

function connect(wsUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    const pending = new Map();
    let seq = 0;
    const timer = setTimeout(() => reject(new Error("Timeout abrindo CDP")), 5000);
    ws.onopen = () => {
      clearTimeout(timer);
      resolve({
        send(method, params = {}) {
          const id = ++seq;
          ws.send(JSON.stringify({ id, method, params }));
          return new Promise((res, rej) => {
            pending.set(id, { res, rej });
            setTimeout(() => {
              if (pending.has(id)) {
                pending.delete(id);
                rej(new Error(`Timeout CDP ${method}`));
              }
            }, 5000);
          });
        },
        close() {
          try {
            ws.close();
          } catch {}
        },
      });
    };
    ws.onerror = () => reject(new Error("Erro WebSocket CDP"));
    ws.onmessage = (event) => {
      const msg = JSON.parse(event.data);
      if (msg.id && pending.has(msg.id)) {
        const item = pending.get(msg.id);
        pending.delete(msg.id);
        if (msg.error) item.rej(new Error(JSON.stringify(msg.error)));
        else item.res(msg.result);
      }
    };
  });
}

async function readPage(port, label) {
  try {
    const targets = await cdpTargets(port);
    const target = targets.find((item) => (item.url || "").includes(PANEL_ID)) || targets[0];
    if (!target || !target.webSocketDebuggerUrl) return { label, error: "sem alvo CDP" };
    const cdp = await connect(target.webSocketDebuggerUrl);
    const expr = `JSON.stringify({
      url: location.href,
      title: document.title,
      text: String(document.body && document.body.innerText || '').slice(0, 1600),
      observer: !!window.__painelTvNextObserverInstalled,
      nativeTtsVersion: window.__painelTvNativeTts && window.__painelTvNativeTts.version || null,
      lastNext: window.__painelTvLastNextCall || null,
      lastDom: window.__painelTvLastDomSync || null,
      lastSpokenKey: window.__painelTvLastSpokenKey || null,
      lastSpokenAt: window.__painelTvLastSpokenAt || null,
      pendingSpeechKey: window.__painelTvPendingSpeechKey || null,
      pendingSpeechAt: window.__painelTvPendingSpeechAt || null
    })`;
    const result = await cdp.send("Runtime.evaluate", { expression: expr, returnByValue: true });
    cdp.close();
    const value = JSON.parse(result.result.value);
    value.label = label;
    value.mainPatient = mainPatient(value.text);
    return value;
  } catch (error) {
    return { label, error: error.message };
  }
}

async function ensureChromeTab() {
  try {
    const targets = await cdpTargets(CHROME_PORT);
    if (targets.some((item) => (item.url || "").includes(PANEL_ID))) return;
    await fetch(`http://127.0.0.1:${CHROME_PORT}/json/new?${encodeURIComponent(PANEL_URL)}`, { method: "PUT" });
  } catch {}
}

(async () => {
  await ensureChromeTab();
  const startedAt = Date.now();
  let lastHistoryId = "";
  while (Date.now() - startedAt < DURATION_MS) {
    const at = new Date().toISOString();
    let history = null;
    try {
      history = await getJson(HISTORY_URL);
    } catch (error) {
      history = { error: error.message };
    }
    const latest = history && history.data && history.data[0] ? history.data[0] : null;
    const chrome = await readPage(CHROME_PORT, "chrome");
    const webview = await readPage(WEBVIEW_PORT, "webview-apk");
    const event = latest && latest.id !== lastHistoryId ? "new-history-call" : "tick";
    if (latest) lastHistoryId = latest.id;
    fs.appendFileSync(
      logFile,
      JSON.stringify({
        at,
        elapsedSeconds: Math.round((Date.now() - startedAt) / 1000),
        event,
        latest,
        chrome: {
          error: chrome.error,
          mainPatient: chrome.mainPatient,
          observer: chrome.observer,
          nativeTtsVersion: chrome.nativeTtsVersion,
          lastNext: chrome.lastNext,
          lastDom: chrome.lastDom,
          textSample: chrome.text ? chrome.text.slice(0, 500) : undefined,
        },
        webview: {
          error: webview.error,
          mainPatient: webview.mainPatient,
          observer: webview.observer,
          nativeTtsVersion: webview.nativeTtsVersion,
          lastNext: webview.lastNext,
          lastDom: webview.lastDom,
          lastSpokenKey: webview.lastSpokenKey,
          lastSpokenAt: webview.lastSpokenAt,
          pendingSpeechKey: webview.pendingSpeechKey,
          pendingSpeechAt: webview.pendingSpeechAt,
          textSample: webview.text ? webview.text.slice(0, 500) : undefined,
        },
      }) + "\n",
      "utf8",
    );
    await sleep(INTERVAL_MS);
  }
  console.log(logFile);
})().catch((error) => {
  fs.appendFileSync(logFile, JSON.stringify({ at: new Date().toISOString(), fatal: error.message }) + "\n");
  process.exit(1);
});
