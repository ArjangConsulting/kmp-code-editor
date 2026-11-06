# Verification evidence

## Automated checks

The complete matrix passed: 258 JVM tests, 190 Android host tests, and 190 iOS simulator tests;
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

Managed simulator/emulator sessions were used and released. The busy Android emulator 5554 was
left alone; a separate Medium_Phone_API_35 emulator 5556 was leased for the recheck. iOS ran on an
iPad simulator with iOS 27.2. Native evidence was inspected separately from the desktop captures.

| Check | iOS | Android |
| --- | --- | --- |
| Synthetic native composition に→にほ→日本 | Passed; `UITextInput` marks true,true, exact text 日本 | Passed; `InputConnection` stages and exact text 日本, selection 2 |
| 15,023-character JSON tail, wrapping, unchanged source length | Passed | Not established by completed evidence |
| Native insertion beyond 15,000, wrapped and unwrapped | Passed with individual native A/B/C keys and exact selection/length | Untested with native keystrokes |
| Read-only Copy/Select All affordance; no typing mutation | Observed | Untested |
| Rotation preserves wrapped source/tail | Passed | Untested |
| Actual non-Latin keyboard candidate journey | Untested | Untested |
| Actual VoiceOver/TalkBack traversal and spoken output | Untested | Untested |

Native screenshots: [iOS composition](evidence/ios-composition.png),
[iOS wrapped insertion](evidence/ios-wrapped-native-keys.png),
[iOS unwrapped insertion](evidence/ios-unwrapped-native-keys.png),
[Android composition](evidence/android-composition.png),
[iOS rotation](evidence/ios-landscape.png), [iOS read-only menu](evidence/ios-readonly-menu.png).

The first iOS probe failed because the verification flow dismissed the keyboard, resigning the
native responder before probing. Keeping it active passed. Amoo's iOS `type_text` retaps the field
before typing; individual `press_key` calls were used to verify exact caret→insertion mapping.
For wrapped insertion, caret 15022 became selection 15025 and length 15026 after ABC. For unwrapped
end insertion, caret 15023 became selection 15026 and length 15026.

Android `type_text` uses a whole-field accessibility text assignment rather than native insertion.
That assignment replaced the mirrored input window, so it is not evidence for native keystroke
insertion or an app typing regression. Companion timeouts and the bounded session budget prevented
completion with individual keys. Android long-line typing, mobile read-only clipboard journeys,
real IME candidate interaction, and actual assistive-technology journeys remain coverage gaps.
The native report is therefore partial, not a comprehensive accessibility pass.

## Consumption notes

Use the Compose engine with `JsonRules` plus `JsonLanguageService()` for JSON bodies, or `PlainText`
without a service for raw text. Set `CodeEditorOptions(softWrap = true)` when wrapping is desired;
the default remains unwrapped. `TabBehavior.MOVE_FOCUS` suits form fields; read-only selection/copy
remain available. Formatting is explicit and undoable through `state.format`.

Existing source callers retain their behavior. Adding a data-class constructor field changes
binary constructor/copy signatures, so binary consumers need recompilation; Studio's sibling
composite build already recompiles from source. Wrapped rows do not change logical ranges or
persisted text. Complex bidi shaping across tiles, oversized combining sequences, and the existing
first 10,000 find matches cap are documented limitations.
