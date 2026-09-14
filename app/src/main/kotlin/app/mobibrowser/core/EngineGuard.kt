package app.mobibrowser.core

import android.content.Context
import java.io.File

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
 * `WebExtensionController`, a ponte e o YAML que libera pacote sem assinatura), porque é ali que
 * o GeckoView do canal estável recusa/aborta com mais frequência. Navegar continua funcionando —
 * desligar tudo seria trocar um crash por um app inútil.
 *
 * [engineOff] é o interruptor manual, e a razão de ele morar aqui e não no DataStore: precisa ser
 * lido *antes* de qualquer suspensão, na primeira linha do `Application.onCreate`. Bloquear o
 * motor inteiro é a alavanca de bisseção — com ela desligada e o app ainda caindo, o problema não
 * é o Gecko, e isso vale mais que qualquer palpite.
 */
object EngineGuard {

    enum class Phase { NONE, STARTING, READY }

    /** Instantâneo lido no início; imutável para o resto da sessão. */
    data class Snapshot(
        val phase: Phase,
        val engineOff: Boolean,
        /** true quando a sessão anterior terminou entre iniciar o motor e abrir a primeira sessão. */
        val diedAtEngineStart: Boolean,
    )

    @Volatile
    var engineOff: Boolean = false
        private set

    @Volatile
    var extensionsPaused: Boolean = false
        private set

    /** Texto do aviso mostrado na primeira tela; nulo quando não há nada a dizer. */
    @Volatile
    var notice: String? = null
        private set

    private const val FILE = "mobibrowser-engine.txt"

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** Lê o estado anterior e decide as flags desta sessão. Chamar antes de criar qualquer camada. */
    fun begin(context: Context): Snapshot {
        val raw = runCatching { file(context).readText() }.getOrNull()
        val phase = raw?.substringBefore('|')?.let { p -> Phase.entries.firstOrNull { it.name == p } } ?: Phase.NONE
        val manualOff = raw?.substringAfter('|')?.substringBefore('|') == "1"
        val snapshot = Snapshot(
            phase = phase,
            engineOff = manualOff,
            diedAtEngineStart = phase == Phase.STARTING,
        )
        engineOff = manualOff
        extensionsPaused = snapshot.diedAtEngineStart && !manualOff
        notice = when {
            manualOff ->
                "Motor desligado para diagnóstico. Enquanto estiver assim, nada abre em aba: " +
                    "só as telas locais (scripts, ajustes, favoritos). Reative abaixo para navegar."
            snapshot.diedAtEngineStart ->
                "A sessão anterior parou enquanto o motor nascia, e sem exceção registrada — isso " +
                    "aponta para o Gecko, não para o Compose. Por segurança esta abertura começa sem " +
                    "o motor de extensões e sem o YAML de pacote sem assinatura. Navegar funciona. " +
                    "Se ainda fechar sozinho, desligue o motor (abaixo) e me diga: assim separo o " +
                    "Gecko do resto do app."
            else -> null
        }
        MobiLog.i(
            "engine",
            "guarda: fase anterior=${phase.name} · manual=${manualOff} · recuperação=${extensionsPaused}",
        )
        return snapshot
    }

    fun markStarting(context: Context) = write(context, Phase.STARTING)

    fun markReady(context: Context) = write(context, Phase.READY)

    /** Manual: desliga/religa o motor. O efeito é no próximo início — por isso o reinício. */
    fun setEngineOff(context: Context, off: Boolean) {
        val raw = runCatching { file(context).readText() }.getOrNull()
        val phase = raw?.substringBefore('|')?.let { p -> Phase.entries.firstOrNull { it.name == p } } ?: Phase.NONE
        write(context, phase, off)
        engineOff = off
        MobiLog.i("engine", "motor ${if (off) "desligado" else "reativado"} para diagnóstico")
    }

    private fun write(context: Context, phase: Phase, off: Boolean = engineOff) {
        runCatching { file(context).writeText("${phase.name}|${if (off) 1 else 0}") }
            .onFailure { MobiLog.w("engine", "não consegui gravar o estado do motor: ${it.message}") }
    }
}
