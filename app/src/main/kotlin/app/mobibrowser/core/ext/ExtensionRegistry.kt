package app.mobibrowser.core.ext

import android.content.Context
import app.mobibrowser.core.MobiLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registro local das instalações: o que o motor sabe sobre a extensão
 * ([org.mozilla.geckoview.WebExtension]) é volátil e incompleto para a nossa UI
 * (origem, versão convertida, modo de execução, política por site). Então guardamos
 * um espelho em JSON no diretório privado e casamos por `geckoId` na hora de montar a tela.
 *
 * Arquivo em vez de banco: são poucos registros, leitura uma vez no start, e assim o
 * usuário pode inspecionar/copiar o estado sem toolchain extra.
 */
class ExtensionRegistry(
    context: Context,
    private val scope: CoroutineScope,
) {

    enum class Mode {
        /** Instalada no motor como WebExtension real (assinada/aceita pelo Gecko). */
        NATIVE,

        /** Content scripts/estilos/regras servidos pela extensão-ponte embutida. */
        BRIDGE,
        ;
    }

    enum class SiteAccess {
        ALL_SITES,
        ALLOW_LIST,
        ;
    }

    data class Record(
        val geckoId: String,
        val storeId: String? = null,
        val name: String,
        val version: String = "0",
        val mode: Mode = Mode.BRIDGE,
        val xpiPath: String? = null,
        val dirPath: String? = null,
        val enabled: Boolean = true,
        val siteAccess: SiteAccess = SiteAccess.ALL_SITES,
        val allowList: List<String> = emptyList(),
        /** Só tem efeito em [SiteAccess.ALL_SITES]: sites onde a extensão está desligada. */
        val denyList: List<String> = emptyList(),
        val privateAllowed: Boolean = false,
        val permissions: List<String> = emptyList(),
        val origins: List<String> = emptyList(),
        val dataCollectionPermissions: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val droppedKeys: List<String> = emptyList(),
        /** Array JSON (texto) das regras dNR extraídas do pacote, para o modo ponte. */
        val dnrRules: String? = null,
        val optionsPage: String? = null,
        val popupPage: String? = null,
        val installedAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        /** Versão mais nova encontrada na loja, se houver (badge de atualização). */
        val updateAvailable: String? = null,
    ) {
        /**
         * Decide se a extensão pode rodar em [url].
         * ALL_SITES + denyList cobre o caso mais comum no celular ("desliga só neste site"),
         * ALLOW_LIST + allowList é o modo conservador (whitelist explícita).
         */
        fun allowsSite(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            if (siteAccess == SiteAccess.ALL_SITES) {
                return denyList.none { pattern -> MatchPattern.matches(pattern, url) }
            }
            return MatchPattern.matchesAny(allowList.ifEmpty { origins }, url)
        }

        val isBridge: Boolean get() = mode == Mode.BRIDGE
    }

    private val file = File(context.filesDir, "extensoes.json")
    private val _records = MutableStateFlow<Map<String, Record>>(emptyMap())
    val records: StateFlow<Map<String, Record>> = _records.asStateFlow()

    private var persistJob: Job? = null

    init {
        _records.value = read()
        MobiLog.d(SCOPE, "${_records.value.size} registro(s) carregados de ${file.name}")
    }

    fun all(): List<Record> = _records.value.values.sortedBy { it.name.lowercase() }

    fun get(geckoId: String): Record? = _records.value[geckoId]

    fun upsert(record: Record) {
        _records.value = _records.value + (record.geckoId to record)
        persist()
    }

    fun update(geckoId: String, transform: (Record) -> Record) {
        val current = _records.value[geckoId] ?: return
        upsert(transform(current))
    }

    fun remove(geckoId: String) {
        _records.value = _records.value - geckoId
        persist()
    }

    fun bridgeRecords(): List<Record> = _records.value.values.filter { it.mode == Mode.BRIDGE && it.enabled }

    /* ------------------------------------------------------------------ */

    private fun persist() {
        persistJob?.cancel()
        persistJob = scope.launch(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("version", 1)
                put(
                    "records",
                    JSONArray().apply { _records.value.values.forEach { put(it.toJson()) } },
                )
            }
            runCatching { file.writeText(payload.toString(1)) }
                .onFailure { MobiLog.w(SCOPE, "não consegui gravar o registro de extensões", it) }
        }
    }

    private fun read(): Map<String, Record> = runCatching {
        if (!file.isFile) return emptyMap()
        val root = JSONObject(file.readText())
        val arr = root.optJSONArray("records") ?: JSONArray()
        buildMap {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { json ->
                    val record = fromJson(json)
                    if (record != null) put(record.geckoId, record)
                }
            }
        }
    }.getOrElse {
        MobiLog.w(SCOPE, "registro de extensões ilegível, recomeçando vazio", it)
        emptyMap()
    }

    private fun Record.toJson() = JSONObject().apply {
        put("geckoId", geckoId)
        put("storeId", storeId ?: JSONObject.NULL)
        put("name", name)
        put("version", version)
        put("mode", mode.name)
        put("xpiPath", xpiPath ?: JSONObject.NULL)
        put("dirPath", dirPath ?: JSONObject.NULL)
        put("enabled", enabled)
        put("siteAccess", siteAccess.name)
        put("allowList", allowList.toJsonArray())
        put("denyList", denyList.toJsonArray())
        put("privateAllowed", privateAllowed)
        put("permissions", permissions.toJsonArray())
        put("origins", origins.toJsonArray())
        put("dataCollectionPermissions", dataCollectionPermissions.toJsonArray())
        put("warnings", warnings.toJsonArray())
        put("droppedKeys", droppedKeys.toJsonArray())
        put("dnrRules", dnrRules ?: JSONObject.NULL)
        put("optionsPage", optionsPage ?: JSONObject.NULL)
        put("popupPage", popupPage ?: JSONObject.NULL)
        put("installedAt", installedAt)
        put("updatedAt", updatedAt)
        put("updateAvailable", updateAvailable ?: JSONObject.NULL)
    }

    private fun fromJson(json: JSONObject): Record? {
        val geckoId = json.optString("geckoId").ifBlank { return null }
        return Record(
            geckoId = geckoId,
            storeId = json.optStringOrNull("storeId"),
            name = json.optString("name", geckoId),
            version = json.optString("version", "0"),
            mode = runCatching { Mode.valueOf(json.optString("mode", Mode.BRIDGE.name)) }
                .getOrDefault(Mode.BRIDGE),
            xpiPath = json.optStringOrNull("xpiPath"),
            dirPath = json.optStringOrNull("dirPath"),
            enabled = json.optBoolean("enabled", true),
            siteAccess = runCatching { SiteAccess.valueOf(json.optString("siteAccess", SiteAccess.ALL_SITES.name)) }
                .getOrDefault(SiteAccess.ALL_SITES),
            allowList = json.optStringList("allowList"),
            denyList = json.optStringList("denyList"),
            privateAllowed = json.optBoolean("privateAllowed", false),
            permissions = json.optStringList("permissions"),
            origins = json.optStringList("origins"),
            dataCollectionPermissions = json.optStringList("dataCollectionPermissions"),
            warnings = json.optStringList("warnings"),
            droppedKeys = json.optStringList("droppedKeys"),
            dnrRules = json.optStringOrNull("dnrRules"),
            optionsPage = json.optStringOrNull("optionsPage"),
            popupPage = json.optStringOrNull("popupPage"),
            installedAt = json.optLong("installedAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            updateAvailable = json.optStringOrNull("updateAvailable"),
        )
    }

    private fun List<String>.toJsonArray() = JSONArray().apply { forEach { put(it) } }

    private fun JSONObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }

    private companion object {
        const val SCOPE = "registry"
    }
}
