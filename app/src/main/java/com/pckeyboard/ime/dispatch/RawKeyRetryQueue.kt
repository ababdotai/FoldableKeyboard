package com.pckeyboard.ime.dispatch

import java.util.ArrayDeque

/** A logical raw keystroke waiting briefly for a usable input connection. */
data class QueuedRawKey(
    val keyCode: Int,
    val metaState: Int,
    val targetPackage: String?,
    val enqueuedAtMs: Long,
)

/**
 * Small time-bounded FIFO for raw keys that could not start dispatching.
 *
 * Entries are isolated to the editor package captured at enqueue time. A null package is a short-
 * lived bootstrap wildcard for keys pressed before EditorInfo arrives; lifecycle clearing and TTL
 * still bound it. Capacity overflow drops the oldest entry so stale input cannot grow unbounded.
 */
class RawKeyRetryQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private val entries = ArrayDeque<QueuedRawKey>()

    init {
        require(capacity > 0)
        require(ttlMs > 0)
    }

    /** Adds [key], dropping the oldest queued key when the queue is full. */
    fun enqueue(key: QueuedRawKey) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(key)
    }

    /**
     * Removes and returns the next live key for [targetPackage].
     *
     * Expired entries and entries captured for a different package are discarded before polling.
     */
    fun poll(targetPackage: String?, nowMs: Long): QueuedRawKey? {
        discardInvalid(targetPackage, nowMs)
        return entries.pollFirst()
    }

    /** Discards expired or cross-package entries and returns whether live work remains. */
    fun hasPending(targetPackage: String?, nowMs: Long): Boolean {
        discardInvalid(targetPackage, nowMs)
        return entries.isNotEmpty()
    }

    /** Removes every queued key when the input session ends. */
    fun clear() {
        entries.clear()
    }

    /** Returns the current bounded entry count for diagnostics and tests. */
    fun size(): Int = entries.size

    /** Removes entries that are expired or belong to another editor package. */
    private fun discardInvalid(targetPackage: String?, nowMs: Long) {
        entries.removeAll { entry ->
            val packageChanged = entry.targetPackage != null &&
                entry.targetPackage != targetPackage
            nowMs - entry.enqueuedAtMs >= ttlMs || packageChanged
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 32
        const val DEFAULT_TTL_MS = 1_000L
    }
}
