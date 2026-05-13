import http from "node:http";
import https from "node:https";
import { spawn } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { mkdir, readFile, writeFile, unlink } from "node:fs/promises";
import { existsSync, createReadStream, readFileSync, writeFileSync, mkdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, "..");
const publicDir = path.join(rootDir, "public");
const dataDir = process.env.DATA_DIR ? path.resolve(process.env.DATA_DIR) : path.join(rootDir, "data");
const stateFile = path.join(dataDir, "state.json");
const runtimeConfigFile = path.join(dataDir, "runtime-config.json");
const ttsCacheDir = path.join(dataDir, "tts-cache");
const mediaDir = path.join(dataDir, "media");
const MEDIA_UPLOAD_LIMIT_BYTES = 350 * 1024 * 1024;

const BLOCKED_VOICE_PATTERNS = [
  /rhvoice/i,
  /premium/i,
  /trial/i,
  /plus/i,
  /henrique/i,
  /let[ií]cia/i
];

function normalizeText(value) {
  return String(value || "")
    .replace(/\s+/g, " ")
    .trim();
}

function isVoiceBlocked(voiceName) {
  const name = String(voiceName || "");
  return BLOCKED_VOICE_PATTERNS.some((pattern) => pattern.test(name));
}

function base64UrlEncodeUtf8(value) {
  return Buffer.from(String(value || ""), "utf8").toString("base64url");
}

function base64UrlDecodeUtf8(value) {
  try {
    return Buffer.from(String(value || ""), "base64url").toString("utf8");
  } catch {
    return "";
  }
}

function parseVoiceValue(value) {
  const raw = String(value || "").trim();
  if (!raw) return { type: "default", raw: "" };
  if (raw.startsWith("natural:")) {
    const encodedId = raw.slice("natural:".length);
    const id = base64UrlDecodeUtf8(encodedId);
    return { type: id ? "natural" : "invalid", raw, id };
  }
  return { type: "sapi", raw, name: raw };
}

function loadRuntimeConfig() {
  try {
    if (!existsSync(runtimeConfigFile)) return {};
    return JSON.parse(readFileSync(runtimeConfigFile, "utf8"));
  } catch {
    return {};
  }
}

function saveRuntimeConfig(config) {
  mkdirSync(dataDir, { recursive: true });
  writeFileSync(runtimeConfigFile, JSON.stringify(config, null, 2), "utf8");
}

const runtimeConfig = loadRuntimeConfig();
const PORT = Number(process.env.PORT || runtimeConfig.port || 8787);
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || "admin123";
const HEARTBEAT_TIMEOUT_MS = Number(process.env.HEARTBEAT_TIMEOUT_MS || 60000);

const sockets = new Map();
const naturalTtsFailures = new Map();
const NATURAL_TTS_COOLDOWN_MS = 5 * 60 * 1000;

function isNaturalTtsCoolingDown(voiceId) {
  const until = naturalTtsFailures.get(String(voiceId || ""));
  if (!until) return false;
  if (Date.now() < until) return true;
  naturalTtsFailures.delete(String(voiceId || ""));
  return false;
}

function markNaturalTtsFailure(voiceId) {
  naturalTtsFailures.set(String(voiceId || ""), Date.now() + NATURAL_TTS_COOLDOWN_MS);
}
const adminSockets = new Set();
const deviceSockets = new Map();
const wsBuffers = new Map();

let state = {
  devices: [],
  pairingSessions: [],
  events: [],
  settings: {
    heartbeatIntervalSeconds: 15,
    autoRefreshSeconds: 0,
    preventiveReloadHours: 12,
    memoryLimitMb: 650,
    pageOfflineTimeoutSeconds: 60,
    fallbackUrl: "about:blank",
    ttsVoice: "",
    telegram: {
      enabled: false,
      botToken: "",
      chatId: ""
    }
  }
};

const DIRECT_VIDEO_EXTENSIONS = [".mp4", ".webm", ".ogg", ".mov", ".m4v"];

function now() {
  return new Date().toISOString();
}

function id(prefix) {
  return `${prefix}_${randomBytes(8).toString("hex")}`;
}

function token() {
  return randomBytes(18).toString("base64url").toUpperCase();
}

function shortCode() {
  return randomBytes(3).toString("hex").toUpperCase();
}

function safeJson(value) {
  return JSON.stringify(value, null, 2);
}

async function loadState() {
  await mkdir(dataDir, { recursive: true });
  if (!existsSync(stateFile)) {
    await saveState();
    return;
  }
  const parsed = JSON.parse(await readFile(stateFile, "utf8"));
  state = {
    ...state,
    ...parsed,
    settings: { ...state.settings, ...(parsed.settings || {}) }
  };
}

async function saveState() {
  await mkdir(dataDir, { recursive: true });
  await writeFile(stateFile, safeJson(state), "utf8");
}

let saveStateTimer = null;
function scheduleSaveState() {
  if (saveStateTimer) return;
  saveStateTimer = setTimeout(() => {
    saveStateTimer = null;
    saveState().then(() => broadcastAdmins(snapshot())).catch(() => {});
  }, 800);
}

function heartbeatTimeoutMs() {
  const configured = Number(state.settings.pageOfflineTimeoutSeconds || 0);
  if (Number.isFinite(configured) && configured > 0) return Math.round(configured * 1000);
  return HEARTBEAT_TIMEOUT_MS;
}

function publicDevice(device) {
  const online = Date.now() - new Date(device.lastHeartbeat || 0).getTime() <= heartbeatTimeoutMs();
  const withinOperatingHours = isWithinOperatingHours(device);
  const deviceVoice = isVoiceBlocked(device.ttsVoice) ? "" : (device.ttsVoice || "");
  const serverVoice = isVoiceBlocked(state.settings.ttsVoice) ? "" : (state.settings.ttsVoice || "");
  return {
    ...device,
    operatingMode: device.operatingMode || "always",
    operatingStart: device.operatingStart || "07:00",
    operatingEnd: device.operatingEnd || "18:00",
    operatingDays: device.operatingDays || "1,2,3,4,5",
    withinOperatingHours,
    operatingLabel: operatingLabel(device),
    themeId: device.themeId || "sus_verde",
    displayMode: device.displayMode || "panel_only",
    mediaUrl: device.mediaUrl || "",
    mediaLabel: device.mediaLabel || "",
    ttsVoice: deviceVoice || serverVoice,
    online,
    activationToken: undefined
  };
}

function minutesFromTime(value, fallback) {
  const clean = typeof value === "string" ? value : fallback;
  const match = /^(\d{1,2}):(\d{2})$/.exec(clean || "");
  if (!match) return minutesFromTime(fallback, "07:00");
  return Math.min(23, Number(match[1])) * 60 + Math.min(59, Number(match[2]));
}

function operatingDays(device) {
  return String(device.operatingDays || "1,2,3,4,5")
    .split(",")
    .map((day) => Number(day.trim()))
    .filter((day) => day >= 0 && day <= 6);
}

function isWithinOperatingHours(device, date = new Date()) {
  if ((device.operatingMode || "always") === "always") return true;
  const days = operatingDays(device);
  if (!days.includes(date.getDay())) return false;
  const start = minutesFromTime(device.operatingStart || "07:00", "07:00");
  const end = minutesFromTime(device.operatingEnd || "18:00", "18:00");
  const current = date.getHours() * 60 + date.getMinutes();
  if (start === end) return true;
  if (start < end) return current >= start && current < end;
  return current >= start || current < end;
}

function operatingLabel(device) {
  if ((device.operatingMode || "always") === "always") return "24 horas";
  const days = operatingDays(device);
  const names = ["Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sab"];
  const dayLabel = days.length === 7 ? "Todos os dias" : days.map((day) => names[day]).join(", ");
  return `${dayLabel} ${device.operatingStart || "07:00"}-${device.operatingEnd || "18:00"}`;
}

function addEvent(deviceId, type, message, meta = {}) {
  const event = {
    id: id("evt"),
    deviceId,
    type,
    message,
    meta,
    createdAt: now()
  };
  state.events.unshift(event);
  state.events = state.events.slice(0, 1000);
  broadcastAdmins({ type: "event", event });
  return event;
}

function telegramConfig() {
  return state.settings.telegram || {};
}

function publicSettings() {
  const telegram = telegramConfig();
  return {
    ...state.settings,
    telegram: {
      enabled: Boolean(telegram.enabled),
      botTokenConfigured: Boolean(telegram.botToken),
      chatId: telegram.chatId || ""
    }
  };
}

function publicServerConfig() {
  return {
    port: PORT,
    configuredPort: Number(runtimeConfig.port || PORT),
    envPortLocked: Boolean(process.env.PORT),
    restartSupported: true
  };
}

function backupPayload() {
  return {
    kind: "painel-tv-backup",
    version: 1,
    exportedAt: now(),
    state: {
      devices: state.devices,
      settings: state.settings,
      events: state.events.slice(0, 500)
    },
    runtimeConfig: loadRuntimeConfig()
  };
}

function normalizeRestoredDevice(device) {
  return {
    ...createDevice({
      name: device.name,
      location: device.location,
      group: device.group,
      currentUrl: device.currentUrl,
      fallbackUrl: device.fallbackUrl,
      audioEnabled: device.audioEnabled,
      operatingMode: device.operatingMode,
      operatingStart: device.operatingStart,
      operatingEnd: device.operatingEnd,
      operatingDays: device.operatingDays,
      themeId: device.themeId,
      displayMode: device.displayMode,
      mediaUrl: device.mediaUrl,
      mediaLabel: device.mediaLabel,
      ttsVoice: device.ttsVoice,
      ipAddress: device.ipAddress || device.lastIpAddress || "",
      deviceInfo: device.deviceInfo || {},
      appVersion: device.appVersion || "-"
    }, device.activationToken || token()),
    id: device.id || id("tv"),
    status: "offline",
    lastHeartbeat: null,
    notifiedOffline: false,
    createdAt: device.createdAt || now(),
    updatedAt: now()
  };
}

function restoreBackupPayload(payload) {
  if (!payload || payload.kind !== "painel-tv-backup" || !payload.state) {
    throw new Error("Arquivo de backup invalido.");
  }
  const restoredState = payload.state;
  state.devices = Array.isArray(restoredState.devices)
    ? restoredState.devices.map(normalizeRestoredDevice)
    : [];
  state.pairingSessions = [];
  state.events = Array.isArray(restoredState.events)
    ? restoredState.events.slice(0, 500)
    : [];
  state.settings = {
    ...state.settings,
    ...(restoredState.settings || {}),
    telegram: {
      ...(state.settings.telegram || {}),
      ...((restoredState.settings || {}).telegram || {})
    }
  };
  if (payload.runtimeConfig && Number.isInteger(Number(payload.runtimeConfig.port))) {
    saveRuntimeConfig({ ...loadRuntimeConfig(), port: Number(payload.runtimeConfig.port) });
  }
  addEvent(null, "settings.backup_restore", "Backup restaurado com sucesso", {
    devices: state.devices.length
  });
}

function snapshot() {
  return {
    type: "snapshot",
    devices: state.devices.map(publicDevice),
    pairingSessions: publicPairingSessions(),
    events: state.events.slice(0, 200),
    settings: publicSettings(),
    serverConfig: publicServerConfig()
  };
}

function publicPairingSessions() {
  const cutoff = Date.now();
  return (state.pairingSessions || [])
    .filter((session) => session.status === "pending" && new Date(session.expiresAt).getTime() > cutoff)
    .map((session) => ({
      id: session.id,
      code: session.code,
      suggestedName: session.suggestedName,
      appVersion: session.appVersion,
      ipAddress: session.ipAddress,
      clientId: session.clientId,
      deviceInfo: session.deviceInfo || {},
      createdAt: session.createdAt,
      expiresAt: session.expiresAt,
      status: session.status
    }));
}

function broadcastAdmins(message) {
  for (const socket of adminSockets) sendWs(socket, message);
}

function sendToDevice(deviceId, message) {
  const socket = deviceSockets.get(deviceId);
  if (!socket) return false;
  sendWs(socket, message);
  return true;
}

function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(payload)
  });
  res.end(payload);
}

function sendTelegramMessage(text) {
  const config = telegramConfig();
  if (!config.enabled || !config.botToken || !config.chatId) return Promise.resolve({ skipped: true });

  const payload = JSON.stringify({
    chat_id: config.chatId,
    text,
    parse_mode: "HTML",
    disable_web_page_preview: true
  });

  const options = {
    hostname: "api.telegram.org",
    path: `/bot${encodeURIComponent(config.botToken)}/sendMessage`,
    method: "POST",
    headers: {
      "content-type": "application/json",
      "content-length": Buffer.byteLength(payload)
    },
    timeout: 10000
  };

  return new Promise((resolve) => {
    const req = https.request(options, (res) => {
      const chunks = [];
      res.on("data", (chunk) => chunks.push(chunk));
      res.on("end", () => {
        resolve({ ok: res.statusCode >= 200 && res.statusCode < 300, statusCode: res.statusCode, body: Buffer.concat(chunks).toString("utf8") });
      });
    });
    req.on("error", (error) => resolve({ ok: false, error: error.message }));
    req.on("timeout", () => {
      req.destroy();
      resolve({ ok: false, error: "timeout" });
    });
    req.write(payload);
    req.end();
  });
}

function notifyTelegram(text) {
  sendTelegramMessage(text).then((result) => {
    if (result?.ok === false) {
      addEvent(null, "alert.telegram", `Falha ao enviar Telegram: ${result.error || result.statusCode}`);
    }
  }).catch(() => {});
}

function escapeTelegram(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function notFound(res) {
  sendJson(res, 404, { error: "not_found" });
}

function isAdmin(req, url) {
  const header = req.headers["x-admin-token"];
  const query = url.searchParams.get("token");
  return header === ADMIN_TOKEN || query === ADMIN_TOKEN;
}

function deviceFromToken(req, url) {
  const header = req.headers["x-device-token"];
  const query = url.searchParams.get("deviceToken") || url.searchParams.get("token");
  const value = header || query || "";
  if (!value) return null;
  return state.devices.find((device) => device.activationToken === value) || null;
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  if (!chunks.length) return {};
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

async function readBinaryBody(req, limitBytes = MEDIA_UPLOAD_LIMIT_BYTES) {
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > limitBytes) {
      const error = new Error("payload_too_large");
      error.code = "payload_too_large";
      throw error;
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

function normalizeDisplayMode(value) {
  const mode = String(value || "").trim();
  if (mode === "panel_with_media") return "panel_with_media";
  if (mode === "panel_idle_media") return "panel_idle_media";
  return "panel_only";
}

function sanitizeMediaUrl(value) {
  const raw = String(value || "").trim();
  if (!raw || raw === "about:blank") return "";
  if (raw.startsWith("/media/")) return raw;
  if (/^https?:\/\//i.test(raw)) return raw;
  return "";
}

function sanitizeMediaLabel(value) {
  return String(value || "")
    .replace(/[^\p{L}\p{N}\s._\-]/gu, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 120);
}

function mediaIsDirectVideo(urlValue) {
  const value = String(urlValue || "").trim().toLowerCase();
  if (!value) return false;
  return DIRECT_VIDEO_EXTENSIONS.some((extension) => value.includes(extension));
}

function mediaPlaybackUrl(mediaUrl) {
  const normalized = sanitizeMediaUrl(mediaUrl);
  if (!normalized) return "";
  if (mediaIsDirectVideo(normalized)) {
    return `/media-player.html?src=${encodeURIComponent(normalized)}`;
  }
  return normalized;
}

function sanitizeUploadFilename(filename, contentType = "") {
  const original = String(filename || "").trim().replace(/[/\\?%*:|"<>]/g, "-");
  const extFromName = path.extname(original).toLowerCase();
  const extFromType = ({
    "video/mp4": ".mp4",
    "video/webm": ".webm",
    "video/ogg": ".ogg",
    "video/quicktime": ".mov"
  })[String(contentType || "").toLowerCase()] || "";
  const extension = DIRECT_VIDEO_EXTENSIONS.includes(extFromName) ? extFromName : (extFromType || ".mp4");
  const base = (path.basename(original, extFromName || undefined) || "video")
    .replace(/[^\p{L}\p{N}._\-]+/gu, "-")
    .replace(/-+/g, "-")
    .slice(0, 80) || "video";
  return `${Date.now()}-${base}${extension}`;
}

function ttsHash({ text, voice, rate, pitch }) {
  return createHash("sha256")
    .update(JSON.stringify({ text, voice, rate, pitch }))
    .digest("hex")
    .slice(0, 32);
}

function clampNumber(value, min, max, fallback) {
  const number = Number(value);
  if (!Number.isFinite(number)) return fallback;
  return Math.max(min, Math.min(max, number));
}

function windowsTtsRate(webRate) {
  return Math.round((clampNumber(webRate, 0.6, 1.4, 1) - 1) * 10);
}

async function synthesizeWindowsSpeech({ text, voice, rate, filePath }) {
  await mkdir(ttsCacheDir, { recursive: true });
  const script = [
    "Add-Type -AssemblyName System.Speech",
    "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer",
    "try {",
    "  $voice = [Environment]::GetEnvironmentVariable('PTV_TTS_VOICE')",
    "  if ($voice) { try { $s.SelectVoice($voice) } catch {} }",
    "  $rate = [int]([Environment]::GetEnvironmentVariable('PTV_TTS_RATE'))",
    "  if ($rate -lt -10) { $rate = -10 }",
    "  if ($rate -gt 10) { $rate = 10 }",
    "  $s.Rate = $rate",
    "  $s.SetOutputToWaveFile([Environment]::GetEnvironmentVariable('PTV_TTS_FILE'))",
    "  $s.Speak([Environment]::GetEnvironmentVariable('PTV_TTS_TEXT'))",
    "} finally {",
    "  $s.Dispose()",
    "}"
  ].join("; ");

  await new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", ["-STA", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script], {
      windowsHide: true,
      env: {
        ...process.env,
        PTV_TTS_TEXT: text,
        PTV_TTS_FILE: filePath,
        PTV_TTS_VOICE: voice || "",
        PTV_TTS_RATE: String(windowsTtsRate(rate))
      }
    });
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      try { child.kill("SIGKILL"); } catch {}
      reject(new Error("windows_tts_timeout"));
    }, 12000);
    let stderr = "";
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
    });
    child.on("error", (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(error);
    });
    child.on("exit", (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (code === 0 && existsSync(filePath)) resolve();
      else reject(new Error(stderr.trim() || `PowerShell TTS terminou com codigo ${code}`));
    });
  });
}

async function synthesizeNaturalSpeech({ text, voiceId, rate, filePath }) {
  await mkdir(ttsCacheDir, { recursive: true });
  const script = [
    "$ErrorActionPreference = 'Stop'",
    "$OutputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)",
    "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
    "$null = [Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media.SpeechSynthesis, ContentType=WindowsRuntime]",
    "$null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType=WindowsRuntime]",
    "$s = New-Object Windows.Media.SpeechSynthesis.SpeechSynthesizer",
    "try {",
    "  $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and $_.GetParameters().Count -eq 1 } | Select-Object -First 1",
    "  if (-not $asTask) { throw 'AsTask indisponivel' }",
    "  $voiceId = [Environment]::GetEnvironmentVariable('PTV_TTS_VOICE_ID')",
    "  if ($voiceId) {",
    "    $v = [Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices | Where-Object { $_.Id -eq $voiceId } | Select-Object -First 1",
    "    if ($v) { try { $s.Voice = $v } catch {} }",
    "  }",
    "  $rate = [double]([Environment]::GetEnvironmentVariable('PTV_TTS_RATE_FLOAT'))",
    "  if ($rate -lt 0.5) { $rate = 0.5 }",
    "  if ($rate -gt 2.0) { $rate = 2.0 }",
    "  try { $s.Options.SpeakingRate = $rate } catch {}",
    "  $text = [Environment]::GetEnvironmentVariable('PTV_TTS_TEXT')",
    "  $op = $s.SynthesizeTextToStreamAsync($text)",
    "  $task = $asTask.MakeGenericMethod([Windows.Media.SpeechSynthesis.SpeechSynthesisStream]).Invoke($null, @($op))",
    "  $stream = $task.GetAwaiter().GetResult()",
    "  $input = $stream.GetInputStreamAt(0)",
    "  $reader = [Windows.Storage.Streams.DataReader]::new($input)",
    "  $size = [uint32]$stream.Size",
    "  $loadOp = $reader.LoadAsync($size)",
    "  $task2 = $asTask.MakeGenericMethod([uint32]).Invoke($null, @($loadOp))",
    "  $task2.GetAwaiter().GetResult() | Out-Null",
    "  $bytes = New-Object byte[] $size",
    "  $reader.ReadBytes($bytes)",
    "  [IO.File]::WriteAllBytes([Environment]::GetEnvironmentVariable('PTV_TTS_FILE'), $bytes)",
    "} finally {",
    "  try { $s.Dispose() } catch {}",
    "}"
  ].join("; ");
  await new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", ["-STA", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script], {
      windowsHide: true,
      env: {
        ...process.env,
        PTV_TTS_TEXT: text,
        PTV_TTS_FILE: filePath,
        PTV_TTS_VOICE_ID: String(voiceId || ""),
        PTV_TTS_RATE_FLOAT: String(clampNumber(rate, 0.6, 1.4, 1))
      }
    });
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      try { child.kill("SIGKILL"); } catch {}
      reject(new Error("natural_tts_timeout"));
    }, 3000);
    let stderr = "";
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
    });
    child.on("error", (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(error);
    });
    child.on("exit", (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (code === 0 && existsSync(filePath)) resolve();
      else reject(new Error(stderr.trim() || `PowerShell TTS (Natural) terminou com codigo ${code}`));
    });
  });
}

async function previewWindowsSpeech({ text, voice, rate }) {
  const script = [
    "$ErrorActionPreference = 'Stop'",
    "$OutputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)",
    "Add-Type -AssemblyName System.Speech",
    "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer",
    "try {",
    "  $voice = [Environment]::GetEnvironmentVariable('PTV_TTS_VOICE')",
    "  if ($voice) { try { $s.SelectVoice($voice) } catch {} }",
    "  $rate = [int]([Environment]::GetEnvironmentVariable('PTV_TTS_RATE'))",
    "  if ($rate -lt -10) { $rate = -10 }",
    "  if ($rate -gt 10) { $rate = 10 }",
    "  $s.Rate = $rate",
    "  $s.Speak([Environment]::GetEnvironmentVariable('PTV_TTS_TEXT'))",
    "} finally {",
    "  $s.Dispose()",
    "}"
  ].join("; ");

  await new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script], {
      windowsHide: true,
      env: {
        ...process.env,
        PTV_TTS_TEXT: text,
        PTV_TTS_VOICE: voice || "",
        PTV_TTS_RATE: String(windowsTtsRate(rate))
      }
    });
    let stderr = "";
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
    });
    child.on("error", reject);
    child.on("exit", (code) => {
      if (code === 0) resolve();
      else reject(new Error(stderr.trim() || `PowerShell TTS terminou com codigo ${code}`));
    });
  });
}

async function previewNaturalSpeech({ text, voiceId, rate }) {
  const script = [
    "$ErrorActionPreference = 'Stop'",
    "$OutputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)",
    "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
    "$null = [Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media.SpeechSynthesis, ContentType=WindowsRuntime]",
    "$null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType=WindowsRuntime]",
    "$s = New-Object Windows.Media.SpeechSynthesis.SpeechSynthesizer",
    "try {",
    "  $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and $_.GetParameters().Count -eq 1 } | Select-Object -First 1",
    "  if (-not $asTask) { throw 'AsTask indisponivel' }",
    "  $voiceId = [Environment]::GetEnvironmentVariable('PTV_TTS_VOICE_ID')",
    "  if ($voiceId) {",
    "    $v = [Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices | Where-Object { $_.Id -eq $voiceId } | Select-Object -First 1",
    "    if ($v) { try { $s.Voice = $v } catch {} }",
    "  }",
    "  $rate = [double]([Environment]::GetEnvironmentVariable('PTV_TTS_RATE_FLOAT'))",
    "  if ($rate -lt 0.5) { $rate = 0.5 }",
    "  if ($rate -gt 2.0) { $rate = 2.0 }",
    "  try { $s.Options.SpeakingRate = $rate } catch {}",
    "  $text = [Environment]::GetEnvironmentVariable('PTV_TTS_TEXT')",
    "  $op = $s.SynthesizeTextToStreamAsync($text)",
    "  $task = $asTask.MakeGenericMethod([Windows.Media.SpeechSynthesis.SpeechSynthesisStream]).Invoke($null, @($op))",
    "  $stream = $task.GetAwaiter().GetResult()",
    "  $input = $stream.GetInputStreamAt(0)",
    "  $reader = [Windows.Storage.Streams.DataReader]::new($input)",
    "  $size = [uint32]$stream.Size",
    "  $loadOp = $reader.LoadAsync($size)",
    "  $task2 = $asTask.MakeGenericMethod([uint32]).Invoke($null, @($loadOp))",
    "  $task2.GetAwaiter().GetResult() | Out-Null",
    "  $bytes = New-Object byte[] $size",
    "  $reader.ReadBytes($bytes)",
    "  $tmp = [IO.Path]::Combine([IO.Path]::GetTempPath(), ('ptv-natural-' + [Guid]::NewGuid().ToString('N') + '.wav'))",
    "  [IO.File]::WriteAllBytes($tmp, $bytes)",
    "  $p = New-Object System.Media.SoundPlayer($tmp)",
    "  $p.Load()",
    "  $p.PlaySync()",
    "  try { Remove-Item -LiteralPath $tmp -Force } catch {}",
    "} finally {",
    "  try { $s.Dispose() } catch {}",
    "}"
  ].join("; ");
  await new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script], {
      windowsHide: true,
      env: {
        ...process.env,
        PTV_TTS_TEXT: text,
        PTV_TTS_VOICE_ID: String(voiceId || ""),
        PTV_TTS_RATE_FLOAT: String(clampNumber(rate, 0.6, 1.4, 1))
      }
    });
    let stderr = "";
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
    });
    child.on("error", reject);
    child.on("exit", (code) => {
      if (code === 0) resolve();
      else reject(new Error(stderr.trim() || `PowerShell TTS preview (Natural) terminou com codigo ${code}`));
    });
  });
}

async function listWindowsVoices() {
  const script = [
    "$ErrorActionPreference = 'Stop'",
    "$OutputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)",
    "Add-Type -AssemblyName System.Speech",
    "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer",
    "try {",
    "  $s.GetInstalledVoices() | ForEach-Object { $_.VoiceInfo } | Select-Object @{n='name';e={$_.Name}}, @{n='lang';e={$_.Culture.Name}} | ConvertTo-Json -Compress",
    "} finally {",
    "  $s.Dispose()",
    "}"
  ].join('; ');
  return await new Promise((resolve) => {
    const child = spawn("powershell.exe", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script], {
      windowsHide: true
    });
    let stdout = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString("utf8");
    });
    child.on("error", () => resolve([]));
    child.on("exit", () => {
      const raw = stdout.trim();
      if (!raw) return resolve([]);
      try {
        const parsed = JSON.parse(raw);
        const list = Array.isArray(parsed) ? parsed : [parsed];
        const normalized = list
          .map((voice) => ({
            name: String(voice?.name || "").trim(),
            lang: String(voice?.lang || "").trim()
          }))
          .filter((voice) => voice.name);
        const seen = new Set();
        const allowed = normalized.filter((voice) => {
          const name = String(voice.name || "").trim();
          const lang = String(voice.lang || "").trim().toLowerCase();
          if (!name) return false;
          if (!lang.startsWith("pt")) return false;
          if (isVoiceBlocked(name)) return false;
          if (seen.has(name)) return false;
          seen.add(name);
          return true;
        });
        resolve(allowed);
      } catch {
        const fallback = raw
          .split(/\r?\n/)
          .map((line) => ({ name: line.trim(), lang: "" }))
          .filter((v) => v.name && !isVoiceBlocked(v.name));
        resolve(fallback);
      }
    });
  });
}

async function listNaturalVoices() {
  const script = [
    "$ErrorActionPreference = 'Stop'",
    "$OutputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)",
    "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
    "$null = [Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media.SpeechSynthesis, ContentType=WindowsRuntime]",
    "[Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices | Select-Object @{n='id';e={$_.Id}}, @{n='name';e={$_.DisplayName}}, @{n='lang';e={$_.Language}} | ConvertTo-Json -Compress"
  ].join("; ");
  return await new Promise((resolve) => {
    const child = spawn("powershell.exe", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script], {
      windowsHide: true
    });
    let stdout = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString("utf8");
    });
    child.on("error", () => resolve([]));
    child.on("exit", () => {
      const raw = stdout.trim();
      if (!raw) return resolve([]);
      try {
        const parsed = JSON.parse(raw);
        const list = Array.isArray(parsed) ? parsed : [parsed];
        const normalized = list
          .map((voice) => ({
            id: String(voice?.id || "").trim(),
            displayName: String(voice?.name || "").trim(),
            lang: String(voice?.lang || "").trim()
          }))
          .filter((voice) => voice.id && voice.displayName);
        const seen = new Set();
        const allowed = normalized
          .filter((voice) => {
            const lang = String(voice.lang || "").trim().toLowerCase();
            if (!lang.startsWith("pt")) return false;
            if (isVoiceBlocked(voice.displayName)) return false;
            if (seen.has(voice.id)) return false;
            seen.add(voice.id);
            return true;
          })
          .map((voice) => ({
            name: `natural:${base64UrlEncodeUtf8(voice.id)}`,
            lang: voice.lang,
            label: `${voice.displayName} (Natural)`,
            subtitle: voice.lang
          }));
        resolve(allowed);
      } catch {
        resolve([]);
      }
    });
  });
}

async function listAvailableVoices() {
  const [sapi, natural] = await Promise.all([listWindowsVoices(), listNaturalVoices()]);
  const merged = [...(Array.isArray(natural) ? natural : []), ...(Array.isArray(sapi) ? sapi : [])];
  const seen = new Set();
  return merged.filter((voice) => {
    const key = String(voice?.name || "").trim();
    if (!key) return false;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

let cachedAllowedVoicesAt = 0;
let cachedAllowedVoiceNames = new Set();

async function allowedVoiceNames() {
  const ttlMs = 60_000;
  if (Date.now() - cachedAllowedVoicesAt < ttlMs && cachedAllowedVoiceNames.size) return cachedAllowedVoiceNames;
  const voices = await listAvailableVoices();
  cachedAllowedVoiceNames = new Set(voices.map((v) => v.name));
  cachedAllowedVoicesAt = Date.now();
  return cachedAllowedVoiceNames;
}

async function sanitizeVoice(value) {
  const voice = String(value || "").trim();
  if (!voice) return "";
  const parsed = parseVoiceValue(voice);
  if (parsed.type === "invalid") return "";
  if (parsed.type === "sapi" && isVoiceBlocked(voice)) return "";
  const allowed = await allowedVoiceNames();
  return allowed.has(voice) ? voice : "";
}

async function resolveIncomingTtsVoice(body, fallbackVoice = "") {
  const explicit = await sanitizeVoice(body?.ttsVoice || "");
  if (explicit) return explicit;

  const preference = String(body?.ttsVoicePreference || "").trim().toLowerCase();
  if (!preference || preference === "default") {
    return await sanitizeVoice(fallbackVoice);
  }

  const voices = await listAvailableVoices();
  const choose = (pattern) =>
    voices.find((voice) => pattern.test(String(voice.label || voice.displayName || voice.name || "")))?.name || "";

  if (preference === "male") {
    return choose(/daniel/i) || await sanitizeVoice(fallbackVoice);
  }

  if (preference === "female") {
    return choose(/maria/i) || await sanitizeVoice(fallbackVoice);
  }

  return await sanitizeVoice(fallbackVoice);
}

function normalizeUrl(value) {
  if (!value) return "";
  if (value === "about:blank") return value;
  const parsed = new URL(value);
  if (!["http:", "https:"].includes(parsed.protocol)) {
    throw new Error("URL deve usar http ou https");
  }
  return parsed.toString();
}

function baseUrlFromRequest(req) {
  const proto = req.headers["x-forwarded-proto"] || "http";
  return `${proto}://${req.headers.host}`;
}

function clientIpFromRequest(req) {
  const forwarded = req.headers["x-forwarded-for"];
  const raw = Array.isArray(forwarded) ? forwarded[0] : forwarded;
  const value = raw ? raw.split(",")[0].trim() : req.socket.remoteAddress || "";
  return normalizeIp(value);
}

function normalizeIp(value) {
  if (!value) return "";
  if (value.startsWith("::ffff:")) return value.slice("::ffff:".length);
  if (value === "::1") return "127.0.0.1";
  return value;
}

function safeTtsText(value) {
  return String(value || "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 280);
}

function powershellEncoded(script) {
  return Buffer.from(script, "utf16le").toString("base64");
}

function generateWindowsTtsWav(text, filePath) {
  const script = `
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
$voice.Rate = -1
$voice.Volume = 100
$voice.SetOutputToWaveFile(${JSON.stringify(filePath)})
$voice.Speak(${JSON.stringify(text)})
$voice.Dispose()
`;
  return new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", [
      "-NoProfile",
      "-ExecutionPolicy",
      "Bypass",
      "-EncodedCommand",
      powershellEncoded(script)
    ], {
      windowsHide: true,
      stdio: "ignore"
    });
    child.on("error", reject);
    child.on("exit", (code) => {
      if (code === 0 && existsSync(filePath)) resolve(filePath);
      else reject(new Error(`tts_failed_${code}`));
    });
  });
}

async function serveTtsWav(req, res, url) {
  const text = safeTtsText(url.searchParams.get("text"));
  if (!text) {
    sendJson(res, 400, { error: "missing_text" });
    return;
  }
  await mkdir(ttsCacheDir, { recursive: true });
  const key = createHash("sha1").update(text).digest("hex");
  const filePath = path.join(ttsCacheDir, `${key}.wav`);
  if (!existsSync(filePath)) {
    await generateWindowsTtsWav(text, filePath);
  }
  res.writeHead(200, {
    "content-type": "audio/wav",
    "cache-control": "public, max-age=31536000, immutable"
  });
  createReadStream(filePath).pipe(res);
}

function createDevice(body, activationToken = token()) {
  return {
    id: id("tv"),
    name: body.name || "Nova TV",
    location: body.location || "",
    group: body.group || "",
    currentUrl: normalizeUrl(body.currentUrl || "about:blank"),
    fallbackUrl: normalizeUrl(body.fallbackUrl || state.settings.fallbackUrl || "about:blank"),
    audioEnabled: body.audioEnabled !== false,
    operatingMode: body.operatingMode === "schedule" ? "schedule" : "always",
    operatingStart: body.operatingStart || "07:00",
    operatingEnd: body.operatingEnd || "18:00",
    operatingDays: body.operatingDays || "1,2,3,4,5",
    themeId: body.themeId || "sus_verde",
    displayMode: normalizeDisplayMode(body.displayMode),
    mediaUrl: sanitizeMediaUrl(body.mediaUrl),
    mediaLabel: sanitizeMediaLabel(body.mediaLabel || body.mediaUrl || ""),
    ttsVoice: body.ttsVoice || state.settings.ttsVoice || "",
    activationToken,
    status: "waiting_activation",
    ipAddress: body.ipAddress || "",
    lastIpAddress: body.ipAddress || "",
    deviceInfo: body.deviceInfo || {},
    playerStatus: "idle",
    appVersion: body.appVersion || "-",
    memoryMb: 0,
    reloads: 0,
    reconnects: 0,
    recoveryCycles: 0,
    webViewRecreates: 0,
    appRestarts: 0,
    panelNetworkEvents: 0,
    panelAutoReloads: 0,
    lastPanelNetworkActivitySecondsAgo: -1,
    lastSuccessfulPageLoadSecondsAgo: -1,
    lastWebViewRecreateSecondsAgo: -1,
    uptimeSeconds: 0,
    lastError: "",
    lastHeartbeat: null,
    createdAt: now(),
    updatedAt: now()
  };
}

async function handleApi(req, res, url) {
  if (url.pathname === "/api/health") {
    sendJson(res, 200, { ok: true, time: now() });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/tts/voices") {
    if (!isAdmin(req, url)) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    sendJson(res, 200, { provider: "windows", voices: await listAvailableVoices() });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/tts/synthesize") {
    const device = deviceFromToken(req, url);
    if (!device && !isAdmin(req, url)) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    const body = await readBody(req);
    const text = normalizeText(body.text || "");
    if (!text) {
      sendJson(res, 400, { error: "empty_text" });
      return;
    }
    if (text.length > 500) {
      sendJson(res, 400, { error: "text_too_long" });
      return;
    }
    const requestedVoice = String(body.voice || device?.ttsVoice || state.settings.ttsVoice || "").trim();
    const voice = await sanitizeVoice(requestedVoice);
    const rate = clampNumber(body.rate, 0.6, 1.4, 1);
    const pitch = clampNumber(body.pitch, 0.7, 1.3, 1);
    let hash = ttsHash({ text, voice, rate, pitch });
    let filename = `${hash}.wav`;
    let filePath = path.join(ttsCacheDir, filename);
    if (!existsSync(filePath)) {
      const parsed = parseVoiceValue(voice);
      try {
        if (parsed.type === "natural") {
          if (isNaturalTtsCoolingDown(parsed.id)) throw new Error("natural_tts_cooling_down");
          await synthesizeNaturalSpeech({ text, voiceId: parsed.id, rate, filePath });
          naturalTtsFailures.delete(String(parsed.id || ""));
        } else {
          await synthesizeWindowsSpeech({ text, voice, rate, pitch, filePath });
        }
      } catch (error) {
        if (parsed.type !== "natural") throw error;
        markNaturalTtsFailure(parsed.id);
        const fallbackVoice = "";
        hash = ttsHash({ text, voice: fallbackVoice, rate, pitch });
        filename = `${hash}.wav`;
        filePath = path.join(ttsCacheDir, filename);
        if (!existsSync(filePath)) {
          await synthesizeWindowsSpeech({ text, voice: fallbackVoice, rate, pitch, filePath });
        }
      }
    }
    sendJson(res, 200, {
      provider: "windows",
      cached: true,
      audioUrl: `/tts-cache/${filename}`,
      format: "audio/wav"
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/tts/preview") {
    if (!isAdmin(req, url)) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    const body = await readBody(req);
    const text = String(body.text || "").replace(/\s+/g, " ").trim() || "Teste de voz do Painel TV.";
    if (text.length > 500) {
      sendJson(res, 400, { error: "text_too_long" });
      return;
    }
    const voice = String(body.voice || state.settings.ttsVoice || "").trim();
    const rate = clampNumber(body.rate, 0.6, 1.4, 1);
    const sanitized = await sanitizeVoice(voice);
    const parsed = parseVoiceValue(sanitized);
    if (parsed.type === "natural") {
      await previewNaturalSpeech({ text, voiceId: parsed.id, rate });
    } else {
      await previewWindowsSpeech({ text, voice: sanitized, rate });
    }
    sendJson(res, 200, { ok: true, provider: "windows" });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/pairing/request") {
    const body = await readBody(req);
    const clientId = body.clientId || body.deviceInfo?.clientId || "";
    const existing = clientId
      ? (state.pairingSessions || []).find((session) =>
          session.clientId === clientId &&
          session.status === "pending" &&
          new Date(session.expiresAt).getTime() > Date.now())
      : null;
    if (existing) {
      existing.suggestedName = body.suggestedName || body.deviceName || existing.suggestedName;
      existing.appVersion = body.appVersion || existing.appVersion;
      existing.deviceInfo = { ...(existing.deviceInfo || {}), ...(body.deviceInfo || {}) };
      existing.ipAddress = body.localIpAddress || body.deviceInfo?.localIp || clientIpFromRequest(req);
      existing.remoteIpAddress = clientIpFromRequest(req);
      existing.updatedAt = now();
      await saveState();
      broadcastAdmins(snapshot());
      sendJson(res, 200, {
        pairing: {
          id: existing.id,
          secret: existing.secret,
          code: existing.code,
          ipAddress: existing.ipAddress,
          approveUrl: `${baseUrlFromRequest(req)}/pair.html?id=${encodeURIComponent(existing.id)}`,
          expiresAt: existing.expiresAt
        }
      });
      return;
    }
    const pairingId = id("pair");
    const secret = token();
    const session = {
      id: pairingId,
      secret,
      code: shortCode(),
      status: "pending",
      suggestedName: body.suggestedName || body.deviceName || "TV aguardando aprovacao",
      clientId,
      appVersion: body.appVersion || "-",
      deviceInfo: body.deviceInfo || {},
      ipAddress: body.localIpAddress || body.deviceInfo?.localIp || clientIpFromRequest(req),
      remoteIpAddress: clientIpFromRequest(req),
      activationToken: null,
      deviceId: null,
      createdAt: now(),
      expiresAt: new Date(Date.now() + 15 * 60 * 1000).toISOString()
    };
    state.pairingSessions = [session, ...(state.pairingSessions || [])].slice(0, 100);
    addEvent(null, "pairing.requested", `Novo pareamento solicitado: ${session.code}`);
    await saveState();
    broadcastAdmins(snapshot());
    sendJson(res, 201, {
      pairing: {
        id: session.id,
        secret: session.secret,
        code: session.code,
        ipAddress: session.ipAddress,
        approveUrl: `${baseUrlFromRequest(req)}/pair.html?id=${encodeURIComponent(session.id)}`,
        expiresAt: session.expiresAt
      }
    });
    return;
  }

  const pairingStatusMatch = url.pathname.match(/^\/api\/pairing\/([^/]+)\/status$/);
  if (pairingStatusMatch && req.method === "GET") {
    const session = (state.pairingSessions || []).find((item) => item.id === pairingStatusMatch[1]);
    if (!session || session.secret !== url.searchParams.get("secret")) return notFound(res);
    if (session.status === "pending" && new Date(session.expiresAt).getTime() < Date.now()) {
      session.status = "expired";
      await saveState();
    }
    sendJson(res, 200, {
      pairing: {
        id: session.id,
        code: session.code,
        status: session.status,
        ipAddress: session.ipAddress,
        activationToken: session.status === "approved" ? session.activationToken : null,
        deviceId: session.deviceId,
        expiresAt: session.expiresAt
      }
    });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/tts.wav") {
    await serveTtsWav(req, res, url);
    return;
  }

  if (!isAdmin(req, url)) {
    sendJson(res, 401, { error: "unauthorized" });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/devices") {
    sendJson(res, 200, { devices: state.devices.map(publicDevice) });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/pairing") {
    sendJson(res, 200, { pairingSessions: publicPairingSessions() });
    return;
  }

  const pairingGetMatch = url.pathname.match(/^\/api\/pairing\/([^/]+)$/);
  if (pairingGetMatch && req.method === "GET") {
    const session = (state.pairingSessions || []).find((item) => item.id === pairingGetMatch[1]);
    if (!session) return notFound(res);
    sendJson(res, 200, {
      pairing: {
        id: session.id,
        code: session.code,
        status: session.status,
        suggestedName: session.suggestedName,
        appVersion: session.appVersion,
        ipAddress: session.ipAddress,
        deviceInfo: session.deviceInfo || {},
        createdAt: session.createdAt,
        expiresAt: session.expiresAt
      }
    });
    return;
  }

  const pairingApproveMatch = url.pathname.match(/^\/api\/pairing\/([^/]+)\/approve$/);
  if (pairingApproveMatch && req.method === "POST") {
    const session = (state.pairingSessions || []).find((item) => item.id === pairingApproveMatch[1]);
    if (!session) return notFound(res);
    if (session.status !== "pending") {
      sendJson(res, 409, { error: `pairing_${session.status}` });
      return;
    }
    if (new Date(session.expiresAt).getTime() < Date.now()) {
      session.status = "expired";
      await saveState();
      sendJson(res, 409, { error: "pairing_expired" });
      return;
    }
    const body = await readBody(req);
    const resolvedTtsVoice = await resolveIncomingTtsVoice(body, state.settings.ttsVoice || "");
    const activationToken = token();
    const device = createDevice({
      name: body.name || session.suggestedName || `TV ${session.code}`,
      location: body.location || "",
      group: body.group || "",
      currentUrl: body.currentUrl || "about:blank",
      fallbackUrl: body.fallbackUrl || state.settings.fallbackUrl || "about:blank",
      audioEnabled: body.audioEnabled !== false,
      operatingMode: body.operatingMode === "schedule" ? "schedule" : "always",
      operatingStart: body.operatingStart || "07:00",
      operatingEnd: body.operatingEnd || "18:00",
      operatingDays: body.operatingDays || "1,2,3,4,5",
      themeId: body.themeId,
      displayMode: body.displayMode,
      mediaUrl: body.mediaUrl,
      mediaLabel: body.mediaLabel,
      ttsVoice: resolvedTtsVoice,
      appVersion: session.appVersion,
      ipAddress: session.ipAddress,
      deviceInfo: session.deviceInfo || {}
    }, activationToken);
    state.devices.unshift(device);
    session.status = "approved";
    session.activationToken = activationToken;
    session.deviceId = device.id;
    session.approvedAt = now();
    addEvent(device.id, "pairing.approved", `Pareamento aprovado: ${device.name}`, { code: session.code });
    await saveState();
    broadcastAdmins(snapshot());
    sendJson(res, 201, { device: { ...publicDevice(device), activationToken } });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/devices") {
    const body = await readBody(req);
    const resolvedTtsVoice = await resolveIncomingTtsVoice(body, state.settings.ttsVoice || "");
    const device = createDevice({ ...body, ttsVoice: resolvedTtsVoice });
    state.devices.unshift(device);
    addEvent(device.id, "device.created", `TV cadastrada: ${device.name}`);
    await saveState();
    broadcastAdmins(snapshot());
    sendJson(res, 201, { device: { ...publicDevice(device), activationToken: device.activationToken } });
    return;
  }

  const deviceMatch = url.pathname.match(/^\/api\/devices\/([^/]+)$/);
  if (deviceMatch && req.method === "DELETE") {
    const index = state.devices.findIndex((item) => item.id === deviceMatch[1]);
    if (index === -1) return notFound(res);
    const [device] = state.devices.splice(index, 1);
    const socket = deviceSockets.get(device.id);
    if (socket) {
      sendWs(socket, { type: "command", command: { id: id("cmd"), command: "unpair", payload: {}, createdAt: now() } });
      socket.end();
      deviceSockets.delete(device.id);
    }
    addEvent(device.id, "device.deleted", `TV excluida: ${device.name}`, { deviceId: device.id });
    await saveState();
    broadcastAdmins(snapshot());
    sendJson(res, 200, { deleted: true, deviceId: device.id });
    return;
  }

  if (deviceMatch && req.method === "PATCH") {
    const device = state.devices.find((item) => item.id === deviceMatch[1]);
    if (!device) return notFound(res);
    const body = await readBody(req);
    if (body.name !== undefined) device.name = String(body.name);
    if (body.location !== undefined) device.location = String(body.location);
    if (body.group !== undefined) device.group = String(body.group);
    if (body.ipAddress !== undefined) device.ipAddress = String(body.ipAddress);
    if (body.currentUrl !== undefined) device.currentUrl = normalizeUrl(body.currentUrl);
    if (body.fallbackUrl !== undefined) device.fallbackUrl = normalizeUrl(body.fallbackUrl);
    if (body.audioEnabled !== undefined) device.audioEnabled = Boolean(body.audioEnabled);
    if (body.operatingMode !== undefined) device.operatingMode = body.operatingMode === "schedule" ? "schedule" : "always";
    if (body.operatingStart !== undefined) device.operatingStart = String(body.operatingStart || "07:00");
    if (body.operatingEnd !== undefined) device.operatingEnd = String(body.operatingEnd || "18:00");
    if (body.operatingDays !== undefined) device.operatingDays = String(body.operatingDays || "1,2,3,4,5");
    if (body.themeId !== undefined) device.themeId = body.themeId || "sus_verde";
    if (body.displayMode !== undefined) device.displayMode = normalizeDisplayMode(body.displayMode);
    if (body.mediaUrl !== undefined) device.mediaUrl = sanitizeMediaUrl(body.mediaUrl);
    if (body.mediaLabel !== undefined || body.mediaUrl !== undefined) {
      device.mediaLabel = sanitizeMediaLabel(body.mediaLabel || body.mediaUrl || "");
    }
    if (body.ttsVoice !== undefined || body.ttsVoicePreference !== undefined) {
      device.ttsVoice = await resolveIncomingTtsVoice(body, device.ttsVoice || state.settings.ttsVoice || "");
    }
    device.updatedAt = now();
    addEvent(device.id, "device.updated", `Configuração atualizada: ${device.name}`);
    await saveState();
    sendToDevice(device.id, { type: "config", config: deviceConfig(device) });
    broadcastAdmins(snapshot());
    sendJson(res, 200, { device: publicDevice(device) });
    return;
  }

  const commandMatch = url.pathname.match(/^\/api\/devices\/([^/]+)\/commands$/);
  if (commandMatch && req.method === "POST") {
    const device = state.devices.find((item) => item.id === commandMatch[1]);
    if (!device) return notFound(res);
    const body = await readBody(req);
    const command = {
      id: id("cmd"),
      deviceId: device.id,
      command: body.command,
      payload: body.payload || {},
      createdAt: now()
    };
    addEvent(device.id, "command.sent", `Comando enviado: ${command.command}`, command);
    const delivered = sendToDevice(device.id, { type: "command", command });
    await saveState();
    broadcastAdmins(snapshot());
    sendJson(res, 202, { command, delivered });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/events") {
    sendJson(res, 200, { events: state.events.slice(0, 500) });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/backup") {
    const payload = JSON.stringify(backupPayload(), null, 2);
    const filename = `painel-tv-backup-${new Date().toISOString().slice(0, 10)}.json`;
    res.writeHead(200, {
      "content-type": "application/json; charset=utf-8",
      "content-disposition": `attachment; filename="${filename}"`,
      "content-length": Buffer.byteLength(payload)
    });
    res.end(payload);
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/backup/restore") {
    const body = await readBody(req);
    restoreBackupPayload(body);
    await saveState();
    for (const device of state.devices) {
      sendToDevice(device.id, { type: "config", config: deviceConfig(device) });
    }
    broadcastAdmins(snapshot());
    sendJson(res, 200, {
      restored: true,
      devices: state.devices.length,
      restartRecommended: body.runtimeConfig && Number(body.runtimeConfig.port || 0) !== PORT
    });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/settings") {
    sendJson(res, 200, { settings: publicSettings() });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/server-config") {
    sendJson(res, 200, { serverConfig: publicServerConfig() });
    return;
  }

  if (req.method === "PATCH" && url.pathname === "/api/server-config") {
    const body = await readBody(req);
    const nextPort = Number(body.port);
    if (!Number.isInteger(nextPort) || nextPort < 1024 || nextPort > 65535) {
      sendJson(res, 400, { error: "invalid_port" });
      return;
    }
    const nextConfig = { ...runtimeConfig, port: nextPort };
    saveRuntimeConfig(nextConfig);
    addEvent(null, "settings.server_port", `Porta configurada para ${nextPort}. Reinicie o servidor para aplicar.`);
    await saveState();
    broadcastAdmins(snapshot());
    sendJson(res, 200, {
      serverConfig: {
        ...publicServerConfig(),
        configuredPort: nextPort,
        pendingRestart: nextPort !== PORT
      }
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/server/restart") {
    sendJson(res, 202, { restarting: true, configuredPort: Number(loadRuntimeConfig().port || PORT) });
    setTimeout(() => {
      const child = spawn(process.execPath, [fileURLToPath(import.meta.url)], {
        cwd: process.cwd(),
        detached: true,
        stdio: "ignore",
        env: { ...process.env, PORT: "" }
      });
      child.unref();
      process.exit(0);
    }, 500);
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/media/upload") {
    try {
      const buffer = await readBinaryBody(req);
      if (!buffer.length) {
        sendJson(res, 400, { error: "empty_upload" });
        return;
      }
      await mkdir(mediaDir, { recursive: true });
      const contentType = String(req.headers["content-type"] || "application/octet-stream");
      const filename = sanitizeUploadFilename(req.headers["x-file-name"], contentType);
      const filePath = path.join(mediaDir, filename);
      await writeFile(filePath, buffer);
      sendJson(res, 201, {
        media: {
          filename,
          mediaUrl: `/media/${filename}`,
          mediaPlaybackUrl: mediaPlaybackUrl(`/media/${filename}`),
          label: sanitizeMediaLabel(filename)
        }
      });
      return;
    } catch (error) {
      sendJson(res, error?.code === "payload_too_large" ? 413 : 500, { error: error?.code || "upload_failed" });
      return;
    }
  }

  if (req.method === "DELETE" && url.pathname === "/api/media") {
    const mediaUrlValue = sanitizeMediaUrl(url.searchParams.get("mediaUrl") || "");
    if (!mediaUrlValue.startsWith("/media/")) {
      sendJson(res, 400, { error: "invalid_media" });
      return;
    }
    const filename = path.basename(mediaUrlValue);
    const filePath = path.join(mediaDir, filename);
    try {
      if (existsSync(filePath)) {
        await unlink(filePath);
      }
      sendJson(res, 200, { deleted: true });
      return;
    } catch {
      sendJson(res, 500, { error: "delete_failed" });
      return;
    }
  }

  if (req.method === "PATCH" && url.pathname === "/api/settings") {
    const body = await readBody(req);
    state.settings = {
      ...state.settings,
      ...body,
      telegram: {
        ...(state.settings.telegram || {}),
        ...(body.telegram || {})
      }
    };
    addEvent(null, "settings.updated", "Configuracoes gerais atualizadas", publicSettings());
    await saveState();
    for (const device of state.devices) {
      sendToDevice(device.id, { type: "config", config: deviceConfig(device) });
    }
    broadcastAdmins(snapshot());
    sendJson(res, 200, { settings: publicSettings() });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/telegram/test") {
    const result = await sendTelegramMessage("✅ Teste do Painel TV: Telegram configurado com sucesso.");
    if (result.skipped) {
      sendJson(res, 400, { error: "telegram_not_configured" });
      return;
    }
    sendJson(res, result.ok ? 200 : 502, { result });
    return;
  }

  notFound(res);
}

function deviceConfig(device) {
  const deviceVoice = isVoiceBlocked(device.ttsVoice) ? "" : (device.ttsVoice || "");
  const serverVoice = isVoiceBlocked(state.settings.ttsVoice) ? "" : (state.settings.ttsVoice || "");
  return {
    deviceId: device.id,
    name: device.name,
    currentUrl: device.currentUrl,
    fallbackUrl: device.fallbackUrl || state.settings.fallbackUrl,
    audioEnabled: device.audioEnabled,
    themeId: device.themeId || "sus_verde",
    displayMode: normalizeDisplayMode(device.displayMode),
    mediaUrl: sanitizeMediaUrl(device.mediaUrl),
    mediaLabel: sanitizeMediaLabel(device.mediaLabel || device.mediaUrl || ""),
    mediaPlaybackUrl: mediaPlaybackUrl(device.mediaUrl),
    heartbeatIntervalSeconds: state.settings.heartbeatIntervalSeconds,
    autoRefreshSeconds: state.settings.autoRefreshSeconds,
    preventiveReloadHours: state.settings.preventiveReloadHours,
    memoryLimitMb: state.settings.memoryLimitMb,
    pageOfflineTimeoutSeconds: state.settings.pageOfflineTimeoutSeconds,
    ttsVoice: deviceVoice || serverVoice
  };
}

function serveStatic(req, res, url) {
  if (url.pathname.startsWith("/tts-cache/")) {
    const filename = path.basename(url.pathname);
    if (!/^[a-f0-9]{32}\.wav$/.test(filename)) return notFound(res);
    const filePath = path.join(ttsCacheDir, filename);
    if (!existsSync(filePath)) return notFound(res);
    res.writeHead(200, {
      "content-type": "audio/wav",
      "cache-control": "public, max-age=31536000, immutable"
    });
    createReadStream(filePath).pipe(res);
    return;
  }

  if (url.pathname === "/media-player.html") {
    const source = sanitizeMediaUrl(url.searchParams.get("src") || "");
    if (!source) return notFound(res);
    const escapedSource = source.replaceAll("&", "&amp;").replaceAll("\"", "&quot;");
    const html = `<!doctype html>
<html lang="pt-BR">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>Video do painel</title>
    <style>
      html, body { margin: 0; width: 100%; height: 100%; background: #000; overflow: hidden; }
      video { width: 100%; height: 100%; object-fit: cover; background: #000; }
    </style>
  </head>
  <body>
    <video autoplay muted loop playsinline controlslist="nodownload noplaybackrate">
      <source src="${escapedSource}" />
    </video>
  </body>
</html>`;
    res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" });
    res.end(html);
    return;
  }

  if (url.pathname.startsWith("/media/")) {
    const filename = path.basename(url.pathname);
    const filePath = path.join(mediaDir, filename);
    if (!existsSync(filePath)) return notFound(res);
    const ext = path.extname(filename).toLowerCase();
    const contentTypes = {
      ".mp4": "video/mp4",
      ".webm": "video/webm",
      ".ogg": "video/ogg",
      ".mov": "video/quicktime",
      ".m4v": "video/x-m4v"
    };
    res.writeHead(200, {
      "content-type": contentTypes[ext] || "application/octet-stream",
      "cache-control": "public, max-age=31536000, immutable"
    });
    createReadStream(filePath).pipe(res);
    return;
  }

  const requested = url.pathname === "/" ? "/index.html" : url.pathname;
  const filePath = path.normalize(path.join(publicDir, requested));
  if (!filePath.startsWith(publicDir)) return notFound(res);
  if (!existsSync(filePath)) return notFound(res);
  const ext = path.extname(filePath);
  const contentTypes = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".svg": "image/svg+xml"
  };
  res.writeHead(200, { "content-type": contentTypes[ext] || "application/octet-stream" });
  createReadStream(filePath).pipe(res);
}

function wsAccept(key) {
  return createHash("sha1")
    .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
    .digest("base64");
}

function encodeWs(data) {
  const payload = Buffer.from(JSON.stringify(data));
  if (payload.length < 126) return Buffer.concat([Buffer.from([0x81, payload.length]), payload]);
  if (payload.length < 65536) {
    const header = Buffer.alloc(4);
    header[0] = 0x81;
    header[1] = 126;
    header.writeUInt16BE(payload.length, 2);
    return Buffer.concat([header, payload]);
  }
  const header = Buffer.alloc(10);
  header[0] = 0x81;
  header[1] = 127;
  header.writeBigUInt64BE(BigInt(payload.length), 2);
  return Buffer.concat([header, payload]);
}

function encodePong(payload = Buffer.alloc(0)) {
  const data = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
  if (data.length < 126) return Buffer.concat([Buffer.from([0x8a, data.length]), data]);
  return Buffer.from([0x8a, 0]);
}

function decodeWsFrames(buffer) {
  const messages = [];
  let cursor = 0;
  while (cursor + 2 <= buffer.length) {
    const frameStart = cursor;
    const opcode = buffer[cursor] & 0x0f;
    let offset = cursor + 2;
    let length = buffer[cursor + 1] & 0x7f;
    if (length === 126) {
      if (offset + 2 > buffer.length) break;
      length = buffer.readUInt16BE(offset);
      offset += 2;
    } else if (length === 127) {
      if (offset + 8 > buffer.length) break;
      length = Number(buffer.readBigUInt64BE(offset));
      offset += 8;
    }
    const masked = (buffer[cursor + 1] & 0x80) !== 0;
    let mask;
    if (masked) {
      if (offset + 4 > buffer.length) break;
      mask = buffer.slice(offset, offset + 4);
      offset += 4;
    }
    if (offset + length > buffer.length) break;
    const payload = Buffer.from(buffer.slice(offset, offset + length));
    if (masked) {
      for (let index = 0; index < payload.length; index += 1) {
        payload[index] ^= mask[index % 4];
      }
    }
    if (opcode === 0x8) {
      messages.push({ close: true });
    } else if (opcode === 0x9) {
      messages.push({ ping: true, payload });
    } else if (opcode === 0xA) {
      messages.push({ pong: true });
    } else if (opcode === 0x1) {
      const text = payload.toString("utf8").trim();
      if (text) messages.push(JSON.parse(text));
    }
    cursor = offset + length;
    if (cursor <= frameStart) break;
  }
  return { messages, rest: buffer.slice(cursor) };
}

function decodeWs(buffer) {
  const opcode = buffer[0] & 0x0f;
  if (opcode === 0x8) return { close: true };
  let offset = 2;
  let length = buffer[1] & 0x7f;
  if (length === 126) {
    length = buffer.readUInt16BE(offset);
    offset += 2;
  } else if (length === 127) {
    length = Number(buffer.readBigUInt64BE(offset));
    offset += 8;
  }
  const masked = (buffer[1] & 0x80) !== 0;
  let mask;
  if (masked) {
    mask = buffer.slice(offset, offset + 4);
    offset += 4;
  }
  const payload = buffer.slice(offset, offset + length);
  if (masked) {
    for (let index = 0; index < payload.length; index += 1) {
      payload[index] ^= mask[index % 4];
    }
  }
  return JSON.parse(payload.toString("utf8"));
}

function sendWs(socket, message) {
  if (!socket.destroyed) socket.write(encodeWs(message));
}

async function handleWs(req, socket, url) {
  const key = req.headers["sec-websocket-key"];
  if (!key) return socket.destroy();
  socket.write([
    "HTTP/1.1 101 Switching Protocols",
    "Upgrade: websocket",
    "Connection: Upgrade",
    `Sec-WebSocket-Accept: ${wsAccept(key)}`,
    "",
    ""
  ].join("\r\n"));

  const role = url.pathname === "/ws/device" ? "device" : "admin";
  sockets.set(socket, { role, deviceId: null });

  if (role === "admin") {
    if (url.searchParams.get("token") !== ADMIN_TOKEN) return socket.destroy();
    adminSockets.add(socket);
    sendWs(socket, snapshot());
  } else {
    const activationToken = url.searchParams.get("token");
    const device = state.devices.find((item) => item.activationToken === activationToken);
    if (!device) {
      sendWs(socket, { type: "error", error: "invalid_activation_token" });
      return socket.destroy();
    }
    sockets.set(socket, { role, deviceId: device.id });
    deviceSockets.set(device.id, socket);
    const wasNotifiedOffline = Boolean(device.notifiedOffline);
    const previousStatus = device.status;
    device.status = "online";
    device.notifiedOffline = false;
    device.ipAddress = clientIpFromRequest(req);
    device.lastIpAddress = device.ipAddress;
    device.lastHeartbeat = now();
    device.updatedAt = now();
    addEvent(device.id, "device.connected", `TV conectada: ${device.name}`);
    if ((wasNotifiedOffline || previousStatus === "offline") && isWithinOperatingHours(device)) {
      notifyTelegram(`✅ <b>TV voltou</b>\n${escapeTelegram(device.name)}\nIP: ${escapeTelegram(device.ipAddress || "-")}\nHorário: ${new Date().toLocaleString("pt-BR")}`);
    }
    await saveState();
    sendWs(socket, { type: "registered", config: deviceConfig(device) });
    broadcastAdmins(snapshot());
  }

  socket.on("data", async (chunk) => {
    try {
      const previous = wsBuffers.get(socket) || Buffer.alloc(0);
      const decoded = decodeWsFrames(Buffer.concat([previous, chunk]));
      wsBuffers.set(socket, decoded.rest);
      for (const message of decoded.messages) {
        if (message.close) return socket.end();
        if (message.ping) {
          if (!socket.destroyed) socket.write(encodePong(message.payload));
          continue;
        }
        if (message.pong) continue;
        await handleWsMessage(socket, message);
      }
    } catch (error) {
      console.warn("WebSocket frame ignored:", error.message);
    }
  });

  socket.on("close", () => cleanupSocket(socket));
  socket.on("end", () => cleanupSocket(socket));
  socket.on("error", () => cleanupSocket(socket));
}

async function handleWsMessage(socket, message) {
  const meta = sockets.get(socket);
  if (!meta || meta.role !== "device") return;
  const device = state.devices.find((item) => item.id === meta.deviceId);
  if (!device) return;

  if (message.type === "heartbeat") {
    const telemetry = message.telemetry || {};
    device.status = telemetry.status || "online";
    device.currentUrl = telemetry.currentUrl || device.currentUrl;
    device.playerStatus = telemetry.playerStatus || device.playerStatus;
    device.audioEnabled = telemetry.audioEnabled ?? device.audioEnabled;
    device.memoryMb = Number(telemetry.memoryMb || device.memoryMb || 0);
    device.reloads = Number(telemetry.reloads || device.reloads || 0);
    device.reconnects = Number(telemetry.reconnects || device.reconnects || 0);
    device.recoveryCycles = Number(telemetry.recoveryCycles || device.recoveryCycles || 0);
    device.webViewRecreates = Number(telemetry.webViewRecreates || device.webViewRecreates || 0);
    device.appRestarts = Number(telemetry.appRestarts || device.appRestarts || 0);
    device.panelNetworkEvents = Number(telemetry.panelNetworkEvents ?? device.panelNetworkEvents ?? 0);
    device.panelAutoReloads = Number(telemetry.panelAutoReloads ?? device.panelAutoReloads ?? 0);
    device.lastPanelNetworkActivitySecondsAgo = Number(telemetry.lastPanelNetworkActivitySecondsAgo ?? device.lastPanelNetworkActivitySecondsAgo ?? -1);
    device.lastSuccessfulPageLoadSecondsAgo = Number(telemetry.lastSuccessfulPageLoadSecondsAgo ?? device.lastSuccessfulPageLoadSecondsAgo ?? -1);
    device.lastWebViewRecreateSecondsAgo = Number(telemetry.lastWebViewRecreateSecondsAgo ?? device.lastWebViewRecreateSecondsAgo ?? -1);
    device.uptimeSeconds = Number(telemetry.uptimeSeconds || device.uptimeSeconds || 0);
    device.lastError = telemetry.lastError || "";
    device.appVersion = telemetry.appVersion || device.appVersion || "-";
    device.lastHeartbeat = now();
    device.updatedAt = now();
    const memoryLimit = Number(state.settings.memoryLimitMb || 0);
    if (memoryLimit > 0 && device.memoryMb > memoryLimit) {
      const lastAt = device.lastMemoryAlertAt ? new Date(device.lastMemoryAlertAt).getTime() : 0;
      if (!lastAt || Date.now() - lastAt > 30 * 60 * 1000) {
        device.lastMemoryAlertAt = now();
        addEvent(device.id, "alert.memory", `Memória alta em ${device.name}: ${device.memoryMb} MB`);
      }
    }
    await saveState();
    broadcastAdmins(snapshot());
  }

  if (message.type === "command_ack") {
    addEvent(device.id, "command.ack", `Comando confirmado: ${message.commandId}`, message);
    await saveState();
    broadcastAdmins(snapshot());
  }

  if (message.type === "client_error") {
    device.lastError = message.error || "Erro desconhecido";
    addEvent(device.id, "alert.client_error", `${device.name}: ${device.lastError}`);
    await saveState();
    broadcastAdmins(snapshot());
  }

  if (message.type === "device_log") {
    if (!device.logs) device.logs = [];
    const entry = {
      ts: now(),
      tag: String(message.tag || ""),
      msg: String(message.msg || "")
    };
    device.logs.unshift(entry);
    if (device.logs.length > 200) device.logs.length = 200;
    broadcastAdmins({ type: "device_log", deviceId: device.id, entry });
    scheduleSaveState();
  }
}

function cleanupSocket(socket) {
  const meta = sockets.get(socket);
  wsBuffers.delete(socket);
  sockets.delete(socket);
  adminSockets.delete(socket);
  if (meta?.deviceId && deviceSockets.get(meta.deviceId) === socket) {
    deviceSockets.delete(meta.deviceId);
    const device = state.devices.find((item) => item.id === meta.deviceId);
    if (device) {
      device.status = "disconnected";
      addEvent(device.id, "device.disconnected", `TV desconectada: ${device.name}`);
      saveState().then(() => broadcastAdmins(snapshot())).catch(() => {});
    }
  }
}

function offlineSweep() {
  let changed = false;
  const timeoutMs = heartbeatTimeoutMs();
  for (const device of state.devices) {
    if (!device.lastHeartbeat) continue;
    const offline = Date.now() - new Date(device.lastHeartbeat).getTime() > timeoutMs;
    const inHours = isWithinOperatingHours(device);
    if (offline && !inHours && device.status !== "scheduled_offline") {
      device.status = "scheduled_offline";
      device.notifiedOffline = false;
      addEvent(device.id, "device.scheduled_offline", `TV fora do horario de funcionamento: ${device.name}`, {
        schedule: operatingLabel(device)
      });
      changed = true;
    } else if (offline && inHours && device.status !== "offline") {
      device.status = "offline";
      device.notifiedOffline = true;
      addEvent(device.id, "alert.offline", `TV offline: ${device.name}`);
      notifyTelegram(`🚨 <b>TV offline</b>\n${escapeTelegram(device.name)}\nHorário monitorado: ${escapeTelegram(operatingLabel(device))}\nÚltimo heartbeat: ${escapeTelegram(device.lastHeartbeat || "nunca")}\nIP: ${escapeTelegram(device.ipAddress || device.lastIpAddress || "-")}`);
      changed = true;
    }
  }
  if (changed) saveState().then(() => broadcastAdmins(snapshot())).catch(() => {});
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || "/", `http://${req.headers.host}`);
    if (url.pathname.startsWith("/api/")) return await handleApi(req, res, url);
    return serveStatic(req, res, url);
  } catch (error) {
    sendJson(res, 400, { error: error.message });
  }
});

server.on("upgrade", (req, socket) => {
  const url = new URL(req.url || "/", `http://${req.headers.host}`);
  if (!url.pathname.startsWith("/ws/")) return socket.destroy();
  handleWs(req, socket, url).catch(() => socket.destroy());
});

await loadState();
setInterval(offlineSweep, 5000);
server.listen(PORT, () => {
  console.log(`Servidor do Painel TV rodando em http://localhost:${PORT}`);
});
