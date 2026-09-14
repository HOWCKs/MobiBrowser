package app.mobibrowser.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rastreamento mínimo de "o motor chegou a nascer na sessão anterior".
 *
 * Por que isto existe: um estouro *nativo* do Gecko não deixa exceção Java, e este projeto é
 * testado num aparelho sem `adb` — então "o app fecha sozinho ao abrir" é indistinguível de "o
 * processo do motor morreu ao nascer". Um arquivo com uma palavra resolve: registramos
 * [Phase.STARTING] antes de criar o `GeckoRuntime` e [Phase.READY] quando a primeira sessão
 * abre. Se a próxima abertura encontrar STARTING sem READY, a culpa está no caminho do motor, e o
 * app entra em recuperação em vez de morrer pela segunda vez.
 *
 * A recuperação é estreita de propósito: pausa só o que toca o motor de extensões (o
 * `WebExtensionController` e a ponte), porque é ali que o GeckoView do canal estável recusa/aborta
 * com mais frequência. Navegar continua funcionando — desligar tudo seria trocar um crash por um
 * app inútil.
 *
 * O [streak] (mortes seguidas no mesmo ponto) existe porque um sinal isolado pode ser acaso, e
 * três do mesmo tipo é um estado corrompido. A partir de duas, [repair] tira do caminho do motor
 * exatamente os dois arquivos que uma morte no meio da inicialização deixa para trás e que
 * bloqueiam a próxima: o cadeado do perfil e os despejos de memória do relatório de falha. Não é
 * palpite sobre o nome dos arquivos — o `minidumps` vem do próprio `GeckoRuntime`
 * (`new File(context.getFilesDir(), "minidumps")`), e o cadeado é procurado por padrão de nome,
 * porque o nome exato muda entre versões do Gecko; apagar por padrão é mais honesto do que
 * inventar um caminho.
 *
 * [engineOff] é o interruptor manual, e a razão de ele morar aqui e não no DataStore: precisa ser
 * lido *antes* de qualquer suspensão, na primeira linha do `Application.onCreate`.
 */
object EngineGuard {

    enum class Phase { NONE, STARTING, READY }

    /** Instantâneo lido no início; imutável para o resto da sessão. */
    data class Snapshot(
        val phase: Phase,
        val engineOff: Boolean,
        /** true quando a sessão anterior terminou entre iniciar o motor e abrir a primeira sessão. */
        val diedAtEngineStart: Boolean,
        /** quantas aberturas seguidas morreram antes do READY, incluindo esta contagem anterior. */
        val streak: Int,
        /** o que foi limpo antes desta abertura, ou nulo quando nada precisou ser limpo. */
        val repair: String?,
    )

    @Volatile
    var engineOff: Boolean = false
        private set

    @Volatile
    var extensionsPaused: Boolean = false
        private set

    /** Texto curto e sem jargão mostrado na primeira tela; nulo quando não há nada a dizer. */
    @Volatile
    var notice: String? = null
        private set

    /** Detalhe técnico (o que foi limpo, quantas vezes), guardado para a tela de desenvolvimento. */
    @Volatile
    var technical: String? = null
        private set

    /** Mortes seguidas antes do motor nascer — lido pela tela de diagnóstico. */
    @Volatile
    var streak: Int = 0
        private set

    fun debugStreak(): Int = streak

    private const val FILE = "mobibrowser-engine.txt"

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** Lê o estado anterior e decide as flags desta sessão. Chamar antes de criar qualquer camada. */
    fun begin(context: Context): Snapshot {
        val raw = runCatching { file(context).readText() }.getOrNull()
        val phase = raw?.substringBefore('|')?.let { p -> Phase.entries.firstOrNull { it.name == p } } ?: Phase.NONE
        val manualOff = raw?.substringAfter('|')?.substringBefore('|') == "1"
        val previousStreak = raw?.substringAfterLast('|')?.trim()?.toIntOrNull() ?: 0
        val died = phase == Phase.STARTING
        // Duas contagens separadas: `streak` é o número (aparece no diagnóstico), e a recuperação
        // começa já na primeira morte no berço porque pausar o motor de extensões custa pouco e
        // cobre o caso mais frequente. O reparo de perfil só entra na segunda.
        val count = if (died) previousStreak + 1 else 0
        streak = count
        val repair = if (count >= 2) repairEngineFiles(context) else null
        val snapshot = Snapshot(
            phase = phase,
            engineOff = manualOff,
            diedAtEngineStart = died,
            streak = count,
            repair = repair,
        )
        engineOff = manualOff
        extensionsPaused = died && !manualOff
        notice = when {
            manualOff ->
                "Você desligou o motor de páginas para fazer teste. Enquanto estiver assim, nenhum " +
                    "site abre — ligue de novo em Ajustes → Modo avançado."

            repair != null ->
                "O aplicativo foi interrompido $count vezes seguidas antes de o motor de páginas " +
                    "terminar de abrir. Esta abertura já começou limpando o que a última " +
                    "interrupção deixou para trás (veja Ajustes → Modo avançado → Diagnóstico). " +
                    "Se fechar outra vez, me mande o texto do Diagnóstico: ele agora sai sozinho."

            died ->
                "Na última vez, o aplicativo parou enquanto o motor de páginas abria, sem deixar " +
                    "erro registrado. Por segurança, esta abertura começa sem o motor de " +
                    "extensões. Navegar funciona normalmente."

            else -> null
        }
        technical = buildString {
            append("guarda do motor · fase anterior=${phase.name} · manual=${manualOff} · ")
            append("extensões pausadas=${extensionsPaused} · mortes seguidas no berço=$count")
            if (repair != null) {
                appendLine()
                append(repair)
            }
        }.also { MobiLog.i("engine", it.replace('\n', ' ')) }
        return snapshot
    }

    /**
     * Remove o que uma morte durante a inicialização deixa apontando para o processo morto.
     * Devolve um texto dizendo o que foi apagado — se nada foi encontrado, ele diz isso também,
     * porque "limpei e por isso vai funcionar" sem evidência seria conversa.
     */
    private fun repairEngineFiles(context: Context): String = buildString {
        val at = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        append("limpeza de emergência ($at):")
        var removed = 0
        var bytes = 0L
        val roots = listOf(
            // Perfil do GeckoView: é onde o cadeado do perfil mora.
            runCatching { context.getDir("geckoview", Context.MODE_PRIVATE) }.getOrNull(),
            // Despejos do relatório de falha — o caminho é o do próprio GeckoRuntime.
            File(context.filesDir, "minidumps"),
            File(context.cacheDir, "minidumps"),
        )
        for (root in roots.filterNotNull()) {
            val found = runCatching {
                if (!root.isDirectory) return@runCatching emptyList()
                root.walkTopDown().maxDepth(3)
                    .filter { f ->
                        f.isFile && (
                            f.name.contains("lock", ignoreCase = true) ||
                                f.extension.equals("dmp", ignoreCase = true) ||
                                f.name.endsWith(".tmp-old", ignoreCase = true)
                            )
                    }.toList()
            }.getOrDefault(emptyList())
            for (f in found) {
                val size = runCatching { f.length() }.getOrDefault(0L)
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    removed++
                    bytes += size
                    appendLine("  · ${f.name} (${size / 1024} KB) de ${f.parentFile?.name}/")
                } else {
                    appendLine("  · ${f.name} — não consegui apagar")
                }
            }
        }
        if (removed == 0) {
            append(" nada com nome de cadeado ou despejo de falha estava lá; ")
            append("a próxima hipótese é o conteúdo do perfil, que eu não apago sem você mandar, ")
            append("porque apagar o perfil joga fora as extensões instaladas.")
        } else {
            append("total removido: $removed arquivo(s), ${bytes / 1024} KB.")
        }
    }

    fun markStarting(context: Context) = write(context, Phase.STARTING)

    fun markReady(context: Context) {
        // Chegar aqui é a única coisa que zera a contagem: o motor nasceu e abriu sessão.
        write(context, Phase.READY)
        if (streak != 0) {
            MobiLog.i("engine", "motor chegou a READY depois de $streak abertura(s) morta(s) no berço")
            streak = 0
        }
    }

    /** Manual: desliga/religa o motor. O efeito é no próximo início — por isso o reinício. */
    fun setEngineOff(context: Context, off: Boolean) {
        val raw = runCatching { file(context).readText() }.getOrNull()
        val phase = raw?.substringBefore('|')?.let { p -> Phase.entries.firstOrNull { it.name == p } } ?: Phase.NONE
        write(context, phase, off)
        engineOff = off
        MobiLog.i("engine", "motor ${if (off) "desligado" else "reativado"} para diagnóstico")
    }

    /** O texto que a tela de desenvolvimento mostra sem edição nenhuma. */
    fun debugDump(): String = buildString {
        appendLine("mortes seguidas antes do motor nascer: $streak")
        technical?.let { appendLine(it) }
        appendLine("extensões pausadas nesta abertura: $extensionsPaused")
        appendLine("motor desligado manualmente: $engineOff")
    }.trim()

    private fun write(context: Context, phase: Phase, off: Boolean = engineOff) {
        // A contagem é zerada junto com READY, no mesmo arquivo: se gravar em dois passos, um
        // fechamento entre eles deixaria "READY + streak antigo" e a próxima abertura puniria o
        // usuário por um problema que já passou.
        val streakToWrite = if (phase == Phase.READY) 0 else streak
        runCatching { file(context).writeText("${phase.name}|${if (off) 1 else 0}|$streakToWrite") }
            .onFailure { MobiLog.w("engine", "não consegui gravar o estado do motor: ${it.message}") }
    }
}
