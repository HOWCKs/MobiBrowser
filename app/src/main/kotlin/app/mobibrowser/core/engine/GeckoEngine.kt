package app.mobibrowser.core.engine

import android.content.Context
import app.mobibrowser.BuildConfig
import app.mobibrowser.core.EngineGuard
import app.mobibrowser.core.MobiLog
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtensionController

/**
 * Dono do [GeckoRuntime] e da fábrica de sessões.
 *
 * Por que GeckoView e não WebView: o WebView do Android não tem runtime de extensões
 * (nada de `chrome.*`, service worker de extensão, declarativeNetRequest nem CRX).
 * O GeckoView traz o runtime de WebExtensions do Firefox — incluindo
 * [WebExtensionController], que é como instalamos extensões de verdade.
 *
 * Restrições de thread do GeckoView: os métodos anotados `@HandlerThread` exigem um
 * thread com Looper; criamos o runtime na main thread e todas as chamadas de extensão
 * partem de corrotinas em Dispatchers.Main.immediate, o que satisfaz a exigência.
 */
class GeckoEngine(context: Context) {

    private val appContext = context.applicationContext

    /**
     * true quando esta abertura deve nascer sem o YAML que libera pacote sem assinatura.
     * Definido por [app.mobibrowser.MobiApplication] a partir do EngineGuard: é esse arquivo
     * que dá ao canal estável um motivo a mais para reclamar na criação do runtime.
     */
    @Volatile
    var skipAddonConfig: Boolean = false

    @Volatile
    private var readyMarked: Boolean = false

    val runtime: GeckoRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MobiLog.i(SCOPE, "criando GeckoRuntime (GeckoView ${BuildConfig.GECKOVIEW_VERSION})")
        // Antes de qualquer outra coisa: se o processo morrer aqui, a próxima abertura vê
        // STARTING sem READY e entra em recuperação em vez de fechar sozinha de novo.
        EngineGuard.markStarting(appContext)
        GeckoRuntime.create(appContext, settings())
    }

    /** Controller de WebExtensions do motor. */
    val webExtensions: WebExtensionController
        get() = runtime.webExtensionController

    /**
     * Configurações do runtime. O que importa para este app é o bloco final: sem
     * `xpinstall.signatures.required=false` o motor recusa qualquer pacote convertido do
     * Chrome Web Store (a assinatura é da Google, não da Mozilla) e as extensões ficariam
     * presas ao modo de compatibilidade. Só o canal nightly respeita essa preferência, por
     * isso o `geckoviewChannel` em libs.versions.toml e o campo abaixo andam juntos.
     */
    private fun settings(): GeckoRuntimeSettings {
        val builder = GeckoRuntimeSettings.Builder()
            // O JavaScript por página é decidido em GeckoSessionSettings (por aba); aqui só o padrão global.
            .javaScriptEnabled(true)
            .automaticFontSizeAdjustment(false)
            .webManifest(true)
            .consoleOutput(BuildConfig.MOBI_DEBUG_LOGS)
            // Depuração remota abre soquete de DevTools. Útil em `debug`, indecente num APK que
            // outras pessoas instalam — e o build instável não é debuggable, então era só risco.
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .debugLogging(BuildConfig.MOBI_DEBUG_LOGS)
        // loginAutofillEnabled(true) sem LoginDelegate implementado só mantinha o serviço de
        // senhas do motor acordado, pedindo um delegate que nunca responde. Liga com o delegate.
        val addonsSemAssinatura = BuildConfig.MOBI_ALLOW_UNSIGNED_ADDONS && !skipAddonConfig
        if (addonsSemAssinatura && BuildConfig.GECKOVIEW_CHANNEL == "release") {
            // O canal estável ignora xpinstall.signatures.required (preferência bloqueada fora de
            // build não-oficial). Escrever o arquivo não liberava nada e dava ao motor mais um
            // caminho para reclamar no início, então só o escrevemos onde ele muda algo.
            MobiLog.i(SCOPE, "canal release: sem YAML de assinatura; extensões convertidas rodam pela ponte")
        } else if (addonsSemAssinatura) {
            debugConfigPath()?.let { path -> builder.configFilePath(path) }
        }
        return builder.build()
    }

    /**
     * Sinal de privacidade global. Mudar isto reescreve o YAML; o GeckoView só lê a config
     * na criação do runtime, então o efeito é no próximo início — a UI diz isso, em vez de
     * fingir que é imediato.
     */
    @Volatile
    var globalPrivacyControl: Boolean = true
        set(value) {
            field = value
            runCatching { debugConfigPath() }
                .onFailure { MobiLog.w(SCOPE, "não consegui atualizar a config de privacidade: ${it.message}") }
        }

    /**
     * YAML de configuração do GeckoView — o mecanismo documentado para preferência sem API
     * própria ("Configuring GeckoView for Automation"): `configFilePath` força o runtime a
     * ler o arquivo mesmo sem o app ser debuggable. Regravamos só quando o conteúdo muda,
     * para não escrever no IO de todo cold start.
     */
    private fun debugConfigPath(): String? = runCatching {
        val file = java.io.File(appContext.filesDir, "mobibrowser-geckoview-config.yaml")
        val wanted = buildString {
            append("# Gerado pelo MobiBrowser na inicializacao. Nao editar a mao.\n")
            append("prefs:\n")
            // Add-on sem assinatura: os pacotes que convertemos do Chrome Web Store.
            append("  xpinstall.signatures.required: false\n")
            // Nada de buscar update na AMO: nossos pacotes não existem lá e um update
            // substituiria a conversão que acabamos de fazer.
            append("  extensions.getAddons.cache.enabled: false\n")
            append("  extensions.update.enabled: false\n")
            // Global Privacy Control: o sucessor do cabeçalho "Do Not Track" (o pref DNT foi
            // removido do Gecko). Não há API de sessão para isso, então é preferência do motor.
            append("  privacy.globalprivacycontrol.enabled: ").append(globalPrivacyControl).append('\n')
            append("  privacy.globalprivacycontrol.forasite_enabled: true\n")
        }
        if (!file.isFile || file.readText() != wanted) file.writeText(wanted)
        file.absolutePath
    }.onFailure { MobiLog.w(SCOPE, "sem config do motor (assinaturas seguem exigidas): ${it.message}") }
        .getOrNull()

    /**
     * Cria a sessão de uma aba. Cada aba tem suas próprias [GeckoSessionSettings] porque
     * modo desktop, JavaScript e proteção contra rastreamento são por aba no MobiBrowser.
     */
    fun newSession(
        isPrivate: Boolean,
        javascript: Boolean = true,
        desktop: Boolean = false,
        trackingProtection: Boolean = true,
    ): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(isPrivate)
            .allowJavascript(javascript)
            .useTrackingProtection(trackingProtection)
            .userAgentMode(
                if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
            )
            .viewportMode(
                if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                else GeckoSessionSettings.VIEWPORT_MODE_MOBILE,
            )
            .suspendMediaWhenInactive(true)
            .build()
        // Abrir sessão é a primeira prova de que o motor respondeu; só então o STARTING vira
        // READY. Sem isso, um runtime criado mas surdo na primeira sessão contaria como vivo.
        if (!readyMarked) {
            readyMarked = true
            EngineGuard.markReady(appContext)
        }
        return GeckoSession(settings)
    }

    /** Sessão usada para popups de extensão (chrome-privileged, então pode carregar moz-extension://). */
    fun newExtensionPopupSession(extensionId: String): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .allowJavascript(true)
            .useTrackingProtection(false)
            .displayMode(GeckoSessionSettings.DISPLAY_MODE_BROWSER)
            .build()
        return GeckoSession(settings)
    }

    /**
     * Limpa cache/cookies do perfil do motor. O banco do app é limpo à parte.
     * Em `StorageController.clearData(long flags)` os flags vêm de
     * `StorageController.ClearFlags.*` (o atalho `GeckoRuntime.clearData` não existe no
     * motor; os inteiros de limpeza saíram de `StorageController` para a classe aninhada
     * `ClearFlags` — por isso o `ALL` solto não resolvia).
     */
    fun clearEngineData() {
        runCatching { runtime.storageController.clearData(StorageController.ClearFlags.ALL) }
            .onFailure { MobiLog.w(SCOPE, "clearData falhou", it) }
    }

    fun shutdown() {
        runCatching { runtime.shutdown() }
    }

    private companion object {
        const val SCOPE = "engine"
    }
}
