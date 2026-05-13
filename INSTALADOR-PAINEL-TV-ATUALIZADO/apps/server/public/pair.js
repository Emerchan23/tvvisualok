const params = new URLSearchParams(location.search);
const pairingId = params.get("id");
const statusEl = document.querySelector("#pairStatus");
const resultEl = document.querySelector("#result");
const approveBtn = document.querySelector("#approveBtn");

function adminToken() {
  return document.querySelector("#adminToken").value.trim() || "admin123";
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

async function loadPairing() {
  if (!pairingId) {
    statusEl.textContent = "QR Code invalido: identificador ausente.";
    approveBtn.disabled = true;
    return;
  }
  try {
    const { pairing } = await api(`/api/pairing/${pairingId}`);
    statusEl.textContent = `Codigo ${pairing.code} - ${pairing.status} - IP ${pairing.ipAddress || "nao informado"} - app ${pairing.appVersion || "-"}`;
    document.querySelector("#tvName").value = pairing.suggestedName || `TV ${pairing.code}`;
  } catch (error) {
    statusEl.textContent = `Nao foi possivel carregar: ${error.message}`;
    approveBtn.disabled = true;
  }
}

approveBtn.addEventListener("click", async () => {
  approveBtn.disabled = true;
  resultEl.className = "result";
  resultEl.textContent = "Aprovando...";
  try {
    await api(`/api/pairing/${pairingId}/approve`, {
      method: "POST",
      body: JSON.stringify({
        name: document.querySelector("#tvName").value,
        location: document.querySelector("#tvLocation").value,
        group: document.querySelector("#tvGroup").value,
        currentUrl: document.querySelector("#tvUrl").value || "about:blank",
        fallbackUrl: document.querySelector("#tvFallback").value || "about:blank",
        audioEnabled: document.querySelector("#tvAudio").checked
      })
    });
    resultEl.className = "result ok";
    resultEl.textContent = "TV aprovada. Ela deve conectar automaticamente em alguns segundos.";
  } catch (error) {
    approveBtn.disabled = false;
    resultEl.className = "result bad";
    resultEl.textContent = `Erro: ${error.message}`;
  }
});

loadPairing();
