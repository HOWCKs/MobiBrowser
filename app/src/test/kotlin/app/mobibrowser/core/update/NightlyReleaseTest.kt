package app.mobibrowser.core.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O verificador decide "há atualização" a partir do título do release e escolhe o APK pela ABI.
 * Os dois erros possíveis são ruins de propósito na mão do usuário — nunca oferecer nada, ou
 * oferecer o binário da arquitetura errada — então isso aqui fica coberto por teste, não por
 * instalação.
 */
class NightlyReleaseTest {

    private fun releaseJson(
        title: String,
        publishedAt: String,
        assets: List<Triple<String, String, Long>>,
    ): String {
        val node = JSONObject()
        node.put("name", title)
        node.put("published_at", publishedAt)
        val array = JSONArray()
        assets.forEach { (name, url, size) ->
            array.put(
                JSONObject()
                    .put("name", name)
                    .put("browser_download_url", url)
                    .put("size", size),
            )
        }
        node.put("assets", array)
        return node.toString()
    }

    private val apkAssets = listOf(
        Triple("app-arm64-v8a-unstable.apk", "https://download/arm64.apk", 115_000_000L),
        Triple("app-armeabi-v7a-unstable.apk", "https://download/armv7.apk", 108_000_000L),
        Triple("app-universal-unstable.apk", "https://download/universal.apk", 240_000_000L),
        Triple("SHA256SUMS.txt", "https://download/sums.txt", 256L),
    )

    @Test
    fun `sha vem do parentese final do titulo, sem o parentese`() {
        val title = "MobiBrowser nightly 2026-09-14 (a42ba18)"
        assertEquals("a42ba18", NightlyRelease.shaOf(title))
        // regressão: o match inteiro incluía o parêntese fechado e a comparação com
        // BuildConfig.GIT_SHA nunca batia — o app diria que há atualização para sempre
        assertTrue(!NightlyRelease.shaOf(title).endsWith(")"))
    }

    @Test
    fun `comparacao de sha tolera prefixo curto e recusa vazio`() {
        assertTrue(NightlyRelease.sameBuild("a42ba18", "a42ba18"))
        assertTrue(NightlyRelease.sameBuild("a42ba18f2", "a42ba18"))
        assertTrue(NightlyRelease.sameBuild("A42BA18", "a42ba18"))
        assertFalse(NightlyRelease.sameBuild("a42ba18", "fffffff"))
        assertFalse(NightlyRelease.sameBuild("", "a42ba18"))
        assertFalse(NightlyRelease.sameBuild("a42ba18", ""))
    }
    @Test
    fun `titulo sem sha vira string vazia`() {
        assertEquals("", NightlyRelease.shaOf("MobiBrowser nightly"))
        assertEquals("", NightlyRelease.shaOf("release de 2026 (temporário)"))
    }

    @Test
    fun `parse mantem so apk com url e corta a data`() {
        val info = NightlyRelease.parse(
            releaseJson("MobiBrowser nightly 2026-09-14 (abc1234)", "2026-09-14T03:12:00Z", apkAssets),
        )
        assertEquals("abc1234", info.sha)
        assertEquals("2026-09-14", info.publishedAt)
        assertEquals(3, info.assets.size)
        assertTrue(info.assets.none { it.name.endsWith(".txt") })
        assertEquals(115_000_000L, info.assets.first().bytes)
    }

    @Test
    fun `a abi do aparelho vence, universal so como reserva`() {
        val assets = NightlyRelease.parse(
            releaseJson("t (abc1234)", "2026-09-14T00:00:00Z", apkAssets),
        ).assets
        assertEquals(
            "app-arm64-v8a-unstable.apk",
            NightlyRelease.pickAsset(assets, listOf("arm64-v8a", "armeabi-v7a"))?.name,
        )
        assertEquals(
            "app-armeabi-v7a-unstable.apk",
            NightlyRelease.pickAsset(assets, listOf("armeabi-v7a", "arm64-v8a"))?.name,
        )
        assertEquals(
            "app-universal-unstable.apk",
            NightlyRelease.pickAsset(assets, listOf("x86_64"))?.name,
        )
    }

    @Test
    fun `aparelho sem apk compativel nao recebe nada`() {
        val assets = NightlyRelease.parse(
            releaseJson(
                "t (abc1234)",
                "2026-09-14T00:00:00Z",
                listOf(Triple("app-arm64-v8a-unstable.apk", "https://download/arm64.apk", 1L)),
            ),
        ).assets
        assertNull(NightlyRelease.pickAsset(assets, listOf("riscv64")))
    }

    @Test
    fun `x86 nao aceita o APK de x86_64`() {
        val mixed = listOf(
            Triple("app-x86_64-unstable.apk", "https://download/x64.apk", 2L),
            Triple("app-universal-unstable.apk", "https://download/u.apk", 3L),
        )
        val assets = NightlyRelease.parse(
            releaseJson("t (abc1234)", "2026-09-14T00:00:00Z", mixed),
        ).assets
        // sem o delimitador no casamento, o x86 engoliria o binário de x86_64
        assertEquals("app-universal-unstable.apk", NightlyRelease.pickAsset(assets, listOf("x86"))?.name)
        assertEquals("app-x86_64-unstable.apk", NightlyRelease.pickAsset(assets, listOf("x86_64"))?.name)
    }
}
