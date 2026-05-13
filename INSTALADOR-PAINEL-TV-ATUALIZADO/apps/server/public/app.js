const state = {
  devices: [],
  events: [],
  pairingSessions: [],
  settings: {},
  serverConfig: {},
  availableVoices: [],
  socket: null,
  activeActionDeviceId: ""
};

const $ = (selector) => document.querySelector(selector);
const tokenInput = $("#adminToken");
tokenInput.value = localStorage.getItem("adminToken") || "admin123";

function adminToken() {
  return tokenInput.value.trim() || "admin123";
}

async function api(path, options = {}) {
  const response = await fetch(`${path}${path.includes("?") ? "&" : "?"}token=${encodeURIComponent(adminToken())}`, {
    ...options,
    headers: {
      "content-type": "application/json",
      ...(options.headers || {})
    }
  });
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || "Falha na API");
  return body;
}

function formatTime(value) {
  if (!value) return "-";
  return new Date(value).toLocaleString("pt-BR");
}

function elapsedHeartbeat(value) {
  if (!value) return "nunca";
  const seconds = Math.round((Date.now() - new Date(value).getTime()) / 1000);
  if (seconds < 60) return `${seconds}s atras`;
  return `${Math.round(seconds / 60)}min atras`;
}

function friendlyError(value) {
  const text = String(value || "").trim();
  if (!text) return "";
  const lower = text.toLowerCase();
  if (lower.includes("unexpected end of json input")) {
    return "Falha temporaria na comunicacao entre a TV e o servidor. Atualize o APK da TV e mantenha o servidor ligado.";
  }
  if (lower.includes("failed to connect") || lower.includes("falha websocket") || lower.includes("websocket")) {
    return "A TV perdeu contato com o servidor e esta tentando reconectar.";
  }
  if (lower.includes("cleared") || lower.includes("net::err") || lower.includes("erro de carregamento")) {
    return "A pagina do painel falhou ao carregar. O app vai tentar recuperar automaticamente.";
  }
  if (lower.includes("texttospeech") || lower.includes("narrador")) {
    return "Narrador de voz indisponivel ou ainda inicializando na TV.";
  }
  return text
    .replaceAll("heartbeat", "sinal")
    .replaceAll("Heartbeat", "Sinal")
    .replaceAll("WebView", "tela interna")
    .replaceAll("reload", "recarregamento");
}

function friendlyEvent(event) {
  const type = event.type || "";
  if (type === "device.connected") return `TV conectada: ${deviceName(event.deviceId)}`;
  if (type === "device.disconnected") return `TV desconectada: ${deviceName(event.deviceId)}`;
  if (type === "device.scheduled_offline") return `TV fora do horario: ${deviceName(event.deviceId)}`;
  if (type === "alert.client_error") return friendlyError(event.message);
  if (type === "alert.offline") return event.message.replace("TV offline", "TV sem sinal");
  if (type === "alert.memory") return event.message.replace("Memoria", "Memoria da TV");
  if (type === "command.sent") return event.message.replace("reload_page", "recarregar pagina").replace("recreate_webview", "reiniciar tela interna").replace("clear_cache", "limpar cache").replace("restart_app", "reiniciar app");
  return friendlyError(event.message || type);
}

function friendlyType(type) {
  if (!type) return "-";
  if (type.startsWith("device.")) return "Dispositivo";
  if (type.startsWith("alert.")) return "Alerta";
  if (type.startsWith("command.")) return "Comando";
  if (type.startsWith("pairing.")) return "Pareamento";
  if (type.startsWith("settings.")) return "Configuracao";
  return type;
}

function deviceName(deviceId) {
  return state.devices.find((device) => device.id === deviceId)?.name || "TV";
}

function playerLabel(value) {
  const status = String(value || "idle");
  if (status === "playing") return "Reproduzindo";
  if (status === "recovering") return "Recuperando";
  if (status === "idle") return "Aguardando";
  return status;
}

function panelActivityLabel(device) {
  const seconds = Number(device.lastPanelNetworkActivitySecondsAgo ?? -1);
  const events = Number(device.panelNetworkEvents || 0);
  if (seconds < 0) return "sem diagnostico";
  const age = seconds < 60 ? `${seconds}s` : `${Math.round(seconds / 60)}min`;
  return `${events} eventos, ultimo ha ${age}`;
}

function voiceLabelForValue(value) {
  const raw = String(value || "").trim();
  if (!raw) return "Padrao do servidor";
  const found = state.availableVoices.find((voice) => voice.name === raw);
  return found?.label || found?.displayName || found?.name || raw;
}

function displayModeLabel(value) {
  if (value === "panel_with_media") return "Painel + video";
  if (value === "panel_idle_media") return "Painel normal + espera com video";
  return "Somente painel";
}

function themeLabel(value) {
  if (value === "sus_verde") return "SUS Verde";
  if (value === "hospital_azul") return "Hospital Azul";
  if (value === "clinica_moderna") return "Clinica Moderna";
  if (value === "emergencia") return "Emergencia/UPA";
  if (value === "dark_modern") return "Escuro Moderno";
  if (value === "high_contrast") return "Alto Contraste";
  return "SUS Verde";
}

function mediaSummary(device) {
  if (!["panel_with_media", "panel_idle_media"].includes(device.displayMode)) return "Sem video lateral";
  const label = String(device.mediaLabel || "").trim();
  const url = String(device.mediaUrl || "").trim();
  return label || url || "Video lateral aguardando configuracao";
}

function panelLinkMeta(url) {
  const raw = String(url || "").trim();
  if (!raw || raw === "about:blank") {
    return {
      raw: raw || "about:blank",
      title: "Painel nao configurado",
      subtitle: "about:blank",
      detail: "Defina a URL principal da TV",
      domain: ""
    };
  }
  try {
    const parsed = new URL(raw);
    const id = parsed.pathname.split("/").filter(Boolean).pop() || "";
    const shortId = id ? `${id.slice(0, 8)}...${id.slice(-4)}` : "sem ID";
    return {
      raw,
      title: "Painel SIGSS",
      subtitle: parsed.hostname,
      detail: shortId,
      domain: parsed.hostname
    };
  } catch {
    const compact = raw.length > 36 ? `${raw.slice(0, 28)}...${raw.slice(-5)}` : raw;
    return {
      raw,
      title: "Link personalizado",
      subtitle: compact,
      detail: "URL manual",
      domain: ""
    };
  }
}

function urlActionArg(url) {
  return encodeURIComponent(String(url || ""));
}

async function copyPanelLink(encodedUrl) {
  const url = decodeURIComponent(encodedUrl || "");
  if (!url) return;
  try {
    await navigator.clipboard.writeText(url);
  } catch {
    const area = document.createElement("textarea");
    area.value = url;
    document.body.appendChild(area);
    area.select();
    document.execCommand("copy");
    area.remove();
  }
}

function openPanelLink(encodedUrl) {
  const url = decodeURIComponent(encodedUrl || "");
  if (!url || url === "about:blank") return;
  window.open(url, "_blank", "noopener,noreferrer");
}

function renderPanelLinkCell(device) {
  const current = panelLinkMeta(device.currentUrl || "about:blank");
  const fallback = panelLinkMeta(device.fallbackUrl || "about:blank");
  const identity = device.unitName || state.settings.unitName || "Padrao do servidor";
  const encodedCurrent = urlActionArg(current.raw);
  return `
    <div class="panel-link-card" title="${escapeHtml(current.raw)}">
      <div class="panel-link-header">
        <div>
          <strong>${escapeHtml(current.title)}</strong>
          <span class="small">${escapeHtml(current.subtitle)}</span>
        </div>
        <div class="panel-link-actions">
          <button type="button" class="mini-button" onclick="copyPanelLink('${encodedCurrent}')">Copiar link</button>
          <button type="button" class="mini-button" onclick="openPanelLink('${encodedCurrent}')">Abrir link</button>
        </div>
      </div>
      <span class="small panel-link-id">${escapeHtml(current.detail)}</span>
      <span class="small">Fallback: ${escapeHtml(fallback.raw || "-")}</span>
      <span class="small">Perfil: ${escapeHtml(displayModeLabel(device.displayMode))}</span>
      <span class="small">Tema: ${escapeHtml(themeLabel(device.themeId))}</span>
      <span class="small">Identidade: ${escapeHtml(identity)}</span>
      <span class="small">Midia: ${escapeHtml(mediaSummary(device))}</span>
    </div>
  `;
}

function populateVoiceSelect(selectSelector, currentValue = "") {
  const select = $(selectSelector);
  if (!select) return;
  const current = String(currentValue || "").trim();
  const options = ['<option value="">Padrao do servidor</option>']
    .concat(
      state.availableVoices.map((voice) => {
        const label = escapeHtml(voice.label || voice.displayName || voice.name || "Voz");
        const subtitle = escapeHtml(voice.subtitle || voice.lang || "");
        return `<option value="${escapeHtml(voice.name)}">${label}${subtitle ? ` - ${subtitle}` : ""}</option>`;
      })
    );
  select.innerHTML = options.join("");
  select.value = current;
  if (select.value !== current) select.value = "";
}

function selectedDays(containerSelector) {
  return Array.from(document.querySelectorAll(`${containerSelector} input:checked`))
    .map((input) => input.value)
    .join(",");
}

function setSelectedDays(containerSelector, value) {
  const days = String(value || "1,2,3,4,5").split(",");
  document.querySelectorAll(`${containerSelector} input`).forEach((input) => {
    input.checked = days.includes(input.value);
  });
}

function updateMediaUi(prefix) {
  const mode = $(`#${prefix}DisplayMode`)?.value || "panel_only";
  const mediaUrl = $(`#${prefix}MediaUrl`)?.value?.trim() || "";
  const mediaLabel = $(`#${prefix}MediaLabel`)?.value?.trim() || "";
  const status = $(`#${prefix}MediaStatus`);
  if (!status) return;
  if (!["panel_with_media", "panel_idle_media"].includes(mode)) {
    status.textContent = "Este perfil usa somente o painel de chamados.";
    return;
  }
  if (mediaUrl) {
    if (mode === "panel_idle_media") {
      status.textContent = `Midia de espera pronta: ${mediaLabel || mediaUrl}. Depois de 20s sem nova chamada, a TV troca para video + ultimas chamadas.`;
      return;
    }
    status.textContent = `Midia lateral pronta: ${mediaLabel || mediaUrl}`;
    return;
  }
  status.textContent = mode === "panel_idle_media"
    ? "Cole um link ou envie um video para o modo de espera aparecer apos 20s sem chamada."
    : "Cole um link ou envie um video para aparecer ao lado do painel.";
}

function updateUnitBrandingUi() {
  const name = $("#healthUnitName")?.value?.trim() || "";
  const logoUrl = $("#healthUnitLogoUrl")?.value?.trim() || "";
  const status = $("#healthUnitLogoStatus");
  if (!status) return;
  if (name && logoUrl) {
    status.textContent = `Topo da TV pronto com "${name}" e logomarca configurada.`;
    return;
  }
  if (name) {
    status.textContent = `Nome da unidade pronto: "${name}". Envie a logo para completar o topo da TV.`;
    return;
  }
  if (logoUrl) {
    status.textContent = "Logo configurada. Preencha o nome da unidade para completar a identidade.";
    return;
  }
  status.textContent = "Essa identidade aparece no topo do painel nativo da TV.";
}

function updatePerDeviceBrandingUi(prefix) {
  const name = $(`#${prefix}UnitName`)?.value?.trim() || "";
  const logoUrl = $(`#${prefix}UnitLogoUrl`)?.value?.trim() || "";
  const status = $(`#${prefix}UnitLogoStatus`);
  if (!status) return;
  if (name && logoUrl) {
    status.textContent = `Esta TV vai usar "${name}" com logo propria.`;
    return;
  }
  if (name) {
    status.textContent = `Esta TV vai usar "${name}". Envie a logo para completar a identidade propria.`;
    return;
  }
  if (logoUrl) {
    status.textContent = "Logo propria configurada. Preencha o nome da unidade para completar a identidade desta TV.";
    return;
  }
  status.textContent = "Se ficar vazio, a TV usa a identidade padrao salva em Saude 24/7.";
}

async function uploadMediaFile(file, prefix) {
  if (!file) return;
  const status = $(`#${prefix}MediaStatus`);
  if (status) status.textContent = `Enviando ${file.name}...`;
  const response = await fetch(`/api/media/upload?token=${encodeURIComponent(adminToken())}`, {
    method: "POST",
    headers: {
      "content-type": file.type || "application/octet-stream",
      "x-file-name": file.name
    },
    body: file
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.error || "Falha no upload");
  }
  $(`#${prefix}MediaUrl`).value = body.media.mediaUrl || "";
  if (!$(`#${prefix}MediaLabel`).value.trim()) {
    $(`#${prefix}MediaLabel`).value = body.media.label || file.name;
  }
  updateMediaUi(prefix);
}

async function uploadBrandingFile(file, prefix) {
  if (!file) return;
  const status = $(`#${prefix}UnitLogoStatus`);
  if (status) status.textContent = `Enviando ${file.name}...`;
  const response = await fetch(`/api/media/upload?token=${encodeURIComponent(adminToken())}`, {
    method: "POST",
    headers: {
      "content-type": file.type || "application/octet-stream",
      "x-file-name": file.name
    },
    body: file
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.error || "Falha no upload");
  }
  $(`#${prefix}UnitLogoUrl`).value = body.media.mediaUrl || "";
  updatePerDeviceBrandingUi(prefix);
}

function statusBadge(device) {
  if (device.online) return '<span class="badge online">Online</span>';
  if (device.status === "waiting_activation") return '<span class="badge waiting">Aguardando</span>';
  if (device.status === "scheduled_offline" || device.withinOperatingHours === false) return '<span class="badge scheduled">Fora do horario</span>';
  return '<span class="badge offline">Offline</span>';
}

function activeProblems() {
  const memoryLimit = Number(state.settings.memoryLimitMb || 650);
  const heartbeatLimitSeconds = Number(state.settings.pageOfflineTimeoutSeconds || 60);
  const problems = [];

  for (const device of state.devices) {
    const heartbeatAge = device.lastHeartbeat
      ? Math.round((Date.now() - new Date(device.lastHeartbeat).getTime()) / 1000)
      : null;

    if (device.withinOperatingHours === false && !device.online) {
      continue;
    }

    if (!device.online && device.status !== "waiting_activation") {
      problems.push({
        severity: "critical",
        title: `${device.name} offline`,
        detail: heartbeatAge === null ? "Nunca enviou sinal ao servidor." : `Ultimo sinal ha ${heartbeatAge}s.`,
        deviceId: device.id
      });
    }

    if (device.status === "waiting_activation") {
      problems.push({
        severity: "warning",
        title: `${device.name} aguardando ativacao`,
        detail: "TV cadastrada, mas o app ainda nao conectou com token.",
        deviceId: device.id
      });
    }

    if (heartbeatAge !== null && heartbeatAge > heartbeatLimitSeconds) {
      problems.push({
        severity: "critical",
        title: `${device.name} sem sinal recente`,
        detail: `Sem sinal ha ${heartbeatAge}s. Limite configurado: ${heartbeatLimitSeconds}s.`,
        deviceId: device.id
      });
    }

    if (Number(device.memoryMb || 0) > memoryLimit) {
      problems.push({
        severity: "warning",
        title: `${device.name} com memoria alta`,
        detail: `${device.memoryMb} MB acima do limite ${memoryLimit} MB.`,
        deviceId: device.id
      });
    }

    if (device.lastError) {
      problems.push({
        severity: "warning",
        title: `${device.name} reportou erro`,
        detail: friendlyError(device.lastError),
        deviceId: device.id
      });
    }

    if (Number(device.recoveryCycles || 0) > 0) {
      problems.push({
        severity: "warning",
        title: `${device.name} em recuperacao`,
        detail: `Ciclo atual: ${device.recoveryCycles}. Telas internas recriadas: ${device.webViewRecreates || 0}.`,
        deviceId: device.id
      });
    }

    const panelSilence = Number(device.lastPanelNetworkActivitySecondsAgo ?? -1);
    if (device.online && panelSilence > 120) {
      problems.push({
        severity: "warning",
        title: `${device.name} sem atualizacao interna`,
        detail: `A pagina do painel esta ha ${panelSilence}s sem sinal de rede interna. O APK 0.5.5 tenta recuperar automaticamente.`,
        deviceId: device.id
      });
    }
  }

  return problems;
}

function render() {
  const online = state.devices.filter((device) => device.online).length;
  const problems = activeProblems();
  const alerts = problems.length;
  $("#countOnline").textContent = `${online} online`;
  $("#countAlert").textContent = `${alerts} alertas`;
  $("#deviceCount").textContent = `${state.devices.length} TVs`;
  $("#eventCount").textContent = String(state.events.length);
  $("#pairingCount").textContent = String(state.pairingSessions.length);
  $("#summaryTotal").textContent = String(state.devices.length);
  $("#summaryOnline").textContent = String(online);
  $("#summaryProblems").textContent = String(alerts);
  $("#summaryPairing").textContent = String(state.pairingSessions.length);
  $("#activeAlertCount").textContent = String(alerts);

  $("#deviceRows").innerHTML = state.devices.map((device) => `
    <tr>
      <td>
        <span class="tv-name">${escapeHtml(device.name)}</span>
        <span class="small">${escapeHtml(device.location || "Sem local")} | ${escapeHtml(device.group || "Sem grupo")}</span>
        <span class="small">IP: ${escapeHtml(device.ipAddress || device.lastIpAddress || "nao informado")}</span>
        <span class="small">Monitoramento: ${escapeHtml(device.operatingLabel || "24 horas")}</span>
        <span class="small">${escapeHtml(device.id)}</span>
      </td>
      <td>${statusBadge(device)}<span class="small">v${escapeHtml(device.appVersion || "-")}</span></td>
      <td class="url-cell">${renderPanelLinkCell(device)}</td>
      <td>${escapeHtml(playerLabel(device.playerStatus))}<span class="small">Audio: ${device.audioEnabled ? "ativo" : "mudo"}</span><span class="small">Voz salva nesta TV: ${escapeHtml(voiceLabelForValue(device.ttsVoice))}</span><span class="small">Recuperacao: ${Number(device.recoveryCycles || 0)} ciclos</span></td>
      <td>${Number(device.memoryMb || 0)} MB<span class="small">${Number(device.reloads || 0)} recarregamentos</span><span class="small">${Number(device.webViewRecreates || 0)} telas reiniciadas | ${Number(device.appRestarts || 0)} reinicios</span><span class="small">Rede do painel: ${panelActivityLabel(device)}</span><span class="small">Recuperacoes automaticas: ${Number(device.panelAutoReloads || 0)}</span></td>
      <td>${elapsedHeartbeat(device.lastHeartbeat)}<span class="small">${formatTime(device.lastHeartbeat)}</span></td>
      <td>
        <div class="actions">
          <div class="actions-quick">
            <button onclick="editDevice('${device.id}')">Editar</button>
            <button onclick="sendCommand('${device.id}', 'reload_page')">Recarregar</button>
          </div>
          <button class="actions-more" onclick="openDeviceActions('${device.id}')">Mais acoes</button>
        </div>
      </td>
    </tr>
  `).join("");

  renderEventFilters();
  const filteredEvents = filteredHistoryEvents();
  $("#eventList").innerHTML = filteredEvents.slice(0, 80).map((event) => `
    <div class="event">
      <strong>${escapeHtml(friendlyEvent(event))}</strong>
      <time>${escapeHtml(friendlyType(event.type))} | ${formatTime(event.createdAt)}</time>
    </div>
  `).join("");

  $("#pairingList").innerHTML = state.pairingSessions.length
    ? state.pairingSessions.map((pairing) => `
      <div class="pairing">
        <strong>Codigo ${escapeHtml(pairing.code)}</strong>
        <span>${escapeHtml(pairing.suggestedName || "TV aguardando")} | IP ${escapeHtml(pairing.ipAddress || "nao informado")} | expira ${formatTime(pairing.expiresAt)}</span>
        <div class="pairing-actions">
          <button class="primary" onclick="openPairingApproval('${pairing.id}')">Aprovar</button>
          <a href="/pair.html?id=${encodeURIComponent(pairing.id)}" target="_blank">Abrir no celular</a>
        </div>
      </div>
    `).join("")
    : '<div class="pairing"><strong>Nenhuma TV aguardando</strong><span>Quando o app QR pedir aprovacao, aparece aqui.</span></div>';

  $("#activeAlertList").innerHTML = problems.length
    ? problems.map((problem) => `
      <div class="alert-item ${escapeHtml(problem.severity)}">
        <strong>${escapeHtml(problem.title)}</strong>
        <span>${escapeHtml(problem.detail)}</span>
      </div>
    `).join("")
    : '<div class="alert-item"><strong>Nenhum alerta ativo</strong><span>Todos os dispositivos estao dentro do esperado agora.</span></div>';
}

function renderEventFilters() {
  const select = $("#eventDeviceFilter");
  const current = select.value;
  const options = ['<option value="">Todas as TVs</option>']
    .concat(state.devices.map((device) => `<option value="${escapeHtml(device.id)}">${escapeHtml(device.name)}</option>`));
  select.innerHTML = options.join("");
  select.value = current;
}

function filteredHistoryEvents() {
  const deviceId = $("#eventDeviceFilter")?.value || "";
  const type = $("#eventTypeFilter")?.value || "";
  const date = $("#eventDateFilter")?.value || "";
  return state.events.filter((event) => {
    if (deviceId && event.deviceId !== deviceId) return false;
    if (type && !event.type.startsWith(`${type}.`)) return false;
    if (date && !String(event.createdAt || "").startsWith(date)) return false;
    return true;
  });
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function connectWs() {
  if (state.socket) state.socket.close();
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  const socket = new WebSocket(`${protocol}//${location.host}/ws/admin?token=${encodeURIComponent(adminToken())}`);
  state.socket = socket;

  socket.addEventListener("open", () => {
    $("#wsStatus").textContent = "Ao vivo";
    $("#wsStatus").className = "pill ok";
  });
  socket.addEventListener("close", () => {
    $("#wsStatus").textContent = "Reconectando";
    $("#wsStatus").className = "pill bad";
    setTimeout(connectWs, 2000);
  });
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.type === "snapshot") {
      state.devices = message.devices;
      state.events = message.events;
      state.pairingSessions = message.pairingSessions || [];
      state.settings = message.settings;
      state.serverConfig = message.serverConfig || state.serverConfig;
      render();
    }
    if (message.type === "event") {
      state.events.unshift(message.event);
      render();
    }
    if (message.type === "device_log") {
      const dev = state.devices.find(d => d.id === message.deviceId);
      if (dev) {
        if (!dev.logs) dev.logs = [];
        dev.logs.unshift(message.entry);
        if (dev.logs.length > 200) dev.logs.length = 200;
      }
      // Update log panel if open for this device
      const panel = document.getElementById("logPanel");
      if (panel && panel.dataset.deviceId === message.deviceId) {
        const el = document.getElementById("logEntries");
        if (el) {
          const row = document.createElement("div");
          row.className = "log-entry log-" + escapeHtml(message.entry.tag);
          row.innerHTML = `<time>${escapeHtml(new Date(message.entry.ts).toLocaleTimeString("pt-BR"))}</time> <b>[${escapeHtml(message.entry.tag)}]</b> ${escapeHtml(message.entry.msg)}`;
          el.prepend(row);
          while (el.children.length > 200) el.removeChild(el.lastChild);
        }
      }
    }
  });
}

async function refresh() {
  const [{ devices }, { events }, pairings, server, settings, voices] = await Promise.all([
    api("/api/devices"),
    api("/api/events"),
    api("/api/pairing").catch(() => ({ pairingSessions: [] })),
    api("/api/server-config").catch(() => ({ serverConfig: {} })),
    api("/api/settings").catch(() => ({ settings: {} })),
    api("/api/tts/voices").catch(() => ({ voices: [] }))
  ]);
  state.devices = devices;
  state.events = events;
  state.pairingSessions = pairings.pairingSessions || [];
  state.serverConfig = server.serverConfig || {};
  state.settings = settings.settings || state.settings || {};
  state.availableVoices = voices.voices || [];
  render();
}

function openDialog(device = null) {
  $("#dialogTitle").textContent = device ? "Editar TV" : "Nova TV";
  $("#editingDeviceId").value = device?.id || "";
  $("#deviceName").value = device?.name || "";
  $("#deviceLocation").value = device?.location || "";
  $("#deviceGroup").value = device?.group || "";
  $("#deviceUrl").value = device?.currentUrl || "";
  $("#deviceFallback").value = device?.fallbackUrl || "";
  $("#deviceDisplayMode").value = device?.displayMode || "panel_only";
  $("#deviceThemeId").value = device?.themeId || "classic_warm";
  $("#deviceUnitName").value = device?.unitName || "";
  $("#deviceUnitLogoUrl").value = device?.unitLogoUrl || "";
  $("#deviceUnitLogoFile").value = "";
  $("#deviceMediaUrl").value = device?.mediaUrl || "";
  $("#deviceMediaLabel").value = device?.mediaLabel || "";
  $("#deviceMediaFile").value = "";
  $("#deviceAudio").checked = device?.audioEnabled ?? true;
  populateVoiceSelect("#deviceTtsVoice", device?.ttsVoice || "");
  $("#deviceOperatingMode").value = device?.operatingMode || "always";
  $("#deviceOperatingStart").value = device?.operatingStart || "07:00";
  $("#deviceOperatingEnd").value = device?.operatingEnd || "18:00";
  setSelectedDays("#deviceOperatingDays", device?.operatingDays || "1,2,3,4,5");
  $("#activationBox").classList.add("hidden");
  $("#activationBox").textContent = "";
  updateMediaUi("device");
  updatePerDeviceBrandingUi("device");
  $("#deviceDialog").showModal();
}

function currentActionDevice() {
  return state.devices.find((item) => item.id === state.activeActionDeviceId) || null;
}

function closeDeviceActionsDialog() {
  const dialog = $("#deviceActionsDialog");
  if (dialog?.open) dialog.close();
}

window.openDeviceActions = (deviceId) => {
  const device = state.devices.find((item) => item.id === deviceId);
  if (!device) return;
  state.activeActionDeviceId = deviceId;
  $("#deviceActionsTitle").textContent = `Acoes da TV ${device.name}`;
  $("#deviceActionsInfo").textContent = `${device.location || "Sem local"} | ${displayModeLabel(device.displayMode)} | ${device.online ? "Online" : "Offline"}`;
  $("#actionToggleAudioBtn").textContent = device.audioEnabled ? "Mutar audio da TV" : "Ativar audio da TV";
  $("#deviceActionsDialog").showModal();
};

window.editDevice = (deviceId) => {
  const device = state.devices.find((item) => item.id === deviceId);
  if (device) openDialog(device);
  closeDeviceActionsDialog();
};

window.editDeviceVoice = (deviceId) => {
  const device = state.devices.find((item) => item.id === deviceId);
  if (!device) return;
  openDialog(device);
  closeDeviceActionsDialog();
  setTimeout(() => {
    const select = $("#deviceTtsVoice");
    if (select) select.focus();
  }, 30);
};

window.previewDeviceVoice = async (deviceId) => {
  const device = state.devices.find((item) => item.id === deviceId);
  if (!device) return;
  closeDeviceActionsDialog();
  try {
    await api("/api/tts/preview", {
      method: "POST",
      body: JSON.stringify({
        voice: device.ttsVoice || "",
        text: `Teste de voz da TV ${device.name}.`,
        rate: 1
      })
    });
    alert(`Teste enviado usando a voz: ${voiceLabelForValue(device.ttsVoice)}.`);
  } catch (error) {
    alert(`Nao foi possivel ouvir a voz: ${error.message}`);
  }
};

window.showDeviceLogs = (deviceId) => {
  const device = state.devices.find(d => d.id === deviceId);
  if (!device) return;
  closeDeviceActionsDialog();
  let panel = document.getElementById("logPanel");
  if (!panel) {
    panel = document.createElement("div");
    panel.id = "logPanel";
    panel.style.cssText = "position:fixed;bottom:0;left:0;right:0;height:320px;background:#0d1117;color:#e6edf3;font-family:monospace;font-size:12px;overflow:hidden;display:flex;flex-direction:column;z-index:9999;border-top:2px solid #30363d";
    const header = document.createElement("div");
    header.style.cssText = "display:flex;align-items:center;justify-content:space-between;padding:6px 12px;background:#161b22;border-bottom:1px solid #30363d;flex-shrink:0";
    header.innerHTML = `<span id="logPanelTitle" style="font-weight:bold">Logs TTS</span><div><button onclick="document.getElementById('logEntries').innerHTML=''" style="margin-right:8px;font-size:11px">Limpar</button><button onclick="document.getElementById('logPanel').remove()" style="font-size:11px">Fechar</button></div>`;
    const entries = document.createElement("div");
    entries.id = "logEntries";
    entries.style.cssText = "flex:1;overflow-y:auto;padding:6px 12px";
    panel.appendChild(header);
    panel.appendChild(entries);
    document.body.appendChild(panel);
  }
  panel.dataset.deviceId = deviceId;
  document.getElementById("logPanelTitle").textContent = `Logs TTS — ${device.name}`;
  const el = document.getElementById("logEntries");
  el.innerHTML = (device.logs || []).map(e => {
    const tag = escapeHtml(e.tag);
    const color = e.tag === "TTS_ERR" ? "#ff7b72" : e.tag === "TTS_OK" ? "#7ee787" : e.tag === "CALL" ? "#79c0ff" : e.tag === "SUPPRESS" ? "#d29922" : "#8b949e";
    return `<div class="log-entry" style="margin:1px 0;color:${color}"><time style="color:#8b949e">${escapeHtml(new Date(e.ts).toLocaleTimeString("pt-BR"))}</time> <b>[${tag}]</b> ${escapeHtml(e.msg)}</div>`;
  }).join("");
};

window.sendCommand = async (deviceId, command, payload = {}) => {
  const result = await api(`/api/devices/${deviceId}/commands`, {
    method: "POST",
    body: JSON.stringify({ command, payload })
  });
  if (!result.delivered) {
    alert("Comando registrado, mas a TV nao esta conectada agora. Ele nao foi entregue.");
  }
  return result;
};

window.enterMaintenanceMode = async (deviceId) => {
  const device = state.devices.find((item) => item.id === deviceId);
  if (!device) return;
  closeDeviceActionsDialog();
  const confirmed = confirm(`Colocar "${device.name}" em modo de manutencao?\n\nA TV vai encerrar o aplicativo e ele nao deve reabrir sozinho ate voce abrir novamente ou atualizar o APK.`);
  if (!confirmed) return;
  try {
    const result = await sendCommand(deviceId, "enter_maintenance");
    alert(result.delivered
      ? "Modo de manutencao enviado. O app da TV vai encerrar para permitir a troca do APK."
      : "Comando registrado, mas a TV nao estava conectada para receber agora.");
  } catch (error) {
    alert(`Nao foi possivel ativar o modo de manutencao: ${error.message}`);
  }
};

window.toggleAudio = async (deviceId) => {
  const device = state.devices.find((item) => item.id === deviceId);
  if (!device) return;
  closeDeviceActionsDialog();
  const nextAudioState = !device.audioEnabled;
  device.audioEnabled = nextAudioState;
  render();
  await api(`/api/devices/${deviceId}`, {
    method: "PATCH",
    body: JSON.stringify({ audioEnabled: nextAudioState })
  });
  const result = await sendCommand(deviceId, "set_audio", { audioEnabled: nextAudioState });
  if (result.delivered) {
    alert(nextAudioState ? "Audio ativado na TV." : "Audio mutado na TV.");
  }
  await refresh();
};

window.deleteDevice = async (deviceId) => {
  const device = state.devices.find((item) => item.id === deviceId);
  if (!device) return;
  closeDeviceActionsDialog();
  $("#deletingDeviceId").value = device.id;
  $("#deleteMessage").textContent = `Tem certeza que deseja excluir "${device.name}"? Essa acao remove o cadastro e o token de ativacao.`;
  $("#deleteDialog").showModal();
};

window.openPairingApproval = async (pairingId) => {
  const { pairing } = await api(`/api/pairing/${pairingId}`);
  $("#approvingPairingId").value = pairing.id;
  $("#pairingApproveInfo").textContent = `Codigo ${pairing.code} | IP ${pairing.ipAddress || "nao informado"} | App ${pairing.appVersion || "-"}`;
  $("#pairingDeviceName").value = pairing.suggestedName || `TV ${pairing.code}`;
  $("#pairingDeviceLocation").value = "";
  $("#pairingDeviceGroup").value = "";
  $("#pairingDeviceUrl").value = "about:blank";
  $("#pairingDeviceFallback").value = "about:blank";
  $("#pairingDisplayMode").value = "panel_only";
  $("#pairingThemeId").value = "classic_warm";
  $("#pairingUnitName").value = "";
  $("#pairingUnitLogoUrl").value = "";
  $("#pairingUnitLogoFile").value = "";
  $("#pairingMediaUrl").value = "";
  $("#pairingMediaLabel").value = "";
  $("#pairingMediaFile").value = "";
  $("#pairingDeviceAudio").checked = true;
  populateVoiceSelect("#pairingTtsVoice", "");
  $("#pairingOperatingMode").value = "always";
  $("#pairingOperatingStart").value = "07:00";
  $("#pairingOperatingEnd").value = "18:00";
  setSelectedDays("#pairingOperatingDays", "1,2,3,4,5");
  updateMediaUi("pairing");
  updatePerDeviceBrandingUi("pairing");
  $("#approvePairingDialog").showModal();
};

$("#confirmApprovePairingBtn").addEventListener("click", async (event) => {
  event.preventDefault();
  const pairingId = $("#approvingPairingId").value;
  $("#confirmApprovePairingBtn").disabled = true;
  try {
    await api(`/api/pairing/${pairingId}/approve`, {
      method: "POST",
      body: JSON.stringify({
        name: $("#pairingDeviceName").value,
        location: $("#pairingDeviceLocation").value,
        group: $("#pairingDeviceGroup").value,
        currentUrl: $("#pairingDeviceUrl").value || "about:blank",
        fallbackUrl: $("#pairingDeviceFallback").value || "about:blank",
        themeId: $("#pairingThemeId").value || "classic_warm",
        displayMode: $("#pairingDisplayMode").value || "panel_only",
        unitName: $("#pairingUnitName").value || "",
        unitLogoUrl: $("#pairingUnitLogoUrl").value || "",
        mediaUrl: $("#pairingMediaUrl").value || "",
        mediaLabel: $("#pairingMediaLabel").value || "",
        audioEnabled: $("#pairingDeviceAudio").checked,
        ttsVoice: $("#pairingTtsVoice").value || "",
        operatingMode: $("#pairingOperatingMode").value,
        operatingStart: $("#pairingOperatingStart").value || "07:00",
        operatingEnd: $("#pairingOperatingEnd").value || "18:00",
        operatingDays: selectedDays("#pairingOperatingDays") || "1,2,3,4,5"
      })
    });
    $("#approvePairingDialog").close();
    await refresh();
  } catch (error) {
    alert(`Erro ao aprovar: ${error.message}`);
  } finally {
    $("#confirmApprovePairingBtn").disabled = false;
  }
});

$("#confirmDeleteBtn").addEventListener("click", async (event) => {
  event.preventDefault();
  const deviceId = $("#deletingDeviceId").value;
  if (!deviceId) return;
  $("#confirmDeleteBtn").disabled = true;
  try {
    await api(`/api/devices/${deviceId}`, { method: "DELETE" });
    $("#deleteDialog").close();
    await refresh();
  } catch (error) {
    alert(`Erro ao excluir: ${error.message}`);
  } finally {
    $("#confirmDeleteBtn").disabled = false;
  }
});

$("#newDeviceBtn").addEventListener("click", () => openDialog());
$("#refreshBtn").addEventListener("click", refresh);
$("#serverBtn").addEventListener("click", () => {
  const config = state.serverConfig || {};
  $("#serverPort").value = config.configuredPort || config.port || location.port || 8787;
  $("#serverInfo").textContent = `Porta atual: ${config.port || location.port || 8787}. Porta configurada: ${config.configuredPort || config.port || 8787}.`;
  $("#serverStatus").textContent = config.envPortLocked
    ? "Aviso: esta execucao recebeu PORT por variavel de ambiente. Remova essa variavel para a porta salva no painel assumir."
    : "";
  $("#serverDialog").showModal();
});

$("#saveServerBtn").addEventListener("click", async (event) => {
  event.preventDefault();
  const port = Number($("#serverPort").value);
  try {
    const { serverConfig } = await api("/api/server-config", {
      method: "PATCH",
      body: JSON.stringify({ port })
    });
    state.serverConfig = serverConfig;
    $("#serverStatus").textContent = serverConfig.pendingRestart
      ? `Porta ${port} salva. Clique em Reiniciar servidor para aplicar.`
      : `Porta ${port} salva e ja esta em uso.`;
  } catch (error) {
    $("#serverStatus").textContent = `Erro: ${error.message}`;
  }
});

$("#restartServerBtn").addEventListener("click", async (event) => {
  event.preventDefault();
  const port = Number($("#serverPort").value);
  await api("/api/server-config", { method: "PATCH", body: JSON.stringify({ port }) });
  $("#serverStatus").textContent = "Reiniciando... aguarde alguns segundos.";
  await api("/api/server/restart", { method: "POST", body: JSON.stringify({}) }).catch(() => {});
  const nextUrl = `${location.protocol}//${location.hostname}:${port}/`;
  setTimeout(() => {
    location.href = nextUrl;
  }, 3500);
});

$("#telegramBtn").addEventListener("click", () => {
  const telegram = state.settings.telegram || {};
  $("#telegramEnabled").checked = Boolean(telegram.enabled);
  $("#telegramBotToken").value = "";
  $("#telegramBotToken").placeholder = telegram.botTokenConfigured ? "Token ja configurado; preencha apenas para trocar" : "123456:ABC...";
  $("#telegramChatId").value = telegram.chatId || "";
  $("#telegramStatus").textContent = "";
  $("#telegramDialog").showModal();
});

$("#healthBtn").addEventListener("click", () => {
  $("#healthUnitName").value = state.settings.unitName || "";
  $("#healthUnitLogoUrl").value = state.settings.unitLogoUrl || "";
  $("#healthUnitLogoFile").value = "";
  $("#healthHeartbeat").value = state.settings.heartbeatIntervalSeconds || 15;
  $("#healthAutoRefresh").value = state.settings.autoRefreshSeconds ?? 0;
  $("#healthPreventiveReload").value = state.settings.preventiveReloadHours ?? 12;
  $("#healthMemoryLimit").value = state.settings.memoryLimitMb || 650;
  $("#healthPageTimeout").value = state.settings.pageOfflineTimeoutSeconds || 60;
  $("#healthFallbackUrl").value = state.settings.fallbackUrl || "about:blank";
  $("#healthStatus").textContent = "";
  updateUnitBrandingUi();
  $("#healthDialog").showModal();
});

$("#saveHealthBtn").addEventListener("click", async (event) => {
  event.preventDefault();
  try {
    await api("/api/settings", {
      method: "PATCH",
      body: JSON.stringify({
        heartbeatIntervalSeconds: Number($("#healthHeartbeat").value || 15),
        autoRefreshSeconds: Number($("#healthAutoRefresh").value || 0),
        preventiveReloadHours: Number($("#healthPreventiveReload").value || 0),
        memoryLimitMb: Number($("#healthMemoryLimit").value || 650),
        pageOfflineTimeoutSeconds: Number($("#healthPageTimeout").value || 60),
        fallbackUrl: $("#healthFallbackUrl").value || "about:blank",
        unitName: $("#healthUnitName").value || "",
        unitLogoUrl: $("#healthUnitLogoUrl").value || ""
      })
    });
    $("#healthStatus").textContent = "Configuracao salva e enviada para as TVs conectadas.";
    await refresh();
  } catch (error) {
    $("#healthStatus").textContent = `Erro: ${error.message}`;
  }
});

$("#healthUnitName")?.addEventListener("input", updateUnitBrandingUi);
$("#healthUnitLogoUrl")?.addEventListener("input", updateUnitBrandingUi);
  $("#healthUnitLogoClearBtn")?.addEventListener("click", () => {
  $("#healthUnitLogoUrl").value = "";
  $("#healthUnitLogoFile").value = "";
  updateUnitBrandingUi();
});
$("#healthUnitLogoUploadBtn")?.addEventListener("click", async () => {
  const file = $("#healthUnitLogoFile").files?.[0];
  if (!file) {
    $("#healthUnitLogoStatus").textContent = "Escolha um arquivo PNG, JPG ou WEBP antes de enviar.";
    return;
  }
  try {
    $("#healthUnitLogoStatus").textContent = `Enviando ${file.name}...`;
    const response = await fetch(`/api/media/upload?token=${encodeURIComponent(adminToken())}`, {
      method: "POST",
      headers: {
        "content-type": file.type || "application/octet-stream",
        "x-file-name": file.name
      },
      body: file
    });
    const body = await response.json();
    if (!response.ok) {
      throw new Error(body.error || "Falha no upload");
    }
    $("#healthUnitLogoUrl").value = body.media.mediaUrl || "";
    updateUnitBrandingUi();
  } catch (error) {
    $("#healthUnitLogoStatus").textContent = `Erro no upload: ${error.message}`;
  }
});

$("#manualBtn").addEventListener("click", () => {
  $("#manualDialog").showModal();
});

$("#backupBtn").addEventListener("click", () => {
  $("#backupStatus").textContent = "";
  $("#backupFileInput").value = "";
  $("#backupDialog").showModal();
});

$("#exportBackupBtn").addEventListener("click", () => {
  $("#backupStatus").textContent = "Gerando arquivo de backup...";
  window.location.href = `/api/backup?token=${encodeURIComponent(adminToken())}`;
  setTimeout(() => {
    $("#backupStatus").textContent = "Backup baixado. Guarde esse arquivo em local seguro.";
  }, 700);
});

$("#restoreBackupBtn").addEventListener("click", async () => {
  const file = $("#backupFileInput").files?.[0];
  if (!file) {
    $("#backupStatus").textContent = "Selecione um arquivo .json de backup primeiro.";
    return;
  }
  const confirmed = confirm("Restaurar este backup vai substituir os cadastros e configuracoes atuais. Deseja continuar?");
  if (!confirmed) return;

  $("#restoreBackupBtn").disabled = true;
  $("#backupStatus").textContent = "Restaurando backup...";
  try {
    const text = await file.text();
    const payload = JSON.parse(text);
    const result = await api("/api/backup/restore", {
      method: "POST",
      body: JSON.stringify(payload)
    });
    $("#backupStatus").textContent = result.restartRecommended
      ? `Backup restaurado com ${result.devices} TVs. Reinicie o servidor se a porta do backup for diferente.`
      : `Backup restaurado com ${result.devices} TVs.`;
    await refresh();
  } catch (error) {
    $("#backupStatus").textContent = `Erro ao restaurar: ${error.message}`;
  } finally {
    $("#restoreBackupBtn").disabled = false;
  }
});

$("#saveTelegramBtn").addEventListener("click", async (event) => {
  event.preventDefault();
  const telegram = {
    enabled: $("#telegramEnabled").checked,
    chatId: $("#telegramChatId").value.trim()
  };
  const botToken = $("#telegramBotToken").value.trim();
  if (botToken) telegram.botToken = botToken;
  await api("/api/settings", {
    method: "PATCH",
    body: JSON.stringify({ telegram })
  });
  $("#telegramStatus").textContent = "Configuracao salva.";
  await refresh();
});

$("#testTelegramBtn").addEventListener("click", async (event) => {
  event.preventDefault();
  $("#telegramStatus").textContent = "Enviando teste...";
  try {
    await api("/api/telegram/test", { method: "POST", body: JSON.stringify({}) });
    $("#telegramStatus").textContent = "Mensagem de teste enviada.";
  } catch (error) {
    $("#telegramStatus").textContent = `Erro no teste: ${error.message}`;
  }
});

$("#eventDeviceFilter").addEventListener("change", render);
$("#eventTypeFilter").addEventListener("change", render);
$("#eventDateFilter").addEventListener("change", render);
$("#clearEventFiltersBtn").addEventListener("click", () => {
  $("#eventDeviceFilter").value = "";
  $("#eventTypeFilter").value = "";
  $("#eventDateFilter").value = "";
  render();
});

$("#actionReloadBtn")?.addEventListener("click", async () => {
  const device = currentActionDevice();
  if (!device) return;
  closeDeviceActionsDialog();
  await sendCommand(device.id, "reload_page");
});

$("#actionRecreateBtn")?.addEventListener("click", async () => {
  const device = currentActionDevice();
  if (!device) return;
  closeDeviceActionsDialog();
  await sendCommand(device.id, "recreate_webview");
});

$("#actionRestartAppBtn")?.addEventListener("click", async () => {
  const device = currentActionDevice();
  if (!device) return;
  closeDeviceActionsDialog();
  await sendCommand(device.id, "restart_app");
});

$("#actionMaintenanceBtn")?.addEventListener("click", async () => {
  const device = currentActionDevice();
  if (!device) return;
  await window.enterMaintenanceMode(device.id);
});

$("#actionClearCacheBtn")?.addEventListener("click", async () => {
  const device = currentActionDevice();
  if (!device) return;
  closeDeviceActionsDialog();
  await sendCommand(device.id, "clear_cache");
});

$("#actionLogsBtn")?.addEventListener("click", () => {
  const device = currentActionDevice();
  if (!device) return;
  window.showDeviceLogs(device.id);
});

$("#actionToggleAudioBtn")?.addEventListener("click", async () => {
  const device = currentActionDevice();
  if (!device) return;
  await window.toggleAudio(device.id);
});

$("#actionEditVoiceBtn")?.addEventListener("click", () => {
  const device = currentActionDevice();
  if (!device) return;
  window.editDeviceVoice(device.id);
});

$("#actionPreviewVoiceBtn")?.addEventListener("click", async () => {
  const device = currentActionDevice();
  if (!device) return;
  await window.previewDeviceVoice(device.id);
});

$("#actionEditDeviceBtn")?.addEventListener("click", () => {
  const device = currentActionDevice();
  if (!device) return;
  window.editDevice(device.id);
});

$("#actionDeleteDeviceBtn")?.addEventListener("click", () => {
  const device = currentActionDevice();
  if (!device) return;
  window.deleteDevice(device.id);
});

document.querySelectorAll("[data-close-dialog]").forEach((button) => {
  button.addEventListener("click", () => {
    const dialog = document.getElementById(button.dataset.closeDialog);
    if (dialog) dialog.close();
  });
});

$("#saveTokenBtn").addEventListener("click", () => {
  localStorage.setItem("adminToken", adminToken());
  alert("Token admin salvo neste computador.");
});
tokenInput.addEventListener("change", () => {
  localStorage.setItem("adminToken", adminToken());
  refresh().catch(alert);
  connectWs();
});

["device", "pairing"].forEach((prefix) => {
  $(`#${prefix}DisplayMode`)?.addEventListener("change", () => updateMediaUi(prefix));
  $(`#${prefix}MediaUrl`)?.addEventListener("input", () => updateMediaUi(prefix));
  $(`#${prefix}MediaLabel`)?.addEventListener("input", () => updateMediaUi(prefix));
  $(`#${prefix}UnitName`)?.addEventListener("input", () => updatePerDeviceBrandingUi(prefix));
  $(`#${prefix}UnitLogoUrl`)?.addEventListener("input", () => updatePerDeviceBrandingUi(prefix));
  $(`#${prefix}UnitLogoClearBtn`)?.addEventListener("click", () => {
    $(`#${prefix}UnitLogoUrl`).value = "";
    $(`#${prefix}UnitLogoFile`).value = "";
    updatePerDeviceBrandingUi(prefix);
  });
  $(`#${prefix}UnitLogoUploadBtn`)?.addEventListener("click", async () => {
    const file = $(`#${prefix}UnitLogoFile`).files?.[0];
    if (!file) {
      $(`#${prefix}UnitLogoStatus`).textContent = "Escolha um arquivo PNG, JPG ou WEBP antes de enviar.";
      return;
    }
    try {
      await uploadBrandingFile(file, prefix);
    } catch (error) {
      $(`#${prefix}UnitLogoStatus`).textContent = `Erro no upload: ${error.message}`;
    }
  });
  $(`#${prefix}MediaClearBtn`)?.addEventListener("click", () => {
    $(`#${prefix}MediaUrl`).value = "";
    $(`#${prefix}MediaLabel`).value = "";
    $(`#${prefix}MediaFile`).value = "";
    updateMediaUi(prefix);
  });
  $(`#${prefix}MediaUploadBtn`)?.addEventListener("click", async () => {
    const file = $(`#${prefix}MediaFile`).files?.[0];
    if (!file) {
      $(`#${prefix}MediaStatus`).textContent = "Escolha um arquivo de video antes de enviar.";
      return;
    }
    try {
      await uploadMediaFile(file, prefix);
    } catch (error) {
      $(`#${prefix}MediaStatus`).textContent = `Erro no upload: ${error.message}`;
    }
  });
});

$("#saveDeviceBtn").addEventListener("click", async (event) => {
  event.preventDefault();
  const id = $("#editingDeviceId").value;
  const payload = {
    name: $("#deviceName").value,
    location: $("#deviceLocation").value,
    group: $("#deviceGroup").value,
    currentUrl: $("#deviceUrl").value || "about:blank",
    fallbackUrl: $("#deviceFallback").value || "about:blank",
    themeId: $("#deviceThemeId").value || "classic_warm",
    displayMode: $("#deviceDisplayMode").value || "panel_only",
    unitName: $("#deviceUnitName").value || "",
    unitLogoUrl: $("#deviceUnitLogoUrl").value || "",
    mediaUrl: $("#deviceMediaUrl").value || "",
    mediaLabel: $("#deviceMediaLabel").value || "",
    audioEnabled: $("#deviceAudio").checked,
    ttsVoice: $("#deviceTtsVoice").value || "",
    operatingMode: $("#deviceOperatingMode").value,
    operatingStart: $("#deviceOperatingStart").value || "07:00",
    operatingEnd: $("#deviceOperatingEnd").value || "18:00",
    operatingDays: selectedDays("#deviceOperatingDays") || "1,2,3,4,5"
  };
  if (id) {
    await api(`/api/devices/${id}`, { method: "PATCH", body: JSON.stringify(payload) });
    $("#deviceDialog").close();
    return;
  }
  const { device } = await api("/api/devices", { method: "POST", body: JSON.stringify(payload) });
  $("#activationBox").classList.remove("hidden");
  $("#activationBox").textContent = `Token de ativacao: ${device.activationToken}`;
  await refresh();
});

refresh().catch((error) => alert(error.message));
connectWs();
setInterval(render, 1000);
