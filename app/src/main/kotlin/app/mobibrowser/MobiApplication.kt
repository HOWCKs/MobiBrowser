package app.mobibrowser

import android.app.Application
import android.content.Intent
import app.mobibrowser.BuildConfig
import app.mobibrowser.core.EngineGuard
import app.mobibrowser.core.MobiLog
import app.mobibrowser.core.engine.GeckoEngine
import app.mobibrowser.core.update.UpdateManager
import app.mobibrowser.core.engine.TabController
import app.mobibrowser.core.ext.BridgeScripts
import app.mobibrowser.core.ext.ExtensionManager
import app.mobibrowser.core.ext.ExtensionRegistry
import app.mobibrowser.data.AppPrefs
import app.mobibrowser.data.BrowserDb
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Composição raiz do app (sem DI framework: são 6 objetos e um grafo óbvio).
 *
 * Ordem que importa:
 *  - [GeckoEngine] é lazy: o GeckoRuntime só nasce na primeira sessão (cold start mais
 *    rápido; o processo do motor é caro);
 *  - [ExtensionManager.start] precisa do runtime criado, por isso roda depois do primeiro
 *    acesso à engine e sempre na main thread (o GeckoView exige thread com Looper);
 *  - persistência e preferências são injetadas nos consumidores, não lidas globalmente.
 */
class MobiApplication : Application() {

    /**
     * Escopo das camadas. O handler existe porque um `launch` avulso sem `CoroutineExceptionHandler`
     * entrega o erro à thread — e na main thread isso encerra o processo. Num navegador em que o
     * motor responde por GeckoResult (ponte recusada, extensão inválida, storage ocupado), isso é
     * a diferença entre "uma função ficou indisponível" e "o app fecha sozinho".
     */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, error ->
                MobiLog.e("app", "coroutine de fundo lançou fora de qualquer runCatching; o app segue", error)
            },
    )

    /** Pilha da morte anterior, mostrada uma vez na primeira tela. `MobiLog.takePendingCrash`. */
    var pendingCrash: String? = null
        private set

    lateinit var prefs: AppPrefs
        private set
    lateinit var db: BrowserDb
        private set
    lateinit var engine: GeckoEngine
        private set
    lateinit var registry: ExtensionRegistry
        private set
    lateinit var bridge: BridgeScripts
        private set
    lateinit var extensions: ExtensionManager
        private set
    lateinit var tabs: TabController
        private set

    /** Verificador de pré-lançamento. Vive no app, e não na ViewModel, para que um download
     * em curso sobreviva a trocar de tela. */
    lateinit var updates: UpdateManager
        private set

    /** URL trazida por intent antes de a UI existir (VIEW/SEND). */
    @Volatile
    var pendingIntentUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        // Primeira linha de tudo: sem log em arquivo, uma inicialização travada é
        // indistinguível de um app que "não abre" — e aqui o aparelho não tem adb.
        MobiLog.attach(this)
        MobiLog.guardCrashes()
        // O guarda vem em segundo lugar, antes de qualquer camada tocar o motor: é ele que decide
        // se esta abertura começa em recuperação (sessão anterior parada no berço do Gecko).
        EngineGuard.begin(this)
        // Antes de qualquer outra coisa: se a sessão passada terminou em FATAL, a pessoa precisa
        // ver isso sem correr contra o próximo crash para chegar em Configurações → Sobre.
        pendingCrash = MobiLog.takePendingCrash(this) ?: EngineGuard.notice
        if (pendingCrash != null) MobiLog.e("app", "aviso da sessão anterior pronto para a primeira tela")
        MobiLog.i("app", "onCreate ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) · motor ${BuildConfig.GECKOVIEW_VERSION}")
        prefs = AppPrefs(this)
        db = BrowserDb(this)
        engine = GeckoEngine(this)
        registry = ExtensionRegistry(this, appScope)
        bridge = BridgeScripts(this, db, registry, appScope)
        extensions = ExtensionManager(
            context = this,
            engine = engine,
            prefs = prefs,
            db = db,
            registry = registry,
            bridge = bridge,
            scope = appScope,
        )
        tabs = TabController(engine = engine, prefs = prefs, db = db, scope = appScope)
        updates = UpdateManager(context = this, scope = appScope)
        engine.skipAddonConfig = EngineGuard.extensionsPaused

        MobiLog.i("app", "camadas construídas; iniciando gestor de extensões")
        if (EngineGuard.extensionsPaused) {
            MobiLog.w("app", "motor de extensões pausado pela recuperação; navegação segue normal")
        } else {
            runCatching { extensions.start() }
                .onFailure { MobiLog.e("app", "gestor de extensões não subiu; as telas seguem sem ele", it) }
        }
        appScope.launch {
            if (EngineGuard.engineOff) {
                // Alavanca de diagnóstico: sem tocar em Gecko, qualquer fechamento restante é do
                // app (Compose/persistência), e é isso que precisa ficar provado.
                MobiLog.i("app", "motor desligado manualmente: nenhuma aba criada nesta abertura")
                return@launch
            }
            // Pequena espera de fôlego: a primeira composição existe antes do processo do motor
            // nascer. Não é enfeite — com o motor criado na mesma pilha do onCreate, um abort
            // nativo levava a tela junto antes de qualquer frame, e ninguém via nada.
            delay(180)
            runCatching { tabs.restoreOnStartup() }
                .onSuccess { MobiLog.i("app", "abas no início: ${tabs.tabs.value.size} (home criada: ${it.createdHome})") }
                .onFailure { MobiLog.e("app", "não consegui abrir a primeira aba", it) }
        }

    }

    /**
     * Link capturado antes de a Activity existir (cold start por VIEW/SEND). O `onNewIntent`
     * de runtime vivo é tratado pela MainActivity, que repassa para a ViewModel — Application
     * não tem esse callback, por isso não há override aqui.
     */
    fun consumePendingUrl(): String? = pendingIntentUrl.also { pendingIntentUrl = null }

    /**
     * Liga/desliga o motor para diagnóstico e reinicia. Reiniciar é obrigatório porque runtime,
     * abas e extensões nascem no onCreate — não existe "religar" sem reconstruir o processo, e
     * fingir que existe deixaria a UI mentindo sobre o estado real.
     */
    fun setEngineOffAndRestart(off: Boolean) {
        EngineGuard.setEngineOff(this, off)
        runCatching {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(it)
            }
        }.onFailure { MobiLog.w("app", "não consegui reiniciar sozinho; feche e abra de novo", it) }
        // O novo processo já foi posto para nascer; encerrar este aqui é o que garante que o
        // onCreate rode do zero (flags do guarda são lidas uma única vez, de propósito).
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Estratégia de memória: fechar abas antigas é mais previsível que deixar o
        // sistema matar o processo inteiro com o usuário no meio de uma página.
        val closable = tabs.tabs.value.filter { !it.isPrivate && it.id != tabs.selectedId.value }
        closable.take(maxOf(0, closable.size - MAX_LIVE_TABS)).forEach { tab ->
            MobiLog.i(SCOPE, "memória baixa: descartando aba ${tab.id}")
            tabs.close(tab.id)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        shutdown()
    }

    private fun shutdown() {
        runCatching { engine.shutdown() }
        runCatching { appScope.cancel() }
    }

    private companion object {
        const val SCOPE = "app"
        const val MAX_LIVE_TABS = 6
    }
}
