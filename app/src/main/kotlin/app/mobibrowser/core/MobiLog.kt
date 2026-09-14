package app.mobibrowser.core

import android.content.Context
import android.os.Build
import android.util.Log
import app.mobibrowser.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
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
    private var logFile: File? = null
    private var crashFile: File? = null
    private var errFile: File? = null
    private var startedAt = 0L

    /** Arquivo lido pela próxima abertura para avisar de morte anterior (sem `adb`, é o único canal). */
    private const val CRASH_FILE = "mobibrowser-crash.txt"

    /** Só erros, em ordem, através das aberturas. */
    private const val ERRORS_FILE = "mobibrowser-errors.txt"

    /** Chamar na primeira linha do `Application.onCreate`, antes de qualquer outra camada. */
    fun attach(context: Context) {
        startedAt = System.currentTimeMillis()
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        val file = File(dir, "mobibrowser-log.txt")
        crashFile = File(context.filesDir, CRASH_FILE)
        errFile = File(context.filesDir, ERRORS_FILE)
        runCatching {
            dir.mkdirs()
            if (file.exists() && file.length() > MAX_FILE_BYTES) file.delete()
            writer = PrintWriter(FileOutputStream(file, true), true)
            logFile = file
        }.onFailure { Log.w(TAG, "sem arquivo de log: ${it.message}") }
        i("app", "──── sessão ${stamp.format(Date(startedAt))} ────")
    }

    /**
     * Última linha de defesa: escreve a pilha em dois destinos antes de o processo acabar — o
     * arquivo de log, para quem consegue abrir a tela Sobre, e um arquivo de crash que a
     * PRÓXIMA abertura mostra na primeira tela. Sem o segundo, um app que morre em segundos num
     * aparelho sem `adb` é indistinguível de um app que não abre: a pessoa não tem como chegar ao
     * botão de copiar a tempo.
     */
    fun guardCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            e("app", "FATAL em '${thread.name}'", error)
            val trace = StringWriter().let { sink ->
                PrintWriter(sink).use { it.println(Log.getStackTraceString(error)) }
                sink.toString()
            }
            runCatching {
                crashFile?.writeText(
                    buildString {
                        appendLine("MobiBrowser ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})")
                        appendLine("motor: GeckoView ${BuildConfig.GECKOVIEW_VERSION} · canal ${BuildConfig.GECKOVIEW_CHANNEL}")
                        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
                        appendLine("thread: ${thread.name}")
                        appendLine()
                        appendLine(trace)
                        appendLine("---- últimos 40 segundos de log ----")
                        appendLine(tail(60))
                    },
                )
            }
            runCatching { writer?.flush() }
            // Só a main thread devolve o erro ao sistema (sem ela não há UI para manter viva, e
            // engolir ali escondceria um erro irrecuperável). Uma thread de fundo que morre, ao
            // contrário, não pode levar o navegador inteiro junto: era isso que transformava
            // "o motor recusou a ponte" em "o app fecha sozinho".
            if (thread.name == "main") {
                runCatching { previous?.uncaughtException(thread, error) }
            } else {
                Log.e(TAG, "mantendo o app vivo após FATAL em '${thread.name}'", error)
            }
        }
    }

    /**
     * A pilha da morte anterior, se houver. Lida e apagada: é um aviso de uma vez, não uma
     * cicatriz permanente na primeira tela.
     */
    fun takePendingCrash(context: Context): String? {
        val file = crashFile ?: File(context.filesDir, CRASH_FILE)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        return text?.takeIf { it.isNotBlank() }
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
        // Erros vão também para um arquivo próprio, que não é podado pelo limite de tamanho do
        // log: quando o app fecha dez vezes seguidas, o que interessa é a *sequência* das
        // falhas entre aberturas, e ela sobreviveria ao corte só neste segundo arquivo.
        if (level == 'E') runCatching {
            errFile?.appendText(line + "\n" + (err?.let { Log.getStackTraceString(it) + "\n" } ?: "\n"))
        }
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
        // O anel em memória guarda uma linha por evento; só o arquivo tem as pilhas. Prefiro o
        // arquivo e deixo o anel como reserva (storage externo não montado, arquivo desativado).
        val fromFile = runCatching {
            logFile?.readLines()?.takeLast(400)?.joinToString("\n")
        }.getOrNull()
        append(fromFile?.takeIf { it.isNotBlank() } ?: tail())
        val errs = runCatching { errFile?.readText()?.lines()?.takeLast(80)?.joinToString("\n") }.getOrNull()
        if (!errs.isNullOrBlank()) {
            appendLine()
            appendLine("---- erros acumulados entre aberturas ----")
            append(errs)
        }
    }
}
