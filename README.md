<div align="center">
  <img src="public/icons/icon.png" width="128" height="128" alt="FoldableKeyboard icon">
  <h1>FoldableKeyboard</h1>
  <p><strong>Virtual keyboard for remote vibe coding.</strong><br>Works best on foldable phones.</p>
  <p>
    <a href="#quick-start">Quick start</a> ·
    <a href="#features">Features</a> ·
    <a href="#development">Development</a> ·
    <a href="doc/README.zh-CN.md">简体中文</a>
  </p>
</div>

FoldableKeyboard brings desktop-style keys to Android, with a UU Remote overlay for controlling a remote Mac or Windows computer. It combines familiar modifiers and navigation keys with layouts suited to unfolded screens.

The UU overlay uses a locally registered HID keyboard through Shizuku. Ordinary Android input-method mode remains available, but it is a separate input path: selecting the keyboard in Android's input-method picker does **not** activate the UU HID overlay.

<a id="quick-start"></a>
## Quick start

### UU Remote keyboard

Requires **Android 14+**, **Shizuku 13+**, overlay permission, and a ROM that permits the system HID tool and UHID device access. Compatibility is not guaranteed on every phone or UU version.

1. Install an APK from this repository's [Releases](https://github.com/ababdotai/FoldableKeyboard/releases), if available, or build the debug APK below.
2. Install and start [Shizuku](https://shizuku.rikka.app/guide/setup/). Wireless debugging can start it without root; after a phone reboot, you will generally need to start it again.
3. Open FoldableKeyboard settings. In the UU keyboard section, grant Shizuku and **Display over other apps** permissions, then start the overlay.
4. Connect in UU, select **Computer keyboard / 电脑键盘**, close its **Input method / 输入法** panel, and focus the remote editor. Restore our overlay using its floating button or notification.
5. For remote Chinese composition, select a Pinyin input source **on the Mac**, then type `nihao`. The remote input method—not the Android keyboard—should compose the text.

For Mac shortcuts, select the **Mac** layout and leave Command compatibility enabled. In UU's key substitutions, enable **Right Ctrl → Cmd**, disable **Right Alt → Esc**, and disable UU's **Full keyboard control** accessibility feature. Actual Control remains separate from Command. See the [Mac shortcut guide](doc/mac-keyboard-shortcuts.md) for version-specific caveats.

### Ordinary Android input method

Enable FoldableKeyboard in Android's input-method settings and select it from the keyboard picker. This route supports normal text entry without Shizuku. RAW mode sends key events through the receiving app's input connection; it does not create an external keyboard and does not solve UU's letter-event filtering in its input-method panel.

<a id="features"></a>
## Features

- **Mac and Windows layouts:** Command, Option, Control, function keys, and navigation keys. Mac mode uses an inverted-T arrow cluster and PageUp/PageDn.
- **Foldable-friendly sizing:** adjustable height, margins, optional split layout, and a configurable function row.
- **Remote shortcuts:** a scrollable bar for select all, copy, paste, undo, redo, find, and save. These operate on the remote application's clipboard.
- **Magic Keyboard-inspired themes:** silver/white and graphite/black, alongside AMOLED and custom themes. Styling does not change key semantics.
- **Follow system appearance:** enable the switch under keyboard appearance to alternate between silver/white and graphite/black with Android's light/dark mode, for both the IME and UU overlay. It is off by default; turning it off restores your manual theme. Selecting a theme manually exits automatic mode. A cooperating terminal's session-provided palette still takes priority in its own IME session. IME appearance changes close temporary panels, including voice input, and refresh suggestions without replacing editor text.
- **Movable or docked overlay:** drag, collapse to a small restore button, or restore from the persistent notification.
- **Layered diagnostics:** distinguish permission, service, device-registration, focus, and HID-submission problems without logging typed content.
- **Ordinary IME tools:** multilingual layouts, offline suggestions, emoji, clipboard history, and a Space-key cursor-control surface. These are not all available through the HID overlay.

## Docking without covering the remote desktop

Docking uses the region **UU already reserves**; it does not resize UU or trigger Android IME avoidance.

1. Expand UU's own **Computer keyboard**, so its remote display sits above that keyboard region.
2. Open our overlay and choose **Dock / 停靠**. The top action bar scrolls horizontally if needed.
3. Drag the calibration strip, or use the height buttons, to align our window's upper edge with UU's keyboard boundary.

Portrait and landscape heights are remembered independently. Diagnostics replace the dock's key area instead of extending its height. Small docks hide the shortcut bar; collapsing reveals UU's original keyboard. Recalibrate when UU's layout changes. If UU has not reserved a keyboard region, our dock still covers the desktop.

## Input boundaries and privacy

HID registration or successful report submission does not prove that UU or the remote computer received a key. The working path has been confirmed in a user-tested UU/Mac session, not across all devices. See [setup, diagnostics, and acceptance notes](doc/uu-remote-keyboard.md).

The overlay checks UU's foreground window and hardware-keyboard focus before sending, and rejects uncertain states. HID follows system focus: the check cannot eliminate focus-switch races. Do not switch apps while typing. Only the primary display is supported. This is not a Bluetooth connection or a way to bypass Android permissions.

Diagnostics retain session-level aggregate counters, not text, key values, clipboard data, or device identifiers. Reports are generated only when explicitly exported and are not automatically uploaded. Ordinary IME clipboard history is a separate local feature. Update checks contact this repository's GitHub Releases.

Some combinations are intercepted by Android or UU. Supported Fn+arrow/delete combinations are translated locally; media keys, Touch ID, and a modifier held across several remote commands are not emulated.

<a id="development"></a>
## Development

Use **JDK 17** and the Android SDK:

```sh
./gradlew :app:assembleDebug       # Installable debug APK
./gradlew :app:testDebugUnitTest   # JUnit and Robolectric tests
./gradlew :app:lintDebug           # Android lint
./gradlew :app:assembleRelease     # Unsigned unless signing is configured
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Release signing uses `PCK_KEYSTORE_PASSWORD`, `PCK_KEY_PASSWORD`, and an existing `app/release.keystore` or `PCK_KEYSTORE_FILE`; `PCK_KEY_ALIAS` is optional. The application ID remains `com.pckeyboard.ime`; replacing an existing installation requires the same signing key.

Kotlin code lives under `app/src/main/java/com/pckeyboard/ime/`: `remote/` owns the overlay/HID path, `service/` the IME, and `view/`, `layout/`, and `theme/` the shared keyboard. Tests live in `app/src/test/java/`; read-only device probes are in `test/device/`. Automated tests do not establish end-to-end remote delivery.

## License and acknowledgements

Licensed under [GPL v3](LICENSE), building on [9hm2's upstream keyboard project](https://github.com/9hm2/pcKeyboard). Existing copyright and license notices remain applicable.

Frequency dictionaries derive from [FrequencyWords](https://github.com/hermitdave/FrequencyWords), OpenSubtitles-2018 lists (CC-BY-SA 4.0). Bigram models use the [Leipzig Corpora Collection](https://wortschatz.uni-leipzig.de/en/download), news-2020 corpora (CC BY). Hunspell assets come from [LibreOffice dictionaries](https://github.com/LibreOffice/dictionaries): hu_HU (magyarispell, GPL/LGPL/MPL), en_US (SCOWL), de_DE (frami, GPL 3), and es_ES (GPL 3/LGPL/MPL). The Hunspell implementation uses Apache Lucene (Apache License 2.0).
