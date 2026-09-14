package app.mobibrowser.core.ext

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converte um `manifest.json` de extensão do Chrome (MV2 ou MV3) para o formato
 * WebExtensions que o GeckoView aceita — o mesmo trabalho que o Firefox faz no
 * "Return to AMO", só que aqui com o pacote na mão.
 *
 * Sem dependência de Android: é testável no JVM puro (veja ManifestConverterTest).
 *
 * Regras aplicadas:
 *  - força `manifest_version: 3` (o Firefox removeu MV2; um manifest MV2 instalado
 *    como MV2 é recusado, então MV2 é promovido: `browser_action`→`action`, hosts
 *    movidos para `host_permissions`, CSP para o formato de objeto, WAR estruturado);
 *  - declara `browser_specific_settings.gecko.id` — sem id, o install de um pacote
 *    convertido não tem identidade estável entre reinstalações;
 *  - `background.service_worker` → página de eventos (scripts + persistent:false);
 *  - remove chaves que o Gecko não implementa e que dariam erro de manifest;
 *  - reporta, em [ConversionReport], tudo que foi removido para a UI avisar o usuário.
 */
object ManifestConverter {

    data class ConversionReport(
        val geckoId: String,
        val name: String,
        val version: String,
        val manifestVersion: Int,
        val upgradedFromV2: Boolean,
        val droppedKeys: List<String>,
        val warnings: List<String>,
        val contentScriptFiles: List<String>,
        val contentStyleFiles: List<String>,
        val ruleResourcePaths: List<String>,
        val hasPopup: Boolean,
        val hasOptionsPage: Boolean,
    )

    data class Converted(val manifest: String, val report: ConversionReport)

    class ConversionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Chaves que o Firefox/GeckoView não aceita e que invalidam o manifest. */
    private val UNSUPPORTED_KEYS = listOf(
        "minimum_chrome_version",
        "update_url",
        "offline_enabled",
        "kiosk",
        "kiosk_only",
        "chrome_settings_overrides",
        "omnibox",
        "storage", // chave de manifest de app (`"storage": {"managed_schema"}`), não a permissão
        "file_handlers",
        "user_scripts",
        "declarative_content_filters",
        "incognito",
        "signature",
        "key",
        "nacl_modules",
        "url_handlers",
    )

    /** APIs do Chrome que não existem no motor; só aviso, não removemos a permissão. */
    private val CHROME_ONLY_PERMISSIONS = setOf(
        "debugger",
        "tabCapture",
        "enterprise.*",
        "fileSystem",
        "clipboard",
        "sessions",
        "webstore",
        "management",
        "system.*",
        "mdns",
        "serial",
        "usb",
        "bluetooth",
    )

    fun convert(rawManifest: String, forcedGeckoId: String? = null): Converted {
        val json = try {
            JSONObject(rawManifest)
        } catch (e: Exception) {
            throw ConversionException("manifest.json não é JSON válido", e)
        }

        val manifestVersion = json.optInt("manifest_version", 0)
        if (manifestVersion != 2 && manifestVersion != 3) {
            throw ConversionException(
                "Versão de manifest não suportada (manifest_version=$manifestVersion). " +
                    "O MobiBrowser converte extensões MV2 e MV3 do Chrome.",
            )
        }
        if (!json.has("name") || json.optString("name").isBlank()) {
            throw ConversionException("A extensão não tem `name` no manifest.")
        }

        val out = JSONObject(rawManifest)
        val dropped = linkedSetOf<String>()
        val warnings = linkedSetOf<String>()

        val name = out.optString("name").trim()
        val version = out.optString("version", "0").ifBlank { "0" }
        val slug = CrxPackage.safeFileName(name)
        val geckoId = forcedGeckoId?.takeIf { it.contains('@') } ?: "ext-$slug-${stableHash(name + version)}@extensions.mobibrowser"

        // --- id estável (exigido para carregar pacote não-AMO) ---
        val bss = out.optJSONObject("browser_specific_settings") ?: JSONObject()
        val gecko = bss.optJSONObject("gecko") ?: JSONObject()
        val applications = out.optJSONObject("applications")?.optJSONObject("gecko")
        val existingId = gecko.optString("id").ifBlank { applications?.optString("id").orEmpty() }
        gecko.put("id", existingId.ifBlank { geckoId })
        if (manifestVersion == 3) gecko.put("strict_min_version", MIN_GECKO_VERSION)
        bss.put("gecko", gecko)
        out.put("browser_specific_settings", bss)
        out.remove("applications")

        // --- MV2 → MV3 ---
        val upgraded = manifestVersion == 2
        if (upgraded) {
            upgradeToV3(out, json, warnings)
        } else {
            normalizeV3(out, json)
        }
        out.put("manifest_version", 3)

        // --- background: service worker do Chrome → event page do Gecko ---
        normalizeBackground(out, warnings)

        // --- CSP string (MV2) → objeto (MV3) ---
        normalizeCsp(out)

        // --- web accessible resources: strings → objetos ---
        normalizeWebAccessibleResources(out)

        // --- content_scripts: garantir matches válidos ---
        val scripts = linkedSetOf<String>()
        val styles = linkedSetOf<String>()
        val cs = out.optJSONArray("content_scripts")
        if (cs != null) {
            for (i in 0 until cs.length()) {
                val entry = cs.optJSONObject(i) ?: continue
                val matches = entry.optJSONArray("matches")
                if (matches == null || matches.length() == 0) {
                    entry.put("matches", JSONArray().put("<all_urls>"))
                    warnings.add("content_scripts[$i] sem `matches`: aplicado `<all_urls>`.")
                }
                entry.optJSONArray("js")?.let { js -> for (k in 0 until js.length()) scripts += js.optString(k) }
                entry.optJSONArray("css")?.let { css -> for (k in 0 until css.length()) styles += css.optString(k) }
                // `match_about_blank` e `run_at` são aceitos pelo Firefox; `all_frames` também.
            }
        }

        // --- regras dNR: registrar caminhos para o modo compatibilidade ---
        val rules = linkedSetOf<String>()
        out.optJSONObject("declarative_net_request")
            ?.optJSONArray("rule_resources")
            ?.let { arr ->
                for (i in 0 until arr.length()) {
                    val path = arr.optJSONObject(i)?.optString("path")
                    if (!path.isNullOrBlank()) rules += path.trimStart('/')
                }
            }

        // --- permissões: avisar sobre APIs que o motor não tem ---
        val perms = out.optJSONArray("permissions")
        if (perms != null) {
            for (i in 0 until perms.length()) {
                val p = perms.optString(i)
                if (CHROME_ONLY_PERMISSIONS.any { p == it || (it.endsWith(".*") && p.startsWith(it.dropLast(1))) }) {
                    warnings.add("A permissão `$p` não tem equivalente no motor; a extensão pode degradar.")
                }
            }
        }

        // --- chave `options_ui`: Firefox usa options_ui (ok) — normalizar chrome_style ---
        out.optJSONObject("options_ui")?.apply {
            remove("chrome_style")
            remove("open_in_tab") // suportado, mas o valor padrão já é true no Gecko
        }

        // --- popup padrão da ação ---
        val action = out.optJSONObject("action")
        val hasPopup = action?.optJSONObject("default_popup") != null ||
            !action?.optString("default_popup").isNullOrBlank()

        UNSUPPORTED_KEYS.forEach { key ->
            if (out.has(key)) {
                out.remove(key)
                dropped += key
            }
        }

        return Converted(
            manifest = out.toString(1),
            report = ConversionReport(
                geckoId = gecko.optString("id"),
                name = name,
                version = version,
                manifestVersion = 3,
                upgradedFromV2 = upgraded,
                droppedKeys = dropped.toList(),
                warnings = warnings.toList(),
                contentScriptFiles = scripts.toList(),
                contentStyleFiles = styles.toList(),
                ruleResourcePaths = rules.toList(),
                hasPopup = hasPopup,
                hasOptionsPage = out.has("options_ui") || out.has("options_page"),
            ),
        )
    }

    private fun upgradeToV3(out: JSONObject, src: JSONObject, warnings: MutableSet<String>) {
        // browser_action / page_action → action
        val browserAction = src.optJSONObject("browser_action")
        val pageAction = src.optJSONObject("page_action")
        val action = JSONObject()
        listOf(browserAction, pageAction).filterNotNull().forEach { legacy ->
            listOf("default_title", "default_popup", "default_icon").forEach { k ->
                if (legacy.has(k)) action.put(k, legacy.get(k))
            }
        }
        if (action.length() > 0) out.put("action", action)
        out.remove("browser_action")
        out.remove("page_action")
        if (browserAction == null && pageAction == null && src.has("action")) {
            out.put("action", src.get("action"))
        }

        // host permissions saem de `permissions` para `host_permissions`
        val permissions = src.optJSONArray("permissions") ?: JSONArray()
        val apis = JSONArray()
        val hosts = src.optJSONArray("host_permissions") ?: JSONArray()
        for (i in 0 until permissions.length()) {
            val value = permissions.optString(i)
            if (value.contains("://") || value == "<all_urls>" || value == "<all_frames>") {
                hosts.put(value)
            } else if (value.isNotEmpty()) {
                apis.put(value)
            }
        }
        out.put("permissions", dedupe(apis))
        if (hosts.length() > 0) out.put("host_permissions", dedupe(hosts))

        // MV2 sem background.scripts? Nada a fazer; event page é aceita no MV3 do Firefox.
        if (!src.has("background")) {
            warnings.add("Extensão MV2 sem `background`: partes que dependem de background podem não funcionar.")
        }
        // `tabs` em MV3 perde `url`; avisar scripts que dependem.
        if (apis.let { a -> (0 until a.length()).any { a.optString(it) == "tabs" } }) {
            warnings.add("Em MV3, `tabs` não expõe `url` dos itens: extensões que leem URL por aba podem falhar.")
        }
    }

    private fun normalizeV3(out: JSONObject, src: JSONObject) {
        // Alguns packs do Chrome ainda trazem browser_action em MV3 → mover para action.
        src.optJSONObject("browser_action")?.let { legacy ->
            val action = out.optJSONObject("action") ?: JSONObject()
            listOf("default_title", "default_popup", "default_icon").forEach { k ->
                if (legacy.has(k) && !action.has(k)) action.put(k, legacy.get(k))
            }
            if (action.length() > 0) out.put("action", action)
        }
        out.remove("browser_action")
        out.remove("page_action")
    }

    private fun normalizeBackground(out: JSONObject, warnings: MutableSet<String>) {
        val background = out.optJSONObject("background") ?: return
        val sw = background.optString("service_worker")
        if (sw.isNotBlank()) {
            val scripts = JSONArray().put(sw)
            background.put("scripts", scripts)
            background.put("persistent", false)
            background.remove("service_worker")
            warnings.add("`background.service_worker` convertido em página de eventos (modelo do Firefox).")
        }
        if (background.has("type")) background.remove("type")
    }

    private fun normalizeCsp(out: JSONObject) {
        val csp = out.opt("content_security_policy")
        if (csp is String) {
            out.put(
                "content_security_policy",
                JSONObject().put("extension_pages", csp),
            )
        }
    }

    private fun normalizeWebAccessibleResources(out: JSONObject) {
        val war = out.opt("web_accessible_resources")
        if (war is JSONArray && war.length() > 0 && war.opt(0) is String) {
            val resources = JSONArray()
            (0 until war.length()).forEach { resources.put(war.optString(it)) }
            out.put(
                "web_accessible_resources",
                JSONArray().put(
                    JSONObject()
                        .put("resources", resources)
                        .put("matches", JSONArray().put("<all_urls>")),
                ),
            )
        }
    }

    private fun dedupe(arr: JSONArray): JSONArray {
        val seen = linkedSetOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            if (v.isNotBlank()) seen += v
        }
        return JSONArray().apply { seen.forEach { put(it) } }
    }

    /** Hash curto determinístico (substituto do id da Chrome Web Store quando não há). */
    private fun stableHash(seed: String): String {
        // FNV-1a de 32 bits. O offset basis não cabe em Int: literal maior que
        // Int.MAX_VALUE é inferido como Long e o resto do laço não compila.
        var h = 0x811C9DC5.toInt()
        for (c in seed) {
            h = h xor c.code
            h *= 0x01000193
        }
        return String.format("%08x", h)
    }

    private const val MIN_GECKO_VERSION = "128.0"
}
