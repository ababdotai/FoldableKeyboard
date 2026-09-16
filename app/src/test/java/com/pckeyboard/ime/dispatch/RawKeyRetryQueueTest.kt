package com.pckeyboard.ime.dispatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the bounded, expiring, target-isolated raw retry queue. */
class RawKeyRetryQueueTest {

    /** Verifies missing-connection input remains available for one later drain. */
    @Test
    fun retainsQueuedKeyUntilPolled() {
        val queue = RawKeyRetryQueue()
        val key = QueuedRawKey(29, 0, "remote.app", 100L)

        queue.enqueue(key)

        assertTrue(queue.hasPending("remote.app", 150L))
        assertEquals(key, queue.poll("remote.app", 150L))
        assertFalse(queue.hasPending("remote.app", 150L))
    }

    /** Verifies capacity overflow drops the oldest key and keeps recent input ordered. */
    @Test
    fun boundsCapacityByDroppingOldest() {
        val queue = RawKeyRetryQueue(capacity = 2)
        queue.enqueue(QueuedRawKey(1, 0, "remote.app", 0L))
        queue.enqueue(QueuedRawKey(2, 0, "remote.app", 1L))
        queue.enqueue(QueuedRawKey(3, 0, "remote.app", 2L))

        assertEquals(2, queue.size())
        assertEquals(2, queue.poll("remote.app", 10L)?.keyCode)
        assertEquals(3, queue.poll("remote.app", 10L)?.keyCode)
    }

    /** Verifies keys expire at the configured TTL boundary. */
    @Test
    fun discardsExpiredKeys() {
        val queue = RawKeyRetryQueue(ttlMs = 1_000L)
        queue.enqueue(QueuedRawKey(1, 0, "remote.app", 100L))

        assertNull(queue.poll("remote.app", 1_100L))
        assertEquals(0, queue.size())
    }

    /** Verifies a key queued before editor metadata exists can reach the newly known target. */
    @Test
    fun treatsUnknownBootstrapPackageAsWildcard() {
        val queue = RawKeyRetryQueue()
        val key = QueuedRawKey(1, 0, null, 0L)
        queue.enqueue(key)

        assertEquals(key, queue.poll("remote.app", 10L))
    }

    /** Verifies a known target is never polled while the current target is unknown. */
    @Test
    fun rejectsKnownPackageWhenCurrentTargetIsUnknown() {
        val queue = RawKeyRetryQueue()
        queue.enqueue(QueuedRawKey(1, 0, "remote.app", 0L))

        assertNull(queue.poll(null, 10L))
        assertEquals(0, queue.size())
    }

    /** Verifies keys captured for a previous package never reach the new target. */
    @Test
    fun isolatesTargetPackages() {
        val queue = RawKeyRetryQueue()
        queue.enqueue(QueuedRawKey(1, 0, "first.app", 0L))
        queue.enqueue(QueuedRawKey(2, 0, "second.app", 1L))

        assertEquals(2, queue.poll("second.app", 10L)?.keyCode)
        assertNull(queue.poll("second.app", 10L))
    }
}
