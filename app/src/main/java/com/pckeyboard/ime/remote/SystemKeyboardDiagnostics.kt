package com.pckeyboard.ime.remote

/** Describes transport lifecycle independently of injection capability or remote receipt. */
enum class SystemConnectionState {
    IDLE, CONNECTING, CONNECTED, DISCONNECTED, TIMED_OUT, CLOSED, BLOCKED,
}

/** Contains only aggregate metadata; no input contents, device identities, or event history. */
data class SystemKeyboardSnapshot(
    val connection: SystemConnectionState = SystemConnectionState.IDLE,
    val capabilityCode: Int? = null,
    val attempted: Long = 0,
    val accepted: Long = 0,
    val failed: Long = 0,
    val dropped: Long = 0,
    val overflowDropped: Long = 0,
    val expiredDropped: Long = 0,
    val cancelled: Long = 0,
    val lastResultCode: Int? = null,
    val lastExpectedEvents: Int? = null,
)

/** Identifies a content-free reason that queued input was discarded. */
internal enum class SystemKeyDropReason { CAPACITY, EXPIRED, UNAVAILABLE }

/** Keeps one in-memory session, never treating HID submission as remote confirmation. */
object SystemKeyboardDiagnostics {
    private var session = 0L
    private var counterEpoch = 0L
    private var current = SystemKeyboardSnapshot()

    /** Returns an immutable snapshot without exposing queued input or target identifiers. */
    @Synchronized
    fun snapshot(): SystemKeyboardSnapshot = current

    /** Clears counters while retaining the current connection and device readiness result. */
    @Synchronized
    fun clearCounters() {
        counterEpoch++
        current = SystemKeyboardSnapshot(current.connection, current.capabilityCode)
    }

    /** Starts a new owner session and invalidates all previous owners' asynchronous callbacks. */
    @Synchronized
    internal fun beginSession(): Long {
        session++
        counterEpoch++
        current = SystemKeyboardSnapshot()
        return session
    }

    /** Updates the active transport and invalidates capability data when it is not connected. */
    @Synchronized
    internal fun connectionChanged(owner: Long, state: SystemConnectionState) {
        if (owner != session) return
        current = current.copy(
            connection = state,
            capabilityCode = if (state == SystemConnectionState.CONNECTED) current.capabilityCode else null,
        )
    }

    /** Records device readiness only for the currently connected owner. */
    @Synchronized
    internal fun capabilitiesChecked(owner: Long, code: Int) {
        if (owner != session || current.connection != SystemConnectionState.CONNECTED) return
        current = current.copy(capabilityCode = sanitizeStatus(code))
    }

    /** Counts an IPC attempt and returns the epoch needed to ignore results after a clear. */
    @Synchronized
    internal fun attemptStarted(owner: Long): Long? {
        if (owner != session) return null
        current = current.copy(attempted = current.attempted + 1)
        return counterEpoch
    }

    /** Counts complete HID submissions separately from rejected or partial event plans. */
    @Synchronized
    internal fun attemptFinished(owner: Long, epoch: Long, result: Int, expected: Int) {
        if (owner != session || epoch != counterEpoch) return
        val complete = result == expected && expected > 0
        val safeResult = if (result in 0..expected) result else sanitizeStatus(result)
        current = current.copy(
            accepted = current.accepted + if (complete) 1 else 0,
            failed = current.failed + if (complete) 0 else 1,
            lastResultCode = safeResult,
            lastExpectedEvents = expected,
        )
    }

    /** Counts discarded pending input without accepting any key or modifier information. */
    @Synchronized
    internal fun dropped(owner: Long, count: Long, reason: SystemKeyDropReason) {
        if (owner != session || count <= 0) return
        current = current.copy(
            dropped = current.dropped + count,
            overflowDropped = current.overflowDropped + if (reason == SystemKeyDropReason.CAPACITY) count else 0,
            expiredDropped = current.expiredDropped + if (reason == SystemKeyDropReason.EXPIRED) count else 0,
        )
    }

    /** Counts queued cancellation, or a cancelled in-flight attempt from the same counter epoch. */
    @Synchronized
    internal fun cancelled(owner: Long, count: Long, epoch: Long? = null) {
        if (owner != session || count <= 0 || epoch != null && epoch != counterEpoch) return
        current = current.copy(cancelled = current.cancelled + count)
    }

    /** Restricts service status to the documented protocol instead of retaining arbitrary values. */
    private fun sanitizeStatus(code: Int): Int =
        if (code in SystemInputStatus.UNSUPPORTED_DISPLAY..SystemInputStatus.READY) code
        else SystemInputStatus.UNKNOWN_ERROR
}
