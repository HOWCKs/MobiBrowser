package app.mobibrowser.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mobibrowser.MobiApplication
import app.mobibrowser.core.MobiLog
import app.mobibrowser.core.engine.toNavigationTarget
import app.mobibrowser.data.BrowserDb
import app.mobibrowser.data.SearchEngine
import app.mobibrowser.data.Settings
import app.mobibrowser.data.ThemeMode
import app.mobibrowser.data.ScriptKind
import app.mobibrowser.data.UserScript
import app.mobibrowser.core.ext.BridgeScripts
import app.mobibrowser.core.ext.ChromeWebStore
import app.mobibrowser.core.ext.ExtensionRegistry
import app.mobibrowser.core.engine.BrowserTab
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Telas em overlay sobre a navegação. Uma única fonte de verdade para o back stack. */
@Immutable
enum class Overlay { NONE, TABS, EXTENSIONS, USERSCRIPTS, SETTINGS, HISTORY, BOOKMARKS, FIRST_RUN }

data class SnackbarMessage(val text: String, val actionLabel: String? = null)

/**
 * Estado e comandos da UI. Não conhece Compose, e o GeckoView conhece só por meio dos
 * controllers ([MobiApplication.tabs] e [MobiApplication.extensions]).
 */
class MobiViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MobiApplication
    private val tabs = app.tabs
    private val extensions = app.extensions
    private val db: BrowserDb = app.db

    val settings: StateFlow<Settings> = app.prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val tabList = tabs.tabs
    val selectedTabId = tabs.selectedId
    val selectedTab = tabs.selectedTab
    val tabState = tabs.selectedState

    val extensionsUi = extensions.extensions
    val actions = extensions.actions
    val popup = extensions.popup
    val installProgress = extensions.progress
    val installPrompt = extensions.prompt

    private val _overlay = MutableStateFlow(Overlay.NONE)
    val overlay: StateFlow<Overlay> = _overlay.asStateFlow()

    /** Aviso rápido para o usuário: a UI só conhece [snack], ninguém emite SnackbarMessage na mão. */
    private suspend fun snack(text: String, actionLabel: String? = null) {
        _snack.emit(SnackbarMessage(text, actionLabel))
    }

    private val _snack = MutableSharedFlow<SnackbarMessage>(extraBufferCapacity = 4)
    val snack = _snack.shareIn(viewModelScope, SharingStarted.Eagerly)

    /** URL de instalação pendente na folha de extensões (vinda do banner da loja). */
    private val _storeInstallInput = MutableStateFlow<String?>(null)
    val storeInstallInput: StateFlow<String?> = _storeInstallInput.asStateFlow()

    private val _bookmarkActive = MutableStateFlow(false)
    val bookmarkActive: StateFlow<Boolean> = _bookmarkActive.asStateFlow()

    init {
        viewModelScope.launch {
            if (!app.prefs.current().firstRunDone) _overlay.value = Overlay.FIRST_RUN
        }
        app.extensions.onOpenUrl = { url -> openUrl(url) }
        handleStartupIntent()
    }

    /* ------------------------------ navegação ------------------------------ */

    fun submit(raw: String) {
        val engine = settings.value.searchEngine
        val url = raw.toNavigationTarget(engine.url)
        openUrl(url)
    }

    fun openUrl(url: String) {
        tabs.openInSelectedOrNew(url, isPrivate = tabs.selectedIsPrivate())
        _overlay.value = Overlay.NONE
    }

    /** Link em nova aba (control/aba do menu de contexto, resultado de busca etc.). */
    fun openInNewTab(url: String, privateMode: Boolean = false) {
        tabs.create(url = url, isPrivate = privateMode)
    }

    fun newTab(privateMode: Boolean = tabs.selectedIsPrivate()) {
        tabs.create(url = settings.value.homepage, isPrivate = privateMode)
    }

    fun closeTab(id: String) = tabs.close(id)

    fun selectTab(id: String) {
        tabs.select(id)
        refreshBookmarkFlag()
    }

    /** Ciclo de abas (usado pelo gesto de arrastar na barra de abas). */
    fun cycleTab() = tabs.selectNext()

    fun closeAllTabs(privateMode: Boolean) = tabs.closeAll(privateMode)

    fun closeCurrentTab() {
        selectedTabId.value?.let { tabs.close(it) }
    }

    fun reload() = selectedTab.value?.reload()
    fun stop() = selectedTab.value?.stop()
    fun goBack() = selectedTab.value?.goBack()
    fun goForward() = selectedTab.value?.goForward()

    fun toggleDesktopMode() {
        val tab = selectedTab.value ?: return
        tab.setDesktopMode(!tab.state.value.desktopMode)
    }

    fun toggleJavascript() {
        val tab = selectedTab.value ?: return
        tab.setJavascript(!tab.state.value.javascript)
        viewModelScope.launch {
            snack(if (tab.state.value.javascript) "JavaScript ligado" else "JavaScript desligado")
        }
    }

    fun toggleTrackingProtection() {
        val tab = selectedTab.value ?: return
        tab.setTrackingProtection(!tab.state.value.trackingProtection)
    }

    fun showFind() = selectedTab.value?.showFind()

    fun find(query: String) = selectedTab.value?.find(query)

    /** Sugestões do campo de endereço: histórico primeiro, depois complemento de URL. */
    fun addressSuggestions(query: String): List<String> {
        val current = tabState.value?.url
        val rows = if (query.isBlank()) {
            db.recentHistory(6).map { it.url }
        } else {
            db.searchHistory(query, 6).map { it.url }
        }
        return (rows + listOfNotNull(current)).distinct().filter { it.isNotBlank() && it != query }.take(5)
    }
    fun findNext() {
        val tab = selectedTab.value ?: return
        tab.find(tab.state.value.findQuery, backwards = false)
    }

    fun closeFind() = selectedTab.value?.closeFind()

    /* ------------------------------ favoritos ----------------------------- */

    fun refreshBookmarkFlag() {
        val url = tabState.value?.url ?: return
        if (url.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val marked = db.isBookmarked(url)
            _bookmarkActive.value = marked
        }
    }

    fun toggleBookmark() {
        val state = tabState.value ?: return
        if (state.url.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (db.isBookmarked(state.url)) {
                db.removeBookmark(state.url)
                _bookmarkActive.value = false
                snack("Removido dos favoritos")
            } else {
                db.addBookmark(state.url, state.title.ifBlank { state.displayUrl })
                _bookmarkActive.value = true
                snack("Salvo nos favoritos")
            }
        }
    }

    fun listBookmarks(): List<BrowserDbBookmark> = db.listBookmarks().map { BrowserDbBookmark(it.url, it.title) }

    fun listHistory(query: String?): List<HistoryRow> =
        (if (query.isNullOrBlank()) db.recentHistory(200) else db.searchHistory(query, 200))
            .map { HistoryRow(it.url, it.title, it.lastVisit) }

    fun deleteHistoryEntry(url: String) = viewModelScope.launch(Dispatchers.IO) { db.deleteHistory(url) }

    fun clearHistory() = viewModelScope.launch(Dispatchers.IO) {
        db.clearHistory()
        snack("Histórico limpo")
    }

    fun removeBookmark(url: String) = viewModelScope.launch(Dispatchers.IO) {
        db.removeBookmark(url)
        _bookmarkActive.value = false
    }

    @Immutable
    data class BrowserDbBookmark(val url: String, val title: String)

    @Immutable
    data class HistoryRow(val url: String, val title: String, val at: Long)

    /* ----------------------------- extensões ------------------------------ */

    fun openExtensions() {
        _overlay.value = Overlay.EXTENSIONS
        viewModelScope.launch { extensions.syncWithEngine() }
    }

    fun installFromStore(input: String) {
        viewModelScope.launch {
            val ok = extensions.installFromStore(input)
            if (ok) snack("Extensão instalada")
        }
    }

    fun installFromUri(uri: Uri) {
        viewModelScope.launch {
            val ok = extensions.installFromFile(uri)
            if (ok) snack("Extensão instalada")
        }
    }

    fun setStoreInstallInput(value: String?) {
        _storeInstallInput.value = value
    }

    fun dismissInstallProgress() = extensions.dismissProgress()

    fun toggleExtension(geckoId: String, enabled: Boolean) =
        viewModelScope.launch { extensions.setEnabled(geckoId, enabled) }

    fun uninstallExtension(geckoId: String) = viewModelScope.launch {
        extensions.uninstall(geckoId)
        snack("Extensão removida")
    }

    fun setExtensionPrivate(geckoId: String, allowed: Boolean) =
        viewModelScope.launch { extensions.setPrivateAllowed(geckoId, allowed) }

    fun setExtensionSiteAccess(
        geckoId: String,
        access: ExtensionRegistry.SiteAccess,
        allowList: List<String>,
        denyList: List<String>,
    ) = viewModelScope.launch { extensions.setSiteAccess(geckoId, access, allowList, denyList) }

    fun updateExtension(geckoId: String) = viewModelScope.launch {
        if (extensions.updateFromStore(geckoId)) snack("Extensão atualizada")
    }

    fun openExtensionOptions(geckoId: String) = extensions.openOptionsPage(geckoId)

    fun tapExtensionAction(geckoId: String) = extensions.tapAction(geckoId)

    fun dismissPopup() = extensions.dismissPopup()

    fun respondToPrompt(geckoId: String, grant: Boolean, allowPrivate: Boolean) =
        extensions.respondToPrompt(geckoId, grant, allowPrivate)

    /**
     * Um toque na folha da aba: liga/desliga a extensão *neste site* — o controle mais
     * usado de um navegador com extensões (quebrar uma página e seguir navegando).
     */
    fun toggleExtensionOnThisSite(geckoId: String) {
        val host = tabState.value?.host ?: return
        val pattern = "*://$host/*"
        viewModelScope.launch {
            val record = app.registry.get(geckoId) ?: return@launch
            if (record.siteAccess == ExtensionRegistry.SiteAccess.ALL_SITES) {
                val deny = record.denyList.toMutableList()
                if (deny.any { it.contains(host) }) deny.removeAll { it.contains(host) } else deny += pattern
                extensions.setSiteAccess(geckoId, ExtensionRegistry.SiteAccess.ALL_SITES, record.allowList, deny)
            } else {
                val allow = record.allowList.toMutableList()
                if (allow.any { it.contains(host) }) allow.removeAll { it.contains(host) } else allow += pattern
                extensions.setSiteAccess(geckoId, ExtensionRegistry.SiteAccess.ALLOW_LIST, allow, record.denyList)
            }
            snack("Atualizado para $host")
        }
    }

    fun sitesBlockedOnThisTab(extensionsNow: List<ExtensionRegistry.Record>): List<ExtensionRegistry.Record> {
        val url = tabState.value?.url ?: return emptyList()
        return extensionsNow.filter { !it.allowsSite(url) }
    }

    /* ----------------------------- user scripts --------------------------- */

    fun saveScript(script: UserScript) = viewModelScope.launch(Dispatchers.IO) {
        db.saveScript(script)
        snack("Salvo")
    }

    fun deleteScript(id: String) = viewModelScope.launch(Dispatchers.IO) {
        db.deleteScript(id)
        snack("Excluído")
    }

    fun setScriptEnabled(id: String, enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        db.setScriptEnabled(id, enabled)
    }

    fun scripts(): List<UserScript> = db.listScripts()

    fun importUserscript(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val text = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("arquivo vazio")
            val name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "user script" }
            val isCss = name.endsWith(".css")
            db.saveScript(
                UserScript(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    code = text,
                    pattern = "<all_urls>",
                    kind = if (isCss) ScriptKind.CSS else ScriptKind.JS,
                    enabled = true,
                    sourceExtension = null,
                    runAtIdle = false,
                ),
            )
            snack("Importado: $name")
        }.onFailure { MobiLog.w("ui", "import falhou", it) }
    }

    /* ----------------------------- configurações -------------------------- */

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { app.prefs.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { app.prefs.setDynamicColor(enabled) }
    fun setExpressive(enabled: Boolean) = viewModelScope.launch { app.prefs.setExpressive(enabled) }
    fun setSearchEngine(engine: SearchEngine) = viewModelScope.launch { app.prefs.setSearchEngine(engine) }
    fun setUserscriptsEnabled(enabled: Boolean) = viewModelScope.launch { app.prefs.setUserscriptsEnabled(enabled) }
    fun setDnrEnabled(enabled: Boolean) = viewModelScope.launch { app.prefs.setDnrEnabled(enabled) }
    fun setHomepage(url: String) = viewModelScope.launch { app.prefs.setHomepage(url) }
    fun finishFirstRun() = viewModelScope.launch {
        app.prefs.setFirstRunDone()
        _overlay.value = Overlay.NONE
    }

    fun setBridgeEnabled(enabled: Boolean) = viewModelScope.launch { app.prefs.setBridgeEnabled(enabled) }

    /**
     * GPC (sucessor do DNT): não há API por sessão no GeckoView, é preferência do motor lida
     * na criação do runtime. Reescrevemos o arquivo na hora e dizemos que vale do próximo início.
     */
    fun setGlobalPrivacyControl(enabled: Boolean) = viewModelScope.launch {
        app.prefs.setGlobalPrivacyControl(enabled)
        app.engine.globalPrivacyControl = enabled
        snack("Sinal de privacidade atualizado — vale a partir do próximo início do motor")
    }

    /** Padrão de ETP para abas novas (o por-aba fica no menu da página). */
    fun setTrackingProtectionDefault(enabled: Boolean) = viewModelScope.launch {
        app.prefs.setTrackingProtectionDefault(enabled)
    }

    fun historyCount(): Int = runCatching { db.countHistory() }.getOrDefault(0)

    /** Prévia da loja no sheet de instalação (best effort: se falhar, só falta o preview). */
    private val _storeSummary = MutableStateFlow<ChromeWebStore.Summary?>(null)
    val storeSummary: StateFlow<ChromeWebStore.Summary?> = _storeSummary.asStateFlow()

    fun previewStoreInstall(input: String) {
        val id = ChromeWebStore.idFrom(input)
        _storeSummary.value = null
        if (id == null) return
        viewModelScope.launch {
            runCatching { app.extensions.storeSummary(id) }
                .onSuccess { _storeSummary.value = it }
        }
    }

    fun resyncExtensions() = viewModelScope.launch { extensions.syncWithEngine() }

    val bridgeStats: StateFlow<BridgeScripts.InjectionStats> = app.bridge.stats

    fun clearEverything() = viewModelScope.launch(Dispatchers.IO) {
        db.clearEverything()
        app.engine.clearEngineData()
        snack("Dados de navegação limpos")
    }

    fun shareCurrentUrl() {
        val url = tabState.value?.url ?: return
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            app.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openExternal(url: String) = runCatching {
        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /* ------------------------------- overlays ----------------------------- */

    fun show(overlay: Overlay) {
        _overlay.value = overlay
        if (overlay == Overlay.HISTORY || overlay == Overlay.BOOKMARKS) refreshBookmarkFlag()
    }

    fun hideOverlay() {
        _overlay.value = Overlay.NONE
    }

    val toolbarAtBottom: Boolean get() = settings.value.toolbarAtBottom

    private fun handleStartupIntent() {
        app.consumePendingUrl()?.let { url ->
            viewModelScope.launch {
                val search = app.prefs.current().searchEngine
                tabs.create(url = url.toNavigationTarget(search.url), isPrivate = false)
            }
        }
    }

    /** Link entregue pelo Activity (navegador padrão) — consome o pending do Application. */
    fun onResumed() {
        app.consumePendingUrl()?.let { url -> onNewIntentData(url) }
    }

    /**
     * `ACTION_VIEW`/`WEB_SEARCH`/`SEND` chegam pelo Activity (o app é o navegador padrão,
     * então isso é o caminho mais usado dele). Um `content://` que pareça pacote de extensão
     * vai direto para o instalador — "recebi um .crx por chat e quero testar" é real.
     */
    fun onNewIntent(intent: Intent) {
        val uri = intent.data ?: intent.clipData?.getItemAt(0)?.uri
        val raw = uri?.toString().orEmpty()
        val parecePacote = listOf(".crx", ".xpi", ".zip").any { raw.endsWith(it, ignoreCase = true) }
        if (parecePacote && uri != null) {
            viewModelScope.launch { extensions.installFromFile(uri) }
            return
        }
        onNewIntentData(intent.dataString ?: intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    fun onNewIntentData(url: String?) {
        if (url.isNullOrBlank()) return
        viewModelScope.launch {
            val search = app.prefs.current().searchEngine
            tabs.openInSelectedOrNew(url.toNavigationTarget(search.url), isPrivate = false)
            _overlay.value = Overlay.NONE
        }
    }

    override fun onCleared() {
        // Abas continuam vivas no Application: oViewModel não é dono delas.
        super.onCleared()
    }
}
