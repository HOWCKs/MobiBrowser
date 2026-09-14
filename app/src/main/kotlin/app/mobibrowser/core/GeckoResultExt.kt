package app.mobibrowser.core

import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.GeckoResult
import kotlin.coroutines.resume

/**
 * Ponte GeckoResult → corrotina.
 *
 * Os DOIS consumidores, sempre — isso não é capricho, é o que impede o app de fechar sozinho.
 * No GeckoView 155, `GeckoResult.thenInternal` trata assim um resultado que completou com erro:
 * sem `exceptionListener`, ele faz `result.mIsUncaughtError = mIsUncaughtError` e
 * `completeExceptionally` no resultado derivado; o derivado não tem listeners; e
 * `dispatchLocked`, ao ver `mIsUncaughtError` sem ninguém para encaminhar, **relança**
 * `UncaughtException` na thread que despachou. Isso não passa por `SupervisorJob` nem por
 * `CoroutineExceptionHandler`: o processo morre, e o sintoma no aparelho é "abre e fecha em
 * poucos segundos sem eu tocar em nada".
 *
 * A outra metade do mesmo perigo é o `resume` inline: quando o `GeckoResult` foi criado na main
 * thread, o dispatcher dele é o Looper dela, e um coroutine em `Dispatchers.Main.immediate`
 * continua **dentro do nosso lambda**. Aí qualquer exceção do código depois do `await` cai no
 * `catch (Throwable)` do `thenInternal`, que marca `mIsUncaughtError = true` — mesma rota de
 * morte. Por isso o `runCatching` em volta do `resume`.
 *
 * Contrato: rejeição vale `null`, igual a um timeout — os chamadores já sabem que o motor pode
 * não responder (foi decisão deliberada: o navegador continua navegando quando uma extensão
 * recusa, em vez de fechar).
 */
suspend fun <T> GeckoResult<T>.awaitResult(): T? = suspendCancellableCoroutine { cont ->
    accept(
        { value ->
            runCatching { if (cont.isActive) cont.resume(value) }
                .onFailure { MobiLog.e("gecko", "o código depois de awaitResult lançou", it) }
        },
        { error ->
            MobiLog.w("gecko", "o motor recusou o resultado; sigo sem valor", error)
            runCatching { if (cont.isActive) cont.resume(null) }
        },
    )
}

