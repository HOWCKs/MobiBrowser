package app.mobibrowser.core.ext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O conversor é a peça mais propensa a quebrar silenciosamente (mexer no manifest errado
 * faz a extensão instalar e não funcionar). Roda no CI como teste JVM puro: sem Robolectric,
 * porque a lógica é só org.json.
 */
class ManifestConverterTest {

    @Test
    fun `mv3 adiciona id gecko e mantem o resto`() {
        val manifest = """
            {
              "manifest_version": 3,
              "name": "Escurecedor",
              "version": "1.4",
              "permissions": ["storage"],
              "host_permissions": ["*://*.exemplo.com/*"],
              "action": { "default_title": "Abrir" },
              "content_scripts": [
                { "js": ["content.js"], "matches": ["*://*.exemplo.com/*"] }
              ]
            }
        """.trimIndent()
        val out = ManifestConverter.convert(manifest)
        val json = org.json.JSONObject(out.manifest)

        assertEquals(3, json.getInt("manifest_version"))
        val geckoId = json.getJSONObject("browser_specific_settings").getJSONObject("gecko").getString("id")
        assertNotNull(geckoId)
        assertTrue("id deve ser estável e com domínio próprio: $geckoId", geckoId.endsWith("@extensions.mobibrowser"))
        assertEquals(1, json.getJSONArray("host_permissions").length())
        assertTrue(out.report.contentScriptFiles.contains("content.js"))
    }

    @Test
    fun `service worker vira pagina de eventos`() {
        val manifest = """
            {
              "manifest_version": 3,
              "name": "Fundo",
              "version": "1",
              "background": { "service_worker": "bg.js", "type": "module" }
            }
        """.trimIndent()
        val json = org.json.JSONObject(ManifestConverter.convert(manifest).manifest)
        val background = json.getJSONObject("background")
        assertFalse("service_worker precisa sumir", background.has("service_worker"))
        assertEquals("bg.js", background.getJSONArray("scripts").getString(0))
        assertFalse(background.getBoolean("persistent"))
        assertFalse("type é exclusivo do Chrome", background.has("type"))
        assertTrue(
            "aviso deve explicar a troca",
            ManifestConverter.convert(manifest).report.warnings.any { it.contains("service_worker") },
        )
    }

    @Test
    fun `mv2 promovido a mv3 com action no lugar de browser action`() {
        val manifest = """
            {
              "manifest_version": 2,
              "name": "Antigo",
              "version": "0.9",
              "permissions": ["tabs", "*://*/*", "storage"],
              "browser_action": { "default_popup": "popup.html", "default_title": "Abrir" },
              "content_security_policy": "script-src 'self'",
              "web_accessible_resources": ["data.json"]
            }
        """.trimIndent()
        val converted = ManifestConverter.convert(manifest)
        val json = org.json.JSONObject(converted.manifest)

        assertEquals(3, json.getInt("manifest_version"))
        assertFalse("browser_action não existe em MV3 do Firefox", json.has("browser_action"))
        assertEquals("popup.html", json.getJSONObject("action").getString("default_popup"))
        assertTrue(converted.report.upgradedFromV2)

        val permissions = json.getJSONArray("permissions")
        val hosts = json.getJSONArray("host_permissions")
        assertFalse("host não pode ficar em permissions", (0 until permissions.length()).any { permissions.getString(it).contains("://") })
        assertTrue("*://*/* deve migrar para host_permissions", (0 until hosts.length()).any { hosts.getString(it) == "*://*/*" })

        val csp = json.getJSONObject("content_security_policy")
        assertEquals("script-src 'self'", csp.getString("extension_pages"))

        val war = json.getJSONArray("web_accessible_resources").getJSONObject(0)
        assertEquals("data.json", war.getJSONArray("resources").getString(0))
        assertEquals("<all_urls>", war.getJSONArray("matches").getString(0))
    }

    @Test
    fun `chaves so do chrome sao descartadas e reportadas`() {
        val manifest = """
            {
              "manifest_version": 3,
              "name": "Com Lixo",
              "version": "1",
              "minimum_chrome_version": "111",
              "offline_enabled": true,
              "chrome_settings_overrides": { "homepage": "https://exemplo.com" },
              "key": "MIIBIjAN..."
            }
        """.trimIndent()
        val report = ManifestConverter.convert(manifest).report
        assertTrue(report.droppedKeys.containsAll(listOf("minimum_chrome_version", "offline_enabled", "chrome_settings_overrides", "key")))
    }

    @Test
    fun `conteudo de regras dnr e listado para o modo ponte`() {
        val manifest = """
            {
              "manifest_version": 3,
              "name": "Bloqueador",
              "version": "2.0",
              "declarative_net_request": {
                "rule_resources": [{ "id": "ads", "path": "rules/ads.json", "enabled": true }]
              }
            }
        """.trimIndent()
        val report = ManifestConverter.convert(manifest).report
        assertEquals(listOf("rules/ads.json"), report.ruleResourcePaths)
    }

    @Test
    fun `id forcado da loja e preservado`() {
        val manifest = """{"manifest_version":3,"name":"X","version":"1"}"""
        val forced = "cjpalhdlnbpafiamejdnhcphjbkeiagm@mobi-store"
        val report = ManifestConverter.convert(manifest, forcedGeckoId = forced).report
        assertEquals(forced, report.geckoId)
    }

    @Test
    fun `manifest invalido falha com mensagem util`() {
        val bad = """{"manifest_version":1,"name":"MV1"}"""
        val error = runCatching { ManifestConverter.convert(bad) }.exceptionOrNull()
        assertTrue(error is ManifestConverter.ConversionException)
        assertTrue((error?.message ?: "").contains("não suportada"))
    }

    @Test
    fun `permissoes so do chrome geram aviso`() {
        val manifest = """
            {"manifest_version":3,"name":"Depuradora","version":"1","permissions":["debugger","tabs","management"]}
        """.trimIndent()
        val warnings = ManifestConverter.convert(manifest).report.warnings
        assertTrue(warnings.any { it.contains("debugger") })
        assertTrue(warnings.any { it.contains("management") })
    }

    @Test
    fun `content script sem matches recebe all_urls`() {
        val manifest = """
            {
              "manifest_version": 3,
              "name": "SemMatches",
              "version": "1",
              "content_scripts": [{ "js": ["a.js"] }]
            }
        """.trimIndent()
        val converted = ManifestConverter.convert(manifest)
        val json = org.json.JSONObject(converted.manifest)
        val entry = json.getJSONArray("content_scripts").getJSONObject(0)
        assertEquals("<all_urls>", entry.getJSONArray("matches").getString(0))
        assertTrue(converted.report.warnings.any { it.contains("matches") })
    }
}
