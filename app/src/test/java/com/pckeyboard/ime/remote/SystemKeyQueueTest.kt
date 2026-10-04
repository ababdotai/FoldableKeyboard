package com.pckeyboard.ime.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies bounded input cannot outlive its captured overlay session. */
class SystemKeyQueueTest {
    /** Preserves target UID, display, modifiers, and FIFO order while keeping capacity bounded. */
    @Test
    fun retainsRecentKeysWithTheirCapturedTarget() {
        val queue = SystemKeyQueue(capacity = 2)
        queue.enqueue(29, 0, 10_101, 0, 0)
        queue.enqueue(30, 65, 10_102, 2, 1)
        queue.enqueue(31, 0, 10_103, 3, 2)

        val first = requireNotNull(queue.poll(10))
        assertEquals(30, first.keyCode)
        assertEquals(65, first.metaState)
        assertEquals(10_102, first.targetUid)
        assertEquals(2, first.displayId)
        assertEquals(31, queue.poll(10)?.keyCode)
        assertNull(queue.poll(10))
    }

    /** Drops a key precisely at the TTL boundary but allows a newer key from the same session. */
    @Test
    fun skipsExpiredKeys() {
        val queue = SystemKeyQueue(ttlMs = 1_000)
        queue.enqueue(29, 0, 10_101, 0, 100)
        queue.enqueue(30, 0, 10_101, 0, 101)

        assertEquals(30, queue.poll(1_100)?.keyCode)
        assertNull(queue.poll(1_100))
    }

    /** Invalidates already-dequeued keys as well as queued keys when the overlay loses its target. */
    @Test
    fun cancellationInvalidatesDequeuedAndPendingKeys() {
        val queue = SystemKeyQueue()
        queue.enqueue(29, 0, 10_101, 0, 0)
        queue.enqueue(30, 0, 10_101, 0, 0)
        val dequeued = requireNotNull(queue.poll(1))
        assertTrue(queue.isCurrent(dequeued))

        queue.cancel()

        assertFalse(queue.isCurrent(dequeued))
        assertNull(queue.poll(2))
        queue.enqueue(31, 0, 10_101, 0, 3)
        assertTrue(queue.isCurrent(requireNotNull(queue.poll(4))))
    }

    /** Never replays a key after it has been removed for its single dispatch attempt. */
    @Test
    fun pollingRemovesKeyPermanently() {
        val queue = SystemKeyQueue()
        queue.enqueue(29, 0, 10_101, 0, 0)

        assertEquals(29, queue.poll(1)?.keyCode)
        assertNull(queue.poll(2))
    }

    /** Reports capacity and TTL drops as counts, without passing any input to the callback. */
    @Test
    fun reportsSeparateDropReasonsAndQueuedCancellationCount() {
        val drops = mutableListOf<Pair<SystemKeyDropReason, Long>>()
        val queue = SystemKeyQueue(capacity = 2, ttlMs = 10) { reason, count -> drops.add(reason to count) }
        queue.enqueue(29, 0, 10_101, 0, 0)
        queue.enqueue(30, 0, 10_101, 0, 1)
        queue.enqueue(31, 0, 10_101, 0, 2)

        assertEquals(31, queue.poll(11)?.keyCode)
        assertEquals(
            listOf(SystemKeyDropReason.CAPACITY to 1L, SystemKeyDropReason.EXPIRED to 1L),
            drops,
        )
        queue.enqueue(32, 0, 10_101, 0, 12)
        queue.enqueue(33, 0, 10_101, 0, 13)
        assertEquals(2, queue.cancel())
        assertEquals(0, queue.cancel())
        assertEquals(2, drops.size)
    }
}
