/*
 * MobiBridge — content script injetada em TODAS as páginas (document_start).
 *
 * Fluxo:
 *   1. pergunta ao background quais scripts/CSS valem para esta URL;
 *   2. o background pergunta ao app (native messaging) — é o app que decide por site;
 *   3. injetamos como <script>/<style> no documento, ou seja, no mundo da página,
 *      com o shim chrome.* e GM_* na frente, para que content scripts de extensões
 *      do Chrome encontrem as APIs que esperam.
 *
 * Injetar via elemento DOM é deliberado: conteúdo criado pelo documento ainda não
 * existe neste ponto, então pegamos os <script> da página antes deles rodarem.
 */

(() => {
  "use strict";
  if (window.__mobiBridgeLoaded) return;
  window.__mobiBridgeLoaded = true;

  const pendingCss = [];
  const pendingJs = [];

  function injectCss(css) {
    if (!css) return;
    const style = document.createElement("style");
    style.setAttribute("data-mobi", "1");
    style.textContent = css;
    (document.head || document.documentElement).appendChild(style);
  }

  function injectJs(code, sourceUrl) {
    if (!code) return;
    const script = document.createElement("script");
    script.setAttribute("data-mobi", "1");
    if (sourceUrl) script.setAttribute("data-mobi-src", sourceUrl);
    script.textContent = code;
    (document.documentElement || document.head || document).appendChild(script);
    script.remove();
  }

  function flush() {
    while (pendingCss.length) injectCss(pendingCss.shift());
    while (pendingJs.length) injectJs(pendingJs.shift());
  }

  async function requestScripts() {
    let reply;
    try {
      reply = await browser.runtime.sendMessage({
        type: "mobi:need-scripts",
        url: location.href,
      });
    } catch (err) {
      // Background adormecido/indisponível: seguir sem injetar nada.
      return;
    }
    if (!reply) return;

    if (reply.shim) injectJs(reply.shim, "mobi:shim");
    for (const css of reply.css || []) pendingCss.push(css);
    for (const js of reply.js || []) pendingJs.push(js);
    flush();
  }

  // Chamadas do shim (página -> app): repassamos ao background e devolvemos a resposta.
  window.addEventListener("message", async (ev) => {
    const d = ev.data;
    if (!d || d.__mobi !== 1 || d.__mobiReply === 1) return;
    let result = null;
    try {
      const reply = await browser.runtime.sendMessage({
        type: "mobi:app-call",
        kind: d.kind,
        ext: d.ext,
        payload: d.payload,
        url: location.href,
      });
      result = reply ? reply.result : null;
    } catch (err) {
      result = null;
    }
    window.postMessage({ __mobiReply: 1, id: d.id, result }, "*");
  });

  // Respostas do app sobre a página (ex.: CSS dinâmico de um tema escuro).
  browser.runtime.onMessage.addListener((msg) => {
    if (!msg) return;
    if (msg.type === "mobi:page-complete") flush();
    if (msg.type === "mobi:push-css") injectCss(msg.css);
    if (msg.type === "mobi:push-js") injectJs(msg.js, msg.source);
  });

  requestScripts().catch(() => {});
})();
