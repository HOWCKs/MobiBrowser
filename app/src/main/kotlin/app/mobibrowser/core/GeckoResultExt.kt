package app.mobibrowser.core

import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.GeckoResult
import kotlin.coroutines.resume

/**
 * Ponte GeckoResult → corrotina.
 *
 * O GeckoView devolve [GeckoResult] em vez de `suspend`. Completar a continuação é
 * seguro de qualquer thread (GeckoResult é sincronizado), e o timeout fica no chamador
 * para nenhuma UI ficar esperando para sempre um motor que não respondeu.
 */
suspend fun <T> GeckoResult<T>.awaitResult(): T? = suspendCancellableCoroutine { cont ->
    accept { value ->
        if (cont.isActive) cont.resume(value)
    }
}
