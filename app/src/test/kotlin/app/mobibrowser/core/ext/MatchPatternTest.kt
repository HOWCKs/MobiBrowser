package app.mobibrowser.core.ext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O mesmo interpretador é usado para content scripts, host_permissions e para o filtro
 * por site do cartão de extensão — então vale testar os casos que os manifests reais usam.
 */
class MatchPatternTest {

    @Test
    fun `padrao com coringa casa subdominios`() {
        assertTrue(MatchPattern.matches("*://*.exemplo.com/*", "https://www.exemplo.com/pagina"))
        assertTrue(MatchPattern.matches("*://*.exemplo.com/*", "http://exemplo.com/"))
        assertTrue(MatchPattern.matches("*://*.exemplo.com/app/*", "https://exemplo.com/app/x?y=1"))
    }

    @Test
    fun `nao casa sobrenome de dominio parecido`() {
        assertFalse(MatchPattern.matches("*://*.exemplo.com/*", "https://naoexemplo.com/"))
        assertFalse(MatchPattern.matches("*://*.exemplo.com/*", "https://exemplo.com.evil.net/"))
    }

    @Test
    fun `esquema especifico e respeitado`() {
        assertTrue(MatchPattern.matches("https://*.exemplo.com/*", "https://exemplo.com/"))
        assertFalse(MatchPattern.matches("https://*.exemplo.com/*", "http://exemplo.com/"))
    }

    @Test
    fun `all urls cobre http, https e arquivos`() {
        assertTrue(MatchPattern.matches("<all_urls>", "https://qualquer.co.uk/a"))
        assertTrue(MatchPattern.matches("<all_urls>", "http://qualquer.co.uk/a"))
        assertTrue(MatchPattern.matches("<all_urls>", "file:///sdcard/documento.txt"))
        assertFalse(MatchPattern.matches("<all_urls>", "chrome://settings"))
    }

    @Test
    fun `portas explicitas`() {
        assertTrue(MatchPattern.matches("*://*.exemplo.com:8080/*", "https://exemplo.com:8080/x"))
        assertFalse(MatchPattern.matches("*://*.exemplo.com:8080/*", "https://exemplo.com/x"))
        assertTrue(MatchPattern.matches("*://*.exemplo.com/*", "https://exemplo.com:9000/x"))
    }

    @Test
    fun `padrao invalido nunca casa`() {
        assertFalse(MatchPattern.matches("exemplo.com", "https://exemplo.com/"))
        assertFalse(MatchPattern.matches("", "https://exemplo.com/"))
        assertFalse(MatchPattern.matches("ftp://*.exemplo.com/*", "https://exemplo.com/"))
    }

    @Test
    fun `qualquer subdominio casa com host exato`() {
        assertTrue(MatchPattern.matches("https://exemplo.com/*", "https://exemplo.com/a"))
        assertFalse(MatchPattern.matches("https://exemplo.com/*", "https://sub.exemplo.com/a"))
    }

    @Test
    fun `matchesAny aceita lista`() {
        assertTrue(MatchPattern.matchesAny(listOf("*://cdn.*/*"), "https://cdn.exemplo.com/x.js"))
        assertFalse(MatchPattern.matchesAny(emptyList(), "https://exemplo.com/"))
    }
}
