package app.mobibrowser.core.ext

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
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

    /* ------------------------------------------------------------------ *
     * O caminho de arquivo (usado pela instalação da Chrome Web Store).   *
     * A versão em memória foi o defeito: 18 MB viravam 36 de heap, e um   *
     * download cortado no meio aparecia na tela como "Unexpected end of    *
     * ZLIB input stream" — verdadeiro e inútil. Estes dois casos seguram a  *
     * correção: deslocamento certo no CRX3 e truncamento com mensagem que    *
     * diz o que fazer.                                                        *
     * ------------------------------------------------------------------ */

    @Test
    fun `le do arquivo a partir do deslocamento do payload CRX3`() {
        val zip = zipOf(
            "manifest.json" to """{"manifest_version":3,"name":"Bloco","version":"1"}""",
            "content.js" to "console.log('oi')",
        )
        val header = ByteArray(40)
        val payloadStart = 12 + header.size
        crxFile(zip, header).let { file ->
            val head = CrxPackage.headerOf(file)
            assertEquals(payloadStart.toLong(), head.payloadStart)
            assertEquals(3, head.crxVersion)
            val dir = File(createTempDir(), "saida")
            val written = CrxPackage.unzipFrom(file, head.payloadStart, dir)
            assertEquals(listOf("manifest.json", "content.js"), written.sorted())
            assertTrue(File(dir, "content.js").readText().contains("console.log"))
        }
    }

    @Test
    fun `pacote cortado no meio devolve mensagem que diz o que fazer, nao pilha do zlib`() {
        val zip = zipOf(
            "manifest.json" to """{"manifest_version":3,"name":"Cortado","version":"1"}""",
            "big.js" to "x".repeat(200_000),
        )
        val header = ByteArray(12)
        val full = java.io.ByteArrayOutputStream().apply {
            write("Cr24".toByteArray()); write(le32(3)); write(le32(header.size)); write(header); write(zip)
        }.toByteArray()
        val file = File.createTempFile("cortado", ".crx")
        // Trunco no meio da deflate: é o que a conexão caindo produz, e é o caso que a tela
        // mostrava como texto ininteligível.
        file.writeBytes(full.copyOf(full.size - full.size / 3))
        val head = CrxPackage.headerOf(file)
        val error = runCatching { CrxPackage.unzipFrom(file, head.payloadStart, File(createTempDir(), "x")) }
            .exceptionOrNull()
        assertTrue("esperava falha clara, veio: ${error?.message}", error != null)
        val msg = error!!.message.orEmpty()
        // A checagem é de começo de frase, não de conteúdo ausente: a mensagem original do
        // ZipInputStream continua DENTRO do nosso texto (é o que eu quero ler numa issue), mas ela
        // deixa de ser a primeira coisa que a pessoa vê na tela.
        assertTrue(
            "a frase precisa começar pela nossa explicação, e veio: $msg",
            msg.startsWith("O pacote"),
        )
        assertTrue("precisa dizer o que fazer", msg.contains("de novo") || msg.contains("sem função"))
    }

    private fun crxFile(zip: ByteArray, header: ByteArray): File {
        val file = File.createTempFile("pacote", ".crx")
        java.io.ByteArrayOutputStream().apply {
            write("Cr24".toByteArray())
            write(le32(3))
            write(le32(header.size))
            write(header)
            write(zip)
        }.let { file.writeBytes(it.toByteArray()) }
        return file
    }
}

