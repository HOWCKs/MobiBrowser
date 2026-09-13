package app.mobibrowser.core.ext

import android.content.Context
import app.mobibrowser.core.MobiLog
import app.mobibrowser.data.BrowserDb
import app.mobibrowser.data.ScriptKind
import app.mobibrowser.data.UserScript
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension

/**
 * Atende a extensão-ponte embutida (MobiBridge) por *native messaging*.
 *
 * Modelo de comunicação: puxado (pull). A content script da ponte pergunta
 * "o que roda nesta URL?" no `document_start`; respondemos com JS/CSS já filtrados por
 * site + o shim `chrome.*`/`GM_*`. Pull (e não push) porque o app não tem canal confiável
 * para empurrar eventos para dentro de uma WebExtension no GeckoView, e esse caminho
 * sobrevive ao descarte do event page — comportamento normal de MV3.
 *
 * O delegate é `@UiThread`: todo I/O (arquivo, rede) é movido para [Dispatchers].IO e o
 * [GeckoResult] é completado de lá. Nada bloqueia a UI.
 */
class BridgeScripts(
    private val context: Context,
    private val db: BrowserDb,
    private val registry: ExtensionRegistry,
    private val scope: CoroutineScope,
) : WebExtension.MessageDelegate {

    /** Espelho síncrono das preferências: o delegate não pode suspender para ler DataStore. */
    @Volatile
    var userscriptsEnabled: Boolean = true

    @Volatile
    var dnrEnabled: Boolean = true

    private val storageRoot = File(context.filesDir, "ext-storage").apply { mkdirs() }

    data class InjectionStats(val requests: Int = 0, val injected: Int = 0, val lastUrl: String = "")

    private val _stats = MutableStateFlow(InjectionStats())
    val stats: StateFlow<InjectionStats> = _stats.asStateFlow()

    private val requests = AtomicInteger()
    private val injections = AtomicInteger()

    private val shim: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            context.assets.open("$ASSET_DIR/shim.js").bufferedReader().use { it.readText() }
        }.getOrElse {
            MobiLog.w(SCOPE, "shim.js ausente no APK; modo compatibilidade sem chrome.*/GM_*", it)
            ""
        }
    }

    override fun onMessage(
        nativeApp: String,
        message: Any,
        sender: WebExtension.MessageSender,
    ): GeckoResult<Any?> {
        if (nativeApp != NATIVE_APP) return GeckoResult.fromValue(null)
        val request = asJson(message) ?: return GeckoResult.fromValue(null)
        val result = GeckoResult<Any?>()
        scope.launch(Dispatchers.IO) {
            val reply = runCatching { handle(request) }.getOrElse { error ->
                MobiLog.w(SCOPE, "erro ao atender ${request.optString("type")}", error)
                null
            }
            // Devolvemos JSON-texto: é o formato que sobrevive identicamente ao
            // atravessar o bundle do motor (objeto/JSON tem conversão ambígua).
            result.complete(reply?.toString())
        }
        return result
    }

    private fun handle(request: JSONObject): JSONObject? = when (request.optString("type")) {
        "scripts-for" -> scriptsFor(request.optString("url"))
        "dnr-rules" -> dnrRules(request.optInt("since", -1))
        "storage.get" -> storageGet(request)
        "storage.set" -> storageSet(request)
        "storage.remove" -> storageRemove(request)
        "storage.clear" -> storageClear(request)
        "xhr" -> proxyRequest(request)
        "runtime.sendMessage" -> JSONObject().put("response", JSONObject.NULL)
        else -> null
    }

    private fun asJson(message: Any): JSONObject? = when (message) {
        is JSONObject -> message
        is String -> runCatching { JSONObject(message) }.getOrNull()
        else -> runCatching { JSONObject(message.toString()) }.getOrNull()
    }

    /* ---------------------------------------------------------------- *
     * Scripts/estilos para uma URL                                     *
     * ---------------------------------------------------------------- */

    private fun scriptsFor(url: String): JSONObject {
        requests.incrementAndGet()
        val js = JSONArray()
        val css = JSONArray()
        if (url.isNotBlank() && !url.startsWith("about:") && !url.startsWith("view-source:")) {
            db.listScripts().forEach { script ->
                if (!script.enabled) return@forEach
                val fromExtension = script.sourceExtension != null
                if (!userscriptsEnabled && !fromExtension) return@forEach

                val patterns = script.pattern.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (!MatchPattern.matchesAny(patterns, url)) return@forEach

                val owner = script.sourceExtension?.let { registry.get(it) }
                if (owner != null && (!owner.enabled || !owner.allowsSite(url))) return@forEach

                when (script.kind) {
                    ScriptKind.CSS -> css.put(script.code)
                    ScriptKind.JS -> js.put(preludeFor(script) + "\n" + script.code)
                }
            }
        }
        if (js.length() > 0 || css.length() > 0) {
            injections.addAndGet(js.length() + css.length())
        }
        _stats.value = InjectionStats(requests.get(), injections.get(), url)
        return JSONObject().put("js", js).put("css", css).put("shim", shim)
    }

    /** Identidade visível para o código injetado (e para o shim decidir onde persistir). */
    private fun preludeFor(script: UserScript): String {
        val id = script.sourceExtension ?: "userscript-${script.id}"
        return "window.__mobiExtensionId=${JSONObject.quote(id)};" +
            "window.__mobiScriptName=${JSONObject.quote(script.name)};"
    }

    /* ---------------------------------------------------------------- *
     * declarativeNetRequest com as regras extraídas do pacote convertido *
     * ---------------------------------------------------------------- */

    private fun dnrRules(since: Int): JSONObject {
        val rules = JSONArray()
        var version = 0
        var nextId = 1
        if (dnrEnabled) {
            registry.bridgeRecords().forEach { record ->
                val raw = record.dnrRules ?: return@forEach
                val parsed = runCatching { JSONArray(raw) }.getOrNull() ?: return@forEach
                for (i in 0 until parsed.length()) {
                    if (nextId > MAX_SESSION_RULES) return@forEach
                    val rule = parsed.optJSONObject(i) ?: continue
                    val condition = rule.optJSONObject("condition") ?: continue
                    val urlFilter = condition.optString("urlFilter")
                    val regexFilter = condition.optString("regexFilter")
                    if (urlFilter.isBlank() && regexFilter.isBlank()) continue

                    rules.put(
                        JSONObject()
                            .put("id", nextId++)
                            .put("priority", rule.optInt("priority", 1))
                            .put(
                                "action",
                                JSONObject().put("type", mapAction(rule.optJSONObject("action")?.optString("type"))),
                            )
                            .put("condition", translateCondition(condition)),
                    )
                }
                version = maxOf(version, (record.updatedAt / 1000).toInt())
            }
        }
        if (version == since) return JSONObject().put("version", since)
        return JSONObject().put("version", version).put("rules", rules)
    }

    /**
     * Chrome e Firefox quase coincidem no formato de regra dNR; o que traduzimos aqui é:
     * `resourceTypes` ausente (o Chrome assume "everything", o Firefox exige lista), e
     * `domainType` com nomes diferentes (thirdParty → third_party).
     */
    private fun translateCondition(condition: JSONObject): JSONObject = JSONObject().apply {
        condition.optString("urlFilter").takeIf { it.isNotBlank() }?.let { put("urlFilter", it) }
        condition.optString("regexFilter").takeIf { it.isNotBlank() }?.let { put("regexFilter", it) }
        condition.optJSONArray("resourceTypes")?.takeIf { it.length() > 0 }
            ?: JSONArray()
                .put("main_frame")
                .put("sub_frame")
                .put("script")
                .put("stylesheet")
                .put("image")
                .put("font")
                .put("object")
                .put("xmlhttprequest")
                .put("ping")
                .put("media")
                .put("websocket")
                .also { put("resourceTypes", it) }
        put("domainType", normalizeDomainType(condition.optString("domainType")))
        condition.optJSONArray("initiatorDomains")?.let { put("initiatorDomains", it) }
        condition.optJSONArray("excludedInitiatorDomains")?.let { put("excludedInitiatorDomains", it) }
        condition.optJSONArray("excludedResourceTypes")?.let { put("excludedResourceTypes", it) }
        // `requestMethods`/`topDomain` ficam como vieram: o motor descarta o que não
        // entender, e a regra vira no-op em vez de erro de instalação.
    }

    private fun normalizeDomainType(value: String?): String = when (value) {
        "thirdParty" -> "third_party"
        "firstParty" -> "first_party"
        "all", null, "" -> "all"
        else -> value
    }

    private fun mapAction(type: String?): String = when (type) {
        "upgradeScheme" -> "upgradeScheme"
        "allow", "allowAllRequests" -> "allow"
        else -> "block" // block, blockMainframe, redirect, modifyHeaders: ainda não traduzimos as ops
    }

    /* ---------------------------------------------------------------- *
     * chrome.storage / GM_getValue — persistência real por extensão    *
     * ---------------------------------------------------------------- */

    private fun storageFile(ext: String): File =
        File(storageRoot, ext.replace(Regex("[^A-Za-z0-9._@-]"), "_").take(64) + ".json")

    private fun readData(file: File): JSONObject = runCatching {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    }.getOrDefault(JSONObject())

    private fun storageGet(request: JSONObject): JSONObject {
        val data = readData(storageFile(ownerOf(request)))
        val keys = request.optJSONObject("payload")?.optJSONArray("keys")
        val out = JSONObject()
        if (keys == null || keys.length() == 0) {
            data.keys().forEach { key -> out.put(key, data.get(key)) }
        } else {
            for (i in 0 until keys.length()) {
                val key = keys.optString(i)
                if (data.has(key)) out.put(key, data.get(key))
            }
        }
        return JSONObject().put("value", out)
    }

    private fun storageSet(request: JSONObject): JSONObject {
        val file = storageFile(ownerOf(request))
        val data = readData(file)
        request.optJSONObject("payload")?.optJSONObject("items")?.let { items ->
            items.keys().forEach { key -> data.put(key, items.get(key)) }
        }
        file.writeText(data.toString())
        return JSONObject().put("ok", true)
    }

    private fun storageRemove(request: JSONObject): JSONObject {
        val file = storageFile(ownerOf(request))
        val data = readData(file)
        request.optJSONObject("payload")?.optJSONArray("keys")?.let { keys ->
            for (i in 0 until keys.length()) data.remove(keys.optString(i))
        }
        file.writeText(data.toString())
        return JSONObject().put("ok", true)
    }

    private fun storageClear(request: JSONObject): JSONObject {
        storageFile(ownerOf(request)).writeText("{}")
        return JSONObject().put("ok", true)
    }

    private fun ownerOf(request: JSONObject): String =
        request.optString("extensionId").ifBlank { request.optString("ext").ifBlank { "default" } }

    /* ---------------------------------------------------------------- *
     * GM_xmlhttpRequest — pedido feito pelo app (a página não tem CORS) *
     * ---------------------------------------------------------------- */

    private fun proxyRequest(request: JSONObject): JSONObject {
        val payload = request.optJSONObject("payload") ?: JSONObject()
        val target = payload.optString("url")
        if (target.isBlank() || !(target.startsWith("http://") || target.startsWith("https://"))) {
            return JSONObject().put("error", "url inválida")
        }
        return runCatching {
            val conn = java.net.URL(target).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.requestMethod = payload.optString("method", "GET").uppercase()
            val headers = payload.optJSONObject("headers")
            headers?.keys()?.forEach { key -> conn.setRequestProperty(key, headers.optString(key)) }
            if (conn.requestMethod != "GET" && conn.requestMethod != "HEAD") {
                conn.doOutput = true
                payload.optString("body").takeIf { it.isNotEmpty() }?.let { body ->
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
            }
            val status = conn.responseCode
            val text = runCatching {
                (if (status in 200..399) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            JSONObject()
                .put("status", status)
                .put("statusText", conn.responseMessage ?: "")
                .put("responseText", text)
                .put("finalUrl", conn.url.toString())
                .also { conn.disconnect() }
        }.getOrElse {
            MobiLog.w(SCOPE, "proxy GM_xmlhttpRequest falhou para $target", it)
            JSONObject().put("error", it.message ?: "falha de rede")
        }
    }

    companion object {
        private const val SCOPE = "bridge"
        private const val NATIVE_APP = "mobibridge-native"
        private const val ASSET_DIR = "extensions/mobibridge"

        /** Teto de session rules: mais que isso estoura a memória do motor por aba. */
        private const val MAX_SESSION_RULES = 5_000
    }
}
