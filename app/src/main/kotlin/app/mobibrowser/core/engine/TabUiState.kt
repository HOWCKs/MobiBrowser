package app.mobibrowser.core.engine

import android.graphics.Bitmap

/** Estado de UI de uma aba. Imutável: o GeckoView notifica pelos delegates e publicamos cópias. */
data class TabUiState(
    val id: String,
    val url: String = "",
    val title: String = "",
    /** 0..100, ou -1 quando não há progresso (barra escondida). */
    val progress: Int = -1,
    val loading: Boolean = false,
    val secure: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val crashed: Boolean = false,
    val isPrivate: Boolean = false,
    val desktopMode: Boolean = false,
    val javascript: Boolean = true,
    val trackingProtection: Boolean = true,
    val findQuery: String = "",
    val findVisible: Boolean = false,
    val findMatches: Int = 0,
    /** Textos do placeholder do campo de endereço: "Pesquisar" vs. URL atual editável. */
    val editing: Boolean = false,
) {
    val displayUrl: String
        get() = url
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")

    val host: String?
        get() = url.toHost()
}

/** Estado de um botão de extensão (browser action) vindo do motor. */
data class ExtensionActionUi(
    val extensionId: String,
    val name: String,
    val title: String? = null,
    val enabled: Boolean = true,
    val badgeText: String? = null,
    val badgeBackground: Int? = null,
    val badgeTextColor: Int? = null,
    val icon: Bitmap? = null,
)

/** Popup de extensão: a sessão criada por nós e devolvida em ActionDelegate.onOpenPopup. */
data class ExtensionPopupUi(
    val extensionId: String,
    val name: String,
    val session: org.mozilla.geckoview.GeckoSession,
)

/** Pedido de permissão que o motor fez ao app (PromptDelegate) e que a UI precisa responder. */
data class InstallPromptUi(
    val extensionId: String,
    val name: String,
    val permissions: List<String>,
    val origins: List<String>,
    val dataCollection: List<String>,
)

fun String?.toHost(): String? = runCatching {
    val uri = android.net.Uri.parse(this ?: return null)
    uri.host
}.getOrNull()

/** Converte entrada do usuário em URL: aceita domínio, "pesquisa", url com scheme e `about:`. */
fun String.toNavigationTarget(searchEngineUrl: String): String {
    val input = trim()
    if (input.isEmpty()) return "about:blank"
    val looksLikeScheme = input.contains("://") || input.startsWith("about:") ||
        input.startsWith("data:") || input.startsWith("moz-extension:") || input.startsWith("file:")
    if (looksLikeScheme) return input
    val noSpacesSingleToken = !input.contains(" ") && (input.contains(".") || input.startsWith("localhost"))
    val isIp = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?$""").matches(input)
    return if (noSpacesSingleToken || isIp) {
        "https://$input"
    } else {
        searchEngineUrl.replace("%s", android.net.Uri.encode(input))
    }
}
