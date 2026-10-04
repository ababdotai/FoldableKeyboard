package com.pckeyboard.ime.dispatch

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import com.pckeyboard.ime.model.Key
import com.pckeyboard.ime.model.ModifierState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Verifies actual Android event construction and delivery to an input connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RawKeyDispatcherIntegrationTest {

    /** Ensures every pinyin letter carries matching key codes and Linux keyboard scan codes. */
    @Test
    fun sendsNihaoAsKeyboardEventPairsWithoutTextOperations() {
        val connection = RecordingConnection()
        val dispatcher = RawKeyDispatcher()
        val scanCodes = listOf(49, 23, 35, 30, 24)

        "nihao".forEach { character ->
            val stroke = requireNotNull(RawKeyRouter.strokeFor(Key.letter(character.toString()), ModifierState()))
            val result = dispatcher.send(connection, stroke.keyCode, stroke.requiredMetaState)
            assertEquals(RawDispatchResult(2, 2), result)
        }

        assertEquals(10, connection.events.size)
        connection.events.chunked(2).forEachIndexed { index, pair ->
            val (down, up) = pair
            val keyCode = requireNotNull(RawKeyMapper.forCharacter("nihao"[index])).keyCode
            assertEquals(KeyEvent.ACTION_DOWN, down.action)
            assertEquals(KeyEvent.ACTION_UP, up.action)
            assertEquals(down.downTime, up.downTime)
            pair.forEach { event ->
                assertEquals(keyCode, event.keyCode)
                assertEquals(scanCodes[index], event.scanCode)
                assertEquals(InputDevice.SOURCE_KEYBOARD, event.source)
                assertEquals(KeyCharacterMap.VIRTUAL_KEYBOARD, event.deviceId)
                assertEquals(0, event.metaState)
                assertEquals(0, event.repeatCount)
                assertEquals(0, event.flags and KeyEvent.FLAG_SOFT_KEYBOARD)
                assertTrue(event.eventTime >= event.downTime)
            }
        }
        assertEquals(emptyList<String>(), connection.textOperations)
    }

    /** Confirms Shift stays an event modifier instead of turning a letter into committed text. */
    @Test
    fun routesShiftedLetterThroughPhysicalModifierEvents() {
        val connection = RecordingConnection()
        val modifiers = ModifierState().apply { tapShift() }
        val stroke = requireNotNull(RawKeyRouter.strokeFor(Key.letter("n"), modifiers))

        val result = RawKeyDispatcher().send(connection, stroke.keyCode, stroke.requiredMetaState)

        assertTrue(result.fullyAccepted)
        assertEquals(
            listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_SHIFT_LEFT),
            connection.events.map { it.keyCode },
        )
        assertEquals(listOf(42, 49, 49, 42), connection.events.map { it.scanCode })
        val shiftMeta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        assertEquals(listOf(shiftMeta, shiftMeta, shiftMeta, 0), connection.events.map { it.metaState })
        assertEquals(emptyList<String>(), connection.textOperations)
    }

    /** Checks both Command keys retain their side throughout the full shortcut event sequence. */
    @Test
    fun sendsLeftAndRightCommandCWithMatchingModifierScanCodes() {
        val sides = listOf(
            Triple(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_LEFT_ON, 125),
            Triple(KeyEvent.KEYCODE_META_RIGHT, KeyEvent.META_META_RIGHT_ON, 126),
        )

        sides.forEach { (modifierCode, sideMeta, modifierScanCode) ->
            val connection = RecordingConnection()
            val meta = KeyEvent.META_META_ON or sideMeta
            val result = RawKeyDispatcher().send(connection, KeyEvent.KEYCODE_C, meta)

            assertTrue(result.fullyAccepted)
            assertEquals(
                listOf(modifierCode, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_C, modifierCode),
                connection.events.map { it.keyCode },
            )
            assertEquals(
                listOf(modifierScanCode, 46, 46, modifierScanCode),
                connection.events.map { it.scanCode },
            )
            assertEquals(
                listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP, KeyEvent.ACTION_UP),
                connection.events.map { it.action },
            )
            assertEquals(listOf(meta, meta, meta, 0), connection.events.map { it.metaState })
            assertEquals(emptyList<String>(), connection.textOperations)
        }
    }

    /** Shows transport success alone cannot prove a scan-code-consuming receiver handled a key. */
    @Test
    fun scanCodeReceiverIgnoresLegacyZeroScanButHandlesNewEvents() {
        val receiver = ScanCodeConnection()
        val legacy = KeyEvent(
            1L,
            1L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_N,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            RAW_REMOTE_EVENT_FLAGS,
            InputDevice.SOURCE_KEYBOARD,
        )

        assertTrue(receiver.sendKeyEvent(legacy))
        assertTrue(receiver.sendKeyEvent(KeyEvent.changeAction(legacy, KeyEvent.ACTION_UP)))
        assertEquals("", receiver.receivedLetters.toString())

        "nihao".forEach { character ->
            val stroke = requireNotNull(RawKeyMapper.forCharacter(character))
            assertTrue(RawKeyDispatcher().send(receiver, stroke.keyCode).fullyAccepted)
        }

        assertEquals("nihao", receiver.receivedLetters.toString())
        assertEquals(emptyList<String>(), receiver.textOperations)
    }

    /** Ensures a partially accepted shortcut never resends its already delivered key-down. */
    @Test
    fun partialRejectionDoesNotReplayAcceptedKeyEvents() {
        val connection = RecordingConnection { index -> index < 2 }
        val meta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON

        val result = RawKeyDispatcher().send(connection, KeyEvent.KEYCODE_C, meta)

        assertEquals(RawDispatchResult(2, 4), result)
        assertFalse(result.fullyAccepted)
        assertEquals(3, connection.events.size)
        assertEquals(
            1,
            connection.events.count { it.keyCode == KeyEvent.KEYCODE_C && it.action == KeyEvent.ACTION_DOWN },
        )
        assertEquals(emptyList<String>(), connection.textOperations)
    }

    /** Records framework calls while allowing a test to reject an individual event. */
    private open class RecordingConnection(
        private val acceptsEvent: (Int) -> Boolean = { true },
    ) : BaseInputConnection(View(RuntimeEnvironment.getApplication()), false) {
        val events = mutableListOf<KeyEvent>()
        val textOperations = mutableListOf<String>()

        /** Copies each event before returning the configured transport result. */
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            events.add(KeyEvent(event))
            return acceptsEvent(events.lastIndex)
        }

        /** Records unexpected text commits without using an Android editor. */
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            textOperations.add("commitText:$text")
            return true
        }

        /** Records unexpected composing updates without using an Android editor. */
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            textOperations.add("setComposingText:$text")
            return true
        }

        /** Records unexpected composing completion without using an Android editor. */
        override fun finishComposingText(): Boolean {
            textOperations.add("finishComposingText")
            return true
        }
    }

    /** Models one scan-code-based consumer; it does not reproduce or claim to test UU internals. */
    private class ScanCodeConnection : RecordingConnection() {
        val receivedLetters = StringBuilder()
        private val lettersByScanCode = mapOf(49 to 'n', 23 to 'i', 35 to 'h', 30 to 'a', 24 to 'o')

        /** Accepts transport calls but only consumes key-downs with recognized physical scan codes. */
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val accepted = super.sendKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN && event.source == InputDevice.SOURCE_KEYBOARD) {
                lettersByScanCode[event.scanCode]?.let(receivedLetters::append)
            }
            return accepted
        }
    }
}
