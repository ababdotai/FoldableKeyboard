package com.pckeyboard.ime.remote

import android.os.SystemClock
import android.view.InputDevice
import com.pckeyboard.ime.dispatch.RawKeyEventSpec
import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val hidDeadlines = ScheduledThreadPoolExecutor(1) { task ->
    Thread(task, "keyboard-hid-deadline").apply { isDaemon = true }
}.apply { removeOnCancelPolicy = true }

/** Kills only the captured child on timeout, independently of transport and service locks. */
internal fun <T> withHidDeadline(child: Process, timeoutMillis: Long, action: () -> T): T {
    val state = AtomicInteger(0)
    val alarm = hidDeadlines.schedule({
        if (state.compareAndSet(0, 1)) {
            try { child.destroyForcibly() } catch (_: Exception) { }
        }
    }, timeoutMillis, TimeUnit.MILLISECONDS)
    try {
        val result = action()
        if (!state.compareAndSet(0, 2)) throw IOException("HID operation timed out")
        return result
    } finally {
        state.compareAndSet(0, 2)
        alarm.cancel(false)
    }
}

/** Owns a shell-privileged HID subprocess; no typed content is retained or logged. */
internal class HidKeyboardTransport : AutoCloseable {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var deviceId: Int? = null

    /** Registers an empty keyboard and waits until Android exposes its alphabetic input device. */
    @Synchronized
    fun ensureReady(): Boolean {
        if (process?.isAlive == true && deviceId?.let { isOurDevice(InputDevice.getDevice(it)) } == true) {
            return true
        }
        close()
        return try {
            val previousIds = InputDevice.getDeviceIds().toSet()
            val child = ProcessBuilder("/system/bin/hid", "-").redirectErrorStream(true).start()
            process = child
            Thread({
                try {
                    child.inputStream.use { input ->
                        val buffer = ByteArray(1024)
                        while (input.read(buffer) != -1) { /* Discard content without accumulating it. */ }
                    }
                } catch (_: Exception) { /* Closing the child also closes its output stream. */ }
            }, "keyboard-hid-output").apply { isDaemon = true; start() }
            writer = child.outputStream.bufferedWriter(Charsets.UTF_8)
            val descriptor = listOf(5,1,9,6,161,1,5,7,25,224,41,231,21,0,37,1,117,1,149,8,
                129,2,149,1,117,8,129,1,149,6,117,8,21,0,37,101,5,7,25,0,41,101,129,0,192)
            val ready = withHidDeadline(child, 2000) {
                val deadline = SystemClock.uptimeMillis() + 2000
                writeCommand("{\"id\":1,\"command\":\"register\",\"name\":\"$DEVICE_NAME\",\"vid\":$VENDOR_ID," +
                "\"pid\":$PRODUCT_ID,\"bus\":\"usb\",\"descriptor\":${descriptor}}")
                while (child.isAlive && SystemClock.uptimeMillis() < deadline) {
                    val found = InputDevice.getDeviceIds().firstOrNull {
                        it !in previousIds && isOurDevice(InputDevice.getDevice(it))
                    }
                    if (found != null) {
                        deviceId = found
                        return@withHidDeadline true
                    }
                    Thread.sleep(25)
                }
                false
            }
            if (!ready) close()
            ready
        } catch (_: Exception) {
            close()
            false
        }
    }

    /** Sends a guarded sequence, releasing every held key even on partial failure. */
    @Synchronized
    fun send(plan: List<RawKeyEventSpec>, canSend: () -> Boolean): Int {
        if (!ensureReady()) return SystemInputStatus.HID_UNAVAILABLE
        return try {
            withHidDeadline(checkNotNull(process), 5000) {
                sendHidReports(plan, canSend, ::writeReport)
            }
        } catch (_: Exception) {
            close()
            SystemInputStatus.HID_UNAVAILABLE
        }
    }

    /** Releases keys and closes stdin before forcibly reaping an unresponsive subprocess. */
    @Synchronized
    override fun close() {
        val child = process
        if (child != null) {
            try {
                withHidDeadline(child, 300) {
                    try { if (writer != null) writeReport(List(8) { 0 }) } catch (_: Exception) { }
                    try { writer?.close() } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
        writer = null
        process = null
        deviceId = null
        if (child != null) {
            try {
                if (!child.waitFor(200, TimeUnit.MILLISECONDS)) {
                    child.destroy()
                    if (!child.waitFor(200, TimeUnit.MILLISECONDS)) child.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                child.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
    }

    /** Writes one immediate report without delayed commands that could outlive focus. */
    private fun writeReport(report: List<Int>) {
        writeCommand("{\"id\":1,\"command\":\"report\",\"report\":$report}")
    }

    /** Flushes each JSON object so no keystrokes remain buffered in the client. */
    private fun writeCommand(command: String) {
        check(process?.isAlive == true) { "HID process unavailable" }
        val output = checkNotNull(writer)
        output.write(command)
        output.newLine()
        output.flush()
    }

    /** Rejects unrelated devices and waits for framework keyboard classification. */
    private fun isOurDevice(device: InputDevice?): Boolean = device != null &&
        device.name == DEVICE_NAME && device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID &&
        device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
        device.supportsSource(InputDevice.SOURCE_KEYBOARD)

    private companion object {
        const val DEVICE_NAME = "FoldableKeyboard HID"
        const val VENDOR_ID = 0x1209
        const val PRODUCT_ID = 0xf002
    }
}
