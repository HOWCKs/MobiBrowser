package app.mobibrowser.core.engine

import android.content.Context
import app.mobibrowser.BuildConfig
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

    val runtime: GeckoRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MobiLog.i(SCOPE, "criando GeckoRuntime (GeckoView ${BuildConfig.GECKOVIEW_VERSION})")
        GeckoRuntime.create(appContext, settings())
    }

    /** Controller de WebExtensions do motor. */
    val webExtensions: WebExtensionController
        get() = runtime.webExtensionController

    private fun settings(): GeckoRuntimeSettings = GeckoRuntimeSettings.Builder()
        // O JavaScript por página é decidido em GeckoSessionSettings (por aba); aqui só o padrão global.
        .javaScriptEnabled(true)
        .automaticFontSizeAdjustment(false)
        .webManifest(true)
        // Autofill de senhas: o motor pede ao app; habilitado para o delegate responder.
        .loginAutofillEnabled(true)
        .consoleOutput(BuildConfig.MOBI_DEBUG_LOGS)
        .remoteDebuggingEnabled(BuildConfig.MOBI_DEBUG_LOGS)
        .debugLogging(BuildConfig.MOBI_DEBUG_LOGS)
        .build()

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

    /** Limpa cache/cookies do perfil do motor. O banco do app é limpo à parte. */
    fun clearEngineData() {
        runCatching { runtime.clearData(StorageController.ALL) }
            .onFailure { MobiLog.w(SCOPE, "clearData falhou", it) }
    }

    fun shutdown() {
        runCatching { runtime.shutdown() }
    }

    private companion object {
        const val SCOPE = "engine"
    }
}
