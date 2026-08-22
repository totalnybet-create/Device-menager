(() => {
  "use strict";

  const REQUEST_TIMEOUT_MS = 12000;

  let token = "";
  let principal = null;
  let devices = [];
  let connectPending = false;
  let mutationPending = false;
  let loadGeneration = 0;
  let toastTimer = null;

  const $ = (id) => document.getElementById(id);
  const loginCard = $("loginCard");
  const dashboard = $("dashboard");
  const identity = $("identity");
  const dialog = $("deviceDialog");
  const form = $("deviceForm");
  const grid = $("deviceGrid");
  const emptyState = $("emptyState");
  const statusLine = $("statusLine");
  const loginSubmit = $("loginForm").querySelector('button[type="submit"]');
  const saveButton = form.querySelector('button[type="submit"]');

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
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

    try {
      const response = await fetch(path, {
        ...options,
        signal: controller.signal,
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
    } catch (error) {
      if (error.name === "AbortError") {
        const timeoutError = new Error("Przekroczono limit czasu połączenia");
        timeoutError.status = 0;
        throw timeoutError;
      }
      throw error;
    } finally {
      window.clearTimeout(timeout);
    }
  }

  function toast(message) {
    const node = $("toast");
    if (toastTimer !== null) window.clearTimeout(toastTimer);
    node.textContent = message;
    node.hidden = false;
    toastTimer = window.setTimeout(() => {
      node.hidden = true;
      toastTimer = null;
    }, 3200);
  }

  function setBusy(message = "") {
    statusLine.textContent = message;
  }

  function setMutationBusy(busy) {
    mutationPending = busy;
    saveButton.disabled = busy;
    $("deleteBtn").disabled = busy;
    $("closeDialogBtn").disabled = busy;
    $("cancelBtn").disabled = busy;
    $("addBtn").disabled = busy;
  }

  function handleAuthenticatedError(prefix, error) {
    if (error.status === 401 && principal) {
      logout();
      toast("Sesja wygasła. Wklej token ponownie.");
      return;
    }
    toast(`${prefix}: ${error.message}`);
  }

  function statusClass(status) {
    return String(status || "").toLowerCase().replace(/[^a-ząćęłńóśźż0-9-]/g, "");
  }

  function formatTimestamp(value) {
    if (!value) return "—";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString("pl-PL");
  }

  function element(tag, className = "", text = "") {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== "") node.textContent = text;
    return node;
  }

  function appendMeta(list, label, value) {
    list.append(element("dt", "", label), element("dd", "", value || "—"));
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
      const haystack = [
        device.name,
        device.device_type,
        device.status,
        device.hostname,
        device.platform,
        device.agent_version,
      ]
        .filter(Boolean)
        .join(" ")
        .toLowerCase();
      const queryMatch = !query || haystack.includes(query);
      const statusMatch = !status || String(device.status).toLowerCase() === status;
      return queryMatch && statusMatch;
    });
  }

  function openEditor(device = null) {
    if (!roleCan("write") || mutationPending) return;
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
      const article = element("article", "device-card");
      article.dataset.deviceId = String(device.id);

      const header = element("header");
      const heading = element("div");
      heading.append(
        element("p", "eyebrow", `#${device.id} · ${device.device_type}`),
        element("h3", "", device.name),
      );
      const badge = element("span", `badge ${statusClass(device.status)}`, device.status);
      header.append(heading, badge);

      const meta = element("dl", "meta");
      appendMeta(meta, "Host", device.hostname || "—");
      appendMeta(meta, "Platforma", device.platform || "—");
      appendMeta(meta, "Agent", device.agent_id ? "połączony" : "brak");
      appendMeta(meta, "Wersja agenta", device.agent_version || "—");
      appendMeta(meta, "Ostatnio", formatTimestamp(device.last_seen_at));
      appendMeta(meta, "Utworzono", formatTimestamp(device.created_at));
      appendMeta(meta, "Zmieniono", formatTimestamp(device.updated_at));

      const actions = element("div", "card-actions");
      if (roleCan("write")) {
        const button = element("button", "ghost", "Edytuj");
        button.type = "button";
        button.addEventListener("click", () => openEditor(device));
        actions.append(button);
      }

      article.append(header, meta, actions);
      grid.append(article);
    });

    setBusy(`${visible.length} z ${devices.length} urządzeń`);
  }

  async function loadDevices() {
    if (!principal) return;
    const generation = ++loadGeneration;
    $("refreshBtn").disabled = true;
    setBusy("Ładowanie urządzeń…");
    try {
      const nextDevices = await api("/devices");
      if (generation !== loadGeneration || !principal) return;
      devices = nextDevices;
      render();
    } catch (error) {
      if (generation !== loadGeneration) return;
      setBusy(`Nie udało się pobrać urządzeń: ${error.message}`);
      handleAuthenticatedError("Nie udało się pobrać urządzeń", error);
    } finally {
      if (generation === loadGeneration) $("refreshBtn").disabled = false;
    }
  }

  async function connect(rawToken) {
    if (connectPending) return;
    const candidate = rawToken.trim();
    if (!candidate) return;

    connectPending = true;
    loginSubmit.disabled = true;
    token = candidate;
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
    } finally {
      connectPending = false;
      loginSubmit.disabled = false;
    }
  }

  function logout() {
    token = "";
    principal = null;
    devices = [];
    loadGeneration += 1;
    setMutationBusy(false);
    dashboard.hidden = true;
    identity.hidden = true;
    loginCard.hidden = false;
    grid.replaceChildren();
    setBusy("");
    $("tokenInput").focus();
  }

  async function saveDevice(event) {
    event.preventDefault();
    if (!roleCan("write") || mutationPending) return;

    const id = $("deviceId").value;
    const body = {
      name: $("deviceName").value.trim(),
      device_type: $("deviceType").value.trim(),
      status: $("deviceStatus").value.trim(),
    };

    setMutationBusy(true);
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
      handleAuthenticatedError("Nie zapisano", error);
    } finally {
      setMutationBusy(false);
    }
  }

  async function deleteCurrent() {
    const id = $("deviceId").value;
    if (!id || !roleCan("delete") || mutationPending) return;
    const name = $("deviceName").value;
    if (!window.confirm(`Usunąć urządzenie „${name}”?`)) return;

    setMutationBusy(true);
    try {
      await api(`/devices/${id}`, { method: "DELETE" });
      dialog.close();
      toast("Urządzenie usunięte");
      await loadDevices();
    } catch (error) {
      handleAuthenticatedError("Nie usunięto", error);
    } finally {
      setMutationBusy(false);
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
