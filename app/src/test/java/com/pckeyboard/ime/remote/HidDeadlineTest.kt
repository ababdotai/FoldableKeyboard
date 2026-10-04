package com.pckeyboard.ime.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Verifies lock-independent timeout cleanup without launching a real HID device. */
class HidDeadlineTest {
    /** A timeout unblocks stalled work and kills only the child captured for that operation. */
    @Test fun timeoutDestroysCapturedChildOnly() {
        val old = FakeProcess()
        val replacement = FakeProcess()
        var current: Process = old
        assertThrows(IOException::class.java) {
            withHidDeadline(current, 25) {
                current = replacement
                assertTrue(old.killed.await(2, TimeUnit.SECONDS))
            }
        }
        assertSame(replacement, current)
        assertEquals(0L, old.killed.count)
        assertEquals(1L, replacement.killed.count)
    }

    /** Completed work cancels its alarm so the persistent child stays alive. */
    @Test fun completionCancelsDeadline() {
        val child = FakeProcess()
        assertEquals(7, withHidDeadline(child, 25) { 7 })
        assertFalse(child.killed.await(100, TimeUnit.MILLISECONDS))
    }

    /** Failure cancels its alarm too; the caller owns immediate exception cleanup. */
    @Test fun exceptionCancelsDeadline() {
        val child = FakeProcess()
        assertThrows(IllegalStateException::class.java) {
            withHidDeadline(child, 25) { error("broken pipe") }
        }
        assertFalse(child.killed.await(100, TimeUnit.MILLISECONDS))
    }

    /** Minimal child substitute whose kill signal releases blocked test work. */
    private class FakeProcess : Process() {
        val killed = CountDownLatch(1)
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { killed.await(); return 0 }
        override fun exitValue() = 0
        override fun destroy() { killed.countDown() }
        override fun destroyForcibly(): Process { killed.countDown(); return this }
    }
}
