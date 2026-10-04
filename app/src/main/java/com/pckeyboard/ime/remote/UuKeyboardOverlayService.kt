package com.pckeyboard.ime.remote

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.pckeyboard.ime.R
import com.pckeyboard.ime.dispatch.RawKeyRouter
import com.pckeyboard.ime.layout.LayoutBlocks
import com.pckeyboard.ime.layout.LayoutRegistry
import com.pckeyboard.ime.layout.LayoutSelector
import com.pckeyboard.ime.layout.LayoutVariant
import com.pckeyboard.ime.model.Key
import com.pckeyboard.ime.model.KeyType
import com.pckeyboard.ime.model.LayoutMode
import com.pckeyboard.ime.model.ModifierState
import com.pckeyboard.ime.settings.KeyboardPrefs
import com.pckeyboard.ime.settings.SettingsActivity
import com.pckeyboard.ime.theme.ThemeRepository
import com.pckeyboard.ime.theme.KeyboardTheme
import com.pckeyboard.ime.theme.applyKeyboardChrome
import com.pckeyboard.ime.view.KeyboardView
import com.pckeyboard.ime.view.MenuAction

/** Displays a non-focusable physical-key surface that targets only UU's Android UID. */
class UuKeyboardOverlayService : Service(), KeyboardView.Listener {
    private val prefs by lazy { KeyboardPrefs(this) }
    private var windowManager: WindowManager? = null
    private var panel: LinearLayout? = null
    private var keyboard: KeyboardView? = null
    private var shortcutBar: RemoteShortcutBar? = null
    private var stopObservingPreferences: (() -> Unit)? = null
    private var stopObservingTheme: (() -> Unit)? = null
    private var header: LinearLayout? = null
    private var headerScroll: HorizontalScrollView? = null
    private var calibrationBar: LinearLayout? = null
    private var calibrationHint: TextView? = null
    private var dockButton: Button? = null
    private var dragButton: Button? = null
    private var docked = false
    private var floatingPosition: Pair<Int, Int>? = null
    private var title: TextView? = null
    private var collapseButton: Button? = null
    private var restoreButton: Button? = null
    private var dockOnNextLayout = true
    private var diagnostics: SystemKeyboardDiagnosticsPanel? = null
    private var bridge: ShizukuKeyBridge? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var targetUid = -1
    private var mode = LayoutMode.MAIN
    private var collapsed = false
    private var receiverRegistered = false
    private var destroyed = false
    private val screenReceiver = object : BroadcastReceiver() {
        /** Ends the explicit session when the screen is locked or switched off. */
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) stopOverlay()
        }
    }

    /** Accepts explicit starts only; Android must never restore an injection session itself. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW && panel != null) {
            setCollapsed(false)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopOverlay()
            return START_NOT_STICKY
        }
        if (panel != null) return START_NOT_STICKY
        targetUid = runCatching { packageManager.getApplicationInfo(UU_PACKAGE, 0).uid }.getOrDefault(-1)
        if (!supportsTargetedSystemInput(Build.VERSION.SDK_INT) || targetUid < 0 || !Settings.canDrawOverlays(this) ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked
        ) {
            Toast.makeText(this, R.string.uu_overlay_unavailable, Toast.LENGTH_LONG).show()
            stopOverlay()
            return START_NOT_STICKY
        }
        startSessionNotification()
        ContextCompat.registerReceiver(
            this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        windowManager = getSystemService(WindowManager::class.java)
        bridge = ShizukuKeyBridge(this) { status ->
            ContextCompat.getMainExecutor(this).execute {
                if (!destroyed) title?.text = getString(
                    R.string.uu_overlay_status, getString(R.string.uu_overlay_title), status,
                )
            }
        }
        showPanel()
        stopObservingPreferences = prefs.observeRemoteLayoutChanges {
            if (!destroyed && panel != null) rebuildKeyboard()
        }
        stopObservingTheme = ThemeRepository(this).observeSelectedTheme {
            if (!destroyed && panel != null) rebuildKeyboard()
        }
        bridge?.connect()
        return START_NOT_STICKY
    }

    /** Keeps the service private to explicit start and stop intents. */
    override fun onBind(intent: Intent?): IBinder? = null

    /** Publishes the persistent stop affordance before constructing the overlay window. */
    private fun startSessionNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, getString(R.string.uu_overlay_channel), NotificationManager.IMPORTANCE_LOW,
        ))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, UuKeyboardOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val show = PendingIntent.getService(this, 1,
            Intent(this, UuKeyboardOverlayService::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(getString(R.string.uu_overlay_channel))
            .setContentText(getString(R.string.uu_overlay_notification))
            .setOngoing(true)
            .setContentIntent(show)
            .addAction(Notification.Action.Builder(null, getString(R.string.uu_overlay_restore), show).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.uu_overlay_stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Creates a bottom-aligned touch surface without taking focus from UU's desktop view. */
    @SuppressLint("RtlHardcoded") // Drag coordinates are physical screen coordinates, not text direction.
    private fun showPanel() {
        docked = prefs.uuOverlayDocked
        val theme = ThemeRepository(this).getSelectedTheme()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }
        calibrationBar = createCalibrationBar()
        container.addView(calibrationBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.modifierKeyColor)
        }
        this.header = header
        dragButton = Button(this).apply {
            setText(R.string.uu_overlay_drag)
            contentDescription = getString(R.string.uu_overlay_drag)
            installDragHandle(this)
        }
        header.addView(dragButton)
        dockButton = Button(this).apply {
            setOnClickListener { setDocked(!docked) }
        }
        header.addView(dockButton)
        title = TextView(this).apply {
            text = getString(R.string.uu_overlay_title)
            setTextColor(theme.modifierTextColor)
            textSize = 12f
            maxLines = 2
            setPadding(dp(8), dp(4), dp(4), dp(4))
        }
        header.addView(title, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(Button(this).apply {
            setText(R.string.uu_diagnostics_toggle)
            setOnClickListener {
                diagnostics?.let { view ->
                    val expanding = view.visibility != View.VISIBLE
                    view.visibility = if (expanding) View.VISIBLE else View.GONE
                    if (expanding) {
                        bridge?.refreshDiagnostics(targetUid, panel?.display?.displayId ?: Display.DEFAULT_DISPLAY)
                        view.startUpdating()
                    } else {
                        view.stopUpdating()
                    }
                    bridge?.cancelPending()
                    keyboard?.cancelInteractionsForShortcut()
                    updateDockContent()
                    panel?.post { updatePanelPosition() }
                }
            }
        })
        collapseButton = Button(this).apply {
            setText(if (collapsed) R.string.uu_overlay_expand else R.string.uu_overlay_collapse)
            setOnClickListener {
                setCollapsed(true)
            }
        }
        header.addView(collapseButton)
        header.addView(Button(this).apply {
            setText(R.string.uu_overlay_close)
            setOnClickListener { stopOverlay() }
        })
        headerScroll = HorizontalScrollView(this).apply {
            isFocusable = false
            isHorizontalScrollBarEnabled = true
            addView(header, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        container.addView(headerScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        restoreButton = Button(this).apply {
            setText(R.string.uu_overlay_restore)
            contentDescription = getString(R.string.uu_overlay_expand)
            visibility = View.GONE
            setOnClickListener { setCollapsed(false) }
            installDragHandle(this, clickable = true)
        }
        container.addView(restoreButton, LinearLayout.LayoutParams(dp(56), dp(56)))
        diagnostics = SystemKeyboardDiagnosticsPanel(this) {
            setCollapsed(true)
        }.apply {
            visibility = View.GONE
            setBackgroundColor(theme.modifierKeyColor)
            setDetailsColor(theme.modifierTextColor)
        }
        container.addView(diagnostics, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, diagnosticPanelHeight(),
        ))
        panel = container
        windowParams = WindowManager.LayoutParams(
            UuOverlayGeometry.expandedWidth(usableBounds().width(), dp(360)), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }
        container.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (dockOnNextLayout || right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                container.post { updatePanelPosition() }
            }
        }
        rebuildKeyboard()
        try {
            windowManager?.addView(container, windowParams)
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.uu_overlay_unavailable, Toast.LENGTH_LONG).show()
            stopOverlay()
        }
    }

    /** Moves both axes while reserving a tap on the compact handle for restoring the panel. */
    @SuppressLint("ClickableViewAccessibility")
    private fun installDragHandle(handle: View, clickable: Boolean = false) {
        var initialX = 0f
        var initialY = 0f
        var initialLeft = 0
        var initialTop = 0
        var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener { _, event ->
            val params = windowParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handle.parent?.requestDisallowInterceptTouchEvent(true)
                    initialX = event.rawX
                    initialY = event.rawY
                    initialLeft = params.x
                    initialTop = params.y
                    dragging = false
                    bridge?.cancelPending()
                    keyboard?.cancelInteractionsForShortcut()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialX
                    val dy = event.rawY - initialY
                    if (dx * dx + dy * dy > slop * slop) dragging = true
                    if (dragging) {
                        params.x = initialLeft + dx.toInt()
                        params.y = initialTop + dy.toInt()
                        dockOnNextLayout = false
                        updatePanelPosition()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handle.parent?.requestDisallowInterceptTouchEvent(false)
                    if (clickable && !dragging) handle.performClick()
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    handle.parent?.requestDisallowInterceptTouchEvent(false)
                    dragging = false
                }
            }
            true
        }
    }

    /** Creates a visible top boundary that can be dragged or adjusted without taking UU focus. */
    private fun createCalibrationBar(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        calibrationHint = TextView(this@UuKeyboardOverlayService).apply {
            setText(R.string.uu_overlay_dock_resize)
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            installResizeHandle(this)
            setOnClickListener {
                Toast.makeText(this@UuKeyboardOverlayService, R.string.uu_overlay_dock_hint, Toast.LENGTH_LONG).show()
            }
        }
        addView(calibrationHint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        listOf(R.string.uu_overlay_dock_shrink to -1, R.string.uu_overlay_dock_grow to 1).forEach { (label, direction) ->
            addView(Button(this@UuKeyboardOverlayService).apply {
                setText(label)
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { resizeDock((windowParams?.height ?: 0) + direction * dp(12)) }
            }, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /** Adjusts only the dock height; the lower edge always remains attached to the display. */
    @SuppressLint("ClickableViewAccessibility")
    private fun installResizeHandle(handle: View) {
        var initialY = 0f
        var initialHeight = 0
        var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener { _, event ->
            if (!docked || collapsed) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = event.rawY
                    initialHeight = windowParams?.height ?: 0
                    dragging = false
                    bridge?.cancelPending()
                    keyboard?.cancelInteractionsForShortcut()
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = initialY - event.rawY
                    if (kotlin.math.abs(delta) > slop) dragging = true
                    if (dragging) resizeDock(initialHeight + delta.toInt())
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) handle.performClick()
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            true
        }
    }

    /** Switches presentation modes without rebuilding or reconnecting the HID transport. */
    private fun setDocked(value: Boolean) {
        if (docked == value) return
        if (value) floatingPosition = windowParams?.let { it.x to it.y }
        docked = value
        prefs.uuOverlayDocked = value
        dockOnNextLayout = !value && floatingPosition == null
        if (!value) floatingPosition?.let { (x, y) -> windowParams?.apply { this.x = x; this.y = y } }
        diagnostics?.stopUpdating()
        diagnostics?.visibility = View.GONE
        rebuildKeyboard()
        if (value) Toast.makeText(this, R.string.uu_overlay_dock_hint, Toast.LENGTH_LONG).show()
    }

    /** Saves bounded calibration separately for portrait and landscape before resizing in place. */
    private fun resizeDock(height: Int) {
        if (!docked || collapsed) return
        bridge?.cancelPending()
        keyboard?.cancelInteractionsForShortcut()
        val bounds = usableBounds()
        prefs.setUuDockHeightRatio(bounds.width() > bounds.height(),
            UuOverlayGeometry.dockedRatio(bounds.height(), height, dp(120)))
        updateDockContent()
        updatePanelPosition()
    }

    /** Fits every visible child inside the calibrated region, replacing keys with diagnostics. */
    private fun updateDockContent() {
        val bounded = docked && !collapsed
        calibrationBar?.visibility = if (bounded) View.VISIBLE else View.GONE
        headerScroll?.visibility = if (collapsed) View.GONE else View.VISIBLE
        dragButton?.visibility = if (docked) View.GONE else View.VISIBLE
        dockButton?.setText(if (docked) R.string.uu_overlay_float else R.string.uu_overlay_dock)
        val showingDiagnostics = diagnostics?.visibility == View.VISIBLE
        val bounds = usableBounds()
        val height = UuOverlayGeometry.dockedHeight(bounds.height(),
            prefs.uuDockHeightRatio(bounds.width() > bounds.height()), dp(120))
        calibrationHint?.setText(if (height < dp(200)) R.string.uu_overlay_dock_small else R.string.uu_overlay_dock_resize)
        calibrationBar?.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(36), (height * 0.18f).toInt().coerceAtLeast(1)))
        headerScroll?.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            if (bounded) minOf(dp(48), (height * 0.24f).toInt().coerceAtLeast(1)) else dp(48))
        windowParams?.apply {
            width = when { collapsed -> dp(56); docked -> bounds.width()
                else -> UuOverlayGeometry.expandedWidth(bounds.width(), dp(360)) }
            this.height = if (bounded) height else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        diagnostics?.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            if (bounded) 0 else diagnosticPanelHeight(), if (bounded) 1f else 0f)
        keyboard?.apply {
            visibility = if (bounded && showingDiagnostics) View.GONE else View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                if (bounded) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, if (bounded) 1f else 0f)
        }
        shortcutBar?.visibility = if (bounded && (showingDiagnostics || height < dp(280))) View.GONE else View.VISIBLE
    }

    /** Hides all input surfaces behind a compact movable restore button. */
    private fun setCollapsed(value: Boolean) {
        if (collapsed == value) return
        collapsed = value
        diagnostics?.stopUpdating()
        diagnostics?.visibility = View.GONE
        rebuildKeyboard()
    }

    /** Recreates the reusable keyboard to cancel pressed keys and menus across layout changes. */
    private fun rebuildKeyboard() {
        bridge?.cancelPending()
        val theme = ThemeRepository(this).getSelectedTheme()
        header?.visibility = if (collapsed) View.GONE else View.VISIBLE
        restoreButton?.visibility = if (collapsed) View.VISIBLE else View.GONE
        applyPanelTheme(theme)
        keyboard?.apply { listener = null; resetModifiers(); panel?.removeView(this) }
        keyboard = null
        shortcutBar?.let { it.dispose(); panel?.removeView(it) }
        shortcutBar = null
        if (collapsed) {
            updateDockContent()
            panel?.post { updatePanelPosition() }
            return
        }
        if (prefs.uuShortcutBar) {
            shortcutBar = RemoteShortcutBar(this, prefs.keyboardPlatform, ::sendShortcut)
            panel?.addView(shortcutBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val pack = LayoutRegistry.get("en_US")
        val base = when (mode) {
            LayoutMode.SYMBOLS -> pack.symbols
            LayoutMode.SYMBOLS_SHIFT -> pack.symbolsShift
            else -> pack.main
        }
        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val variant = if (prefs.showFunctionRow) LayoutVariant.FULL else LayoutSelector.pick(widthDp)
        val layout = LayoutSelector.apply(LayoutBlocks.applyPlatform(base, prefs.keyboardPlatform), variant)
        keyboard = KeyboardView(this).apply {
            listener = this@UuKeyboardOverlayService
            setCompactOverlayMode()
            bind(layout, theme)
        }
        panel?.addView(keyboard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        updateDockContent()
        panel?.post { updatePanelPosition() }
    }

    /** Updates every auxiliary surface so live theme changes never leave mismatched controls. */
    private fun applyPanelTheme(theme: KeyboardTheme) {
        header?.let { row ->
            row.setBackgroundColor(theme.backgroundColor)
            for (index in 0 until row.childCount) {
                (row.getChildAt(index) as? Button)?.applyKeyboardChrome(theme)
            }
        }
        title?.setTextColor(theme.secondaryTextColor)
        restoreButton?.applyKeyboardChrome(theme)
        panel?.setBackgroundColor(theme.backgroundColor)
        calibrationBar?.let { row ->
            row.setBackgroundColor(theme.modifierKeyColor)
            for (index in 0 until row.childCount) {
                val child = row.getChildAt(index)
                if (child is Button) child.applyKeyboardChrome(theme)
                else (child as? TextView)?.setTextColor(theme.modifierTextColor)
            }
        }
        diagnostics?.applyTheme(theme)
    }

    /** Keeps an expanded or rotated panel visible and handles overlay permission revocation. */
    private fun updatePanelPosition() {
        val view = panel?.takeIf { it.isAttachedToWindow } ?: return
        val params = windowParams ?: return
        val bounds = usableBounds()
        if (docked && !collapsed) {
            params.x = bounds.left
            params.y = bounds.bottom - params.height
            dockOnNextLayout = false
        }
        if (dockOnNextLayout && !view.isLayoutRequested && view.height > 0) {
            params.x = bounds.left + (bounds.width() - params.width) / 2
            params.y = bounds.bottom - view.height
            dockOnNextLayout = false
        }
        params.x = UuOverlayGeometry.clamp(params.x, bounds.left, bounds.width(), params.width)
        params.y = UuOverlayGeometry.clamp(params.y, bounds.top, bounds.height(),
            if (docked && !collapsed) params.height else view.height)
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (_: RuntimeException) {
            stopOverlay()
        }
    }

    /** Uses coordinates relative to the default system-bar/cutout-fitted window frame. */
    private fun usableBounds(): Rect {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
        val metrics = (windowManager ?: getSystemService(WindowManager::class.java)).currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        return Rect(0, 0, metrics.bounds.width() - insets.left - insets.right,
            metrics.bounds.height() - insets.top - insets.bottom)
    }

    /** Routes layout actions locally and identifies UU for the guarded HID service. */
    override fun onKey(key: Key, modifiers: ModifierState) {
        when (key.type) {
            KeyType.HIDE -> setCollapsed(true)
            KeyType.SYMBOL_SWITCH -> {
                mode = if (mode == LayoutMode.MAIN) LayoutMode.SYMBOLS else LayoutMode.MAIN
                rebuildKeyboard()
            }
            KeyType.LAYOUT_SWITCH -> {
                mode = if (mode == LayoutMode.SYMBOLS_SHIFT) LayoutMode.SYMBOLS else LayoutMode.SYMBOLS_SHIFT
                rebuildKeyboard()
            }
            else -> RawKeyRouter.strokeFor(key, modifiers, prefs.keyboardPlatform)?.let {
                val stroke = UuShortcutMapper.map(it, prefs.keyboardPlatform, prefs.uuCommandCompatibility)
                sendKey(stroke.keyCode, stroke.requiredMetaState)
            } ?: unsupported()
        }
    }

    /** Bounds trackpad bursts before translating movement to desktop arrow keys. */
    override fun onCursorMove(dx: Int, dy: Int) {
        overlayCursorKeys(dx, dy).forEach { sendKey(it, 0) }
    }

    /** Cancels held touch gestures without discarding previously queued remote commands. */
    private fun sendShortcut(shortcut: RemoteShortcut) {
        keyboard?.cancelInteractionsForShortcut()
        val platform = prefs.keyboardPlatform
        val stroke = UuShortcutMapper.map(shortcut.stroke(platform), platform, prefs.uuCommandCompatibility)
        sendKey(stroke.keyCode, stroke.requiredMetaState)
    }

    /** Resolves the current display for each event without permitting an arbitrary target app. */
    private fun sendKey(keyCode: Int, metaState: Int) {
        if (!destroyed && !collapsed && targetUid >= 0) {
            bridge?.send(keyCode, metaState, targetUid, panel?.display?.displayId ?: Display.DEFAULT_DISPLAY)
        }
    }

    /** Exposes supported local actions without activating text, clipboard, or microphone flows. */
    override fun onMenuAction(action: MenuAction) {
        when (action) {
            MenuAction.OpenSymbols -> { mode = LayoutMode.SYMBOLS; rebuildKeyboard() }
            MenuAction.ToggleFunctionRow -> {
                val prefs = KeyboardPrefs(this)
                prefs.showFunctionRow = !prefs.showFunctionRow
                rebuildKeyboard()
            }
            MenuAction.OpenSettings -> {
                bridge?.cancelPending()
                startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            else -> unsupported()
        }
    }

    /** Rejects text insertion because the remote host owns character composition. */
    override fun onText(text: String) = unsupported()
    /** Rejects suggestions because this surface does not compose local words. */
    override fun onSuggestionPicked(word: String) = unsupported()
    /** Rejects clipboard editing because this surface emits physical keys only. */
    override fun onClipboardEdit(text: String) = unsupported()
    /** Rejects microphone setup because voice input is not part of this surface. */
    override fun onOpenAppSettings() = unsupported()

    /** Explains unsupported input actions without silently dropping requested text. */
    private fun unsupported() = Toast.makeText(this, R.string.uu_overlay_unsupported, Toast.LENGTH_SHORT).show()

    /** Cancels queued input and rebuilds the keyboard after folding or rotation. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (panel == null) return
        dockOnNextLayout = !collapsed
        diagnostics?.let { view ->
            view.layoutParams = view.layoutParams.apply { height = diagnosticPanelHeight() }
        }
        rebuildKeyboard()
        bridge?.refreshDiagnostics(targetUid, panel?.display?.displayId ?: Display.DEFAULT_DISPLAY)
        updatePanelPosition()
    }

    /** Stops queued injection immediately before ending the explicitly started session. */
    private fun stopOverlay() { bridge?.cancelPending(); stopSelf() }

    /** Releases every window, receiver, and bridge resource without scheduling a restart. */
    override fun onDestroy() {
        destroyed = true
        stopObservingTheme?.invoke()
        stopObservingTheme = null
        header = null
        headerScroll = null
        calibrationBar = null
        calibrationHint = null
        dockButton = null
        dragButton = null
        stopObservingPreferences?.invoke()
        stopObservingPreferences = null
        bridge?.close()
        bridge = null
        diagnostics?.stopUpdating()
        diagnostics = null

        collapseButton = null
        restoreButton = null
        keyboard?.apply {
            listener = null
            resetModifiers()
        }
        panel?.let { view ->
            if (view.isAttachedToWindow) runCatching { windowManager?.removeViewImmediate(view) }
        }
        panel = null
        keyboard = null
        shortcutBar?.dispose()
        shortcutBar = null
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Converts header spacing to density-independent pixels. */
    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    /** Caps diagnostic height so the keyboard and remote desktop remain accessible on rotation. */
    private fun diagnosticPanelHeight(): Int = minOf(dp(220), resources.displayMetrics.heightPixels / 3)

    companion object {
        const val ACTION_START = "com.pckeyboard.ime.remote.START_UU_KEYBOARD"
        const val ACTION_STOP = "com.pckeyboard.ime.remote.STOP_UU_KEYBOARD"
        const val ACTION_SHOW = "com.pckeyboard.ime.remote.SHOW_UU_KEYBOARD"
        const val UU_PACKAGE = "com.netease.uuremote"
        private const val CHANNEL_ID = "uu_keyboard_overlay"
        private const val NOTIFICATION_ID = 4107
    }
}
