package com.pckeyboard.ime.service

import android.os.UserManager
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import com.pckeyboard.ime.dispatch.DispatchMode
import com.pckeyboard.ime.layout.KeyboardPlatform
import com.pckeyboard.ime.model.Key
import com.pckeyboard.ime.model.KeyType
import com.pckeyboard.ime.model.ModifierState
import com.pckeyboard.ime.settings.KeyboardPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercises production IME callbacks against a recording Android input connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MacShortcutServiceIntegrationTest {
    private lateinit var controller: ServiceController<PcKeyboardService>
    private lateinit var service: PcKeyboardService
    private lateinit var connection: RecordingConnection
    private lateinit var prefs: KeyboardPrefs

    /** Starts the actual service without scheduling update jobs, then binds a recording editor. */
    @Before
    fun bindRecordingEditor() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        prefs = KeyboardPrefs(application).apply {
            keyboardPlatform = KeyboardPlatform.MAC
            dispatchMode = DispatchMode.NORMAL
        }
        controller = Robolectric.buildService(PcKeyboardService::class.java).create()
        service = controller.get()
        connection = RecordingConnection()
        ReflectionHelpers.setField(service, "mInputConnection", connection)
    }

    /** Releases framework callbacks and any queued remote input after each editor session. */
    @After
    fun destroyService() {
        controller.destroy()
    }

    /** Ensures Mac Space, Enter, navigation, and AltGr-labeled keys avoid local text APIs. */
    @Test
    fun normalMacShortcutsDispatchEventsWithoutEditorMutations() {
        val cases = listOf(
            Triple(Key.fn("space", KeyType.SPACE), ModifierState().apply { tapMeta() }, KeyEvent.KEYCODE_SPACE),
            Triple(Key.fn("space", KeyType.SPACE), ModifierState().apply { tapCtrl() }, KeyEvent.KEYCODE_SPACE),
            Triple(Key.fn("Enter", KeyType.ENTER), ModifierState().apply { tapMeta() }, KeyEvent.KEYCODE_ENTER),
            Triple(Key.fn("Left", KeyType.ARROW_LEFT), ModifierState().apply { tapMeta() }, KeyEvent.KEYCODE_DPAD_LEFT),
            Triple(Key.fn("Right", KeyType.ARROW_RIGHT), ModifierState().apply { tapAlt() }, KeyEvent.KEYCODE_DPAD_RIGHT),
            Triple(Key.fn("Delete", KeyType.BACKSPACE), ModifierState().apply { tapAlt() }, KeyEvent.KEYCODE_DEL),
            Triple(Key.letter("c", alt = "©"), ModifierState().apply { tapAlt() }, KeyEvent.KEYCODE_C),
            Triple(Key.char("3", "#"), ModifierState().apply { tapShift(); tapMeta() }, KeyEvent.KEYCODE_3),
        )
        for ((key, modifiers, expectedCode) in cases) {
            connection.events.clear()
            service.onKey(key, modifiers)
            assertTrue(connection.events.any { it.action == KeyEvent.ACTION_DOWN && it.keyCode == expectedCode })
            assertEquals(0, connection.events.last().metaState)
            assertEquals(emptyList<String>(), connection.editorOperations)
        }
    }

    /** Protects the same Fn translation in both Normal and RAW service dispatch modes. */
    @Test
    fun macFnBackspaceBecomesForwardDeleteInBothDispatchModes() {
        for (mode in DispatchMode.entries) {
            prefs.dispatchMode = mode
            connection.events.clear()
            service.onKey(Key.fn("Delete", KeyType.BACKSPACE), ModifierState().apply { tapFn() })
            assertEquals(listOf(KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_FORWARD_DEL), connection.events.map { it.keyCode })
            assertEquals(listOf(0, 0), connection.events.map { it.metaState })
            assertEquals(emptyList<String>(), connection.editorOperations)
        }
    }

    /** Rejects unrepresentable modified glyphs instead of silently committing shortcut text. */
    @Test
    fun unsupportedMacShortcutCannotFallBackToCommittedText() {
        service.onKey(Key.char("你"), ModifierState().apply { tapMeta() })
        assertTrue(connection.events.isEmpty())
        assertTrue(connection.editorOperations.isEmpty())
    }

    /** Preserves ordinary Android text input and the existing Windows AltGr character behavior. */
    @Test
    fun plainMacTextAndWindowsAltGrStillCommitText() {
        service.onKey(Key.letter("c", alt = "©"), ModifierState())
        service.onKey(Key.fn("space", KeyType.SPACE), ModifierState())
        service.onKey(Key.char("3", "#"), ModifierState().apply { toggleCapsLock() })
        prefs.keyboardPlatform = KeyboardPlatform.WIN
        service.onKey(Key.letter("c", alt = "©"), ModifierState().apply { tapAlt() })
        assertEquals(listOf("commit:c", "commit: ", "commit:3", "commit:©"), connection.editorOperations)
        assertTrue(connection.events.isEmpty())
    }

    /** Keeps Caps Lock from extending cursor selections or overriding the editor's Send action. */
    @Test
    fun capsLockPreservesPlainNavigationAndEditorEnterAction() {
        ReflectionHelpers.setField(service, "mInputEditorInfo", EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
        })
        connection.extractedText = ExtractedText().apply {
            text = "abc"
            selectionStart = 1
            selectionEnd = 1
        }
        val modifiers = ModifierState().apply { toggleCapsLock() }

        service.onKey(Key.fn("Right", KeyType.ARROW_RIGHT), modifiers)
        service.onKey(Key.fn("Enter", KeyType.ENTER), modifiers)

        assertEquals(listOf("selection:2:2", "action:${EditorInfo.IME_ACTION_SEND}"), connection.editorOperations)
        assertTrue(connection.events.isEmpty())
    }

    /** Ensures legacy terminal modifier framing also excludes Caps-derived Shift presses. */
    @Test
    fun windowsTerminalControlShortcutIgnoresCapsLockAsModifier() {
        prefs.keyboardPlatform = KeyboardPlatform.WIN
        ReflectionHelpers.setField(service, "mInputEditorInfo", EditorInfo().apply {
            inputType = android.text.InputType.TYPE_NULL
            packageName = "com.termux"
        })

        service.onKey(Key.letter("c"), ModifierState().apply { toggleCapsLock(); tapCtrl() })

        assertEquals(
            listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_CTRL_LEFT),
            connection.events.map { it.keyCode },
        )
        assertTrue(connection.events.all { it.metaState and KeyEvent.META_SHIFT_ON == 0 })
        assertTrue(connection.editorOperations.isEmpty())
    }

    /** Captures event delivery and every editor mutation that shortcuts must bypass. */
    private class RecordingConnection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), false) {
        val events = mutableListOf<KeyEvent>()
        val editorOperations = mutableListOf<String>()
        var extractedText: ExtractedText? = null

        /** Records immutable copies of complete down/up event sequences. */
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            events.add(KeyEvent(event))
            return true
        }

        /** Records text commits so shortcut paths cannot accidentally become Unicode input. */
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            editorOperations.add("commit:$text")
            return true
        }

        /** Records local cursor updates, which would bypass the remote host's shortcut handling. */
        override fun setSelection(start: Int, end: Int): Boolean {
            editorOperations.add("selection:$start:$end")
            return true
        }

        /** Records Android editor actions, which must not replace modified Enter events. */
        override fun performEditorAction(actionCode: Int): Boolean {
            editorOperations.add("action:$actionCode")
            return true
        }

        /** Records composing calls to catch accidental suggestion/correction side effects. */
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            editorOperations.add("compose:$text")
            return true
        }

        /** Records deletion APIs used by local text correction. */
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            editorOperations.add("delete:$beforeLength:$afterLength")
            return true
        }

        /** Provides an empty editor so ordinary typing does not depend on dictionary state. */
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = ""

        /** Supplies a configurable selection model for testing ordinary Android cursor movement. */
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = extractedText
    }
}
