package app.mobibrowser.core

import android.content.Context
import android.os.Build
import android.util.Log
import app.mobibrowser.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log do app em três destinos: o logcat (para quem tem cabo), um anel em memória (para a tela
 * Sobre copiar) e um arquivo em `getExternalFilesDir(…)/logs`.
 *
 * O arquivo existe por um motivo prático deste projeto: quem testa é uma pessoa com o APK
 * instalado, sem `adb`, e o único canal de feedback é o que ela consegue copiar no próprio
 * aparelho. Toda vez que a inicialização trava sem interface, a pergunta vira "o que aconteceu
 * antes de travar?" — sem registro em arquivo, ninguém responde.
 *
 * Níveis: [d] só grava em build instável ([BuildConfig.MOBI_DEBUG_LOGS]); [i], [w] e [e]
 * sempre. O `PrintWriter` abre em modo acréscimo com auto-flush, então uma linha gravada antes
 * de um crash nativo sobrevive ao processo.
 */
object MobiLog {

    private const val TAG = "MobiBrowser"
    private const val MAX_LINES = 800
    private const val MAX_FILE_BYTES = 600_000L

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val ring = ArrayDeque<String>()
    private var writer: PrintWriter? = null
    private var startedAt = 0L

    /** Chamar na primeira linha do `Application.onCreate`, antes de qualquer outra camada. */
    fun attach(context: Context) {
        startedAt = System.currentTimeMillis()
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        val file = File(dir, "mobibrowser-log.txt")
        runCatching {
            dir.mkdirs()
            if (file.exists() && file.length() > MAX_FILE_BYTES) file.delete()
            writer = PrintWriter(FileOutputStream(file, true), true)
        }.onFailure { Log.w(TAG, "sem arquivo de log: ${it.message}") }
        i("app", "log em ${file.absolutePath}")
    }

    /**
     * Última linha de defesa: copia o estado para o arquivo antes de o sistema matar o
     * processo. Sem isto, um estouro na criação do `GeckoRuntime` deixa só um buraco negro na
     * tela e uma pilha que ninguém vê.
     */
    fun guardCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            e("app", "FATAL em '${thread.name}'", error)
            runCatching { writer?.flush() }
            runCatching { previous?.uncaughtException(thread, error) }
        }
    }

    fun d(scope: String, msg: String) {
        if (BuildConfig.MOBI_DEBUG_LOGS) record('D', scope, msg, null)
    }

    fun i(scope: String, msg: String) = record('I', scope, msg, null)

    fun w(scope: String, msg: String, err: Throwable? = null) = record('W', scope, msg, err)

    fun e(scope: String, msg: String, err: Throwable? = null) = record('E', scope, msg, err)

    private fun record(level: Char, scope: String, msg: String, err: Throwable?) {
        val text = "[$level][$scope] $msg"
        when (level) {
            'E' -> Log.e(TAG, text, err)
            'W' -> Log.w(TAG, text, err)
            'I' -> Log.i(TAG, text)
            else -> Log.d(TAG, text)
        }
        val line = "${stamp.format(Date())} $text"
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > MAX_LINES) ring.removeFirst()
            writer?.let { out ->
                out.println(line)
                err?.printStackTrace(out)
                out.flush()
            }
        }
    }

    /** As últimas [n] linhas, na ordem em que aconteceram. */
    fun tail(n: Int = 250): String = synchronized(ring) { ring.takeLast(n).joinToString("\n") }

    /**
     * O texto que a tela Sobre coloca na área de transferência: cabeçalho com o que serve para
     * diagnosticar longe do aparelho, mais o anel de log.
     */
    fun report(context: Context): String = buildString {
        appendLine("MobiBrowser ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})")
        appendLine(
            "motor: GeckoView ${BuildConfig.GECKOVIEW_VERSION} · canal ${BuildConfig.GECKOVIEW_CHANNEL} · " +
                "sem assinatura=${BuildConfig.MOBI_ALLOW_UNSIGNED_ADDONS} · logs=${BuildConfig.MOBI_DEBUG_LOGS}",
        )
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
        val rt = Runtime.getRuntime()
        appendLine("memória: ${rt.maxMemory() / 1048576} MB teto · ${rt.totalMemory() / 1048576} MB reservadas")
        appendLine("sessão: ${System.currentTimeMillis() - startedAt} ms desde o onCreate · ${ring.size} linhas no anel")
        appendLine("---- log ----")
        append(tail())
    }
}
