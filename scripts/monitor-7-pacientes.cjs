const fs = require("fs");
const path = require("path");

const PANEL_ID =
  process.env.PANEL_ID || "dedba94c-6ec7-4e60-9028-2167bccdf108";
const PANEL_HOST =
  process.env.PANEL_HOST || "https://sigss.chapadaodoceu.go.gov.br";
const MANAGER_URL = process.env.MANAGER_URL || "http://192.168.68.114:9090";

const INTERVAL_MS = Number(process.env.INTERVAL_MS || 2000);
const TARGET_CALLS = Number(process.env.TARGET_CALLS || 7);
const MAX_MINUTES = Number(process.env.MAX_MINUTES || 240);

const HISTORY_URL = `${PANEL_HOST}/unique-panel/api/call/history?panelId=${PANEL_ID}&limit=3&sort=updatedAt:desc&select=id,personal,local,attempts,updatedAt`;

const outDir = path.resolve(__dirname, "..", "diagnostics");
fs.mkdirSync(outDir, { recursive: true });
const stamp = new Date().toISOString().replace(/[:.]/g, "-");
const logFile = path.join(outDir, `monitor-7-pacientes-${stamp}.jsonl`);

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

function callSignature(call) {
  return [
    norm(call.id),
    norm(call.personal),
    norm(call.local),
    norm(call.attempts),
    norm(call.updatedAt),
  ].join("|");
}

async function getJson(url) {
  const response = await fetch(url, { headers: { "cache-control": "no-cache" } });
  const text = await response.text();
  if (!response.ok) throw new Error(`${url} HTTP ${response.status}: ${text.slice(0, 200)}`);
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`${url} JSON invalido: ${text.slice(0, 200)}`);
  }
}

async function readPanelLatest() {
  const json = await getJson(HISTORY_URL);
  const first = json && Array.isArray(json.data) && json.data[0] ? json.data[0] : {};
  return {
    id: first.id || "",
    personal: first.personal || "",
    local: first.local || "",
    attempts: first.attempts ?? "",
    updatedAt: first.updatedAt || "",
    count: json.count ?? null,
  };
}

async function readManagerDevices() {
  try {
    const json = await getJson(`${MANAGER_URL}/api/devices`);
    const devices = Array.isArray(json.devices) ? json.devices : [];
    return { devices };
  } catch (error) {
    return { error: error.message };
  }
}

function write(entry) {
  fs.appendFileSync(logFile, `${JSON.stringify(entry)}\n`, "utf8");
}

async function main() {
  console.log(`LOG=${logFile}`);
  console.log(`PANEL=${HISTORY_URL}`);
  console.log(`MANAGER=${MANAGER_URL}`);

  const startedAt = Date.now();
  const deadline = startedAt + MAX_MINUTES * 60 * 1000;

  let lastSig = "";
  let countCalls = 0;

  while (Date.now() < deadline && countCalls < TARGET_CALLS) {
    const at = new Date().toISOString();
    try {
      const latest = await readPanelLatest();
      const sig = callSignature(latest);
      const isNew = Boolean(sig && sig !== lastSig && latest.id);
      if (isNew) {
        lastSig = sig;
        countCalls += 1;
        console.log(
          `${at} NOVA_CHAMADA ${countCalls}/${TARGET_CALLS} paciente="${latest.personal}" local="${latest.local}" attempts="${latest.attempts}"`
        );
      } else {
        console.log(
          `${at} aguardando... pacienteAtual="${latest.personal}" local="${latest.local}" chamadas=${countCalls}/${TARGET_CALLS}`
        );
      }

      const manager = await readManagerDevices();
      const onlineDevices =
        manager && Array.isArray(manager.devices)
          ? manager.devices.filter((d) => String(d.status || "") === "online")
          : [];

      write({
        at,
        elapsedSeconds: Math.round((Date.now() - startedAt) / 1000),
        callsDone: countCalls,
        targetCalls: TARGET_CALLS,
        panel: latest,
        isNewCall: isNew,
        manager: manager.error
          ? { error: manager.error }
          : {
              devices: (manager.devices || []).map((d) => ({
                id: d.id,
                name: d.name,
                status: d.status,
                audioEnabled: d.audioEnabled,
                currentUrl: d.currentUrl,
                playerStatus: d.playerStatus,
                appVersion: d.appVersion,
                lastError: d.lastError,
                speechRequests: d.speechRequests,
                speechCompletions: d.speechCompletions,
              })),
              onlineCount: onlineDevices.length,
            },
      });
    } catch (error) {
      write({ at, error: error.message });
      console.log(`${at} ERRO ${error.message}`);
    }
    await sleep(INTERVAL_MS);
  }

  const finalAt = new Date().toISOString();
  if (countCalls >= TARGET_CALLS) {
    console.log(`${finalAt} FINAL ok: atingiu ${TARGET_CALLS} chamadas. LOG=${logFile}`);
  } else {
    console.log(
      `${finalAt} FINAL timeout: fez ${countCalls}/${TARGET_CALLS} chamadas em ${MAX_MINUTES} min. LOG=${logFile}`
    );
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

