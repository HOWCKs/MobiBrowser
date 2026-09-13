package app.mobibrowser.core.ext

import android.net.Uri
import app.mobibrowser.BuildConfig
import app.mobibrowser.core.MobiLog
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cliente da Chrome Web Store para o fluxo "instalar daqui".
 *
 * Como funciona de verdade: a loja não tem uma API pública de busca, mas o serviço de
 * atualização do Chrome (`clients2.google.com/service/update2/crx`) entrega o pacote
 * CRX de um item da loja dado o id — é o mesmo canal que o Chrome usa para atualizar
 * extensões. Então:
 *
 *  - a **busca** é feita navegando na própria loja dentro do MobiBrowser (UI do Google,
 *    sem scraping nosso, nada para quebrar quando a loja mudar de layout);
 *  - ao chegar numa página `/detail/<slug>/<id>`, extraímos o id da URL e oferecemos
 *    instalar;
 *  - metadados (nome/descrição/ícone) vêm de `og:*` da página do item — *best effort*:
 *    se a loja mudar o HTML, a instalação continua funcionando, só some o preview.
 *
 * É um endpoint não documentado: tratado como otimização, nunca como dependência.
 */
class ChromeWebStore {

    companion object {
        private const val SCOPE = "cws"
        const val STORE_URL = "https://chromewebstore.google.com/"
        const val UPDATE_ENDPOINT = "https://clients2.google.com/service/update2/crx"
        private val EXT_ID = Regex("^[a-p]{32}$")

        /** Extrai o id de 32 letras de uma URL da loja (ou aceita o id digitado). */
        fun idFrom(input: String?): String? {
            val value = input?.trim().orEmpty()
            if (value.isEmpty()) return null
            if (EXT_ID.matches(value)) return value
            val path = runCatching { Uri.parse(value).pathSegments }.getOrNull() ?: return null
            return path.lastOrNull()?.takeIf { EXT_ID.matches(it) }
        }

        fun isStorePage(url: String?): Boolean =
            url != null && url.contains("chromewebstore.google.com") &&
                (url.contains("/detail/") || url.contains("/category/"))

        fun detailUrl(id: String) = "$STORE_URL/detail/item/$id"
    }

    data class Summary(
        val id: String,
        val name: String?,
        val description: String?,
        val iconUrl: String?,
        val pageUrl: String,
    ) {
        val hasMetadata: Boolean get() = name != null || description != null
    }

    /** Baixa o `.crx` para [dest]. Lança IOException com mensagem útil para a UI. */
    suspend fun downloadCrx(id: String, dest: File): File = withContext(Dispatchers.IO) {
        require(EXT_ID.matches(id)) { "Id de extensão inválido: $id" }
        dest.parentFile?.mkdirs()
        val url = "$UPDATE_ENDPOINT?response=redirect&acceptformat=crx3" +
            "&prodversion=${BuildConfig.CWS_PRODVERSION}&x=id%3D$id%26uc"
        val conn = openConnection(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException(
                    when (code) {
                        404 -> "A Chrome Web Store não respondeu por esse id (404). Pode ser item privado, removido ou indisponível no seu país."
                        403 -> "A loja recusou o pedido (403)."
                        else -> "Falha no download da extensão (HTTP $code)."
                    },
                )
            }
            dest.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
            MobiLog.i(SCOPE, "CRX de $id baixado: ${dest.length()} bytes")
            dest
        } finally {
            conn.disconnect()
        }
    }

    /** Nome/descrição/ícone da página do item; nunca falha o fluxo se o HTML mudar. */
    suspend fun fetchSummary(id: String): Summary = withContext(Dispatchers.IO) {
        val pageUrl = detailUrl(id)
        runCatching {
            val conn = openConnection(pageUrl)
            val html = try {
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            Summary(
                id = id,
                name = metaContent(html, "og:title")?.substringBefore(" - ")?.trim(),
                description = metaContent(html, "og:description")?.trim(),
                iconUrl = Regex("""https://lh3\.googleusercontent\.com/[^"'\\s]+""")
                    .find(html)?.value,
                pageUrl = pageUrl,
            )
        }.getOrElse {
            MobiLog.w(SCOPE, "sem metadados para $id: ${it.message}")
            Summary(id = id, name = null, description = null, iconUrl = null, pageUrl = pageUrl)
        }
    }

    private fun metaContent(html: String, property: String): String? {
        val regex = Regex(
            """<meta[^>]+(?:property|name)="$property"[^>]+content="([^"]*)"""",
            RegexOption.IGNORE_CASE,
        )
        val match = regex.find(html) ?: return null
        return match.groupValues.getOrNull(1)?.unescapeHtml()
    }

    private fun String.unescapeHtml(): String =
        replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private fun openConnection(url: String) = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 40_000
        // Chrome se identifica assim para esse endpoint; sem isso a loja pode devolver 403.
        setRequestProperty("User-Agent", "MobiBrowser/${BuildConfig.VERSION_NAME} Chrome/${BuildConfig.CWS_PRODVERSION}")
        setRequestProperty("Accept", "*/*")
    }
}
