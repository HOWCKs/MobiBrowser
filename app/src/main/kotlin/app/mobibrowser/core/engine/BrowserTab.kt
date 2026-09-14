package app.mobibrowser.core.engine

import app.mobibrowser.core.MobiLog
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * Uma aba = um [GeckoSession] + um [MutableStateFlow] de estado de UI.
 *
 * Toda a comunicação GeckoView → Compose passa por aqui: os delegates atualizam o
 * estado, a UI recompõe. Nada de callbacks soltos espalhados pela activity.
 */
class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val isPrivate: Boolean,
    private val engine: GeckoEngine,
    initialUrl: String = "",
    javascript: Boolean = true,
    desktopMode: Boolean = false,
    trackingProtection: Boolean = true,
) {
    private val _state = MutableStateFlow(
        TabUiState(
            id = id,
            url = initialUrl,
            isPrivate = isPrivate,
            javascript = javascript,
            desktopMode = desktopMode,
            trackingProtection = trackingProtection,
        ),
    )
    val state: StateFlow<TabUiState> = _state.asStateFlow()

    // As três configurações que podem ser pedidas antes de a sessão existir. Guardá-las aqui é o
    // que permite criar a sessão tarde sem perder o pedido: quem alterna "modo desktop" numa aba
    // vazia recebe o valor quando o motor nascer, e não um objeto que não existe ainda.
    private var cfgJavascript = javascript
    private var cfgDesktop = desktopMode
    private var cfgTracking = trackingProtection

    /**
     * A sessão do motor nasce **na primeira navegação**, não na criação da aba.
     *
     * É a mudança que separa "abrir o navegador" de "abrir uma página": enquanto a pessoa está na
     * tela de início, nenhum `GeckoRuntime` foi criado, então uma morte nativa durante o nascimento
     * do motor não pode levar o aplicativo inteiro no caminho de abertura — e, se levar, fica provado
     * que a culpa não é dele. `load()`, `reload()` e os delegados tocam [session] e, aí sim, o
     * motor é criado exatamente como era criado antes, pela mesma pilha.
     */
    private val sessionLazy = lazy(LazyThreadSafetyMode.NONE) {
        engine.newSession(
            isPrivate = isPrivate,
            javascript = cfgJavascript,
            desktop = cfgDesktop,
            trackingProtection = cfgTracking,
        ).also { s ->
            s.open(engine.runtime)
            wireDelegates(s)
        }
    }

    val session: GeckoSession get() = sessionLazy.value

    /** true quando o motor já foi convocado por esta aba — a UI usa isto para não forçar o nascimento. */
    val hasSession: Boolean get() = sessionLazy.isInitialized()

    /** Chamado quando a página termina de carregar (histórico, preview de aba). */
    var onCommit: ((url: String, title: String) -> Unit)? = null

    /** Chamado quando o usuário abre um link que sai para app externo (intent://, market:, mailto:). */
    var onExternalApp: ((uri: String) -> Unit)? = null

    var closed = false
        private set

    val currentUrl: String get() = _state.value.url

    fun load(url: String) {
        if (url.isBlank()) return
        _state.update { it.copy(url = url, loading = true, progress = 5, crashed = false, findMatches = 0) }
        session.loadUri(url)
    }

    fun reload() = session.reload()

    fun stop() = session.stop()

    fun goBack() {
        if (_state.value.canGoBack) session.goBack()
    }

    fun goForward() {
        if (_state.value.canGoForward) session.goForward()
    }

    fun setDesktopMode(desktop: Boolean) {
        _state.update { it.copy(desktopMode = desktop) }
        cfgDesktop = desktop
        if (!hasSession) return
        session.settings.setUserAgentMode(
            if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
        )
        session.settings.setViewportMode(
            if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else GeckoSessionSettings.VIEWPORT_MODE_MOBILE,
        )
        reload()
    }

    fun setJavascript(enabled: Boolean) {
        _state.update { it.copy(javascript = enabled) }
        cfgJavascript = enabled
        if (!hasSession) return
        session.settings.setAllowJavascript(enabled)
        reload()
    }

    fun setTrackingProtection(enabled: Boolean) {
        _state.update { it.copy(trackingProtection = enabled) }
        cfgTracking = enabled
        if (!hasSession) return
        session.settings.setUseTrackingProtection(enabled)
        reload()
    }

    /* ------------------------------------------------------------------ */
    /* Busca na página                                                     */
    /* ------------------------------------------------------------------ */

    fun showFind() {
        _state.update { it.copy(findVisible = true) }
    }

    fun find(query: String, backwards: Boolean = false) {
        _state.update { it.copy(findQuery = query) }
        // Sem sessão não há página e não há `finder`; o `session` daqui criaria o motor só para
        // procurar nada, o que anularia a economia da tela de início.
        if (!hasSession) return
        if (query.isBlank()) {
            session.finder.clear()
            _state.update { it.copy(findMatches = 0) }
            return
        }
        var flags = 0
        if (backwards) flags = flags or GeckoSession.FINDER_FIND_BACKWARDS
        session.finder.find(query, flags).accept { result ->
            _state.update { it.copy(findMatches = result?.total ?: 0) }
        }
    }

    fun closeFind() {
        if (hasSession) session.finder.clear()
        _state.update { it.copy(findQuery = "", findMatches = 0, findVisible = false) }
    }

    /* ------------------------------------------------------------------ */
    /* Ciclo de vida                                                       */
    /* ------------------------------------------------------------------ */

    fun destroy() {
        if (closed) return
        closed = true
        onCommit = null
        // `session` nunca é lido aqui: fechar uma aba que não chegou a navegar não deve ter o
        // efeito colateral absurdo de criar o motor do navegador só para fechá-lo em seguida.
        if (hasSession) {
            runCatching { session.close() }
                .onFailure { MobiLog.w(SCOPE, "fechando sessão $id com erro", it) }
        }
    }

    private fun wireDelegates(s: GeckoSession) {
        s.setNavigationDelegate(
            object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(
                    session: GeckoSession,
                    url: String?,
                    perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                    hasUserGesture: Boolean,
                ) {
                    _state.update { it.copy(url = url ?: it.url, editing = false) }
                }

                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    _state.update { it.copy(canGoBack = canGoBack) }
                }

                override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                    _state.update { it.copy(canGoForward = canGoForward) }
                }

                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest,
                ): GeckoResult<AllowOrDeny>? {
                    val uri = request.uri
                    // Sai do navegador (app/protocolo externo): devolvemos a decisão para o UI.
                    if (!uri.startsWith("http://") && !uri.startsWith("https://") &&
                        !uri.startsWith("about:") && !uri.startsWith("moz-extension:") &&
                        !uri.startsWith("data:") && !uri.startsWith("file:")
                    ) {
                        onExternalApp?.invoke(uri)
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
            },
        )

        s.setContentDelegate(
            object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    _state.update { it.copy(title = title ?: "") }
                }

                override fun onCrash(session: GeckoSession) {
                    MobiLog.w(SCOPE, "aba $id travou")
                    _state.update { it.copy(crashed = true, loading = false, progress = -1) }
                }
            },
        )

        s.setProgressDelegate(
            object : GeckoSession.ProgressDelegate {
                override fun onProgressChange(session: GeckoSession, progress: Int) {
                    _state.update {
                        it.copy(
                            progress = progress,
                            loading = progress < 100,
                        )
                    }
                    if (progress >= 100) {
                        val st = _state.value
                        onCommit?.invoke(st.url, st.title)
                        _state.update { it.copy(progress = -1) }
                    }
                }

                override fun onSecurityChange(
                    session: GeckoSession,
                    securityInfo: GeckoSession.ProgressDelegate.SecurityInformation,
                ) {
                    _state.update { it.copy(secure = securityInfo.isSecure) }
                }
            },
        )

        // Sem UI própria para permissões de site (câmera, geoloc., notificações) na v1:
        // negamos explicitamente para o pedido não ficar pendurado esperando resposta.
        s.setPermissionDelegate(
            object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm: GeckoSession.PermissionDelegate.ContentPermission,
                ): GeckoResult<Int> = GeckoResult.fromValue(
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY,
                )
            },
        )
    }

    private companion object {
        const val SCOPE = "tab"
    }
}
