(() => {
  "use strict";

  let token = "";
  let principal = null;
  let devices = [];

  const $ = (id) => document.getElementById(id);
  const loginCard = $("loginCard");
  const dashboard = $("dashboard");
  const identity = $("identity");
  const dialog = $("deviceDialog");
  const form = $("deviceForm");
  const grid = $("deviceGrid");
  const emptyState = $("emptyState");
  const statusLine = $("statusLine");

  function roleCan(permission) {
    const role = principal?.role;
    if (permission === "delete") return role === "admin";
    if (permission === "write") return role === "admin" || role === "operator";
    return Boolean(role);
  }

  function authHeaders(extra = {}) {
    return { ...extra, Authorization: `Bearer ${token}` };
  }

  async function api(path, options = {}) {
    const response = await fetch(path, {
      ...options,
      headers: authHeaders(options.headers || {}),
    });
    if (response.status === 204) return null;
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      const message = typeof body.detail === "string" ? body.detail : `Błąd HTTP ${response.status}`;
      const error = new Error(message);
      error.status = response.status;
      throw error;
    }
    return body;
  }

  function toast(message) {
    const node = $("toast");
    node.textContent = message;
    node.hidden = false;
    window.setTimeout(() => { node.hidden = true; }, 3200);
  }

  function setBusy(message = "") {
    statusLine.textContent = message;
  }

  function escapeText(value) {
    const span = document.createElement("span");
    span.textContent = value ?? "";
    return span.innerHTML;
  }

  function statusClass(status) {
    return String(status || "").toLowerCase().replace(/[^a-ząćęłńóśźż0-9-]/g, "");
  }

  function updateStats() {
    $("totalCount").textContent = String(devices.length);
    $("onlineCount").textContent = String(devices.filter((d) => String(d.status).toLowerCase() === "online").length);
    $("offlineCount").textContent = String(devices.filter((d) => String(d.status).toLowerCase() === "offline").length);
    $("agentCount").textContent = String(devices.filter((d) => d.agent_id).length);
  }

  function filteredDevices() {
    const query = $("searchInput").value.trim().toLowerCase();
    const status = $("statusFilter").value.trim().toLowerCase();
    return devices.filter((device) => {
      const haystack = [device.name, device.device_type, device.status, device.hostname, device.platform]
        .filter(Boolean)
        .join(" ")
        .toLowerCase();
      const queryMatch = !query || haystack.includes(query);
      const statusMatch = !status || String(device.status).toLowerCase() === status;
      return queryMatch && statusMatch;
    });
  }

  function openEditor(device = null) {
    if (!roleCan("write")) return;
    $("dialogTitle").textContent = device ? "Edytuj urządzenie" : "Dodaj urządzenie";
    $("deviceId").value = device?.id ?? "";
    $("deviceName").value = device?.name ?? "";
    $("deviceType").value = device?.device_type ?? "";
    $("deviceStatus").value = device?.status ?? "Online";
    $("deleteBtn").hidden = !device || !roleCan("delete");
    dialog.showModal();
    $("deviceName").focus();
  }

  function render() {
    updateStats();
    const visible = filteredDevices();
    grid.replaceChildren();
    emptyState.hidden = visible.length !== 0;

    visible.forEach((device) => {
      const article = document.createElement("article");
      article.className = "device-card";
      article.dataset.deviceId = String(device.id);
      const lastSeen = device.last_seen_at ? new Date(device.last_seen_at).toLocaleString("pl-PL") : "—";
      article.innerHTML = `
        <header>
          <div><p class="eyebrow">#${device.id} · ${escapeText(device.device_type)}</p><h3>${escapeText(device.name)}</h3></div>
          <span class="badge ${statusClass(device.status)}">${escapeText(device.status)}</span>
        </header>
        <dl class="meta">
          <dt>Host</dt><dd>${escapeText(device.hostname || "—")}</dd>
          <dt>Platforma</dt><dd>${escapeText(device.platform || "—")}</dd>
          <dt>Agent</dt><dd>${device.agent_id ? "połączony" : "brak"}</dd>
          <dt>Ostatnio</dt><dd>${escapeText(lastSeen)}</dd>
        </dl>
        <div class="card-actions"></div>`;
      if (roleCan("write")) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "ghost";
        button.textContent = "Edytuj";
        button.addEventListener("click", () => openEditor(device));
        article.querySelector(".card-actions").append(button);
      }
      grid.append(article);
    });

    setBusy(`${visible.length} z ${devices.length} urządzeń`);
  }

  async function loadDevices() {
    setBusy("Ładowanie urządzeń…");
    try {
      devices = await api("/devices");
      render();
    } catch (error) {
      setBusy(`Nie udało się pobrać urządzeń: ${error.message}`);
      if (error.status === 401) logout();
    }
  }

  async function connect(rawToken) {
    token = rawToken.trim();
    if (!token) return;
    try {
      principal = await api("/me");
      $("identityName").textContent = principal.name;
      $("identityRole").textContent = principal.role;
      identity.hidden = false;
      loginCard.hidden = true;
      dashboard.hidden = false;
      $("addBtn").hidden = !roleCan("write");
      $("tokenInput").value = "";
      await loadDevices();
    } catch (error) {
      token = "";
      principal = null;
      toast(`Logowanie nieudane: ${error.message}`);
    }
  }

  function logout() {
    token = "";
    principal = null;
    devices = [];
    dashboard.hidden = true;
    identity.hidden = true;
    loginCard.hidden = false;
    grid.replaceChildren();
    $("tokenInput").focus();
  }

  async function saveDevice(event) {
    event.preventDefault();
    if (!roleCan("write")) return;
    const id = $("deviceId").value;
    const body = {
      name: $("deviceName").value.trim(),
      device_type: $("deviceType").value.trim(),
      status: $("deviceStatus").value.trim(),
    };
    try {
      if (id) {
        await api(`/devices/${id}`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        });
        toast("Urządzenie zaktualizowane");
      } else {
        await api("/devices", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        });
        toast("Urządzenie dodane");
      }
      dialog.close();
      await loadDevices();
    } catch (error) {
      toast(`Nie zapisano: ${error.message}`);
    }
  }

  async function deleteCurrent() {
    const id = $("deviceId").value;
    if (!id || !roleCan("delete")) return;
    const name = $("deviceName").value;
    if (!window.confirm(`Usunąć urządzenie „${name}”?`)) return;
    try {
      await api(`/devices/${id}`, { method: "DELETE" });
      dialog.close();
      toast("Urządzenie usunięte");
      await loadDevices();
    } catch (error) {
      toast(`Nie usunięto: ${error.message}`);
    }
  }

  $("loginForm").addEventListener("submit", (event) => {
    event.preventDefault();
    connect($("tokenInput").value);
  });
  $("logoutBtn").addEventListener("click", logout);
  $("refreshBtn").addEventListener("click", loadDevices);
  $("addBtn").addEventListener("click", () => openEditor());
  $("searchInput").addEventListener("input", render);
  $("statusFilter").addEventListener("change", render);
  $("closeDialogBtn").addEventListener("click", () => dialog.close());
  $("cancelBtn").addEventListener("click", () => dialog.close());
  $("deleteBtn").addEventListener("click", deleteCurrent);
  form.addEventListener("submit", saveDevice);
})();
