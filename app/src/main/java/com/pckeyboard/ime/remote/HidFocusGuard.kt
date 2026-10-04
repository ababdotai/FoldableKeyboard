package com.pckeyboard.ime.remote

import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fails closed unless the foreground window and served view both identify UU physical input. */
internal class HidFocusGuard(private val dump: (String) -> String? = ::readFocusDump) {
    /** Takes fresh bounded snapshots; callers must repeat this before every key-down report. */
    fun isSafe(): Boolean = try {
        val window = dump("window")
        val input = dump("input_method")
        window != null && input != null && isUuHidFocusSafe(window, input)
    } catch (_: Exception) {
        false
    }
}

/** Accepts only one non-null focus plus UU's non-editor keyboard view, never an IME editor. */
internal fun isUuHidFocusSafe(windowDump: String, inputDump: String): Boolean {
    val focusedDisplays = Regex("(?:mTopFocusedDisplayId|mFocusedDisplayId)=(-?\\d+)")
        .findAll(windowDump).map { it.groupValues[1] }.toList()
    if (focusedDisplays.any { it != "0" }) return false
    val focuses = Regex("(?m)^\\s*mCurrentFocus=(.+)$").findAll(windowDump)
        .map { it.groupValues[1].trim() }.filter { it != "null" }.toList()
    if (focuses.size != 1 || !Regex(
            // Android's ICU regex requires escaping this literal closing brace.
            "^Window\\{[^}]*\\scom\\.netease\\.uuremote/com\\.remote\\.app\\.ui\\.activity\\.ScreenActivity(?:\\s|\\}).*",
        ).matches(focuses.single())) return false
    if (Regex("(?:mShowingLockscreen|isStatusBarKeyguard|mKeyguardShowing)=true").containsMatchIn(windowDump)) {
        return false
    }
    val views = Regex("(?m)^\\s*mServedView=(.+)$").findAll(inputDump)
        .map { it.groupValues[1].trim() }.toList()
    val connections = Regex("(?m)^\\s*mServedInputConnection=(.+)$").findAll(inputDump)
        .map { it.groupValues[1].trim() }.toList()
    return views.size == 1 && views.single().startsWith("com.remote.inputdevice.view.GVDeviceInputView{") &&
        connections == listOf("null")
}

/** Runs a fixed dumpsys command with bounded output, a deadline, and no shell interpolation. */
private fun readFocusDump(service: String): String? {
    if (service != "window" && service != "input_method") return null
    val process = ProcessBuilder("/system/bin/dumpsys", service).redirectErrorStream(true).start()
    val reader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "hid-focus-reader").apply { isDaemon = true }
    }
    return try {
        val output = reader.submit<String?> {
            process.inputStream.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_DUMP_BYTES) return@submit null
                    bytes.write(buffer, 0, count)
                }
                bytes.toString(Charsets.UTF_8.name())
            }
        }
        val result = output.get(DUMP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (process.waitFor(100, TimeUnit.MILLISECONDS) && process.exitValue() == 0) result else null
    } catch (_: Exception) {
        null
    } finally {
        process.destroyForcibly()
        reader.shutdownNow()
    }
}

private const val MAX_DUMP_BYTES = 2 * 1024 * 1024
private const val DUMP_TIMEOUT_MS = 1200L
