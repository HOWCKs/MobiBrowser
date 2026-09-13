/*
 * MobiBridge — background (event page) da extensão-ponte embutida no MobiBrowser.
 *
 * Papel: ser o "motor" que o app alimenta por *native messaging*. Toda a política
 * (quais scripts, qual CSS, quais regras de bloqueio, por site) mora em Kotlin;
 * aqui só executamos no contexto do navegador.
 *
 * Native messaging está disponível porque instalamos esta extensão como *built-in*
 * (WebExtensionController.installBuiltIn), que dispensa assinatura da Mozilla.
 */

const NATIVE_APP = "mobibridge-native";

/** Cache do estado pedido ao app, por aba. */
const tabState = new Map();

function decode(reply) {
  if (typeof reply === "string") {
    try {
      return JSON.parse(reply);
    } catch (_) {
      return null;
    }
  }
  return reply;
}

async function askApp(payload) {
  try {
    return decode(await browser.runtime.sendNativeMessage(NATIVE_APP, payload));
  } catch (err) {
    console.warn("[mobibridge] app não respondeu", err);
    return null;
  }
}

/**
 * O app devolve { js: [...], css: [...], dnrVersion, dnrRules } conforme o pedido.
 * `url` é a URL do documento, para o app decidir por site.
 */
/* Canal genérico: o shim.js da página (chrome.* / GM_*) chega aqui via content script. */
browser.runtime.onMessage.addListener(async (msg, sender) => {
  if (!msg || msg.type !== "mobi:app-call") return;
  const reply = await askApp({
    type: msg.kind,
    url: msg.url || (sender.tab ? sender.tab.url : ""),
    tabId: sender.tab ? sender.tab.id : null,
    extensionId: msg.ext,
    payload: msg.payload,
  });
  return reply === null ? null : { ok: true, result: reply };
});

browser.runtime.onMessage.addListener(async (msg, sender) => {
  if (!msg || msg.type !== "mobi:need-scripts") return;

  const url = (sender.tab && sender.tab.url) || msg.url || "";
  const reply = await askApp({
    type: "scripts-for",
    url,
    tabId: sender.tab ? sender.tab.id : null,
    frameId: sender.frameId,
  });

  if (!reply) return { js: [], css: [] };
  return {
    js: reply.js || [],
    css: reply.css || [],
    shim: reply.shim || "",
  };
});

/* ---------------------------------------------------------------------------
 * Bloqueio de conteúdo (declarativeNetRequest) com regras vindas do app.
 * O app mantém listas simples (regex/substring) e nós as traduzimos para
 * session rules — mesmas APIs que o uBlock Origin Lite usa no Firefox.
 * ------------------------------------------------------------------------- */
let dnrVersion = -1;

async function syncDnr(force) {
  const reply = await askApp({ type: "dnr-rules", since: dnrVersion });
  if (!reply) return;
  if (!force && reply.version === dnrVersion) return;

  const previousIds = dnrVersion === -1 ? [] : await listSessionRuleIds();
  await browser.declarativeNetRequest.updateSessionRules({
    removeRuleIds: previousIds,
    addRules: (reply.rules || []).map((r, i) => ({
      id: i + 1,
      priority: r.priority || 1,
      action: { type: r.type || "block" },
      condition: {
        urlFilter: r.urlFilter,
        regexFilter: r.regexFilter,
        resourceTypes: r.resourceTypes || [
          "script",
          "image",
          "media",
          "sub_frame",
          "font",
          "xmlhttprequest",
        ],
        ...(r.initiatorDomains ? { domainSet: undefined, excludedInitiatorDomains: undefined } : {}),
      },
    })),
  });
  dnrVersion = reply.version;
}

async function listSessionRuleIds() {
  try {
    const rules = await browser.declarativeNetRequest.getSessionRules();
    return rules.map((r) => r.id);
  } catch (_) {
    return [];
  }
}

/* ---------------------------------------------------------------------------
 * Armazenamento pedido pelas content scripts "chrome.*" shimmadas:
 * o `chrome.storage.local` de uma extensão Chrome que rodamos em modo
 * compatibilidade é respondido pelo próprio app (persistência real).
 * ------------------------------------------------------------------------- */
browser.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (changeInfo.status === "loading" && tab && tab.url) {
    tabState.set(tabId, { url: tab.url });
  }
  if (changeInfo.status === "complete") {
    // Reenvia CSS/JS dinâmico que depende de elementos criados depois do load.
    browser.tabs
      .sendMessage(tabId, { type: "mobi:page-complete" })
      .catch(() => {});
  }
});

browser.tabs.onRemoved.addListener((tabId) => tabState.delete(tabId));

browser.runtime.onStartup.addListener(() => syncDnr(true));
syncDnr(true);

// Sincroniza regras quando o app avisa que a lista mudou.
browser.alarms.create("mobi-dnr-sync", { periodInMinutes: 1 });
browser.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === "mobi-dnr-sync") syncDnr(false);
});
