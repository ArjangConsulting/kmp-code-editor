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
2. Empty. Focus the editor, then select Probe composition. On Android, dismiss the keyboard first;
   on iOS, keep the native text responder active. Expect Text to be exactly `日本`, with no duplicated
   fragments. The probe sends native composing replacements
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

## Long-line and wrapping fixture

Long JSON loads a 15,000-character string and reveals its tail without formatting. Wrap/Unwrap
changes only visual layout. Check the marker at the tail in each mode, edit it, select/copy/paste,
and rotate or resize the viewport. Selection, Text length, and Tail expose exact source results.
Run the composition probe on Empty as above; separately type with a real native IME near the
long-line tail. Verify that keyboard dismissal and read-only selection remain usable. Native
hierarchy checks establish labels and actions, not spoken screen-reader navigation.

## Evidence and input paths

Record native source postconditions, including Text, Selection, Text length and Tail, after each
insertion or clipboard action. On Android, Amoo `type_text` assigns the mirrored field as a whole;
use individual native `press_key` calls or actual on-screen keys to establish caret insertion.
A synthetic composition probe proves the native composing-replacement path, not candidate selection
through an installed non-Latin keyboard. For mobile clipboard checks, avoid concurrent host
clipboard tests because simulator clipboard sharing can replace the copied fixture between actions.

When configuring a built-in keyboard, save the original list and restore it afterward. Settings is
an app: bind `com.apple.Preferences` on iOS or the appropriate Android Settings package when navigating
its controls. System scope is for system-owned overlays. After a timeout, inspect the current app
and postcondition before repeating the action.

Capture actual VoiceOver/TalkBack speech and traversal with the assistive technology enabled, then
restore its original state. An authored journey with a targeted XCTest gesture establishes only
that gesture and its observed speech/postconditions; it does not certify screen-reader activation,
rotor or custom actions. Keep unsupported role exports and missing speech separate from app defects.
The current matrix and reviewed captures are in [benchmarks/VERIFICATION.md](../benchmarks/VERIFICATION.md).
