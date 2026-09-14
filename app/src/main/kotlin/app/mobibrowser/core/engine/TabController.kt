package app.mobibrowser.core.engine

import app.mobibrowser.core.Diag
import app.mobibrowser.core.MobiLog
import app.mobibrowser.data.AppPrefs
import app.mobibrowser.data.BrowserDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Dono das abas. Mantém uma lista ordenada de [BrowserTab] + a aba selecionada.
 *
 * Decisão de UI que mora aqui: só a aba ativa tem `GeckoView` attached — as outras
 * ficam com a sessão aberta (estado/JS vivos) mas sem surface, que é como o Firefox
 * economiza memória. Isso simplifica o Compose: um único `AndroidView` e
 * `setSession(abaAtiva)` quando a seleção muda.
 */
class TabController(
    private val engine: GeckoEngine,
    private val prefs: AppPrefs,
    private val db: BrowserDb,
    private val scope: CoroutineScope,
) {
    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    private val _selectedId = MutableStateFlow<String?>(null)

    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    val selectedTab: StateFlow<BrowserTab?> =
        combine(_tabs, _selectedId) { tabs, id -> tabs.firstOrNull { it.id == id } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Estado da aba ativa. `flatMapLatest` é obrigatório aqui: trocar a seleção troca o
     * *flow* observado, e a UI precisa continuar recebendo progresso/título/URL da aba
     * que passou a ser a ativa (um `combine` na lista não veria essas emissões).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedState: StateFlow<TabUiState?> =
        _selectedId
            .map { id -> _tabs.value.firstOrNull { it.id == id } }
            .flatMapLatest { tab -> tab?.state ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val normalTabs: List<BrowserTab> get() = _tabs.value.filterNot { it.isPrivate }
    val privateTabs: List<BrowserTab> get() = _tabs.value.filter { it.isPrivate }

    fun openInSelectedOrNew(url: String, isPrivate: Boolean = false): BrowserTab {
        val selected = _tabs.value.firstOrNull { it.id == _selectedId.value }
        if (selected != null && selected.isPrivate == isPrivate && selected.currentUrl.isEmpty()) {
            selected.load(url)
            return selected
        }
        return create(url, isPrivate)
    }

    fun create(
        url: String = "",
        isPrivate: Boolean = false,
        select: Boolean = true,
        desktop: Boolean = false,
    ): BrowserTab {
        val tab = BrowserTab(
            isPrivate = isPrivate,
            engine = engine,
            initialUrl = url,
            javascript = true,
            desktopMode = desktop,
            trackingProtection = true,
        )
        tab.onCommit = { committed, title ->
            if (!isPrivate && committed.isNotBlank() && !committed.startsWith("about:")) {
                scope.launch(Dispatchers.IO) { db.addHistory(committed, title) }
            }
        }
        _tabs.update { it + tab }
        if (select) _selectedId.value = tab.id
        if (url.isNotBlank()) {
            Diag.at("aba:carregar ${url.take(48)}")
            tab.load(url)
        }
        persist()
        return tab
    }

    fun select(id: String) {
        if (_tabs.value.any { it.id == id }) {
            _selectedId.update { id }
            scope.launch { prefs.setLastUsedTab(id) }
        }
    }

    fun selectNext() {
        val tabs = _tabs.value.filter { it.isPrivate == selectedIsPrivate() }
        if (tabs.isEmpty()) return
        val idx = tabs.indexOfFirst { it.id == _selectedId.value }
        select(tabs.getOrNull(idx - 1)?.id ?: tabs.last().id)
    }

    fun selectedIsPrivate(): Boolean = _tabs.value.firstOrNull { it.id == _selectedId.value }?.isPrivate ?: false

    fun close(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        _tabs.update { list -> list.filterNot { it.id == id } }
        tab.destroy()
        val remaining = _tabs.value.filter { it.isPrivate == tab.isPrivate }
        _selectedId.update { current ->
            when {
                current != id -> current
                remaining.isNotEmpty() -> remaining.last().id
                else -> _tabs.value.lastOrNull()?.id
            }
        }
        persist()
    }

    fun closeAll(isPrivate: Boolean) {
        val doomed = _tabs.value.filter { it.isPrivate == isPrivate }
        if (doomed.isEmpty()) return
        _tabs.update { list -> list.filterNot { it.isPrivate == isPrivate } }
        doomed.forEach { it.destroy() }
        _selectedId.value = _tabs.value.lastOrNull()?.id
        persist()
    }

    /** Nº de abas normais com uma URL "instalável" (usado pelo banner da Chrome Web Store). */
    fun tabContainingStore(): TabUiState? =
        _tabs.value.firstOrNull { it.state.value.url.contains("chromewebstore.google.com") }
            ?.state?.value

    /** Reinício: reabre as URLs salvas (só as normais — abas anônimas nunca persistem). */
    suspend fun restoreOnStartup(): RestoreResult {
        val urls = prefs.startupTabs()
        val desktopByDefault = prefs.defaultDesktopMode()
        MobiLog.d(SCOPE, "restaurando ${urls.size} aba(s)")
        Diag.at("abas:restaurar ${urls.size}")
        urls.forEach { url -> create(url, isPrivate = false, select = false, desktop = desktopByDefault) }
        if (_tabs.value.isEmpty()) {
            // Aba vazia, não a página inicial: quem abre o navegador quer a tela de início
            // (busca + atalhos), e o motor só é chamado se a pessoa escolher um destino.
            create("", isPrivate = false, select = true)
            return RestoreResult(createdHome = true)
        }
        // A aba ativa é parte da restauração, não um detalhe. `create(select = false)` acima
        // deixava _selectedId nulo com abas vivas por baixo: a tela caía no estado vazio, o
        // contador dizia 0 e o motor continuava carregando uma página invisível — foi exatamente
        // o "tenho abas abertas mas ele mostra 0" relatado no aparelho.
        val lastUsed = runCatching { prefs.lastUsedTab() }.getOrNull()
            ?.takeIf { id -> _tabs.value.any { it.id == id } }
        select(lastUsed ?: _tabs.value.last().id)
        return RestoreResult(createdHome = false)
    }

    data class RestoreResult(val createdHome: Boolean)

    private fun persist() {
        val urls = _tabs.value.filterNot { it.isPrivate }.map { it.currentUrl }.filter { it.isNotBlank() }
        scope.launch { prefs.saveStartupTabs(urls) }
    }

    private companion object {
        const val SCOPE = "tabs"
    }
}
