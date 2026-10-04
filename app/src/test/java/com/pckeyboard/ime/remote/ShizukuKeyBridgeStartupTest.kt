package com.pckeyboard.ime.remote

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import rikka.shizuku.Shizuku
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Tests delayed or missing UserService callbacks without a privileged Shizuku process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [ShadowShizukuStartupApi::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ShizukuKeyBridgeStartupTest {
    private val messages = mutableListOf<String>()
    private lateinit var bridge: ShizukuKeyBridge
    private val component = ComponentName("com.pckeyboard.ime", "SystemInputService")

    /** Creates an authorized bridge with a deliberately asynchronous fake bind operation. */
    @Before
    fun createBridge() {
        ShadowShizukuStartupApi.bindCount = 0
        ShadowShizukuStartupApi.unbindCount = 0
        ShadowShizukuStartupApi.connection = null
        ShadowShizukuStartupApi.boundTags.clear()
        ShadowShizukuStartupApi.removedTags.clear()
        bridge = ShizukuKeyBridge(RuntimeEnvironment.getApplication(), messages::add)
    }

    /** Drains asynchronous shutdown so a test cannot leave executor work for another test. */
    @After
    fun closeBridge() {
        bridge.close()
        val field = ShizukuKeyBridge::class.java.getDeclaredField("worker").apply { isAccessible = true }
        assertTrue((field.get(bridge) as ExecutorService).awaitTermination(2, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Isolates a reopened session from an older bridge's delayed service removal. */
    @Test
    fun reopenedBridgeUsesADifferentServiceTag() {
        bridge.connect()
        bridge.close()
        val next = ShizukuKeyBridge(RuntimeEnvironment.getApplication()) {}
        try {
            next.connect()
            val workerField = ShizukuKeyBridge::class.java.getDeclaredField("worker").apply { isAccessible = true }
            assertTrue((workerField.get(bridge) as ExecutorService).awaitTermination(2, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            val tags = ShadowShizukuStartupApi.boundTags
            assertEquals(2, tags.size)
            assertTrue(tags[0] != tags[1])
            assertEquals(listOf(tags[0]), ShadowShizukuStartupApi.removedTags)
        } finally {
            next.close()
            val workerField = ShizukuKeyBridge::class.java.getDeclaredField("worker").apply { isAccessible = true }
            assertTrue((workerField.get(next) as ExecutorService).awaitTermination(2, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Times out exactly once and refuses a late callback from the failed startup attempt. */
    @Test
    fun missingCallbackTimesOutAndLateConnectionCannotReviveBridge() {
        bridge.connect()
        val callback = requireNotNull(ShadowShizukuStartupApi.connection)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9_999))
        assertEquals(0, ShadowShizukuStartupApi.unbindCount)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))

        assertEquals(1, ShadowShizukuStartupApi.unbindCount)
        assertTrue(messages.last().contains("启动超时"))
        assertTrue(messages.last().contains("关闭浮窗后重新打开"))
        assertEquals(SystemConnectionState.TIMED_OUT, SystemKeyboardDiagnostics.snapshot().connection)
        callback.onServiceConnected(component, Binder())
        bridge.connect()
        bridge.send(29, 0, 12_345, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ShadowShizukuStartupApi.bindCount)
        assertFalse(messages.any { it.contains("HID 服务已连接") })
        assertTrue(messages.last().contains("关闭浮窗后重新打开"))
        assertEquals(1L, SystemKeyboardDiagnostics.snapshot().dropped)
        assertEquals(0L, SystemKeyboardDiagnostics.snapshot().attempted)
    }

    /** Removes the timeout when the service attaches before its startup deadline. */
    @Test
    fun successfulConnectionCancelsStartupTimeout() {
        bridge.connect()
        val endpoint = RecordingSystemInputService()
        requireNotNull(ShadowShizukuStartupApi.connection).onServiceConnected(component, endpoint)
        awaitWorker()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))

        assertTrue(messages.any { it.contains("HID 服务已连接") })
        assertFalse(messages.any { it.contains("启动超时") })
        assertEquals(0, ShadowShizukuStartupApi.unbindCount)
        assertEquals(SystemConnectionState.CONNECTED, SystemKeyboardDiagnostics.snapshot().connection)
        assertEquals(SystemInputStatus.READY, SystemKeyboardDiagnostics.snapshot().capabilityCode)
        assertEquals(1, endpoint.checkCount)
        assertEquals(0, endpoint.sendCount)
    }

    /** Cancels startup work when the endpoint disconnects before completing its handshake. */
    @Test
    fun disconnectionCancelsStartupTimeout() {
        bridge.connect()
        requireNotNull(ShadowShizukuStartupApi.connection).onServiceDisconnected(component)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))

        assertTrue(messages.any { it.contains("已断开") })
        assertFalse(messages.any { it.contains("启动超时") })
        assertEquals(SystemConnectionState.DISCONNECTED, SystemKeyboardDiagnostics.snapshot().connection)
    }

    /** Prevents a queued service callback from attaching after the overlay has closed. */
    @Test
    fun closedBridgeIgnoresLateCallbackAndTimeout() {
        bridge.connect()
        val callback = requireNotNull(ShadowShizukuStartupApi.connection)
        bridge.close()
        callback.onServiceConnected(component, Binder())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))

        assertFalse(messages.any { it.contains("系统键盘已连接") || it.contains("启动超时") })
        assertEquals(SystemConnectionState.CLOSED, SystemKeyboardDiagnostics.snapshot().connection)
    }

    /** Refreshes capability evidence without dispatching any synthetic diagnostic input. */
    @Test
    fun explicitDiagnosticRefreshNeverSendsKeys() {
        bridge.connect()
        val endpoint = RecordingSystemInputService()
        requireNotNull(ShadowShizukuStartupApi.connection).onServiceConnected(component, endpoint)
        awaitWorker()
        endpoint.capability = SystemInputStatus.TARGETED_API_UNAVAILABLE

        bridge.refreshDiagnostics(12_345, 0)
        awaitWorker()

        assertEquals(2, endpoint.checkCount)
        assertEquals(0, endpoint.sendCount)
        assertEquals(SystemInputStatus.TARGETED_API_UNAVAILABLE, SystemKeyboardDiagnostics.snapshot().capabilityCode)
        assertEquals(0L, SystemKeyboardDiagnostics.snapshot().attempted)
    }

    /** Records only completed event plans as accepted by Android, not remote confirmation. */
    @Test
    fun deliveryCountsSeparatePartialResultFromCompleteAcceptance() {
        bridge.connect()
        val endpoint = RecordingSystemInputService()
        requireNotNull(ShadowShizukuStartupApi.connection).onServiceConnected(component, endpoint)
        awaitWorker()
        bridge.send(29, 0, 12_345, 0)
        awaitWorker()
        endpoint.sendResult = 1
        bridge.send(30, 0, 12_345, 0)
        awaitWorker()

        val snapshot = SystemKeyboardDiagnostics.snapshot()
        assertEquals(2L, snapshot.attempted)
        assertEquals(1L, snapshot.accepted)
        assertEquals(1L, snapshot.failed)
        assertEquals(1, snapshot.lastResultCode)
        assertEquals(2, snapshot.lastExpectedEvents)
    }

    /** Discards a late successful IPC reply after input is cancelled for a display change. */
    @Test
    fun cancelledInFlightReplyCannotAppearSuccessful() {
        bridge.connect()
        val endpoint = RecordingSystemInputService()
        requireNotNull(ShadowShizukuStartupApi.connection).onServiceConnected(component, endpoint)
        awaitWorker()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        endpoint.beforeSend = {
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
        }
        try {
            bridge.send(29, 0, 12_345, 0)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            bridge.send(30, 0, 12_345, 0)
            bridge.cancelPending()
        } finally {
            release.countDown()
        }
        awaitWorker()

        val snapshot = SystemKeyboardDiagnostics.snapshot()
        assertEquals(1L, snapshot.attempted)
        assertEquals(2L, snapshot.cancelled)
        assertEquals(0L, snapshot.accepted)
        assertEquals(0L, snapshot.failed)
        assertNull(snapshot.lastResultCode)
        assertEquals(1, endpoint.sendCount)
    }

    /** Counts disconnected input separately without pretending it reached the service. */
    @Test
    fun unconnectedKeyIsDroppedWithoutAnAttempt() {
        bridge.send(29, 0, 12_345, 0)

        assertEquals(1L, SystemKeyboardDiagnostics.snapshot().dropped)
        assertEquals(0L, SystemKeyboardDiagnostics.snapshot().attempted)
    }

    /** Ignores an in-flight preflight reply from an endpoint that has since disconnected. */
    @Test
    fun disconnectedEndpointCannotRestoreCapabilityReadiness() {
        bridge.connect()
        val endpoint = RecordingSystemInputService()
        val callback = requireNotNull(ShadowShizukuStartupApi.connection)
        callback.onServiceConnected(component, endpoint)
        awaitWorker()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        endpoint.beforeCheck = {
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
        }
        try {
            bridge.refreshDiagnostics(12_345, 0)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            callback.onServiceDisconnected(component)
        } finally {
            release.countDown()
        }
        awaitWorker()

        assertEquals(SystemConnectionState.DISCONNECTED, SystemKeyboardDiagnostics.snapshot().connection)
        assertNull(SystemKeyboardDiagnostics.snapshot().capabilityCode)
        assertEquals(0, endpoint.sendCount)
    }

    /** Waits for already-scheduled bridge operations without advancing the startup timeout. */
    private fun awaitWorker() {
        val field = ShizukuKeyBridge::class.java.getDeclaredField("worker").apply { isAccessible = true }
        (field.get(bridge) as ExecutorService).submit { }.get(2, TimeUnit.SECONDS)
    }

    /** Records protocol method counts without preserving keys, modifiers, or target details. */
    private class RecordingSystemInputService : ISystemInputService.Stub() {
        var checkCount = 0
        var sendCount = 0
        var capability = SystemInputStatus.READY
        var sendResult = 2
        var beforeSend: (() -> Unit)? = null
        var beforeCheck: (() -> Unit)? = null

        /** Returns configured static capability without invoking the injection method. */
        override fun checkCapabilities(targetUid: Int, displayId: Int): Int {
            checkCount++
            beforeCheck?.invoke()
            return capability
        }

        /** Models one synchronous event-plan response without retaining its arguments. */
        override fun sendKey(keyCode: Int, metaState: Int, targetUid: Int, displayId: Int): Int {
            sendCount++
            beforeSend?.invoke()
            return sendResult
        }

        /** Leaves this in-process test endpoint alive for assertions. */
        override fun destroy() = Unit
    }
}

/** Replaces only Shizuku transport prerequisites while preserving the real bridge lifecycle. */
@Implements(value = Shizuku::class, isInAndroidSdk = false)
class ShadowShizukuStartupApi {
    companion object {
        var bindCount = 0
        var unbindCount = 0
        var connection: ServiceConnection? = null
        val boundTags = mutableListOf<String>()
        val removedTags = mutableListOf<String>()

        /** Reads the real SDK service identity instead of substituting callback identity. */
        private fun serviceTag(args: Shizuku.UserServiceArgs): String =
            Shizuku.UserServiceArgs::class.java.getDeclaredField("tag").apply {
                isAccessible = true
            }.get(args) as String

        /** Reports an available fake server without starting any external process. */
        @JvmStatic
        @Implementation
        fun pingBinder(): Boolean = true

        /** Enables the UserService Context constructor supported by API 13. */
        @JvmStatic
        @Implementation
        fun getVersion(): Int = 13

        /** Models permission already granted by the user. */
        @JvmStatic
        @Implementation
        fun checkSelfPermission(): Int = PackageManager.PERMISSION_GRANTED

        /** Captures the asynchronous callback without completing startup automatically. */
        @JvmStatic
        @Implementation
        fun bindUserService(args: Shizuku.UserServiceArgs, callback: ServiceConnection) {
            bindCount++
            boundTags += serviceTag(args)
            connection = callback
        }

        /** Records removal of a failed or closed service binding. */
        @JvmStatic
        @Implementation
        fun unbindUserService(args: Shizuku.UserServiceArgs, callback: ServiceConnection, remove: Boolean) {
            unbindCount++
            if (remove) removedTags += serviceTag(args)
        }
    }
}
