package app.mobibrowser

import android.app.Application
import android.content.Intent
import app.mobibrowser.BuildConfig
import app.mobibrowser.core.MobiLog
import app.mobibrowser.core.engine.GeckoEngine
import app.mobibrowser.core.update.UpdateManager
import app.mobibrowser.core.engine.TabController
import app.mobibrowser.core.ext.BridgeScripts
import app.mobibrowser.core.ext.ExtensionManager
import app.mobibrowser.core.ext.ExtensionRegistry
import app.mobibrowser.data.AppPrefs
import app.mobibrowser.data.BrowserDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

        MobiLog.i("app", "camadas construídas; iniciando gestor de extensões")
        runCatching { extensions.start() }
            .onFailure { MobiLog.e("app", "gestor de extensões não subiu; as telas seguem sem ele", it) }
        appScope.launch {
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
