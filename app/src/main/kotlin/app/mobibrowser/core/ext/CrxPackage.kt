package app.mobibrowser.core.ext

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Le o envelope CRX (formato do Chrome) e devolve o ZIP de payload, que é o que o
 * GeckoView consome depois de convertermos o manifest.
 *
 * CRX2: `Cr24` + version(2) + pubKeyLen(4) + sigLen(4) + pubKey + sig + ZIP
 * CRX3: `Cr24` + version(3) + headerLen(4) + header(protobuf) + ZIP
 *
 * Não verificamos a assinatura RSA do pacote aqui: ela cobre o par (chave, payload)
 * do *Chrome*, e o GeckoView vai revalidar a assinatura *Mozilla* no install(). O que
 * faz sentido verificar no sideload é integridade do ZIP + conteúdo do manifest, e é o
 * que fazemos (recusamos caminhos absolutos, `..` e arquivos fora da pasta).
 */
object CrxPackage {

    private const val MAGIC = "Cr24"

    data class Unpacked(val zipPayload: ByteArray, val crxVersion: Int)

    fun looksLikeCrx(bytes: ByteArray): Boolean =
        bytes.size > 16 && String(bytes, 0, 4, Charsets.ISO_8859_1) == MAGIC

    /** Aceita .crx (v2 ou v3) ou .zip/.xpi já empacotado. */
    fun extract(bytes: ByteArray): Unpacked {
        if (!looksLikeCrx(bytes)) return Unpacked(bytes, 0)

        // little-endian, como no formato original
        fun le32(at: Int): Int =
            (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)

        val version = le32(4)
        val payloadStart = when (version) {
            2 -> {
                val pubKeyLen = le32(8)
                val sigLen = le32(12)
                16 + pubKeyLen + sigLen
            }

            3 -> 8 + le32(8)
            else -> error("Versão CRX não suportada: $version")
        }
        require(payloadStart in 0 until bytes.size) { "Cabeçalho CRX fora do arquivo" }
        return Unpacked(bytes.copyOfRange(payloadStart, bytes.size), version)
    }

    /** Descompacta com segurança (zip-slip protegido) e remove `_metadata/` do Chrome. */
    fun unzip(data: ByteArray, into: File): List<String> {
        into.mkdirs()
        val canonicalRoot = into.canonicalPath + File.separator
        val written = mutableListOf<String>()
        ZipInputStream(data.inputStream()).use { zis ->
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                val name = entry.name
                if (name.startsWith("_metadata") || name.startsWith("__MACOSX") || entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val out = File(into, name)
                if (!out.canonicalPath.startsWith(canonicalRoot)) {
                    // Zip slip: entrada tentando sair da pasta da extensão.
                    zis.closeEntry()
                    continue
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                written += name
                zis.closeEntry()
            }
        }
        return written
    }

    /** Empacota a pasta (já com manifest convertido) como .xpi plano, sem diretório pai. */
    fun zip(dir: File, dest: File): Int {
        dest.parentFile?.mkdirs()
        var count = 0
        ZipOutputStream(FileOutputStream(dest).buffered()).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(dir).path.replace(File.separatorChar, '/')
                zos.putNextEntry(ZipEntry(relative).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                count++
            }
        }
        return count
    }

    fun readManifest(dir: File): String? {
        val manifest = File(dir, "manifest.json")
        return if (manifest.isFile) manifest.readText() else null
    }

    /** Nome curto e estável a partir do id/slack da extensão, para exibição e arquivos. */
    fun safeFileName(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "extensao" }
}
