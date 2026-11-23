package tn.malak.app.ui.explore

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicFeedSingleFlightTest {
    @Test
    fun overlappingMapAndListReadsShareOneRequestButNextReadRefreshes() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val fetches = AtomicInteger(0)
        val feed = PublicFeedSingleFlight {
            val number = fetches.incrementAndGet()
            started.complete(Unit)
            finish.await()
            listOf(number)
        }

        val map = async(start = CoroutineStart.UNDISPATCHED) { feed.get() }
        started.await()
        val list = async(start = CoroutineStart.UNDISPATCHED) { feed.get() }
        assertEquals(1, fetches.get())
        finish.complete(Unit)
        assertEquals(listOf(1), map.await())
        assertEquals(listOf(1), list.await())
        // A later read must contact the server again: no stale revocation cache.
        assertEquals(listOf(2), feed.get())
        assertEquals(2, fetches.get())
    }

    @Test
    fun failedPublicRequestDoesNotBecomeCachedEmptyInventory() = runBlocking {
        val fetches = AtomicInteger(0)
        val feed = PublicFeedSingleFlight {
            if (fetches.incrementAndGet() == 1) throw IOException("HTTP 403")
            listOf("real-listing")
        }

        var failed = false
        try { feed.get() } catch (_: IOException) { failed = true }
        assertTrue(failed)
        assertEquals(listOf("real-listing"), feed.get())
        assertEquals(2, fetches.get())
    }
}
