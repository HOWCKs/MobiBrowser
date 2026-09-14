package app.mobibrowser.core.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import app.mobibrowser.BuildConfig
import app.mobibrowser.core.MobiLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Decodificação da release `nightly`, separada da rede porque é aqui que a decisão "há
 * atualização" acontece — e errar aqui significa ou nunca oferecer nada ou oferecer o build
 * errado para instalar.
 *
 * Por que não `versionCode`: o canal instável não incrementa versão a cada build, o que muda é
 * o commit. O título do release traz `MobiBrowser nightly AAAA-MM-DD (sha7)` e o app carrega
 * `BuildConfig.GIT_SHA`; diferentes = existe build mais novo. A leitura é uma função pura
 * ([parse], [shaOf], [pickAsset]) justamente para isso poder ser testado sem aparelho.
 */
internal object NightlyRelease {

    data class Asset(val name: String, val url: String, val bytes: Long)

    data class Info(
        val title: String,
        val publishedAt: String,
        val sha: String,
        val assets: List<Asset>,
    ) {
        /** Nome curto para a UI: o título longo do release é ruído numa linha de ajuste. */
        val shortLabel: String
            get() = sha.ifBlank { publishedAt.ifBlank { title } }
    }

    private val SHA_IN_TITLE = Regex("[0-9a-f]{7,40}(?=\\))")

    fun shaOf(title: String): String = SHA_IN_TITLE.find(title)?.value.orEmpty()

    /**
     * "É o mesmo build?" — por prefixo, não por igualdade: `git rev-parse --short` devolve 7
     * caracteres na maioria dos repositórios, mas aumenta sozinho quando o histórico cresce, e
     * o CI compara o sha do Gradle com o sha do shell em dois runner distintos. Igualdade
     * estrita aqui significaria oferecer o próprio build como atualização.
     */
    fun sameBuild(releaseSha: String, currentSha: String): Boolean {
        if (releaseSha.isBlank() || currentSha.isBlank()) return false
        return releaseSha.equals(currentSha, ignoreCase = true) ||
            releaseSha.startsWith(currentSha, ignoreCase = true) ||
            currentSha.startsWith(releaseSha, ignoreCase = true)
    }

    fun parse(body: String): Info {
        val node = JSONObject(body)
        val title = node.optString("name").ifBlank { node.optString("tag_name") }
        return Info(
            title = title,
            publishedAt = node.optString("published_at").substringBefore('T'),
            sha = shaOf(title),
            assets = readAssets(node),
        )
    }

    private fun readAssets(node: JSONObject): List<Asset> {
        val array = node.optJSONArray("assets") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val a = array.optJSONObject(i) ?: continue
                val name = a.optString("name")
                val url = a.optString("browser_download_url")
                if (name.endsWith(".apk") && url.isNotBlank()) {
                    add(Asset(name, url, a.optLong("size")))
                }
            }
        }
    }

    /**
     * ABI do aparelho primeiro, universal como reserva. Ordenar por [supportedAbis] é o que
     * impede o `NO_MATCHING_ABIS` no instalador: um x86_64 recebendo arm64 falha tarde demais.
     */
    fun pickAsset(assets: List<Asset>, supportedAbis: List<String>): Asset? {
        // Casamento por token delimitado: um aparelho x86 veria "-x86" dentro de
        // app-x86_64-unstable.apk. O mesmo par vale para armeabi-v7a contra arm64-v8a.
        // Não há reserva frouxa de propósito — baixar 200 MB para levar NO_MATCHING_ABIS é
        // pior do que ouvir "não há APK para este aparelho" antes de gastar a internet.
        val names = assets.map { it.name.lowercase() }
        for (abi in supportedAbis) {
            val token = abi.lowercase()
            val index = names.indexOfFirst { name ->
                name.contains("-" + token + "-") || name.contains("-" + token + ".") ||
                    name.startsWith(token + "-") || name.startsWith(token + ".") ||
                    name == token + ".apk"
            }
            if (index >= 0) return assets[index]
        }
        return assets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
    }
}

/**
 * Verificador e baixador do canal instável.
 *
 * Escolha deliberada de privacidade: nada aqui roda no início do app. Uma consulta ao GitHub a
 * cada cold start seria um canário de instalação — num navegador cujo argumento é reduzir
 * rastreamento, o usuário é quem aperta o botão (Configurações → Sobre). O preço é óbvio: sem
 * toque, sem aviso.
 *
 * Instalar continua sendo decisão do sistema: entregamos o APK por `FileProvider` e o Android
 * abre o instalador. Não existe (nem existiria) instalação silenciosa.
 */
class UpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data class UpToDate(val sha: String) : Status
        data class Available(
            val title: String,
            val publishedAt: String,
            val sha: String,
            val assetName: String,
            val assetUrl: String,
            val bytes: Long,
        ) : Status

        data class Downloading(val percent: Int, val bytes: Long) : Status
        data class Ready(val file: File, val bytes: Long) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * O APK mora em `filesDir`, não em `cacheDir`: o sistema esvazia o cache quando falta
     * espaço — e jogaria fora justamente o arquivo que o usuário está prestes a instalar.
     */
    private val downloadDir: File
        get() = File(context.filesDir, "updates").apply { mkdirs() }

    fun check() {
        _status.value = Status.Checking
        scope.launch {
            runCatching { fetchLatest() }
                .onSuccess { result ->
                    _status.value = result
                    MobiLog.i("update", "resultado: " + describe(result))
                }
                .onFailure { error ->
                    MobiLog.w("update", "verificação falhou", error)
                    _status.value = Status.Failed(messageOf(error))
                }
        }
    }

    private suspend fun fetchLatest(): Status {
        val body = getText(API_RELEASE)
        val info = NightlyRelease.parse(body)
        val abi = Build.SUPPORTED_ABIS.toList()
        val asset = NightlyRelease.pickAsset(info.assets, abi)
            ?: return Status.Failed("o release nightly não tem APK para este aparelho (abis: " + abi.first() + ")")
        if (info.sha.isBlank()) {
            return Status.Failed("não consegui ler o commit no título do release")
        }
        if (NightlyRelease.sameBuild(info.sha, BuildConfig.GIT_SHA)) return Status.UpToDate(info.sha)
        return Status.Available(
            title = info.title,
            publishedAt = info.publishedAt,
            sha = info.sha,
            assetName = asset.name,
            assetUrl = asset.url,
            bytes = asset.bytes,
        )
    }

    fun download(assetUrl: String, expectedBytes: Long) {
        scope.launch {
            val target = File(downloadDir, "update.apk")
            runCatching {
                withContext(Dispatchers.IO) {
                    // descarta APKs de tentativas anteriores antes de gastar 200 MB de novo
                    downloadDir.listFiles()?.forEach { if (it != target) it.delete() }
                    target.delete()
                    val conn = URL(assetUrl).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    try {
                        val code = conn.responseCode
                        check(code in 200..299) { "HTTP " + code + " no download do APK" }
                        val total = if (expectedBytes > 0) expectedBytes else conn.contentLengthLong
                        conn.inputStream.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    done += read
                                    if (total > 0) {
                                        _status.value = Status.Downloading(
                                            percent = ((done * 100) / total).toInt().coerceIn(0, 100),
                                            bytes = done,
                                        )
                                    }
                                }
                                output.flush()
                            }
                        }
                        if (total > 0 && target.length() != total) {
                            error(
                                "o download parou em " + target.length() + " de " + total +
                                    " bytes — arquivo incompleto, não dá para instalar",
                            )
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                _status.value = Status.Ready(target, target.length())
            }.onFailure { error ->
                target.delete()
                MobiLog.w("update", "download falhou", error)
                _status.value = Status.Failed(messageOf(error))
            }
        }
    }

    /**
     * Entrega o APK ao instalador do sistema. Em Android 8+ falta quase sempre a permissão de
     * instalar fontes desconhecidas: nesse caso abrimos a tela de ajuste correspondente e
     * avisamos — o arquivo já está baixado, então o próximo toque só instala.
     */
    fun installIntent(activity: Activity?): Intent? {
        val file = (_status.value as? Status.Ready)?.file
            ?: run {
                _status.value = Status.Failed("não há instalador baixado ainda")
                return null
            }
        if (!file.isFile) {
            _status.value = Status.Failed("o APK foi apagado do aparelho; baixe de novo")
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            activity?.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            _status.value = Status.Failed(
                "toque em Instalar apps desconhecidos para o MobiBrowser e volte aqui — o APK " +
                    "já está baixado",
            )
            return null
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private suspend fun getText(url: URL): String = withContext(Dispatchers.IO) {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "MobiBrowser/" + BuildConfig.VERSION_NAME)
        conn.connectTimeout = 12_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            check(code in 200..299) {
                if (code == 404) {
                    "o canal nightly ainda não foi publicado (HTTP 404)"
                } else {
                    "GitHub respondeu HTTP " + code
                }
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(status: Status): String = when (status) {
        is Status.UpToDate -> "em dia (" + status.sha + ")"
        is Status.Available -> "novo build " + status.sha + " · " + status.assetName
        is Status.Failed -> "falha: " + status.message
        else -> status.javaClass.simpleName
    }

    private fun messageOf(error: Throwable): String = when (error) {
        is IllegalStateException -> error.message.orEmpty()
        is java.io.FileNotFoundException -> "release não encontrado no repositório"
        is java.net.SocketTimeoutException -> "tempo esgotado — verifique a conexão"
        is java.net.UnknownHostException -> "não consegui resolver api.github.com"
        is org.json.JSONException -> "a resposta do GitHub não é o JSON esperado"
        else -> error.message ?: error.javaClass.simpleName
    }

    private companion object {
        val API_RELEASE = URL(
            "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO +
                "/releases/tags/" + BuildConfig.UPDATE_TAG,
        )
    }
}
