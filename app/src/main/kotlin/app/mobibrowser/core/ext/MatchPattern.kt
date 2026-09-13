package app.mobibrowser.core.ext

import java.util.regex.Pattern

/**
 * Implementação dos *match patterns* das extensões (`*://*.site/*`, `<all_urls>`),
 * igual à semântica do Chrome/Firefox — é o que decide "esta extensão/script roda
 * neste site?" no modo compatibilidade e nos user scripts.
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

    /** Casa contra qualquer padrão da lista (semântica de `matches: [...]`). */
    fun matchesAny(patterns: List<String>, url: String): Boolean =
        patterns.isEmpty() || patterns.any { matches(it, url) }

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
        val host = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "*" else rest.substring(slash + 1)

        // Validação Chrome-like: wildcard de host só vale como prefixo "*." 
        if (host.isNotEmpty() && host != "*" && !host.startsWith("*.") && host.contains("*")) return null

        val sb = StringBuilder()
        sb.append(if (scheme == "*") "https?|file|ftp" else Pattern.quote(scheme))
        sb.append("://")
        when {
            host == "*" || host.isEmpty() -> sb.append("[^/]*")
            host.startsWith("*.") -> {
                val bare = Pattern.quote(host.substring(2))
                sb.append("(?:[^/]*").append(bare).append(")")
            }

            else -> sb.append(Pattern.quote(host))
        }
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
