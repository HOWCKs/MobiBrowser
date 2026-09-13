package app.mobibrowser.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.mobiDataStore: DataStore<Preferences> by preferencesDataStore(name = "mobi_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class SearchEngine(val id: String, val label: String, val url: String) {
    DUCKDUCKGO("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("google", "Google", "https://www.google.com/search?q=%s"),
    BRAVE("brave", "Brave Search", "https://search.brave.com/search?q=%s"),
    STARTPAGE("startpage", "Startpage", "https://www.startpage.com/sp/search?query=%s"),
    BING("bing", "Bing", "https://www.bing.com/search?q=%s"),
    ;

    companion object {
        fun fromId(id: String?): SearchEngine = entries.firstOrNull { it.id == id } ?: DUCKDUCKGO
    }
}

/**
 * Preferências do app (DataStore). Mantido pequeno e sem chaves mágicas espalhadas:
 * a UI lê um snapshot [Settings] e escreve por setters nomeados.
 */
class AppPrefs(private val context: Context) {

    private object Key {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val EXPRESSIVE = booleanPreferencesKey("expressive_motion")
        val SEARCH = stringPreferencesKey("search_engine")
        val HOMEPAGE = stringPreferencesKey("homepage")
        val FIRST_RUN = booleanPreferencesKey("first_run_done")
        val BRIDGE = booleanPreferencesKey("bridge_enabled")
        val DNR = booleanPreferencesKey("dnr_enabled")
        val DESKTOP_DEFAULT = booleanPreferencesKey("desktop_default")
        val STARTUP_TABS = stringPreferencesKey("startup_tabs")
        val LAST_TAB = stringPreferencesKey("last_tab")
        val TOOLBAR_BOTTOM = booleanPreferencesKey("toolbar_bottom")
        val USERSCRIPTS = booleanPreferencesKey("userscripts_enabled")
        val TRACKING_DEFAULT = booleanPreferencesKey("tracking_default")
    }

    val settings: Flow<Settings> = context.mobiDataStore.data
        .catch { t ->
            // DataStore lança IOException em corrupção/concorrência: seguir com vazio é
            // melhor do que derrubar o navegador na inicialização.
            if (t is IOException) emptyPreferences() else throw t
        }
        .map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Key.THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Key.DYNAMIC_COLOR] = enabled }
    suspend fun setExpressive(enabled: Boolean) = edit { it[Key.EXPRESSIVE] = enabled }
    suspend fun setSearchEngine(engine: SearchEngine) = edit { it[Key.SEARCH] = engine.id }
    suspend fun setHomepage(url: String) = edit { it[Key.HOMEPAGE] = url }
    suspend fun setFirstRunDone() = edit { it[Key.FIRST_RUN] = true }
    suspend fun setBridgeEnabled(enabled: Boolean) = edit { it[Key.BRIDGE] = enabled }
    suspend fun setDnrEnabled(enabled: Boolean) = edit { it[Key.DNR] = enabled }
    suspend fun setUserscriptsEnabled(enabled: Boolean) = edit { it[Key.USERSCRIPTS] = enabled }
    suspend fun setToolbarBottom(bottom: Boolean) = edit { it[Key.TOOLBAR_BOTTOM] = bottom }
    suspend fun setLastUsedTab(id: String) = edit { it[Key.LAST_TAB] = id }
    suspend fun setTrackingProtectionDefault(enabled: Boolean) = edit { it[Key.TRACKING_DEFAULT] = enabled }

    suspend fun defaultDesktopMode(): Boolean = snapshot()[Key.DESKTOP_DEFAULT] ?: false
    suspend fun homepage(): String = snapshot()[Key.HOMEPAGE] ?: DEFAULT_HOMEPAGE
    suspend fun saveStartupTabs(urls: List<String>) = edit { it[Key.STARTUP_TABS] = urls.joinToString("\n") }

    suspend fun startupTabs(): List<String> =
        snapshot()[Key.STARTUP_TABS]?.lines()?.filter { it.isNotBlank() } ?: emptyList()

    private suspend fun snapshot(): Preferences = context.mobiDataStore.data.first()

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.mobiDataStore.edit(block)
    }

    private fun Preferences.toSettings() = Settings(
        themeMode = runCatching { ThemeMode.valueOf(this[Key.THEME] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = this[Key.DYNAMIC_COLOR] ?: true,
        expressiveMotion = this[Key.EXPRESSIVE] ?: true,
        searchEngine = SearchEngine.fromId(this[Key.SEARCH]),
        homepage = this[Key.HOMEPAGE] ?: DEFAULT_HOMEPAGE,
        firstRunDone = this[Key.FIRST_RUN] ?: false,
        bridgeEnabled = this[Key.BRIDGE] ?: true,
        dnrEnabled = this[Key.DNR] ?: true,
        userscriptsEnabled = this[Key.USERSCRIPTS] ?: true,
        defaultDesktopMode = this[Key.DESKTOP_DEFAULT] ?: false,
        toolbarAtBottom = this[Key.TOOLBAR_BOTTOM] ?: true,
        trackingProtectionDefault = this[Key.TRACKING_DEFAULT] ?: true,
    )

    companion object {
        const val DEFAULT_HOMEPAGE = "https://mobi.link/"
    }
}

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val expressiveMotion: Boolean = true,
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val homepage: String = AppPrefs.DEFAULT_HOMEPAGE,
    val firstRunDone: Boolean = false,
    val bridgeEnabled: Boolean = true,
    val dnrEnabled: Boolean = true,
    val userscriptsEnabled: Boolean = true,
    val defaultDesktopMode: Boolean = false,
    val toolbarAtBottom: Boolean = true,
    val trackingProtectionDefault: Boolean = true,
)
