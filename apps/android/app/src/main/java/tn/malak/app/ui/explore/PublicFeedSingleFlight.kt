package tn.malak.app.ui.explore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Coalesces only overlapping reads, not completed responses.
 *
 * The public feed is re-read for every later request, so sold/withdrawn records
 * cannot linger in a local success cache. The bounded HTTPS timeouts apply to
 * every in-flight read; cancelling a map request does not cancel a concurrent
 * list request sharing the same public snapshot.
 */
internal class PublicFeedSingleFlight<T>(private val loadFresh: suspend () -> T) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Deferred<T>? = null

    suspend fun get(): T {
        val request = synchronized(lock) {
            pending?.takeUnless { it.isCompleted }
                ?: scope.async(start = CoroutineStart.LAZY) { loadFresh() }.also { pending = it }
        }
        request.start()
        return request.await()
    }
}
