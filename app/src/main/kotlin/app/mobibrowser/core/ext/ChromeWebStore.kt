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

    /**
     * Baixa o `.crx` para [dest] e só devolve quando o arquivo é o que diz ser.
     *
     * As três coisas que esta função garante, porque cada uma delas já virou mensagem de erro na
     * tela do usuário:
     *  - `Accept-Encoding: identity`: o `HttpURLConnection` do Android pede gzip por conta própria
     *    e descompacta sozinho; num corpo binário com redirecionamento isso devolvia stream
     *    truncado e o `ZipInputStream` reclamava com "Unexpected end of ZLIB input stream".
     *  - tamanho conferido com o `Content-Length`: corte no meio da 4G deixa CRX parcial, que é o
     *    mesmo erro acima, e sem esta checagem a mensagem continuaria misteriosa.
     *  - mágica `Cr24`/`PK` no começo: se a loja devolver HTML (página de erro, filtro
     *    regional), isso diz "a loja não mandou um pacote" em vez de deixar o parser reclamar.
     */
    suspend fun downloadCrx(id: String, dest: File): File = withContext(Dispatchers.IO) {
        require(EXT_ID.matches(id)) { "Id de extensão inválido: $id" }
        dest.parentFile?.mkdirs()
        val url = "$UPDATE_ENDPOINT?response=redirect&acceptformat=crx3" +
            "&prodversion=${BuildConfig.CWS_PRODVERSION}&x=id%3D$id%26uc"
        var lastError: Throwable? = null
        // Duas tentativas: o endpoint de atualização da loja responde 403/503 com frequência em
        // rede móvel, e "tente de novo" não é resposta para dar a uma pessoa que só queria
        // instalar uma extensão.
        for (attempt in 1..2) {
            val outcome = runCatching { fetchOnce(url, dest) }
            val error = outcome.exceptionOrNull()
            if (error == null) return@withContext dest
            lastError = error
            MobiLog.w(SCOPE, "tentativa $attempt de download de $id falhou: ${error.message}")
            runCatching { dest.delete() }
            if (attempt < 2) kotlinx.coroutines.delay(1_200L)
        }
        throw IOException(
            lastError?.message ?: "Falha no download da extensão.",
            lastError,
        )
    }

    private fun fetchOnce(url: String, dest: File) {
        val conn = openConnection(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException(
                    when (code) {
                        404 -> "A Chrome Web Store não respondeu por esse id (404). Pode ser item privado, removido ou indisponível no seu país."
                        403 -> "A loja recusou o pedido (403). Isso costuma passar se tentar de novo em instantes."
                        else -> "Falha no download da extensão (HTTP $code)."
                    },
                )
            }
            val enc = conn.contentEncoding.orEmpty()
            val declared = conn.contentLengthLong
            val raw: java.io.InputStream = conn.inputStream
            val body = if (enc.equals("gzip", ignoreCase = true)) java.util.zip.GZIPInputStream(raw) else raw
            body.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
            val written = dest.length()
            if (written <= 0L) throw IOException("A loja devolveu um pacote vazio ($code).")
            // Content-Length não é confiável quando o servidor compacta por conta própria; só
            // reclamo quando os dois números existem e divergem.
            if (enc.isEmpty() && declared > 0 && written < declared) {
                throw IOException(
                    "O download parou em ${written / 1024} KB de ${declared / 1024} KB — a conexão " +
                        "caiu no meio. Tente de novo.",
                )
            }
            val magic = ByteArray(4).also { a ->
                java.io.FileInputStream(dest).use { it.read(a) }
            }.toString(Charsets.ISO_8859_1)
            if (magic != "Cr24" && magic.take(2) != "PK") {
                throw IOException(
                    "A Chrome Web Store devolveu uma página, não um pacote de extensão " +
                        "(começa com ${magic.replace("\n", " ")}). O item pode estar indisponível na sua região.",
                )
            }
            MobiLog.i(SCOPE, "CRX baixado para ${dest.name}: $written bytes")
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
        readTimeout = 90_000
        // Chrome se identifica assim para esse endpoint; sem isso a loja pode devolver 403.
        setRequestProperty("User-Agent", "MobiBrowser/${BuildConfig.VERSION_NAME} Chrome/${BuildConfig.CWS_PRODVERSION}")
        setRequestProperty("Accept", "*/*")
        // Sem isto o Android ativa gzip transparente sozinho, e o corpo binário do CRX voltava
        // truncado (foi o "Unexpected end of ZLIB input stream" que apareceu na tela).
        setRequestProperty("Accept-Encoding", "identity")
    }
}
