package com.pckeyboard.ime.remote

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import com.pckeyboard.ime.dispatch.rawKeyEventPlan
import rikka.shizuku.Shizuku
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors

/** Owns a bounded, serialized connection to the shell keyboard service. */
class ShizukuKeyBridge(context: Context, private val onStatus: (String) -> Unit) {
    private val appContext = context.applicationContext
    private val initialDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { context.display.displayId }.getOrNull() ?: Display.DEFAULT_DISPLAY
    } else Display.DEFAULT_DISPLAY
    private val diagnosticsSession = SystemKeyboardDiagnostics.beginSession()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "SystemKeyboard") }
    private val queue = SystemKeyQueue { reason, count ->
        SystemKeyboardDiagnostics.dropped(diagnosticsSession, count, reason)
    }
    private val drainLock = Any()
    private var activeEntry: PendingSystemKey? = null
    private var activeEpoch: Long? = null
    private var capabilityRequest = 0L
    private var drainScheduled = false
    private var bound = false
    private var startupTimedOut = false
    @Volatile private var closed = false
    @Volatile private var service: ISystemInputService? = null
    private val args = Shizuku.UserServiceArgs(ComponentName(context, SystemInputService::class.java))
        .daemon(false)
        .tag("uu-hid-${UUID.randomUUID()}") // Old asynchronous close must never destroy a new session.
        .version(4) // Replace cached injection services with the HID backend.
        .processNameSuffix("system_keyboard")
    private val binderDead = Shizuku.OnBinderDeadListener { disconnected("Shizuku 已断开，请重新启动后连接") }
    private val serviceDead = IBinder.DeathRecipient { disconnected("系统键盘服务已断开，请重新连接") }
    private val binderReceived = Shizuku.OnBinderReceivedListener { connect() }
    private val connection: ServiceConnection = object : ServiceConnection {
        /** Attaches the service without retaining keys from the previous connection. */
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(drainLock) {
                if (closed || startupTimedOut || !bound) return
                mainHandler.removeCallbacks(startupTimeout)
                try {
                    binder.linkToDeath(serviceDead, 0)
                    cancelQueue()
                    service = ISystemInputService.Stub.asInterface(binder)
                    SystemKeyboardDiagnostics.connectionChanged(diagnosticsSession, SystemConnectionState.CONNECTED)
                    report("HID 服务已连接；请切换 UU 电脑键盘并点中远端输入框")
                    @Suppress("DEPRECATION")
                    val targetUid = runCatching {
                        appContext.packageManager.getApplicationInfo(SystemInputService.UU_PACKAGE, 0).uid
                    }.getOrDefault(-1)
                    refreshDiagnostics(targetUid, initialDisplayId)
                } catch (_: Exception) {
                    disconnected("系统键盘连接失败，请重新连接")
                }
            }
        }

        /** Cancels queued input when the remote endpoint disappears. */
        override fun onServiceDisconnected(name: ComponentName) {
            disconnected("系统键盘服务已断开，请重新连接")
        }
    }
    private val startupTimeout = Runnable {
        synchronized(drainLock) {
            if (closed || !bound || service != null) return@Runnable
            startupTimedOut = true
            bound = false
            cancelQueue()
            SystemKeyboardDiagnostics.connectionChanged(diagnosticsSession, SystemConnectionState.TIMED_OUT)
            try { Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) { }
            report("系统键盘启动超时，请关闭浮窗后重新打开；若仍失败请重启 Shizuku")
        }
    }

    init {
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addBinderReceivedListener(binderReceived)
    }

    /** Connects only after existing Shizuku permission is granted; never opens permission UI. */
    fun connect() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { connect() }
            return
        }
        if (closed || startupTimedOut || service != null || bound) return
        if (!supportsTargetedSystemInput(Build.VERSION.SDK_INT)) {
            SystemKeyboardDiagnostics.connectionChanged(diagnosticsSession, SystemConnectionState.BLOCKED)
            report("HID 键盘目前支持 Android 14 或更新版本，需系统提供 UHID 接口")
            return
        }
        try {
            if (!Shizuku.pingBinder()) {
                SystemKeyboardDiagnostics.connectionChanged(diagnosticsSession, SystemConnectionState.BLOCKED)
                report("请先启动 Shizuku，再连接系统键盘")
            } else if (Shizuku.getVersion() < 13) {
                SystemKeyboardDiagnostics.connectionChanged(diagnosticsSession, SystemConnectionState.BLOCKED)
                report("请更新至 Shizuku 13 或更新版本")
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                SystemKeyboardDiagnostics.connectionChanged(diagnosticsSession, SystemConnectionState.BLOCKED)
                report("请先在设置中授权 Shizuku")
            } else {
                bound = true
                SystemKeyboardDiagnostics.connectionChanged(diagnosticsSession, SystemConnectionState.CONNECTING)
                mainHandler.postDelayed(startupTimeout, 10_000L)
                report("正在连接系统键盘…")
                Shizuku.bindUserService(args, connection)
            }
        } catch (_: Exception) {
            mainHandler.removeCallbacks(startupTimeout)
            if (bound) {
                try { Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) { }
            }
            bound = false
            synchronized(drainLock) { cancelQueue() }
            SystemKeyboardDiagnostics.connectionChanged(diagnosticsSession, SystemConnectionState.BLOCKED)
            report("无法连接 Shizuku，请检查启动和授权状态")
        }
    }

    /** Queues one key for the captured UU target without falling back to an IME or text API. */
    fun send(keyCode: Int, metaState: Int, targetUid: Int, displayId: Int) {
        synchronized(drainLock) {
            if (closed) return
            if (startupTimedOut) {
                SystemKeyboardDiagnostics.dropped(diagnosticsSession, 1, SystemKeyDropReason.UNAVAILABLE)
                return
            }
            if (service == null) {
                SystemKeyboardDiagnostics.dropped(diagnosticsSession, 1, SystemKeyDropReason.UNAVAILABLE)
                report("系统键盘尚未连接，本次按键未发送")
                return
            }
            queue.enqueue(keyCode, metaState, targetUid, displayId, SystemClock.uptimeMillis())
            if (drainScheduled) return
            drainScheduled = true
            worker.execute { drain() }
        }
    }

    /** Invalidates queued input when the overlay changes display, target, or visibility. */
    fun cancelPending() {
        synchronized(drainLock) { cancelQueue() }
    }

    /** Provisions the HID device without pressing a key or proving remote receipt. */
    fun refreshDiagnostics(targetUid: Int, displayId: Int) {
        synchronized(drainLock) {
            val endpoint = service ?: return
            if (closed || startupTimedOut) return
            val request = ++capabilityRequest
            worker.execute {
                val shouldCheck = synchronized(drainLock) {
                    !closed && service === endpoint && request == capabilityRequest
                }
                if (!shouldCheck) return@execute
                val result = try {
                    endpoint.checkCapabilities(targetUid, displayId)
                } catch (_: SecurityException) {
                    SystemInputStatus.PERMISSION_DENIED
                } catch (_: Exception) {
                    SystemInputStatus.UNKNOWN_ERROR
                }
                synchronized(drainLock) {
                    if (!closed && service === endpoint && request == capabilityRequest) {
                        SystemKeyboardDiagnostics.capabilitiesChecked(diagnosticsSession, result)
                    }
                }
            }
        }
    }

    /** Cancels pending keys and lets the in-flight sequence release its keys before unbinding. */
    fun close() {
        synchronized(drainLock) {
            if (closed) return
            closed = true
            mainHandler.removeCallbacks(startupTimeout)
            cancelQueue()
            SystemKeyboardDiagnostics.connectionChanged(diagnosticsSession, SystemConnectionState.CLOSED)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeBinderReceivedListener(binderReceived)
            service?.asBinder()?.let { try { it.unlinkToDeath(serviceDead, 0) } catch (_: Exception) { } }
            service = null
            worker.execute {
                mainHandler.post {
                    if (bound) {
                        try { Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) { }
                        bound = false
                    }
                }
            }
            worker.shutdown()
        }
    }

    /** Sends queued keys once, dropping the remaining queue after any uncertain result. */
    private fun drain() {
        while (!closed) {
            val entry = synchronized(drainLock) {
                val pending = queue.poll(SystemClock.uptimeMillis()) ?: run {
                    drainScheduled = false
                    return
                }
                activeEntry = pending
                activeEpoch = SystemKeyboardDiagnostics.attemptStarted(diagnosticsSession)
                pending
            }
            if (!queue.isCurrent(entry)) continue
            val endpoint = service
            val accepted = try {
                endpoint?.sendKey(entry.keyCode, entry.metaState, entry.targetUid, entry.displayId)
                    ?: SystemInputStatus.UNKNOWN_ERROR
            } catch (_: SecurityException) {
                SystemInputStatus.PERMISSION_DENIED
            } catch (_: Exception) {
                SystemInputStatus.UNKNOWN_ERROR
            }
            synchronized(drainLock) {
                if (!closed && activeEntry === entry && queue.isCurrent(entry)) {
                    val expected = rawKeyEventPlan(entry.keyCode, entry.metaState).size
                    activeEpoch?.let {
                        SystemKeyboardDiagnostics.attemptFinished(diagnosticsSession, it, accepted, expected)
                    }
                    activeEntry = null
                    activeEpoch = null
                    if (accepted != expected) {
                        cancelQueue()
                        report(when (accepted) {
                            SystemInputStatus.FOCUS_UNSAFE -> "未发送：请在 UU 切换到电脑键盘，并关闭通知栏或其他弹窗"
                            SystemInputStatus.HID_UNAVAILABLE -> "HID 设备不可用：请检查 Shizuku，并关闭浮窗后重开"
                            SystemInputStatus.UNSUPPORTED_DISPLAY -> "HID 键盘暂只支持手机主显示屏"
                            SystemInputStatus.INVALID_KEY -> "该按键组合不受 HID 支持；若 fn 已锁定，请先取消 fn"
                            else -> "HID 报告未完整提交；请查看诊断并确认 UU 电脑键盘位于前台"
                        })
                    }
                }
            }
        }
        synchronized(drainLock) { drainScheduled = false }
    }

    /** Detaches a dead endpoint and discards its session's pending input. */
    private fun disconnected(message: String) {
        synchronized(drainLock) {
            if (closed) return
            mainHandler.removeCallbacks(startupTimeout)
            service = null
            capabilityRequest++
            cancelQueue()
            if (!startupTimedOut) {
                SystemKeyboardDiagnostics.connectionChanged(diagnosticsSession, SystemConnectionState.DISCONNECTED)
            }
            mainHandler.post {
                bound = false
                if (!startupTimedOut) report(message)
            }
        }
    }

    /** Invalidates pending and in-flight accounting while holding the bridge lifecycle lock. */
    private fun cancelQueue() {
        SystemKeyboardDiagnostics.cancelled(diagnosticsSession, queue.cancel().toLong())
        activeEpoch?.let { SystemKeyboardDiagnostics.cancelled(diagnosticsSession, 1, it) }
        activeEntry = null
        activeEpoch = null
    }

    /** Delivers UI status on the main thread while this bridge is alive. */
    private fun report(message: String) {
        mainHandler.post { if (!closed) onStatus(message) }
    }
}

/** Immutable target and session identity captured when a key is pressed. */
internal data class PendingSystemKey(
    val keyCode: Int,
    val metaState: Int,
    val targetUid: Int,
    val displayId: Int,
    val createdAtMs: Long,
    val generation: Long,
)

/** Bounded, expiring keyboard work shared by the UI and one IPC worker. */
internal class SystemKeyQueue(
    private val capacity: Int = 32,
    private val ttlMs: Long = 1_000L,
    private val onDropped: (SystemKeyDropReason, Long) -> Unit = { _, _ -> },
) {
    private val entries = ArrayDeque<PendingSystemKey>()
    private var generation = 0L

    init {
        require(capacity > 0 && ttlMs > 0)
    }

    /** Adds a key to the current session, dropping the oldest pending key at capacity. */
    @Synchronized
    fun enqueue(keyCode: Int, metaState: Int, targetUid: Int, displayId: Int, nowMs: Long) {
        if (entries.size == capacity) {
            entries.removeFirst()
            onDropped(SystemKeyDropReason.CAPACITY, 1)
        }
        entries.addLast(PendingSystemKey(keyCode, metaState, targetUid, displayId, nowMs, generation))
    }

    /** Removes the oldest live key, never retaining expired work for a later reconnect. */
    @Synchronized
    fun poll(nowMs: Long): PendingSystemKey? {
        while (entries.isNotEmpty()) {
            val entry = entries.removeFirst()
            if (entry.generation == generation && nowMs - entry.createdAtMs in 0 until ttlMs) return entry
            onDropped(SystemKeyDropReason.EXPIRED, 1)
        }
        return null
    }

    /** Returns whether a dequeued key still belongs to the active session. */
    @Synchronized
    fun isCurrent(entry: PendingSystemKey): Boolean = entry.generation == generation

    /** Invalidates both queued entries and keys dequeued before a session change. */
    @Synchronized
    fun cancel(): Int {
        val count = entries.size
        generation++
        entries.clear()
        return count
    }
}
