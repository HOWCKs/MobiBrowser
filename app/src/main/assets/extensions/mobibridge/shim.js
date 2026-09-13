/*
 * shim.js — camada de compatibilidade injetada antes de qualquer user script ou
 * content script de extensão do Chrome que rodamos em "modo compatibilidade".
 *
 * Escopo honesto: cobrimos o que os scripts mais comuns usam
 * (chrome.runtime.sendMessage, chrome.storage.{local,sync}, GM_getValue/setValue,
 *  GM_addStyle, GM_xmlhttpRequest simplificado). O que exige privilégio do
 * navegador (webRequest bloqueante, debugger, tabCapture, alarms) não é fingido:
 * vira um no-op que avisa no console, para o erro ser diagnosticável.
 */

(function () {
  "use strict";
  if (window.__mobiShim) return;
  window.__mobiShim = true;

  const EXT = window.__mobiExtensionId || null;
  const seq = (() => { let n = 0; return () => ++n; })();
  const responders = new Map();

  function callApp(kind, payload) {
    return new Promise((resolve) => {
      const id = seq();
      responders.set(id, resolve);
      window.postMessage({ __mobi: 1, id, kind, ext: EXT, payload }, "*");
      // Sem resposta em 5s = ninguém em casa; não travamos a página.
      setTimeout(() => {
        if (responders.has(id)) {
          responders.delete(id);
          resolve(null);
        }
      }, 5000);
    });
  }

  window.addEventListener("message", (ev) => {
    const d = ev.data;
    if (!d || d.__mobiReply !== 1) return;
    const r = responders.get(d.id);
    if (r) {
      responders.delete(d.id);
      r(d.result);
    }
  });

  // Ponte de ida: content scripts que usam window.postMessage para falar conosco.
  window.addEventListener("message", (ev) => {
    const d = ev.data;
    if (!d || d.__mobiCall !== 1) return;
    callApp(d.kind, d.payload).then((result) => {
      window.postMessage({ __mobiReply: 1, id: d.id, result }, "*");
    });
  });

  const storage = {
    get: async (keys, cb) => {
      const result = await callApp("storage.get", { keys: keys ?? null });
      const value = result && result.value ? result.value : {};
      if (typeof cb === "function") cb(value);
      return Promise.resolve(value);
    },
    set: async (items, cb) => {
      await callApp("storage.set", { items });
      if (typeof cb === "function") cb();
    },
    remove: async (keys, cb) => {
      await callApp("storage.remove", { keys });
      if (typeof cb === "function") cb();
    },
    clear: async (cb) => {
      await callApp("storage.clear", {});
      if (typeof cb === "function") cb();
    },
  };

  const notSupported = (api) => (...args) => {
    console.warn("[MobiBrowser] API não suportada na ponte de compatibilidade: " + api);
    const last = args[args.length - 1];
    if (typeof last === "function") last(undefined);
    return Promise.resolve(undefined);
  };

  const chrome = window.chrome || (window.chrome = {});
  chrome.runtime = Object.assign(chrome.runtime || {}, {
    id: EXT,
    lastError: null,
    sendMessage: async (a, b, cb) => {
      const payload = typeof b === "object" || typeof a === "object" ? { message: a, extra: b } : { message: a };
      const result = await callApp("runtime.sendMessage", payload);
      if (typeof cb === "function") cb(result ? result.response : undefined);
      return Promise.resolve(result ? result.response : undefined);
    },
    getURL: (path) => (EXT ? "moz-extension://mobi/" + path : path),
    openOptionsPage: notSupported("chrome.runtime.openOptionsPage"),
    getManifest: () => window.__mobiManifest || {},
  });
  chrome.storage = { local: storage, sync: storage, session: storage, managed: storage };
  chrome.tabs = {
    query: notSupported("chrome.tabs.query"),
    create: notSupported("chrome.tabs.create"),
    update: notSupported("chrome.tabs.update"),
    sendMessage: async (tabId, msg) => {
      // Entregamos na mesma aba via DOM: a content script da página escuta.
      window.postMessage({ __mobiTabMessage: 1, tabId, msg }, "*");
    },
  };
  chrome.action = {
    setBadgeText: notSupported("chrome.action.setBadgeText"),
    setBadgeBackgroundColor: notSupported("chrome.action.setBadgeBackgroundColor"),
    setIcon: notSupported("chrome.action.setIcon"),
    setPopup: notSupported("chrome.action.setPopup"),
    onClicked: { addListener: () => {}, removeListener: () => {} },
  };
  chrome.pageAction = chrome.action;
  chrome.permissions = {
    request: (p, cb) => { const ok = true; if (cb) cb(ok); return Promise.resolve(ok); },
    contains: () => true,
    remove: (p, cb) => { if (cb) cb(true); return Promise.resolve(true); },
  };
  chrome.downloads = { download: notSupported("chrome.downloads.download") };
  chrome.notifications = { create: notSupported("chrome.notifications.create") };

  // ---- Tampermonkey/Greasemonkey ----
  window.GM_info = { uuid: EXT, scriptMetaStr: "", scriptHandler: "MobiBrowser" };
  window.GM_addStyle = (css) => {
    const style = document.createElement("style");
    style.textContent = css;
    document.documentElement.appendChild(style);
    return style;
  };
  window.GM_getValue = async (key, def) => {
    const r = await callApp("storage.get", { keys: [key] });
    const v = r && r.value ? r.value[key] : undefined;
    return v === undefined ? def : v;
  };
  window.GM_setValue = (key, value) => callApp("storage.set", { items: { [key]: value } });
  window.GM_deleteValue = (key) => callApp("storage.remove", { keys: [key] });
  window.GM_listValues = async () => {
    const r = await callApp("storage.get", { keys: null });
    return Object.keys((r && r.value) || {});
  };
  window.GM_log = (msg) => console.log("[userscript]", msg);
  window.GM_registerMenuCommand = notSupported("GM_registerMenuCommand");
  window.GM_xmlhttpRequest = (detail) => {
    // Pedido real feito pelo app (respeita CORS), resposta devolvida por callback.
    return callApp("xhr", {
      method: detail.method || "GET",
      url: detail.url,
      headers: detail.headers || {},
      body: detail.data || null,
    }).then((r) => {
      if (!r) { if (detail.onerror) detail.onerror({ status: 0 }); return; }
      if (r.error) { if (detail.onerror) detail.onerror(r.error); return; }
      const resp = {
        status: r.status,
        statusText: r.statusText || "",
        responseText: r.responseText || "",
        responseHeaders: r.responseHeaders || "",
        finalUrl: r.finalUrl || detail.url,
      };
      if (detail.onload) detail.onload(resp);
      if (detail.onreadystatechange) detail.onreadystatechange(resp);
    });
  };

  // Aliases usados por scripts antigos.
  window.unsafeWindow = window;
  window.GM_getResourceURL = notSupported("GM_getResourceURL");
})();
