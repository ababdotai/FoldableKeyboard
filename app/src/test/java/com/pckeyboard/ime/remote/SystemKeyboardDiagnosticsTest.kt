package com.pckeyboard.ime.remote

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies content-free accounting and isolation between asynchronous bridge sessions. */
class SystemKeyboardDiagnosticsTest {
    /** Separates a complete Android event plan from both partial and rejected attempts. */
    @Test
    fun distinguishesFullAcceptancePartialAcceptanceAndFailure() {
        val owner = SystemKeyboardDiagnostics.beginSession()
        val epoch = requireNotNull(SystemKeyboardDiagnostics.attemptStarted(owner))
        SystemKeyboardDiagnostics.attemptFinished(owner, epoch, 2, 2)
        SystemKeyboardDiagnostics.attemptStarted(owner)
        SystemKeyboardDiagnostics.attemptFinished(owner, epoch, 1, 2)
        SystemKeyboardDiagnostics.attemptStarted(owner)
        SystemKeyboardDiagnostics.attemptFinished(owner, epoch, SystemInputStatus.PERMISSION_DENIED, 2)

        val snapshot = SystemKeyboardDiagnostics.snapshot()
        assertEquals(3L, snapshot.attempted)
        assertEquals(1L, snapshot.accepted)
        assertEquals(2L, snapshot.failed)
        assertEquals(SystemInputStatus.PERMISSION_DENIED, snapshot.lastResultCode)
        assertEquals(2, snapshot.lastExpectedEvents)
    }

    /** Rejects connection, capability, and delivery callbacks belonging to a replaced bridge. */
    @Test
    fun replacedOwnerCannotOverwriteNewSession() {
        val oldOwner = SystemKeyboardDiagnostics.beginSession()
        val oldEpoch = requireNotNull(SystemKeyboardDiagnostics.attemptStarted(oldOwner))
        val owner = SystemKeyboardDiagnostics.beginSession()
        SystemKeyboardDiagnostics.connectionChanged(owner, SystemConnectionState.CONNECTED)
        SystemKeyboardDiagnostics.capabilitiesChecked(owner, SystemInputStatus.READY)

        SystemKeyboardDiagnostics.connectionChanged(oldOwner, SystemConnectionState.CLOSED)
        SystemKeyboardDiagnostics.capabilitiesChecked(oldOwner, SystemInputStatus.UNKNOWN_ERROR)
        SystemKeyboardDiagnostics.attemptFinished(oldOwner, oldEpoch, 2, 2)
        SystemKeyboardDiagnostics.dropped(oldOwner, 5, SystemKeyDropReason.EXPIRED)
        SystemKeyboardDiagnostics.cancelled(oldOwner, 5)
        assertNull(SystemKeyboardDiagnostics.attemptStarted(oldOwner))

        assertEquals(
            SystemKeyboardSnapshot(connection = SystemConnectionState.CONNECTED, capabilityCode = SystemInputStatus.READY),
            SystemKeyboardDiagnostics.snapshot(),
        )
    }

    /** Prevents a pre-clear in-flight completion from repopulating cleared delivery counters. */
    @Test
    fun clearingCountersPreservesConnectionButIgnoresOldAttempt() {
        val owner = SystemKeyboardDiagnostics.beginSession()
        SystemKeyboardDiagnostics.connectionChanged(owner, SystemConnectionState.CONNECTED)
        SystemKeyboardDiagnostics.capabilitiesChecked(owner, SystemInputStatus.READY)
        val epoch = requireNotNull(SystemKeyboardDiagnostics.attemptStarted(owner))

        SystemKeyboardDiagnostics.clearCounters()
        SystemKeyboardDiagnostics.attemptFinished(owner, epoch, 2, 2)
        SystemKeyboardDiagnostics.cancelled(owner, 1, epoch)

        assertEquals(
            SystemKeyboardSnapshot(connection = SystemConnectionState.CONNECTED, capabilityCode = SystemInputStatus.READY),
            SystemKeyboardDiagnostics.snapshot(),
        )
        val newEpoch = requireNotNull(SystemKeyboardDiagnostics.attemptStarted(owner))
        SystemKeyboardDiagnostics.attemptFinished(owner, newEpoch, 2, 2)
        assertEquals(1L, SystemKeyboardDiagnostics.snapshot().accepted)
    }

    /** Records only aggregate capacity, expiration, unavailable, and cancellation counts. */
    @Test
    fun accumulatesDropReasonsWithoutInputRecords() {
        val owner = SystemKeyboardDiagnostics.beginSession()
        SystemKeyboardDiagnostics.dropped(owner, 2, SystemKeyDropReason.CAPACITY)
        SystemKeyboardDiagnostics.dropped(owner, 3, SystemKeyDropReason.EXPIRED)
        SystemKeyboardDiagnostics.dropped(owner, 4, SystemKeyDropReason.UNAVAILABLE)
        SystemKeyboardDiagnostics.cancelled(owner, 5)

        val snapshot = SystemKeyboardDiagnostics.snapshot()
        assertEquals(9L, snapshot.dropped)
        assertEquals(2L, snapshot.overflowDropped)
        assertEquals(3L, snapshot.expiredDropped)
        assertEquals(5L, snapshot.cancelled)
        val fields = SystemKeyboardSnapshot::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(
            setOf(
                "connection", "capabilityCode", "attempted", "accepted", "failed", "dropped",
                "overflowDropped", "expiredDropped", "cancelled", "lastResultCode", "lastExpectedEvents",
            ),
            fields.map { it.name }.toSet(),
        )
    }

    /** Clears capability evidence after disconnect and refuses an unrelated numeric status. */
    @Test
    fun disconnectedCapabilityCannotAppearReady() {
        val owner = SystemKeyboardDiagnostics.beginSession()
        SystemKeyboardDiagnostics.connectionChanged(owner, SystemConnectionState.CONNECTED)
        SystemKeyboardDiagnostics.capabilitiesChecked(owner, 123_456)
        assertEquals(SystemInputStatus.UNKNOWN_ERROR, SystemKeyboardDiagnostics.snapshot().capabilityCode)
        SystemKeyboardDiagnostics.connectionChanged(owner, SystemConnectionState.DISCONNECTED)
        SystemKeyboardDiagnostics.capabilitiesChecked(owner, SystemInputStatus.READY)
        assertNull(SystemKeyboardDiagnostics.snapshot().capabilityCode)
    }
}
