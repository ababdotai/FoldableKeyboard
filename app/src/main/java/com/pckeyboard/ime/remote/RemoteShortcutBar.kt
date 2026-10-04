package com.pckeyboard.ime.remote

import android.content.Context
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.pckeyboard.ime.R
import com.pckeyboard.ime.layout.KeyboardPlatform
import com.pckeyboard.ime.theme.ThemeRepository
import com.pckeyboard.ime.theme.applyKeyboardChrome

/** Keeps remote commands reachable on narrow displays without taking editor focus. */
internal class RemoteShortcutBar @JvmOverloads constructor(
    context: Context,
    platform: KeyboardPlatform = KeyboardPlatform.WIN,
    onShortcut: (RemoteShortcut) -> Unit = {},
) : HorizontalScrollView(context) {
    private var callback: ((RemoteShortcut) -> Unit)? = onShortcut
    init {
        isHorizontalScrollBarEnabled = true
        isFocusable = false
        contentDescription = context.getString(R.string.uu_shortcut_bar)
        val theme = ThemeRepository(context).getSelectedTheme()
        setBackgroundColor(theme.backgroundColor)
        val row = LinearLayout(context)
        RemoteShortcut.entries.forEach { shortcut ->
            row.addView(Button(context).apply {
                text = context.getString(R.string.uu_shortcut_label,
                    context.getString(shortcut.labelRes), shortcut.hint(platform))
                isAllCaps = false
                textSize = 12f
                isFocusable = false
                applyKeyboardChrome(theme)
                setOnClickListener { callback?.invoke(shortcut) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                (48 * resources.displayMetrics.density).toInt()))
        }
        addView(row)
    }

    /** Makes retained buttons inert when their layout or session is no longer current. */
    fun dispose() {
        callback = null
    }
}
