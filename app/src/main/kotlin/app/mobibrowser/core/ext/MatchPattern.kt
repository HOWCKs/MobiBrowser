package app.mobibrowser.core.ext

import java.util.regex.Pattern

/**
 * Implementação dos "match patterns" das extensões: o curinga de protocolo/host/caminho que
 * o Chrome usa (cada parte aceita estrela) e o atalho `all_urls`. É o que decide "esta
 * extensão roda neste site?" no modo compatibilidade e nos user scripts, com a mesma
 * semântica do Firefox.
 *
 * O exemplo literal não cabe neste comentário de propósito: todo match pattern tem uma barra
 * seguida de estrela, e o Kotlin ANINHA comentário de bloco — a sequência abriria um
 * aninhado fechado pelo `termina-comentário` de baixo, deixando este arquivo inteiro como
 * comentário. Foi exatamente assim que este `object` sumiu de um build sem acusar nada aqui.
 * tools/kotlin-comment-check.py é a guarda contra isso.
 */
object MatchPattern {

    private val cache = HashMap<String, Pattern?>()

    /** @return regex compilada, ou null se o padrão for inválido (tratar como "nunca casa"). */
    private fun compile(pattern: String): Pattern? = synchronized(cache) {
        cache.getOrPut(pattern) { build(pattern) }
    }

    fun matches(pattern: String, url: String): Boolean {
        if (pattern.isBlank()) return false
        if (pattern == "<all_urls>") return url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("file://") || url.startsWith("ftp://")
        val regex = compile(pattern) ?: return false
        return regex.matcher(url).matches()
    }

    /**
     * Casa contra qualquer padrão da lista (semântica de `matches: [...]`).
     *
     * Lista VAZIA é "lugar nenhum", não "em todo lugar": um content script sem `matches`
     * não roda (é o que o Chrome faz), e as permissões por site são whitelist — se o
     * usuário negou tudo, "tudo" não pode significar "liberado".
     */
    fun matchesAny(patterns: List<String>, url: String): Boolean = patterns.any { matches(it, url) }

    /** Hosts legíveis para exibir "só roda em *.exemplo.com" na tela da extensão. */
    fun describe(patterns: List<String>): String = when {
        patterns.isEmpty() || patterns.any { it == "<all_urls>" || it == "*://*/*" } ->
            "todos os sites"

        else -> patterns.joinToString(", ") { p ->
            p.removePrefix("*://").removePrefix("https://").removePrefix("http://")
                .removeSuffix("/*").removeSuffix("*")
                .ifBlank { p }
        }
    }

    private fun build(pattern: String): Pattern? {
        val sep = pattern.indexOf("://")
        if (sep < 0) return null
        val scheme = pattern.substring(0, sep)
        if (scheme != "*" && scheme != "http" && scheme != "https" && scheme != "file" && scheme != "ftp") {
            return null
        }
        val rest = pattern.substring(sep + 3)
        val slash = rest.indexOf('/')
        val authority = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "*" else rest.substring(slash + 1)
        val colon = authority.lastIndexOf(':')
        val host = if (colon < 0) authority else authority.substring(0, colon)
        val port = if (colon < 0) null else authority.substring(colon + 1)
        if (port != null && (port.isEmpty() || port.any { !it.isDigit() })) return null

        // Validação Chrome-like: wildcard de host só vale como prefixo "*." 
        if (host.isNotEmpty() && host != "*" && !host.startsWith("*.") && host.contains("*")) return null

        val sb = StringBuilder()
        // O `(?:)` não é enfeite: sem grupo, a alternativa do esquema engole o resto do
        // padrão (`https?` | `file|ftp://…`) e nada casa — foi o que os testes pegaram.
        sb.append(if (scheme == "*") "(?:https?|file|ftp)" else Pattern.quote(scheme))
        sb.append("://")
        when {
            host == "*" || host.isEmpty() -> sb.append("[^/]*")
            // `*.exemplo.com` casa o domínio nu e os subdomínios, mas NÃO `naoexemplo.com`:
            // por isso o curinga precisa terminar em ponto, e não ser "qualquer coisa".
            host.startsWith("*.") ->
                sb.append("(?:[^/]*\\.)?").append(Pattern.quote(host.substring(2)))

            else -> sb.append(Pattern.quote(host))
        }
        // Porta: só é exigida se o padrão escrever uma; sem porta no padrão, casa qualquer
        // porta (útil em servidor de desenvolvimento local, comum no celular).
        sb.append(if (port == null) "(?::\\d+)?" else ":" + Pattern.quote(port))
        sb.append("/")
        // `*` casa qualquer coisa, inclusive nada; outros caracteres são literais.
        val pathRegex = buildString {
            var i = 0
            while (i < path.length) {
                val c = path[i]
                if (c == '*') append(".*") else append(Pattern.quote(c.toString()))
                i++
            }
        }
        sb.append(pathRegex)
        return runCatching { Pattern.compile(sb.toString()) }.getOrNull()
    }
}
