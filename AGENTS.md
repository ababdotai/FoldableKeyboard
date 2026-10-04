# Repository Guidelines

## Project Structure & Module Organization

This repository is a single-module Android application. Kotlin sources live in `app/src/main/java/com/pckeyboard/ime`: `service/` owns the IME lifecycle, `remote/` provides the Shizuku/HID overlay, `view/` contains keyboard UI, `layout/` defines layouts, and `dictionary/` implements suggestions. Android resources are in `app/src/main/res`; dictionaries and models are in `app/src/main/assets`. Tests mirror production packages under `app/src/test/java`; device probes live in `test/device/`. Keep contributor documentation in `doc/` and local investigation artifacts in ignored `temp/`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper with JDK 17 and an Android SDK:

- `./gradlew :app:assembleDebug` builds the signed debug APK.
- `./gradlew :app:testDebugUnitTest` runs all local JUnit tests.
- `./gradlew :app:lintDebug` runs Android lint and writes reports under `app/build/reports/`.
- `./gradlew :app:assembleRelease` creates a minified APK. To sign it, provide `PCK_KEYSTORE_PASSWORD`, `PCK_KEY_PASSWORD`, and an existing `app/release.keystore` or `PCK_KEYSTORE_FILE`; `PCK_KEY_ALIAS` is optional.

Open the project root in Android Studio to install and exercise the IME on an emulator or physical device.

## Coding Style & Naming Conventions

Follow the official Kotlin style configured in `gradle.properties`, with four-space indentation and trailing commas in multiline declarations where they improve diffs. Use `PascalCase` for classes and objects, `camelCase` for functions and properties, `UPPER_SNAKE_CASE` for constants, and `snake_case` for Android resource names. Keep packages beneath `com.pckeyboard.ime`. Add concise English KDoc to new public or non-obvious functions; describe behavior and constraints rather than restating code. Run Android lint before submitting changes.

## Testing Guidelines

Tests use JUnit 4 and Robolectric and should be named `*Test.kt` under the matching package in `app/src/test/java`. Prefer focused pure-logic tests; use Robolectric for Android views and lifecycle behavior. There is no enforced coverage threshold, but bug fixes should include regression tests. Host tests cannot prove Android regex compatibility or remote key reception: use device validation and the acceptance checklist in `doc/uu-remote-keyboard.md` for HID changes.

## Commit & Pull Request Guidelines

Recent history favors short, imperative subjects such as `Fix suggestion pick splitting words...`. Use Angular-style Conventional Commits for new work, for example `fix(dictionary): preserve words at mid-cursor`. Keep each commit scoped to one concern. Pull requests should explain user-visible behavior, list validation commands, and link relevant issues. Include screenshots or recordings for keyboard layout, theme, settings, or gesture changes, and call out generated asset or APK size changes.
