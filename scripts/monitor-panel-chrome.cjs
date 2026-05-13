const fs = require("fs");
const path = require("path");

const PANEL_ID = "dedba94c-6ec7-4e60-9028-2167bccdf108";
const PANEL_URL = `https://sigss.chapadaodoceu.go.gov.br/unique-panel/panel-screen/${PANEL_ID}`;
const API_URL = `https://sigss.chapadaodoceu.go.gov.br/unique-panel/api/call/history?panelId=${PANEL_ID}&limit=3&sort=updatedAt:desc&select=id,personal,local`;
const DEBUG_PORT = process.env.CHROME_DEBUG_PORT || "9222";
const DURATION_MS = Number(process.env.MONITOR_MINUTES || "40") * 60 * 1000;
const INTERVAL_MS = 2000;
const outDir = path.resolve(__dirname, "..", "diagnostics");
fs.mkdirSync(outDir, { recursive: true });
const stamp = new Date().toISOString().replace(/[:.]/g, "-");
const logFile = path.join(outDir, `panel-chrome-monitor-${stamp}.jsonl`);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function normalize(value) {
  return String(value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/\s+/g, " ")
    .trim();
}

async function cdpJson(pathname, init) {
  const response = await fetch(`http://127.0.0.1:${DEBUG_PORT}${pathname}`, init);
  if (!response.ok) throw new Error(`CDP ${pathname} HTTP ${response.status}`);
  return response.json();
}

async function openChromeTab() {
  let targets = await cdpJson("/json");
  let target = targets.find((item) => item.url && item.url.includes(PANEL_ID));
  if (!target) {
    const response = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/new?${encodeURIComponent(PANEL_URL)}`, {
      method: "PUT",
    });
    if (!response.ok) throw new Error(`Nao consegui abrir aba Chrome: HTTP ${response.status}`);
    target = await response.json();
  }
  return target;
}

function connect(wsUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    const pending = new Map();
    let seq = 1;
    ws.onopen = () => {
      resolve({
        send(method, params = {}) {
          const id = seq++;
          ws.send(JSON.stringify({ id, method, params }));
          return new Promise((res, rej) => {
            pending.set(id, { res, rej });
            setTimeout(() => {
              if (pending.has(id)) {
                pending.delete(id);
                rej(new Error(`Timeout CDP ${method}`));
              }
            }, 8000);
          });
        },
        close() {
          ws.close();
        },
      });
    };
    ws.onerror = () => reject(new Error("Erro no WebSocket CDP"));
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

async function readApi() {
  const response = await fetch(API_URL, { headers: { "cache-control": "no-cache" } });
  const text = await response.text();
  if (!response.ok) throw new Error(`API HTTP ${response.status}: ${text.slice(0, 200)}`);
  const json = JSON.parse(text);
  const first = json.data && json.data[0] ? json.data[0] : {};
  return {
    id: first.id || "",
    personal: first.personal || "",
    local: first.local || "",
    count: json.count,
  };
}

async function readChromeText(cdp) {
  const expression = `(() => {
    const body = document.body;
    const text = body ? body.innerText : "";
    return {
      href: location.href,
      title: document.title,
      text: text.slice(0, 4000),
      visible: document.visibilityState,
      focused: document.hasFocus(),
      now: Date.now()
    };
  })()`;
  const result = await cdp.send("Runtime.evaluate", {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  return result.result.value;
}

function write(entry) {
  fs.appendFileSync(logFile, `${JSON.stringify(entry)}\n`, "utf8");
}

async function main() {
  console.log(`LOG=${logFile}`);
  const target = await openChromeTab();
  const cdp = await connect(target.webSocketDebuggerUrl);
  await cdp.send("Runtime.enable");
  await cdp.send("Page.enable");
  await cdp.send("Page.bringToFront");
  await cdp.send("Runtime.evaluate", {
    expression: `
      Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
      Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
      window.addEventListener('error', e => console.log('[MONITOR_ERROR]', e.message));
      window.addEventListener('unhandledrejection', e => console.log('[MONITOR_REJECTION]', String(e.reason)));
      true;
    `,
    returnByValue: true,
  });

  const started = Date.now();
  let lastId = "";
  while (Date.now() - started < DURATION_MS) {
    const at = new Date().toISOString();
    try {
      const api = await readApi();
      const chrome = await readChromeText(cdp);
      const apiName = normalize(api.personal);
      const chromeText = normalize(chrome.text);
      const chromeHasApiName = apiName ? chromeText.includes(apiName) : false;
      const isNewCall = Boolean(api.id && api.id !== lastId);
      if (isNewCall) lastId = api.id;
      const entry = {
        at,
        elapsedSeconds: Math.round((Date.now() - started) / 1000),
        isNewCall,
        api,
        chrome: {
          visible: chrome.visible,
          focused: chrome.focused,
          hasApiName: chromeHasApiName,
          textSample: chrome.text.slice(0, 600),
        },
      };
      write(entry);
      if (isNewCall || !chromeHasApiName) {
        console.log(`${at} new=${isNewCall} api="${api.personal}" chromeHasName=${chromeHasApiName}`);
      }
    } catch (error) {
      const entry = { at, error: error.message };
      write(entry);
      console.log(`${at} ERROR ${error.message}`);
    }
    await sleep(INTERVAL_MS);
  }
  cdp.close();
  console.log(`FINAL_LOG=${logFile}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
