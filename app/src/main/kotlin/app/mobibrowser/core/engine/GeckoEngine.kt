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
            // Autofill de senhas: o motor pede ao app; habilitado para o delegate responder.
            .loginAutofillEnabled(true)
            .consoleOutput(BuildConfig.MOBI_DEBUG_LOGS)
            .remoteDebuggingEnabled(BuildConfig.MOBI_DEBUG_LOGS)
            .debugLogging(BuildConfig.MOBI_DEBUG_LOGS)
        if (BuildConfig.MOBI_ALLOW_UNSIGNED_ADDONS) {
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
            // Sem isso, o check de versão mínima derruba a extensão convertida.
            append("  extensions.langpacks.min_compatible_version: false\n")
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
