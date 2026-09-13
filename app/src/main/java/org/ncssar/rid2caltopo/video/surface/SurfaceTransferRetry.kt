package org.ncssar.rid2caltopo.video.surface

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal object SurfaceTransferRetry {
    suspend fun run(
        onRetry: suspend (Int) -> Unit,
        pause: suspend (Long) -> Unit = { delay(it) },
        transfer: suspend () -> Unit
    ) {
        for(attempt in 0..2) {
            currentCoroutineContext().ensureActive()
            try {
                transfer()
                return
            } catch(error: IOException) {
                currentCoroutineContext().ensureActive()
                if(attempt==2 || error is javax.net.ssl.SSLException || error is java.net.ProtocolException) throw error
                onRetry(attempt+1)
                pause(500L*(attempt+1))
            }
        }
    }
}
