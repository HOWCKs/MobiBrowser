package app.mobibrowser.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Coletor de evidência de encerramento. Existe para responder uma pergunta que o `Logcat`
 * responde e nós não temos como abrir: **por que o processo morreu**.
 *
 * Três sensores, cada um cobrindo um tipo de morte diferente — porque um só não cobre:
 *
 * 1. [ApplicationExitInfo] (API 30+): o sistema registra o motivo de cada encerramento do
 *    nosso próprio pacote — crash nativo, sinal, memória baixa, ANR, saída voluntária — com
 *    PSS/RSS da hora e o *trace* (para crash nativo é o tombstone: `Fatal signal 11 …`, o
 *    `abort message` e as pilhas). É o fim da frase "o app fecha sem avisar": o sistema avisou,
 *    só que num lugar que o app pode ler e o usuário não.
 * 2. Bomba de `logcat --pid <nosso pid>` em arquivo: um `SIGSEGV`/`abort()` não passa por
 *    nenhum handler Java, então o que sobrevive é o que estava sendo escrito. Ler o próprio log
 *    é permitido desde o Android 7 sem `READ_LOGS`, e o `--pid` limita ao nosso processo — nada
 *    de outros apps. Grava a cada linha com autoflush porque a última linha é justamente a que
 *    importa.
 * 3. Batimento com fase: um arquivo reescrito a cada 500 ms dizendo em que ponto do
 *    nascimento estávamos, mais RSS e heap. Se o processo é morto entre dois batimentos, a
 *    última fase escrita delimita o suspeito; se o RSS estava estourando, é o sistema
 *    economizando memória e não um bug nosso.
 *
 * Nada aqui usa `Thread` de UI, nem aloca buffer grande, nem lança para fora: um coletor que
 * derruba o app que ele está observando é pior que o bug.
 */
object Diag {

    private const val MAX_LOGCAT_BYTES = 2_000_000L
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val TRACE_TAIL_CHARS = 3_500

    @Volatile
    private var phase: String = "nascimento"

    /** A última fase escrita — em caso de morte, é ela que delimita o suspeito. */
    val lastPhase: String get() = phase

    @Volatile
    private var running = false

    private var pump: java.lang.Process? = null
    private var heart: Thread? = null
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Causa do encerramento anterior, lida do sistema. Guardada aqui para a primeira tela
     * mostrar sem que ninguém precise navegar até lugar nenhum (e sem correr contra o próximo
     * fechamento, que é o que aconteceria se dependesse de abrir Ajustes).
     */
    @Volatile
    var lastExit: String? = null

    /** Padrão das últimas 24 h ("3× crash nativo"), para o aviso não depender de um só registro. */
    @Volatile
    var exitSummary: String? = null
        private set

    // Externo (Android/data/<pacote>/files/diag) em vez de privado: quem não tem cabo nem root
    // precisa abrir o arquivo num editor de texto no próprio aparelho, e filesDir está fora de
    // alcance para isso. Se o caminho externo falhar (perfil de trabalho, armazenamento cheio),
    // caímos no privado — os coletores têm de funcionar mesmo sem ninguém poder ler.
    private fun dir(context: Context): File {
        val external = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        val base = File(external ?: context.filesDir, "diag")
        runCatching { base.mkdirs() }
        return if (base.isDirectory) base else File(context.filesDir, "diag").apply { mkdirs() }
    }

    private fun exitsFile(context: Context) = File(dir(context), "exits.txt")
    private fun liveFile(context: Context) = File(dir(context), "live.txt")
    private fun logcatFile(context: Context) = File(dir(context), "logcat.txt")

    /** Marca em que ponto do ciclo de vida estamos. Barato o bastante para chamar em toda fase. */
    fun at(name: String) {
        phase = name
    }

    /** Chamar no `Application.onCreate`, antes das camadas. Nunca lança. */
    fun start(context: Context) {
        val app = context.applicationContext
        running = true
        runCatching { readExits(app) }
            .onFailure { MobiLog.w("diag", "não consegui ler os encerramentos anteriores", it) }
        runCatching { startLogcatPump(app) }
            .onFailure { MobiLog.w("diag", "sem bomba de logcat", it) }
        runCatching { startHeartbeat(app) }
            .onFailure { MobiLog.w("diag", "sem batimento de fase", it) }
        MobiLog.i("diag", "coletores ligados · saída anterior: ${lastExit?.lineSequence()?.firstOrNull() ?: "nenhuma"}")
    }

    fun stop(context: Context) {
        running = false
        runCatching { pump?.destroy() }
        pump = null
        runCatching { heart?.interrupt() }
        heart = null
        runCatching {
            liveFile(context).writeText(
                DiagText.ended(stamp.format(Date()), phase, System.currentTimeMillis()),
            )
        }
    }

    /* ------------------------------------------------------------------ *
     * 1. O que o sistema sabe sobre a nossa morte                        *
     * ------------------------------------------------------------------ */

    private fun readExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val note = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) não expõe " +
                "ApplicationExitInfo (precisa de API 30+). O motivo do encerramento fica restrito " +
                "ao logcat e ao batimento de fase abaixo."
            writeExits(context, note)
            lastExit = note
            return
        }
        val am = context.getSystemService(ActivityManager::class.java)
        if (am == null) {
            writeExits(context, "ActivityManager indisponível; sem histórico.")
            return
        }
        val records = am.getHistoricalProcessExitReasons(context.packageName, 0, 8).orEmpty()
        if (records.isEmpty()) {
            val note = "O sistema não tem nenhum encerramento registrado para este pacote. " +
                "Isso acontece antes da primeira morte real — se o app fechar daqui para frente e " +
                "esta lista continuar vazia, quem encerrou o processo não foi um crash."
            writeExits(context, note)
            return
        }
        exitSummary = tallyRecent(records)
        val text = records.joinToString("\n\n") { info ->
            DiagText.format(
                DiagText.ExitRecord(
                    timestamp = info.timestamp,
                    pid = info.pid,
                    reason = reasonName(info.reason),
                    subReason = subReasonName(info),
                    importance = importanceName(info.importance),
                    description = info.description.orEmpty(),
                    status = info.status,
                    pssMb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.pss / 1024 else -1,
                    rssMb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.rss / 1024 else -1,
                    trace = readTrace(info),
                ),
            )
        }
        writeExits(context, text)
        lastExit = buildString {
            appendLine("O sistema registrou este encerramento do processo:")
            appendLine()
            append(text.lineSequence().take(14).joinToString("\n"))
        }
    }

    // Um encerramento é acaso; quatro do mesmo tipo em meia hora é estado. A contagem sai antes
    // do texto longo de propósito: é a primeira linha do aviso na tela, e quem abriu o app dez
    // vezes seguidas precisa ver o padrão, não só o último caso.
    private fun tallyRecent(records: Array<out ApplicationExitInfo>): String? {
        val since = System.currentTimeMillis() - DAY_MS
        val recent = runCatching { records.filter { it.timestamp >= since } }.getOrDefault(emptyList())
        if (recent.isEmpty()) return null
        val byReason = recent.groupingBy { reasonName(it.reason) }.eachCount()
            .entries.sortedByDescending { it.value }
        return buildString {
            append("Nas últimas 24 h o sistema registrou ${recent.size} encerramento(s) deste app: ")
            append(byReason.joinToString { (motivo, n) -> if (n > 1) "$motivo (×$n)" else motivo })
            append(".")
        }
    }

    private fun writeExits(context: Context, text: String) {
        runCatching { exitsFile(context).writeText(text) }
    }

    // Os dois getters de trace mudaram de nome/disponibilidade entre as versões da plataforma
    // (getTraceInputStream até o Android 13, getTraceFile como fonte depois), e nenhum dos dois
    // está no android.jar com que compilei. Um SIGSEGV sem tombstone é um número sem causa, e
    // perder o trace é o pior dano possível aqui — então nenhum nome entra no código compilado:
    // procuro os dois por reflexão, e se a OEM mexer em algum, o resto do relatório fica de pé.
    private fun readTrace(info: ApplicationExitInfo): String {
        var raw = ""
        for (getter in listOf("getTraceInputStream", "getTraceFile")) {
            if (raw.isNotBlank()) break
            raw = runCatching {
                when (val value = ApplicationExitInfo::class.java.getMethod(getter).invoke(info)) {
                    is java.io.InputStream -> value.use { it.readBytes().decodeToString() }
                    is java.io.File -> if (value.canRead()) value.readText() else ""
                    else -> ""
                }
            }.getOrNull().orEmpty()
        }
        // O começo do tombstone traz o sinal e a mensagem de abort; o fim traz as pilhas. Mostro
        // os dois e corto o meio, que é despejo de mapas de memória.
        return DiagText.compactTrace(raw, TRACE_TAIL_CHARS)
    }

    private fun subReasonName(info: ApplicationExitInfo): String = runCatching {
        val code = ApplicationExitInfo::class.java.getMethod("getSubReason").invoke(info) as Int
        // 0 = SUBREASON_UNKNOWN: sem submotivo não há o que dizer, então não invento linha.
        if (code == 0) "" else {
            val label = runCatching {
                ApplicationExitInfo::class.java
                    .getMethod("subreasonToString", Int::class.javaPrimitiveType)
                    .invoke(null, code) as? String
            }.getOrNull()
            label?.takeIf { it.isNotBlank() && !it.equals("UNKNOWN", true) } ?: "submotivo $code"
        }
    }.getOrDefault("")

    // Os códigos são os da própria plataforma (ApplicationExitInfo.REASON_* no AOSP) e cada um
    // vira uma frase que diz o que aconteceu, não só o nome técnico.
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "saída pedida pelo próprio app"
        ApplicationExitInfo.REASON_SIGNALED -> "morto por sinal (nativo; abort ou kill)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "baixa memória: o sistema escolheu este processo"
        ApplicationExitInfo.REASON_CRASH -> "exceção Java não tratada"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash nativo (pilha em código C/C++)"
        ApplicationExitInfo.REASON_ANR -> "ANR (app não respondeu)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "falha de inicialização"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "mudança de permissão"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "uso excessivo de recurso"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "pedido do usuário (forçar parada / limpar)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "parado pelo usuário"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "processo de que dependíamos morreu"
        ApplicationExitInfo.REASON_FREEZER -> "congelado e encerrado pelo sistema"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "mudança de estado do pacote"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "pacote atualizado"
        else -> "motivo ${'$'}reason"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cacheado em segundo plano"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "serviço"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "serviço em primeiro plano"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "primeiro plano"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visível"
        else -> "importância $importance"
    }

    /* ------------------------------------------------------------------ *
     * 2. logcat do próprio processo, em arquivo                          *
     * ------------------------------------------------------------------ */

    private fun startLogcatPump(context: Context) {
        val file = logcatFile(context)
        runCatching { if (file.length() > MAX_LOGCAT_BYTES) file.delete() }
        val builder = ProcessBuilder(
            "logcat", "-v", "threadtime", "--pid", android.os.Process.myPid().toString(),
        ).redirectErrorStream(true)
        val process = builder.start()
        pump = process
        val t = Thread {
            runCatching {
                FileOutputStream(file, true).use { raw ->
                    val out = raw.writer()
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        out.write("──── logcat aberto ${stamp.format(Date())} · pid ${android.os.Process.myPid()} ────\n")
                        out.flush()
                        var bytes = file.length()
                        while (running) {
                            val line = reader.readLine() ?: break
                            out.write(line)
                            out.write("\n")
                            out.flush()
                            bytes += line.length + 1
                            if (bytes > MAX_LOGCAT_BYTES) {
                                out.write("──── logcat cortado em ${MAX_LOGCAT_BYTES / 1024} KB ────\n")
                                out.flush()
                                break
                            }
                        }
                    }
                }
            }.onFailure { MobiLog.w("diag", "bomba de logcat parou", it) }
        }
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.name = "mobi-logcat-pump"
        t.start()
    }

    /* ------------------------------------------------------------------ *
     * 3. batimento de fase                                                 *
     * ------------------------------------------------------------------ */

    private fun startHeartbeat(context: Context) {
        val file = liveFile(context)
        val t = Thread {
            val started = System.currentTimeMillis()
            while (running) {
                val rt = Runtime.getRuntime()
                val text = DiagText.heartbeat(
                    stamp = stamp.format(Date()),
                    phase = phase,
                    aliveMs = System.currentTimeMillis() - started,
                    heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576,
                    heapMaxMb = rt.maxMemory() / 1048576,
                    rssMb = readRssKb() / 1024,
                    threads = Thread.activeCount(),
                )
                runCatching { file.writeText(text) }
                try {
                    Thread.sleep(500)
                } catch (interrupted: InterruptedException) {
                    return@Thread
                }
            }
        }
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.name = "mobi-heartbeat"
        t.start()
        heart = t
    }

    private fun readRssKb(): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.filter { c -> c.isDigit() }
                ?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    /* ------------------------------------------------------------------ */

    /** Tudo o que os três sensores sabem, num texto só — é o que o botão Copiar entrega. */
    fun export(context: Context): String = buildString {
        append(MobiLog.report(context))
        appendLine()
        appendLine("==== encerramentos registrados pelo sistema (ApplicationExitInfo) ====")
        appendLine(read(exitsFile(context)))
        appendLine()
        appendLine("==== batimento de fase (última linha antes da morte) ====")
        appendLine(read(liveFile(context)))
        appendLine()
        appendLine("==== logcat da sessão (últimas 90 linhas) ====")
        appendLine(
            read(logcatFile(context)).lineSequence().toList().takeLast(90).joinToString("\n"),
        )
    }

    private fun read(file: File): String = runCatching { file.readText() }.getOrDefault("(sem arquivo)")
}

/**
 * Formatação pura, sem tipo Android, para poder ser testada na CI — o que o `Diag` coleta só
 * existe num aparelho, mas a *leitura* do que ele coleta é aritmética de string e é aqui que
 * ela pode quebrar silenciosamente.
 */
object DiagText {

    data class ExitRecord(
        val timestamp: Long,
        val pid: Int,
        val reason: String,
        val subReason: String,
        val importance: String,
        val description: String,
        val status: Int,
        val pssMb: Long,
        val rssMb: Long,
        val trace: String,
    )

    fun format(e: ExitRecord): String = buildString {
        val when1 = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(e.timestamp))
        appendLine("em $when1 · pid ${e.pid}")
        appendLine("  motivo: ${e.reason}")
        if (e.subReason.isNotBlank()) appendLine("  sub-motivo: ${e.subReason}")
        appendLine("  estado do processo: ${e.importance}")
        if (e.description.isNotBlank()) appendLine("  descrição: ${e.description}")
        if (e.status != 0) appendLine("  status do wait(): ${e.status}")
        if (e.pssMb >= 0) appendLine("  memória no momento: PSS ${e.pssMb} MB · RSS ${e.rssMb} MB")
        if (e.trace.isNotBlank()) {
            appendLine("  pilha registrada:")
            e.trace.lineSequence().take(40).forEach { appendLine("    $it") }
        }
    }.trimEnd()

    /**
     * Cabeça + cauda do tombstone. A cabeça traz `Fatal signal 6 (SIGABRT), code …` e o
     * `Abort message is '...'`; a cauda, as pilhas. O meio é mapa de memória e não serve para
     * nada em uma mensagem de erro.
     */
    fun compactTrace(raw: String, budget: Int): String {
        val text = raw.trim()
        if (text.length <= budget) return text
        val head = text.lines().take(18).joinToString("\n")
        val tail = text.lines().takeLast(22).joinToString("\n")
        val middle = text.length - head.length - tail.length
        return head + "\n    … $middle caracteres omitidos (mapa de memória) …\n" + tail
    }

    fun heartbeat(stamp: String, phase: String, aliveMs: Long, heapUsedMb: Long, heapMaxMb: Long, rssMb: Long, threads: Int): String =
        "$stamp vivo ${aliveMs}ms · fase=$phase · heap ${heapUsedMb}/${heapMaxMb} MB · RSS ${rssMb} MB · $threads threads\n" +
            "        (se o app morreu, a última fase escrita aqui é o ponto em que estávamos)\n"

    fun ended(stamp: String, phase: String, at: Long): String =
        "$stamp encerrado limpo na fase=$phase · sem morte súbita registrada\n"
}
