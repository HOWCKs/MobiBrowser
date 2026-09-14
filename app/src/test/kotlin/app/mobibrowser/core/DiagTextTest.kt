package app.mobibrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O que o Diag coleta só existe num aparelho — mas a *leitura* do que ele coleta é formatação de
 * string, e é aí que um diagnóstico pode mentir silenciosamente (cortar a frase que importava,
 * sumir com a memória da hora, tratar como limpo o que foi morte). Estes testes cobrem a metade
 * testável; a outra metade é validada no aparelho, e é isso que a CI não pode fingir que cobre.
 */
class DiagTextTest {

    private val tombstone = buildString {
        appendLine("pid: 18232, tid: 18255, name: Gecko >>> app.mobibrowser <<<")
        appendLine("signal 6 (SIGABRT), code -1 (SI_QUEUE), fault addr --------")
        appendLine("Abort message = 'Assertion failure: !IsOnReaderThread(), at nsIRequest'")
        for (i in 1..400) appendLine("    #$i pc 00000000000$f  /data/app/~~xx/lib/arm64/libxul.so (some::frame)")
    }

    @Test
    fun `format nao inventa linha de campo ausente`() {
        val out = DiagText.format(
            DiagText.ExitRecord(
                timestamp = 1_789_000_000_000L,
                pid = 18232,
                reason = "crash nativo (pilha em código C/C++)",
                subReason = "",
                importance = "primeiro plano",
                description = " crashing",
                status = 0,
                pssMb = -1,
                rssMb = -1,
                trace = "",
            ),
        )
        assertTrue(out.contains("crash nativo"))
        assertTrue(out.contains("primeiro plano"))
        assertFalse("sub-motivo vazio não deve virar linha", out.contains("sub-motivo"))
        assertFalse("PSS indisponível (API 30) não deve virar 0 MB", out.contains("PSS"))
        assertFalse("status 0 não é informação", out.contains("wait()"))
        assertFalse("trace ausente não deve abrir seção vazia", out.contains("pilha registrada"))
    }

    @Test
    fun `format preserva memoria e traco quando existem`() {
        val out = DiagText.format(
            DiagText.ExitRecord(
                timestamp = 0L, pid = 1, reason = "baixa memória: o sistema escolheu este processo",
                subReason = "REASON_CHANGE_CACHED_CAP", importance = "cacheado em segundo plano",
                description = "lmkd", status = 9, pssMb = 512, rssMb = 700, trace = "  #00 pc x",
            ),
        )
        assertTrue(out.contains("PSS 512 MB · RSS 700 MB"))
        assertTrue(out.contains("sub-motivo: REASON_CHANGE_CACHED_CAP"))
        assertTrue(out.contains("status do wait(): 9"))
        assertTrue(out.contains("pilha registrada"))
        assertTrue(out.contains("#00 pc x"))
    }

    @Test
    fun `compactTrace guarda o sinal, o abort message e as pilhas do fim`() {
        val compacted = DiagText.compactTrace(tombstone, 3_500)
        // a cabeça: é onde está o que responde "por que morreu"
        assertTrue(compacted.contains("signal 6 (SIGABRT)"))
        assertTrue(compacted.contains("Abort message = 'Assertion failure: !IsOnReaderThread()"))
        // a cauda: as últimas pilhas, que a cabeça não tem
        assertTrue(compacted.contains("#400 pc"))
        assertTrue("o meio omitido precisa dizer quanto foi omitido", compacted.contains("caracteres omitidos"))
        assertTrue("sem isto o arquivo ficaria maior que o budget", compacted.length < tombstone.length)
    }

    @Test
    fun `compactTrace nao mexe em traco curto`() {
        val short = "signal 11 (SIGSEGV), code 1\n    #00 pc 111 libc.so"
        assertEquals(short, DiagText.compactTrace(short, 3_500))
        assertEquals("", DiagText.compactTrace("   \n  ", 3_500))
    }

    @Test
    fun `batimento e encerramento limpo dizem o que a ausencia deles diria`() {
        val beat = DiagText.heartbeat("12:30:44.120", "motor:GeckoRuntime.create", 1_420, 42, 384, 512, 18)
        assertTrue(beat.contains("fase=motor:GeckoRuntime.create"))
        assertTrue(beat.contains("heap 42/384 MB"))
        assertTrue(beat.contains("RSS 512 MB"))
        // a linha de encerramento limpo é o que diferencia "morreu" de "a pessoa fechou"
        val ended = DiagText.ended("12:31:02.000", "primeira tela visível", 0L)
        assertTrue(ended.contains("encerrado limpo"))
        assertTrue(ended.contains("fase=primeira tela visível"))
    }
}
