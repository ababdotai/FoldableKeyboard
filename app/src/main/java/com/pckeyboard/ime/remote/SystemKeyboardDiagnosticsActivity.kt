package com.pckeyboard.ime.remote

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.pckeyboard.ime.R
import com.pckeyboard.ime.settings.addSystemBarPadding

/** Offers setup diagnostics and explicit report export without starting an injection session. */
class SystemKeyboardDiagnosticsActivity : AppCompatActivity() {
    private lateinit var diagnostics: SystemKeyboardDiagnosticsPanel

    /** Builds a scrollable, non-editable diagnostic screen with an explicit return action. */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        diagnostics = SystemKeyboardDiagnosticsPanel(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addSystemBarPadding()
            addView(TextView(this@SystemKeyboardDiagnosticsActivity).apply {
                setText(R.string.uu_diagnostics_title)
                textSize = 22f
                setPadding(24, 16, 24, 16)
            })
            addView(diagnostics, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(Button(this@SystemKeyboardDiagnosticsActivity).apply {
                setText(R.string.uu_diagnostics_back)
                setOnClickListener { finish() }
            })
        }
        setContentView(content)
    }

    /** Refreshes permission changes when the user returns from another app or settings screen. */
    override fun onStart() {
        super.onStart()
        diagnostics.startUpdating()
    }

    /** Stops UI polling while UU or a share destination is in the foreground. */
    override fun onStop() {
        diagnostics.stopUpdating()
        super.onStop()
    }
}
