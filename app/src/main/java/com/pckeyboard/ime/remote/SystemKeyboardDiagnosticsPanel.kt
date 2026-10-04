package com.pckeyboard.ime.remote

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.pckeyboard.ime.R
import com.pckeyboard.ime.theme.KeyboardTheme
import com.pckeyboard.ime.theme.applyKeyboardChrome
import java.io.File

/** Shows content-free status without requiring keyboard focus or a privileged test keystroke. */
internal class SystemKeyboardDiagnosticsPanel @JvmOverloads constructor(
    context: Context,
    private val beforeShare: () -> Unit = {},
) : LinearLayout(context) {
    private val details = TextView(context).apply {
        textSize = 13f
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    private var updating = false
    private val refreshTask = object : Runnable {
        /** Refreshes visible diagnostics only; no background trace or event history is retained. */
        override fun run() {
            if (!updating) return
            refresh()
            postDelayed(this, 1_000L)
        }
    }

    init {
        orientation = VERTICAL
        addView(ScrollView(context).apply { addView(details) }, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        addView(LinearLayout(context).apply {
            addView(Button(context).apply {
                setText(R.string.uu_diagnostics_clear)
                setOnClickListener { SystemKeyboardDiagnostics.clearCounters(); refresh() }
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                setText(R.string.uu_diagnostics_export)
                setOnClickListener { shareReport() }
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        refresh()
    }

    /** Starts live display updates without binding to or authorizing Shizuku. */
    fun startUpdating() {
        if (updating) return
        updating = true
        refreshTask.run()
    }

    /** Stops callbacks when hidden so diagnostics cannot retain a dismissed activity or window. */
    fun stopUpdating() {
        updating = false
        removeCallbacks(refreshTask)
    }

    /** Uses the selected keyboard theme's readable foreground color inside the overlay. */
    fun setDetailsColor(color: Int) { details.setTextColor(color) }

    /** Matches diagnostic controls to the floating keyboard while retaining readable status text. */
    fun applyTheme(theme: KeyboardTheme) {
        setBackgroundColor(theme.backgroundColor)
        setDetailsColor(theme.keyTextColor)
        val actions = getChildAt(1) as LinearLayout
        for (index in 0 until actions.childCount) {
            (actions.getChildAt(index) as Button).applyKeyboardChrome(theme)
        }
    }

    /** Removes pending callbacks even when the containing service is destroyed unexpectedly. */
    override fun onDetachedFromWindow() {
        stopUpdating()
        super.onDetachedFromWindow()
    }

    /** Replaces status text with a current prerequisite snapshot and session aggregates. */
    internal fun refresh() {
        details.text = systemKeyboardDiagnosticSummary(
            context, readSystemKeyboardEnvironment(context), SystemKeyboardDiagnostics.snapshot(),
        )
    }

    /** Shares one allowlisted cache file only after an explicit user action. */
    private fun shareReport() {
        try {
            val report = systemKeyboardDiagnosticReport(
                readSystemKeyboardEnvironment(context), SystemKeyboardDiagnostics.snapshot(),
            )
            beforeShare()
            val directory = File(context.cacheDir, "diagnostics")
            check(directory.isDirectory || directory.mkdirs())
            val file = File(directory, "FoldableKeyboard-uu-diagnostics.txt")
            file.writeText(report, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("FoldableKeyboard UU keyboard diagnostics", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, context.getString(R.string.uu_diagnostics_export))
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(context, R.string.uu_diagnostics_export_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Converts diagnostic spacing to density-independent pixels. */
    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}

/** Formats each boundary separately and never presents system acceptance as remote success. */
internal fun systemKeyboardDiagnosticSummary(
    context: Context,
    environment: SystemKeyboardEnvironment,
    snapshot: SystemKeyboardSnapshot,
): String {
    /** Formats a permission flag without exposing external metadata. */
    fun enabled(value: Boolean): String = context.getString(
        if (value) R.string.uu_diagnostics_yes else R.string.uu_diagnostics_no,
    )
    val connection = when (snapshot.connection) {
        SystemConnectionState.IDLE -> "未启动"
        SystemConnectionState.CONNECTING -> "连接中"
        SystemConnectionState.CONNECTED -> "Binder 已连接（不代表可投递）"
        SystemConnectionState.DISCONNECTED -> "已断开；请关闭浮窗后重新打开"
        SystemConnectionState.TIMED_OUT -> "启动超时；请重新打开或重启 Shizuku"
        SystemConnectionState.CLOSED -> "会话已关闭（下方保留上次统计）"
        SystemConnectionState.BLOCKED -> "连接前置条件不满足，请检查第 1 层"
    }
    val capability = diagnosticResultDescription(snapshot.capabilityCode, capability = true)
    val result = when {
        snapshot.lastResultCode == null -> "尚无投递结果"
        snapshot.lastResultCode < 0 -> diagnosticResultDescription(snapshot.lastResultCode)
        snapshot.lastResultCode == snapshot.lastExpectedEvents -> "HID 报告已完整提交；远端未确认"
        else -> "HID 报告未完整提交（${snapshot.lastResultCode}/${snapshot.lastExpectedEvents} 个事件）"
    }
    return buildString {
        appendLine(context.getString(R.string.uu_diagnostics_scope))
        appendLine()
        appendLine("1 · 环境与权限")
        appendLine("Android ${environment.androidSdk}（需 API 34+） · UU ${environment.uuVersionCode ?: "未安装或不可见"}")
        appendLine("悬浮窗：${enabled(environment.overlayAllowed)} · Shizuku 运行：${enabled(environment.shizukuRunning)}")
        appendLine("Shizuku 版本：${environment.shizukuVersion ?: "未知"}（需 13+） · 授权：${enabled(environment.shizukuAuthorized)}")
        appendLine("2 · 服务连接：$connection")
        appendLine("3 · HID 设备检查（不发送按键）：$capability")
        appendLine("4 · 系统投递：$result")
        appendLine("尝试 ${snapshot.attempted} · 完整提交 ${snapshot.accepted} · 未完整/失败 ${snapshot.failed}")
        appendLine("丢弃 ${snapshot.dropped} · 取消 ${snapshot.cancelled}（单位：逻辑按键，不记录键值）")
        appendLine("其中队列溢出 ${snapshot.overflowDropped} · 排队超时 ${snapshot.expiredDropped}")
        appendLine("5 · 远端接收：未确认")
        appendLine(context.getString(R.string.uu_diagnostics_remote_hint))
        appendLine("当前设置：${environment.platform.name} · 普通输入法 ${environment.imeMode.name} · ⌘→右 Ctrl ${enabled(environment.commandCompatibility)}")
        appendLine(context.getString(R.string.uu_diagnostics_privacy))
    }
}

/** Maps only stable error codes to actionable text; exception messages are never displayed. */
private fun diagnosticResultDescription(code: Int?, capability: Boolean = false): String = when (code) {
    null -> "未检查；请启动 UU 浮动键盘"
    SystemInputStatus.READY -> if (capability) "HID 键盘已登记；远端接收未确认" else "未提交 HID 报告"
    SystemInputStatus.UNSUPPORTED_ANDROID -> "HID 键盘目前需要 Android 14 或更新版本"
    SystemInputStatus.CALLER_REJECTED -> "服务拒绝调用身份；请关闭浮窗后重新打开"
    SystemInputStatus.INVALID_TARGET -> "UU 目标或显示信息无效；请重新打开浮窗"
    SystemInputStatus.INVALID_KEY -> "按键或修饰键不受支持"
    SystemInputStatus.UU_UNAVAILABLE -> "无法获取 UU 安装信息"
    SystemInputStatus.KEYBOARD_UNAVAILABLE -> "系统未提供可用的字母键盘设备"
    SystemInputStatus.TARGETED_API_UNAVAILABLE -> "系统定向投递接口不可用（可能受 ROM 限制）"
    SystemInputStatus.DISPLAY_API_UNAVAILABLE -> "系统显示选择接口不可用（可能受 ROM 限制）"
    SystemInputStatus.PERMISSION_DENIED -> "系统拒绝权限；请检查 Shizuku 状态与授权"
    SystemInputStatus.INJECTION_ERROR -> "系统投递接口异常；请返回 UU 后再试"
    SystemInputStatus.HID_UNAVAILABLE -> "HID 设备启动失败或已断开；请检查 Shizuku 后重新打开浮窗"
    SystemInputStatus.FOCUS_UNSAFE -> "未发送：UU 电脑键盘未获得焦点，或焦点无法安全确认"
    SystemInputStatus.UNSUPPORTED_DISPLAY -> "HID 暂只支持手机主显示屏"
    else -> "检查或服务调用异常（错误码 $code）"
}
