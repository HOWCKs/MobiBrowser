package app.mobibrowser.core.ext

/**
 * Tradução humana das permissões de extensão.
 *
 * Motivação de UX: o usuário não decide nada com a palavra `tabs`. A tela de revisão
 * de instalação mostra uma frase por permissão + nível de risco, que é o que um
 * navegador com extensões de verdade precisa para ser confiável.
 */
enum class Risk { LOW, MEDIUM, HIGH }

data class PermissionInfo(val text: String, val risk: Risk)

object PermissionCatalog {

    private val PERMISSIONS: Map<String, PermissionInfo> = mapOf(
        "tabs" to PermissionInfo("Ver abas, histórico de navegação e ações em abas", Risk.MEDIUM),
        "activeTab" to PermissionInfo("Acessar a aba ativa quando você clicar na extensão", Risk.LOW),
        "storage" to PermissionInfo("Guardar dados da extensão no aparelho", Risk.LOW),
        "unlimitedStorage" to PermissionInfo("Guardar dados sem limite de tamanho", Risk.LOW),
        "bookmarks" to PermissionInfo("Ler e alterar seus favoritos", Risk.MEDIUM),
        "history" to PermissionInfo("Ler todo o seu histórico de navegação", Risk.HIGH),
        "downloads" to PermissionInfo("Ler e alterar o histórico de downloads", Risk.MEDIUM),
        "cookies" to PermissionInfo("Ler e alterar cookies (inclui sessões de login)", Risk.HIGH),
        "webRequest" to PermissionInfo("Observar cada requisição de rede", Risk.MEDIUM),
        "webRequestBlocking" to PermissionInfo("Bloquear requisições de rede", Risk.HIGH),
        "declarativeNetRequest" to PermissionInfo("Blocar/permitir requisições por regras", Risk.MEDIUM),
        "declarativeNetRequestFeedback" to PermissionInfo("Relatar quais regras bloquearam o quê", Risk.LOW),
        "declarativeNetRequestWithHostPermissions" to PermissionInfo("Adicionar regras de bloqueio dinamicamente", Risk.MEDIUM),
        "management" to PermissionInfo("Ver e gerenciar outras extensões do navegador", Risk.HIGH),
        "nativeMessaging" to PermissionInfo("Conversar com programas instalados no aparelho", Risk.HIGH),
        "debugger" to PermissionInfo("Inspecionar e controlar outras abas (inclui dados digitados)", Risk.HIGH),
        "tabCapture" to PermissionInfo("Capturar o conteúdo da aba", Risk.HIGH),
        "clipboardRead" to PermissionInfo("Ler a área de transferência", Risk.MEDIUM),
        "clipboardWrite" to PermissionInfo("Escrever na área de transferência", Risk.LOW),
        "notifications" to PermissionInfo("Mostrar notificações do sistema", Risk.LOW),
        "alarms" to PermissionInfo("Agendar tarefas em segundo plano", Risk.LOW),
        "privacy" to PermissionInfo("Alterar configurações de privacidade do navegador", Risk.HIGH),
        "proxy" to PermissionInfo("Redirecionar todo o tráfego por um proxy", Risk.HIGH),
        "topSites" to PermissionInfo("Ver os sites mais visitados", Risk.MEDIUM),
        "sessions" to PermissionInfo("Restaurar sessões de navegação", Risk.HIGH),
        "scripting" to PermissionInfo("Injetar código em páginas abertas", Risk.HIGH),
        "offscreen" to PermissionInfo("Criar superfícies ocultas para processamento", Risk.LOW),
        "sidePanel" to PermissionInfo("Abrir um painel lateral", Risk.LOW),
        "contextMenus" to PermissionInfo("Adicionar itens ao menu de clique direito", Risk.LOW),
        "favicon" to PermissionInfo("Ler ícones dos sites", Risk.LOW),
        "geolocation" to PermissionInfo("Usar sua localização", Risk.HIGH),
        "host_permissions" to PermissionInfo("Acesso a sites", Risk.MEDIUM),
        "contentSettings" to PermissionInfo("Ler e alterar configurações de conteúdo", Risk.HIGH),
    )

    /** Origens/host patterns: viram frases sobre alcance. */
    fun describeOrigin(origin: String): String = when {
        origin == "<all_urls>" -> "Todos os sites (leitura e alteração de tudo que você abrir)"
        origin == "*://*/*" -> "Todos os sites (leitura e alteração de tudo que você abrir)"
        origin.startsWith("*://*") -> "Todos os sites que começarem com `${origin.removePrefix("*://").removeSuffix("*")}`"
        origin.contains("://") -> "Sites em `${origin.substringAfter("://").substringBefore("/")}`"
        else -> origin
    }

    fun describe(permission: String): PermissionInfo {
        PERMISSIONS[permission]?.let { return it }
        // `chrome_settings_overrides`, `experimental` etc.: descrever genericamente.
        val dynamic = when {
            permission.startsWith("chrome://") -> PermissionInfo("Acessar a página interna ${permission.removePrefix("chrome://")}", Risk.HIGH)
            permission.startsWith("privacy:") -> PermissionInfo("Alterar configuração de privacidade ${permission.substringAfter(":")}", Risk.HIGH)
            permission.contains("://") -> PermissionInfo("Acesso direto a `$permission`", Risk.MEDIUM)
            else -> PermissionInfo("Recurso adicional do navegador: `$permission`", Risk.MEDIUM)
        }
        return dynamic
    }

    fun riskOf(permission: String): Risk = describe(permission).risk

    fun highestRisk(permissions: List<String>, origins: List<String>): Risk {
        val perms = permissions.map { riskOf(it) }
        val originRisk = if (origins.any { it == "<all_urls>" || it == "*://*/*" }) listOf(Risk.HIGH) else listOf(Risk.LOW)
        return (perms + originRisk).maxByOrNull { it.ordinal } ?: Risk.LOW
    }

    fun riskLabel(risk: Risk): String = when (risk) {
        Risk.LOW -> "baixo risco"
        Risk.MEDIUM -> "risco médio"
        Risk.HIGH -> "alto risco"
    }

    fun dataCollectionLabel(permission: String): String = when {
        permission.contains("histories") || permission.contains("history") -> "Histórico de navegação"
        permission.contains("cookies") -> "Cookies"
        permission.contains("servers") || permission.contains("preferences") -> "Preferências e dados de login"
        else -> "Dados de navegação ($permission)"
    }
}
