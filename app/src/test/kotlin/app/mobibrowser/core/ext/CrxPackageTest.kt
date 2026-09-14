package app.mobibrowser.core.ext

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Envelope CRX + ZIP seguro: as duas coisas que um instalador de sideload precisa acertar. */
class CrxPackageTest {

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun le32(value: Int) = byteArrayOf(
        value.toByte(),
        (value shr 8).toByte(),
        (value shr 16).toByte(),
        (value shr 24).toByte(),
    )

    @Test
    fun `payload CRX3 e recuperado apos o cabecalho`() {
        val zip = zipOf("manifest.json" to """{"manifest_version":3,"name":"X","version":"1"}""")
        val header = ByteArray(40) // protobuf assinado, aqui só bytes de preenchimento
        val crx = ByteArrayOutputStream().apply {
            write("Cr24".toByteArray())
            write(le32(3))
            write(le32(header.size))
            write(header)
            write(zip)
        }.toByteArray()

        assertTrue(CrxPackage.looksLikeCrx(crx))
        val unpacked = CrxPackage.extract(crx)
        assertEquals(3, unpacked.crxVersion)
        assertArrayEquals(zip, unpacked.zipPayload)
    }

    @Test
    fun `payload CRX2 remove chave publica e assinatura`() {
        val zip = zipOf("a.txt" to "oi")
        val pubKey = ByteArray(162)
        val signature = ByteArray(256)
        val crx = ByteArrayOutputStream().apply {
            write("Cr24".toByteArray())
            write(le32(2))
            write(le32(pubKey.size))
            write(le32(signature.size))
            write(pubKey)
            write(signature)
            write(zip)
        }.toByteArray()

        assertArrayEquals(zip, CrxPackage.extract(crx).zipPayload)
    }

    @Test
    fun `zip simples passa sem mexer`() {
        val zip = zipOf("a.txt" to "oi")
        assertFalse(CrxPackage.looksLikeCrx(zip))
        assertArrayEquals(zip, CrxPackage.extract(zip).zipPayload)
    }

    @Test
    fun `cabecalho fora do arquivo falha`() {
        val broken = "Cr24".toByteArray() + le32(3) + le32(999_999)
        runCatching { CrxPackage.extract(broken) }.onSuccess {
            throw AssertionError("deveria ter recusado cabeçalho maior que o arquivo")
        }
    }

    @Test
    fun `extração recusa zip slip e ignora pasta _metadata`() {
        val zip = zipOf(
            "_metadata/signedhead" to "lixo",
            "manifest.json" to "{}",
        )
        val target = File(System.getProperty("java.io.tmpdir"), "crx-test-${System.nanoTime()}")
        try {
            val written = CrxPackage.unzip(zip, target)
            assertEquals(listOf("manifest.json"), written)
            assertFalse("_metadata não deve ser extraído", File(target, "_metadata").exists())
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun `round trip unzip e pack preserva manifest convertido`() {
        val zip = zipOf("manifest.json" to """{"name":"A"}""", "content.js" to "console.log(1)")
        val target = File(System.getProperty("java.io.tmpdir"), "crx-pack-${System.nanoTime()}")
        val dest = File(target, "out.xpi")
        try {
            CrxPackage.unzip(zip, target)
            val packed = CrxPackage.zip(target, dest)
            assertTrue("empacotou $packed arquivo(s)", packed >= 2)
            val repacked = java.util.zip.ZipInputStream(dest.inputStream())
            val names = mutableListOf<String>()
            while (true) {
                val e = repacked.nextEntry ?: break
                names += e.name
                repacked.closeEntry()
            }
            assertTrue(names.contains("manifest.json"))
            assertTrue("arquivo xpi não deve aninhar a pasta da extensão", names.none { it.startsWith(target.name) })
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun `nome de arquivo e higienizado`() {
        assertEquals("u-block-origin", CrxPackage.safeFileName("uBlock Origin"))
        assertEquals("extensao-2", CrxPackage.safeFileName("  Extensão 2  "))
        assertEquals("extensao", CrxPackage.safeFileName("!!!"))
    }
}
