# Verification evidence

## Automated checks

The complete matrix passed: 264 JVM tests, 191 Android host tests, and 191 iOS simulator tests;
zero failures/errors. JVM totals include opt-in benchmark entry points that return without running
when their benchmark environment variables are absent.

```bash
./gradlew :code-editor:jvmTest :code-editor:allTests :code-editor:checkKotlinAbi :code-editor:assemble :sample:compileKotlinJvm
verification/build-apps.sh all
```

The first incremental Android host compilation hit a Kotlin dirty-lookup-cache error for
`FoldLayout`; one recovery invocation disabled incremental compilation. The final unmodified
command above also passed with normal incremental settings. No repository-wide compiler setting
was changed. Android APK and Apple Silicon iOS simulator
harness builds succeeded. The iOS harness now declares its generated Xcode scheme explicitly.
The public ABI baselines include `CodeEditorOptions.softWrap`.

Focused regression coverage:

- Complete geometry and navigation beyond 10,000 characters, 50,000-character JSON string, and
  actual navigation/hit testing/edit/undo at the end of a 5 MB line.
- Surrogate pairs, combining marks, accented text, tabs, CRLF, exact source offset mapping, and
  complete tile coverage with bounded native paragraphs.
- Visual-row caret movement, selection, Home/End affinity at wrap boundaries, viewport resize,
  wrap toggling after horizontal scroll, and zero horizontal overflow for fitting content.
- Wrapped folding headers/placeholders, logical source line numbers, and relocation of a hidden
  caret after a public fold action.
- Find navigation, selection, copy/paste, diagnostic squiggles and hover beyond character 15,000;
  diagnostics reflow onto different wrapped rows after resize.
- Undo/redo of many formatting fragments, detached selections larger than the native input
  window, exact restoration of Unicode/CRLF source, and selection preservation when formatting
  returns an identical replacement.
- Native composing replacements in the desktop input adapter, including composition in a long
  wrapped line beyond the old limit; lexer cancellation inside long escaped strings and numbers.
- Read-only selection/copy semantics, localized editor label, editable/read-only Tab traversal,
  reverse traversal, and Compose dialogs, menus, and diagnostic tooltips over the editor.

## Reviewed desktop screenshots

These are actual headless Compose captures inspected for layout and decoration placement, not
screen-reader evidence. Black space outside popup roots is the test scene's unused background.

| Capture | Evidence |
| --- | --- |
| Tail beyond 15,000, Unicode, selection, diagnostic | [unwrapped](evidence/unwrapped-after-15000.png) |
| End of 5 MB line | [5 MB tail](evidence/unwrapped-5MB-tail.png) |
| Wrapped source and diagnostic | [wide](evidence/wrapped-wide.png) |
| Same source after resize | [narrow](evidence/wrapped-narrow.png) |
| Folded wrapped header with logical gutter numbers | [fold](evidence/wrapped-fold-header.png) |
| Tooltip beyond old limit | [tooltip](evidence/tooltip-after-15000.png) |
| Dialog and menu | [dialog](evidence/dialog-over-editor.png), [menu](evidence/menu-over-editor.png) |

## Amoo native observations

The follow-up used the current harness built with `verification/build-apps.sh all`, Android 15
API 35 emulator 5556, an iOS 27.2 iPad simulator, and an iOS 27.2 iPhone 18 Pro simulator. Emulator
5554 was left alone. Screenshots below were inspected for the actual captured layout. These are
bounded observations, not a comprehensive accessibility pass.

| Check | iOS | Android |
| --- | --- | --- |
| Synthetic native composition に→にほ→日本 | Previously passed; native marked ranges and exact text | Previously passed; native InputConnection stages and exact text |
| Long JSON tail and unchanged source when toggling wrapping | Previously passed | Passed; 15,023-character fixture |
| Individual native key insertion beyond 15,000 | Previously passed, wrapped and unwrapped | Passed; abc advanced length/selection to 15,026 unwrapped, def to 15,029 wrapped |
| Editable native Copy/Paste and Cut/Paste | Passed; exact greet suffix removed and restored, caret 74→69→74 | Passed; message copied into Empty, length/caret 7; Cut/Paste restored exactly |
| Read-only Copy and non-editing menu | Passed; Copy/Select All only, unchanged 72-character source; copied greet pasted into editable source | Passed; Copy/Select all only (Cut and Paste absent), source unchanged after Copy then Delete, copied greet pasted with native Ctrl+V into Empty (Text: greet, length 5) |
| Rotation preserves wrapped source/tail | Previously passed | Untested |
| Real non-Latin keyboard candidate | Passed; Japanese native n/i/h/o/n keys and candidate tap committed 日本, selection 2..2 | Passed; Gboard composing input, conversion/candidate selection committed にほん, length/caret 3 |
| Actual assistive-technology traversal and speech | Passed within the bounded VoiceOver journey described below | Not established; TalkBack startup failed three times, and a retry was blocked by the `amoo doctor` preflight |

Android read-only clipboard captures: [menu](evidence/native-20261106/android-final/readonly-menu.png)
and [pasted result](evidence/native-20261106/android-final/readonly-copied-pasted.png); the
[action report](evidence/native-20261106/android-final/android-final-action-reports.json) also retains
the three failed TalkBack attempts (notification-permission prompt, no initial app-owned focus,
dropped companion transport with cleanup unknown).
Android captures: [unwrapped native insertion](evidence/native-20261106/android/long-json-insert-unwrapped.png),
[wrapped native insertion](evidence/native-20261106/android/long-json-wrap-insert.png),
[editable clipboard result](evidence/native-20261106/android/clipboard-cut-paste.png),
[Japanese composition](evidence/native-20261106/android/japanese-composing.png), and
[committed candidate](evidence/native-20261106/android/japanese-candidate-commit.png).
The [Android observations](evidence/native-20261106/android/final-observations.json) and
[paginated raw reports](evidence/native-20261106/android/android-native-action-reports.json) retain
exact postconditions and failed TalkBack startup. Both added Japanese Gboard layouts were removed,
restoring English (US) QWERTY; the managed sessions ended.
The tests used individual native keys for insertion; Android `type_text` whole-field assignment is
not counted as native typing evidence.

iOS clipboard captures: [read-only menu](evidence/native-20261106/ios/readonly-menu.png),
[read-only Copy into editable source](evidence/native-20261106/ios/readonly-paste-result.png),
[after Cut](evidence/native-20261106/ios/editable-after-cut.png),
[after Paste](evidence/native-20261106/ios/editable-after-paste.png).
A first exploratory paste received a pre-existing large JSON clip while desktop clipboard tests
were running. Repeating the native Copy/Paste sequence after those tests stopped established the
exact transferred text. Avoid shared host clipboard writes during simulator clipboard journeys.

The iOS Japanese retry used actual system keyboard candidates:
[candidate strip](evidence/native-20261106/ios-ime/candidates.png),
[committed source](evidence/native-20261106/ios-ime/committed.png), and
[committed hierarchy](evidence/native-20261106/ios-ime/committed-hierarchy.json).
The first Settings attempt timed out in a system overlay; binding Preferences directly on a
separate leased simulator enabled the candidate journey. Synthetic composition was not substituted
for this check. The original English (US), Persian and Emoji keyboard list was restored; see the
[IME report](evidence/native-20261106/ios-ime/report.json) and
[restoration assertion](evidence/native-20261106/ios-ime/restoration-assertion.json). The runtime was
released and its companion stopped.

The [VoiceOver journey](evidence/native-20261106/ios/voiceover-journey.json) retained actual spoken
output while VoiceOver was enabled: Reset, Empty, Make read only, Long JSON, Wrap, Probe composition,
Probe result, Selection, Folds, then “Code editor Text field Is editing Line 1, column 1 Actions
available”. A targeted double tap changed caret metadata to line 2, column 17, and speech recovered;
all six authored expectations passed. The role export was unsupported when requested separately.
The gesture is an ordinary targeted XCTest gesture during a VoiceOver lifetime; it does not certify
screen-reader activation semantics, rotor or custom actions. VoiceOver's original off state was
restored. The [raw session report](evidence/native-20261106/ios/amoo-session-report.json) retains
exploratory failures as well as the final passing journey; its pre-cleanup summary is not an overall
coverage verdict.

Android TalkBack spoken traversal remains a gap. Three attempts failed in tooling (a notification
permission prompt, no initial app-owned focus, a dropped companion transport with cleanup unknown),
and a later retry was blocked before setup because `amoo doctor` reported stale foreign MCP servers
and an unresponsive companion on another emulator. Those are tooling findings, not editor
accessibility defects. Rerun after the owners of those clients stop them. Earlier reviewed iOS long-line captures remain available in this directory:
[iOS wrapped native keys](evidence/ios-wrapped-native-keys.png),
[iOS unwrapped native keys](evidence/ios-unwrapped-native-keys.png), and
[iOS rotation](evidence/ios-landscape.png).

## Consumption notes

Use the Compose engine with `JsonRules` plus `JsonLanguageService()` for JSON bodies, or `PlainText`
without a service for raw text. Set `CodeEditorOptions(softWrap = true)` when wrapping is desired;
the default remains unwrapped. `TabBehavior.MOVE_FOCUS` suits form fields; read-only selection/copy
remain available. Formatting is explicit and undoable through `state.format`.

Existing source callers retain their behavior. Adding a data-class constructor field changes
binary constructor/copy signatures, so binary consumers need recompilation; Studio's explicitly enabled
source fallback recompiles from source. Wrapped rows do not change logical ranges or
persisted text. Complex bidi shaping across tiles, oversized combining sequences, and the existing
first 10,000 find matches cap are documented limitations.
