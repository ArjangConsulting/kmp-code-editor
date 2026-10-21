# Native editor verification

This standalone, unpublished harness consumes the checked-out `:code-editor` through a composite
build. It provides the same small editor on Android and an Apple Silicon iOS simulator. The main
repository build does not include this app.

## Build

Use the repository's JDK/Android SDK requirements. iOS additionally needs Xcode and `xcodegen`.
Dependency versions come from the repository's version catalog.

```bash
verification/build-apps.sh android
verification/build-apps.sh ios
```

The application identifier on both platforms is `com.arjangconsulting.codeeditor.verification`.
Artifacts, relative to this directory:

- Android: `android-app/build/outputs/apk/debug/android-app-debug.apk`.
- iOS: `build/ios/Build/Products/Debug-iphonesimulator/EditorVerification.app`.

Install and drive builds with amoo on a leased emulator/simulator. The Reset, Empty, and Make read
only controls select the fixture. Text, Selection, and Folds expose the resulting document state.
The editor starts below controls so toolbar placement is checked with a nonzero screen offset.

## Checks

1. Reset. Tap within a line, type with the on-screen keyboard, select a suggestion, and verify Text
   and Selection. Use a built-in Japanese or Chinese keyboard for candidate replacement, saving and
   restoring the original keyboard list afterward. Scripted `type_text` alone does not cover IME
   composition.
2. Empty. Focus the editor, dismiss the keyboard, then select Probe composition. Expect Text to be
   exactly `日本`, with no duplicated fragments. The probe sends native composing replacements
   `に` → `にほ` → `日本` across frames using Android `InputConnection` or iOS `UITextInput`.
   Android hides Gboard before creating its probe connection so the real IME cannot finish the
   synthetic composition midway. Its extracted-text API does not export marked spans, so stages
   and committed text are checked instead. iOS also reports whether marked ranges remain present.
3. Reset. Long press a word. Check that the native toolbar appears near that word, select Copy,
   move the caret, and paste. Repeat Cut/Paste and verify exact text and selection. Test read-only
   mode: Copy and selection remain available; Cut/Paste do not edit the document.
4. With a region around the caret, press Cmd+Option+[ / ] on Apple platforms or Ctrl+Shift+[ / ]
   elsewhere. Check Folds and that the caret is on a visible header. Repeat in read-only mode.
   Some device drivers cannot synthesize bracket keys with modifiers; desktop UI tests cover the
   same key bindings when driver support is unavailable.
5. Inspect the native editable node in both populated and empty states. Its name is Code editor;
   the drawing layer must not hide it. Inspect the available fold/unfold actions. Check actual
   screen-reader traversal separately from the hierarchy: a semantics assertion does not prove
   spoken order or custom-action activation through an assistive technology.

The JVM UI tests independently inject platform composition commands and assert marked ranges,
invoke localized folding semantics, exercise fold shortcuts, and check toolbar root coordinates.
Use `./gradlew :code-editor:jvmTest` from the repository root for those checks.
