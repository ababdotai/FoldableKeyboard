package com.pckeyboard.ime.remote

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.pckeyboard.ime.R
import com.pckeyboard.ime.dispatch.DispatchMode
import com.pckeyboard.ime.layout.KeyboardPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** Exercises the real diagnostic screen, lifecycle, and user-initiated content-URI export. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35])
class SystemKeyboardDiagnosticsUiTest {
    private val environment = SystemKeyboardEnvironment(
        34, 1, 440000L, true, true, 13, true,
        KeyboardPlatform.MAC, DispatchMode.RAW_REMOTE, true,
    )

    /** Isolates file-provider roots from other tests while retaining the real provider behavior. */
    @Before
    fun resetProviderPaths() = resetDiagnosticFileProviderCache()

    /** Shows all layers without requesting privileges, starting services, or creating reports. */
    @Test
    fun openingDiagnosticsIsReadOnlyAndNeedsNoPermissions() {
        val controller = Robolectric.buildActivity(SystemKeyboardDiagnosticsActivity::class.java).setup()
        val activity = controller.get()
        try {
            val text = descendants(activity.window.decorView).filterIsInstance<TextView>()
                .joinToString("\n") { it.text }
            assertTrue(text.contains("1 · 环境与权限"))
            assertTrue(text.contains("5 · 远端接收：未确认"))
            assertTrue(text.contains("不包含普通输入法的 RAW 通道"))
            assertEquals(null, shadowOf(activity).nextStartedActivity)
            assertEquals(null, shadowOf(activity).nextStartedService)
            assertFalse(File(activity.cacheDir, "diagnostics/FoldableKeyboard-uu-diagnostics.txt").exists())
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        }
    }

    /** Creates the report only on tap and grants temporary read access, never a file URI. */
    @Test
    fun explicitExportSharesOnlyContentUriWithReadGrant() {
        val controller = Robolectric.buildActivity(SystemKeyboardDiagnosticsActivity::class.java).setup()
        val activity = controller.get()
        try {
            descendants(activity.window.decorView).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.uu_diagnostics_export) }.performClick()
            val chooser = shadowOf(activity).nextStartedActivity
            assertNotNull(chooser)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val share = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            assertEquals(Intent.ACTION_SEND, share.action)
            assertEquals("text/plain", share.type)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, share.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val uri = share.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
            assertEquals("content", uri.scheme)
            assertEquals("${activity.packageName}.fileprovider", uri.authority)
            assertEquals(uri, share.clipData!!.getItemAt(0).uri)
            val file = File(activity.cacheDir, "diagnostics/FoldableKeyboard-uu-diagnostics.txt")
            activity.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(file.name, cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
                assertEquals(file.length(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
            }
            val report = file.readText()
            assertTrue(report.startsWith("FoldableKeyboard UU keyboard diagnostics / schema 2"))
            assertTrue(report.contains("remote_delivery=UNCONFIRMED"))
            assertFalse(report.contains("key_code="))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** Uses different UI explanations for capability readiness, complete acceptance, and failure. */
    @Test
    fun summaryNeverConfusesPreflightOrPartialAcceptanceWithRemoteSuccess() {
        val context = RuntimeEnvironment.getApplication()
        val complete = SystemKeyboardSnapshot(
            connection = SystemConnectionState.CONNECTED, capabilityCode = SystemInputStatus.READY,
            attempted = 1, accepted = 1, lastResultCode = 2, lastExpectedEvents = 2,
        )
        val readyText = systemKeyboardDiagnosticSummary(context, environment, complete)
        assertTrue(readyText.contains("HID 键盘已登记；远端接收未确认"))
        assertTrue(readyText.contains("HID 报告已完整提交；远端未确认"))
        val partial = systemKeyboardDiagnosticSummary(context, environment, complete.copy(lastResultCode = 1))
        assertTrue(partial.contains("未完整提交（1/2 个事件）"))
        val failure = systemKeyboardDiagnosticSummary(context, environment,
            complete.copy(lastResultCode = SystemInputStatus.PERMISSION_DENIED))
        assertTrue(failure.contains("系统拒绝权限"))
        assertTrue(failure.contains("5 · 远端接收：未确认"))
    }

    /** Walks actual widgets without relying on private implementation fields. */
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
