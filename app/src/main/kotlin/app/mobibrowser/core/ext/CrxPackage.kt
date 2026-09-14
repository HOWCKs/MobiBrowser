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
        if (!looksLikeCrx(bytes)) {
            // Começa com Cr24 mas é curto para ter cabeçalho: pacote truncado, não um ZIP.
            if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == MAGIC) {
                error("Pacote CRX truncado (${bytes.size} bytes) — o download quebrou no meio.")
            }
            return Unpacked(bytes, 0)
        }

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

            // O envelope tem 12 bytes antes do header: Cr24(4) + versão(4) + tamanho(4).
            // Com 8, o payload começava 4 bytes antes e o ZIP vinha corrompido.
            3 -> 12 + le32(8)
            else -> error("Versão CRX não suportada: $version")
        }
        require(payloadStart in 0 until bytes.size) { "Cabeçalho CRX fora do arquivo" }
        return Unpacked(bytes.copyOfRange(payloadStart, bytes.size), version)
    }

    /**
     * Cabeçalho lido do arquivo, sem carregar o pacote na memória.
     *
     * O `readBytes()` de antes era o problema, não o estilo: um pacote da Chrome Web Store tem
     * 18 MB, e `extract(bytes)` ainda fazia `copyOfRange` de mais 18 MB — 36 MB de heap num
     * momento em que o processo do motor está nascendo. Aqui lemos 16 bytes, descobrimos onde o
     * ZIP começa e descompactamos a partir desse deslocamento, direto do disco.
     */
    data class Header(val payloadStart: Long, val crxVersion: Int, val totalBytes: Long)

    fun headerOf(file: File): Header {
        val size = file.length()
        val head = ByteArray(16)
        java.io.RandomAccessFile(file, "r").use { raf ->
            if (size < 16) error("Pacote muito pequeno para ser uma extensão ($size bytes).")
            raf.readFully(head)
        }
        if (String(head, 0, 4, Charsets.ISO_8859_1) != MAGIC) {
            // Sem o Cr24 é .xpi/.zip plano — payload começa no byte zero.
            return Header(0, 0, size)
        }
        fun le32(at: Int): Long =
            (head[at].toLong() and 0xFF) or
                ((head[at + 1].toLong() and 0xFF) shl 8) or
                ((head[at + 2].toLong() and 0xFF) shl 16) or
                ((head[at + 3].toLong() and 0xFF) shl 24)

        val version = le32(4).toInt()
        val start = when (version) {
            2 -> 16L + le32(8) + le32(12)
            3 -> 12L + le32(8)
            else -> error("Versão CRX não suportada: $version")
        }
        if (start <= 0 || start >= size) {
            error(
                "Cabeçalho CRX aponta para fora do pacote (offset $start de $size bytes): o " +
                    "download chegou incompleto.",
            )
        }
        return Header(start, version, size)
    }

    /** Descompacta a partir de [at] bytes no arquivo. Mesma proteção anti zip-slip do `unzip`. */
    fun unzipFrom(file: File, at: Long, into: File): List<String> {
        into.mkdirs()
        val canonicalRoot = into.canonicalPath + File.separator
        val written = mutableListOf<String>()
        val raw = java.io.FileInputStream(file)
        // ZipInputStream não aceita offset. O pulo tem de acontecer no FileInputStream ANTES de o
        // invólucro nascer: depois dele, o buffer já teria comido parte do cabeçalho e a primeira
        // entrada sairia corrompida — que é exatamente a cara do "Unexpected end of ZLIB".
        if (at > 0) {
            var left = at
            while (left > 0) {
                val skipped = raw.skip(left)
                if (skipped <= 0) break
                left -= skipped
            }
            require(left == 0L) { "O pacote terminou antes do deslocamento do payload ($at bytes)." }
        }
        // O ZipInputStream devolve "Unexpected end of ZLIB input stream" quando o corpo parou no
        // meio — mensagem exata que apareceu na tela de quem tentou instalar. Ela é verdadeira e é
        // inútil: não diz o que fazer. Traduzo para o que a pessoa pode fazer, guardando o motivo.
        try {
            unzipInto(raw, into, canonicalRoot, written)
        } catch (e: java.io.IOException) {
            error(
                "O pacote chegou incompleto ou corrompido (${e.message}). " +
                    "Baixe de novo — se repetir, é a conexão caindo no meio do download.",
            )
        }
        return written
    }

    private fun unzipInto(
        raw: java.io.FileInputStream,
        into: File,
        canonicalRoot: String,
        written: MutableList<String>,
    ) {
        ZipInputStream(java.io.BufferedInputStream(raw)).use { zis ->
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                val name = entry.name
                if (name.startsWith("_metadata") || name.startsWith("__MACOSX") || entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val out = File(into, name)
                if (!out.canonicalPath.startsWith(canonicalRoot)) {
                    zis.closeEntry()
                    continue
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                written += name
                zis.closeEntry()
            }
        }
        if (written.isEmpty()) {
            error(
                "O pacote não tem nenhum arquivo aproveitado. Isso é truncamento (a loja parou o " +
                    "download no meio) ou um CRX que não é ZIP depois do cabeçalho.",
            )
        }
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
        java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "") // ã vira a, ç vira c — sem isso o acento era hífen
            .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2") // uBlock -> u-Block, legível no nome
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "extensao" }
}
