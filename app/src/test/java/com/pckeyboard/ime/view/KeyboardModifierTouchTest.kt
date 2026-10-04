package com.pckeyboard.ime.view

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.pckeyboard.ime.model.Key
import com.pckeyboard.ime.model.KeyType
import com.pckeyboard.ime.model.KeyboardLayout
import com.pckeyboard.ime.model.ModifierState
import com.pckeyboard.ime.theme.KeyboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Verifies real multi-pointer touch dispatch through the keyboard's nested row containers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardModifierTouchTest {
    private lateinit var keyboard: KeyboardView
    private val events = mutableListOf<Pair<Key, Int>>()
    private val pointers = linkedMapOf<Int, KeyView>()
    private var nextPointer = 0
    private val theme = KeyboardTheme.fromMap(emptyMap())
    private val layout = KeyboardLayout("test", "Test", listOf(listOf(
        Key.fn("⌘", KeyType.META, KeyEvent.KEYCODE_META_LEFT),
        Key.fn("⌘R", KeyType.META, KeyEvent.KEYCODE_META_RIGHT),
        Key.fn("⌥R", KeyType.ALT, KeyEvent.KEYCODE_ALT_RIGHT),
        Key.fn("⇧", KeyType.SHIFT),
        Key.fn("fn", KeyType.FN, KeyEvent.KEYCODE_FUNCTION),
        Key.letter("c", popup = "ç"),
        Key.letter("v"),
        Key.fn("←", KeyType.ARROW_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, repeatable = true),
    )))

    /** Attaches production views so Android performs real split-pointer routing and scheduling. */
    @Before
    fun createKeyboard() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        keyboard = KeyboardView(activity)
        keyboard.bind(layout, theme)
        keyboard.listener = object : KeyboardView.Listener {
            override fun onKey(key: Key, modifiers: ModifierState) {
                events += key to modifiers.toMetaState()
            }
            override fun onCursorMove(dx: Int, dy: Int) = Unit
            override fun onMenuAction(action: MenuAction) = Unit
            override fun onText(text: String) = Unit
            override fun onSuggestionPicked(word: String) = Unit
            override fun onClipboardEdit(text: String) = Unit
            override fun onOpenAppSettings() = Unit
        }
        activity.setContentView(keyboard)
        keyboard.measure(exact(1200), exact(600))
        keyboard.layout(0, 0, 1200, 600)
    }

    /** Holds Command across several keys and verifies release cannot arm a phantom Command. */
    @Test
    fun heldCommandSupportsConsecutiveKeysWithoutStickyRelease() {
        down("⌘")
        tap("c")
        tap("v")
        up("⌘")
        tap("c")

        assertEquals(listOf("c", "v", "c"), events.map { it.first.label })
        assertEquals(listOf(LEFT_COMMAND, LEFT_COMMAND, 0), events.map { it.second })
    }

    /** Saves the chord at letter-down even when the modifier finger lifts before the letter. */
    @Test
    fun modifierCanReleaseBeforeLetterWithoutLosingChord() {
        down("⌘")
        down("c")
        up("⌘")
        up("c")
        tap("v")

        assertEquals(listOf(LEFT_COMMAND, 0), events.map { it.second })
    }

    /** Combines right-sided Command and Option with Shift using separate simultaneous fingers. */
    @Test
    fun rightModifiersAndShiftCombineAtTouchDown() {
        down("⌘R")
        down("⌥R")
        down("⇧")
        tap("c")
        up("⇧")
        up("⌥R")
        up("⌘R")
        tap("v")

        assertEquals(
            KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON or
                KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON or
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
            events.first().second,
        )
        assertEquals(0, events.last().second)
    }

    /** Keeps sequential single-tap shortcuts and double-tap locks available for one-handed use. */
    @Test
    fun tapOnceArmsAndTwoTapsLockUntilTappedAgain() {
        tap("⌘")
        tap("c")
        tap("v")
        tap("⌘")
        tap("⌘")
        tap("c")
        tap("v")
        tap("⌘")
        tap("c")

        assertEquals(listOf(LEFT_COMMAND, 0, LEFT_COMMAND, LEFT_COMMAND, 0), events.map { it.second })
    }

    /** Reserves one-shot Command for the first key even when another finger overlaps its touch. */
    @Test
    fun overlappingActionKeysCannotShareOneShotModifier() {
        tap("⌘")
        down("c")
        down("v")
        up("c")
        up("v")

        assertEquals(listOf(LEFT_COMMAND, 0), events.map { it.second })
    }

    /** Releasing an older key must not consume a modifier tapped after that key went down. */
    @Test
    fun oldKeyReleasePreservesNewlyArmedOneShot() {
        down("c")
        tap("⌘")
        up("c")
        tap("v")
        tap("c")

        assertEquals(listOf(0, LEFT_COMMAND, 0), events.map { it.second })
    }

    /** Releasing Command or Shift immediately changes later repeats and sends no stale key on up. */
    @Test
    fun releasingModifierDuringArrowRepeatUpdatesEveryFollowingStroke() {
        listOf("⌘" to KeyEvent.META_META_ON, "⇧" to KeyEvent.META_SHIFT_ON).forEach { (label, flag) ->
            events.clear()
            down(label)
            down("←")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertTrue(events.isNotEmpty())
            assertTrue(events.all { it.second and flag != 0 })
            up(label)
            events.clear()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90))
            assertTrue(events.isNotEmpty())
            assertTrue(events.all { it.second == 0 })
            val count = events.size
            up("←")
            assertEquals(count, events.size)
            tap("v")
            assertEquals(0, events.last().second)
        }
    }

    /** Holding Shift after repetition begins changes the live chord without leaving sticky Shift. */
    @Test
    fun addingModifierDuringArrowRepeatActivatesAndConsumesItsHold() {
        down("←")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertEquals(0, events.single().second)
        down("⇧")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(45))
        assertTrue(events.last().second and KeyEvent.META_SHIFT_ON != 0)
        up("⇧")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(45))
        assertEquals(0, events.last().second)
        up("←")
        tap("v")
        assertEquals(0, events.last().second)
    }

    /** Cancels both pending characters and modifiers without forwarding stale touch-up events. */
    @Test
    fun cancellationDropsPendingChordAndModifierState() {
        down("⌘")
        down("c")
        send(MotionEvent.ACTION_CANCEL)
        pointers.clear()
        tap("v")

        assertEquals(listOf("v"), events.map { it.first.label })
        assertEquals(0, events.single().second)
    }

    /** Cancels Fn's menu timer once it participates in a chord, preserving its modifier flag. */
    @Test
    fun heldFnChordDoesNotOpenLongPressMenu() {
        down("fn")
        down("c")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(descendants(keyboard).any { it is ActionMenuView || it is KeyPopupView })
        up("c")
        up("fn")

        assertEquals(KeyEvent.META_FUNCTION_ON, events.single().second)
    }

    /** Rebuilds and hidden windows cancel repetition and invalidate all old view touches. */
    @Test
    fun rebuildAndHideCancelRepeatedAndPendingChords() {
        down("⌘")
        down("←")
        keyboard.bind(layout, theme)
        pointers.clear()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(events.isEmpty())
        keyboard.measure(exact(1200), exact(600))
        keyboard.layout(0, 0, 1200, 600)
        tap("c")
        assertEquals(0, events.single().second)
        events.clear()
        down("⌘")
        down("c")
        keyboard.visibility = View.GONE
        pointers.clear()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(events.isEmpty())
    }

    /** Detaching a held Fn key removes its delayed menu callback and all modifier state. */
    @Test
    fun detachCancelsFnLongPressAndOldTouchRelease() {
        down("fn")
        val oldFn = pointers.values.single()
        (keyboard.parent as ViewGroup).removeView(keyboard)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(descendants(keyboard).any { it is ActionMenuView })
        assertFalse(oldFn.isDown)
        assertEquals(0, oldFn.modifiers.toMetaState())
        assertTrue(events.isEmpty())
    }

    /** Sends a complete tap while leaving any other finger positions untouched. */
    private fun tap(label: String) {
        down(label)
        up(label)
    }

    /** Adds one uniquely identified pointer and dispatches the appropriate Android down action. */
    private fun down(label: String) {
        pointers[nextPointer++] = descendants(keyboard).filterIsInstance<KeyView>()
            .single { it.key.label == label }
        val index = pointers.size - 1
        send(if (index == 0) MotionEvent.ACTION_DOWN else {
            MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        })
    }

    /** Releases one pointer while retaining all other ongoing touch targets. */
    private fun up(label: String) {
        val index = pointers.values.indexOfFirst { it.key.label == label }
        check(index >= 0)
        val pointerId = pointers.keys.elementAt(index)
        send(if (pointers.size == 1) MotionEvent.ACTION_UP else {
            MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        })
        pointers.remove(pointerId)
    }

    /** Builds a real touchscreen event with coordinates relative to the keyboard root. */
    private fun send(action: Int) {
        val origin = IntArray(2).also { keyboard.getLocationOnScreen(it) }
        val properties = pointers.keys.map { id -> MotionEvent.PointerProperties().apply {
            this.id = id
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = pointers.values.map { key ->
            val position = IntArray(2).also { key.getLocationOnScreen(it) }
            MotionEvent.PointerCoords().apply {
                x = (position[0] - origin[0] + key.width / 2).toFloat()
                y = (position[1] - origin[1] + key.height / 2).toFloat()
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        val event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, pointers.size,
            properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            keyboard.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    /** Recursively traverses production containers without relying on layout implementation details. */
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()

    /** Returns an exact measure specification for deterministic touch hit testing. */
    private fun exact(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    companion object {
        private const val LEFT_COMMAND = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
    }
}
