# Code editor architecture

```
CodeEditor (public composable)
 ├─ CodeEditorState ─── text, selection, version, folds, EditHistory (undo/redo)
 ├─ LanguageServiceController ── debounce · cancel · stale-drop · filter/rank ── LanguageService
 ├─ DocumentObserver / FoldingRangeUpdater (effects feeding the controller and fold ranges)
 └─ engine
     ├─ ComposeCodeEditor (all targets)
     │    EditorModel ── metrics, scroll, Highlighter, line layout cache, commands, find
     │    InputBridge ── hidden BasicTextField ↔ document window
     │    Gutter / TextArea draw passes, CompletionPopup, HoverPopup, FindBar, Scrollbars
     └─ RstaCodeEditor (desktop) ── RstaEditorPanel (RSyntaxTextArea in SwingPanel)
          SharedLexerTokenMaker · DiagnosticsParser · SharedFoldParser · ServiceAutoCompletion
```

## Layers

| File | Role | Tested in |
| --- | --- | --- |
| `TextModel.kt` | `TextPosition`/`TextRange`/`TextEdit`, `DocumentSnapshot` with a lazy `LineIndex` (LSP line breaks: `\n`, `\r\n`, `\r`), `applyTextEdits`, minimal `diffText`, surrogate-pair helpers. | `TextModelTest` |
| `TokenRules.kt`, `TokenRulesPresets.kt` | DSL and a line lexer. Multi-line state is an `Int` (`(ruleIndex + 1) << 8 | nestingDepth`), so the Compose engine caches it per line and RSyntaxTextArea stores it as a negative token type. | `LexerTest` |
| `Highlighter.kt` | Incremental, lazy line cache. An update keeps the common prefix lines, carries the common suffix as candidates, and re-lexes from the first changed line until a re-lexed line ends in the state its old successor started in. | `LexerTest` (`lexedLineCount`) |
| `EditingOps.kt` | Pure editing commands returning `EditOutcome` (replacements in original coordinates plus the resulting selection): newline with auto-indent, typing with auto-close/overtype/wrap/outdent, backspace, indent/outdent, toggle comment, word boundaries, bracket matching, folding computation, `FoldLayout` (row ↔ line), find, snippets. | `EditingOpsTest`, `FoldingTest` |
| `EditHistory.kt` | Undo stack of `(start, removed, inserted)` fragments with typing/deletion coalescing. | `CodeEditorStateTest` |
| `CodeEditorState.kt` | Snapshot-state holder. `apply(outcome)` is the single mutation path: it records history, bumps `version`, remaps folds, and notifies listeners synchronously (the value overload depends on that). | `CodeEditorStateTest` |
| `LanguageServiceController.kt` | Per-editor orchestration on the composition's scope. One job each for completion, resolve, analysis (diagnostics + semantic tokens + folding ranges), and hover. Every result is checked against the latest seen version before publishing to a `StateFlow`. | `LanguageServiceControllerTest` (virtual time) |
| `Formatting.kt` | Host-triggered `CodeEditorState.format`: snapshot, timed provider call off the UI thread, version check, edit preflight, and one undoable mutation on the caller's thread. Formatting is independent of automatic analysis. | `FormattingTest` |
| `JsonLanguageService.kt` | Shared strict JSON scanner for syntax diagnostics and formatting. Whitespace edits preserve all original tokens and selection positions within them. Cancellation checkpoints and a bounded nesting depth limit work on every target. Diagnostic messages are supplied through labels. | `JsonLanguageServiceTest` |
| `FuzzyMatcher.kt` | Subsequence matching with boundary, prefix, consecutive, and case bonuses; ranking. | `FuzzyMatcherTest` |
| `EditorKeymap.kt` | Key chord → command, platform aware. | `EditorKeymapTest` |
| `HighlightedText.kt` | `highlightedText`, `CodeSnippet`. | `HighlightedTextTest`, UI test |
| `CodeEditorSyntaxColors.kt` | Token palettes: `CodeEditorSyntaxColors` (one color per `TokenKind`), `CodeEditorSyntaxTheme` (light + dark variants chosen by background luminance), WCAG `contrastRatio`. Built-in palette values are generated offline to meet their contrast targets; the test enforces them. | `CodeEditorSyntaxColorsTest` |

## Compose engine

**Rendering.** Lines have a fixed height (measured from `"M"` in the text style), so a visual row's
top is `paddingTop + row × lineHeight − scrollY`, and folding is just a row → line map
(`FoldLayout`). Each draw pass lays out only visible rows. `EditorModel.lineLayout` caches a
`TextLayoutResult` per `LexedLine` instance; unchanged lines keep their instance across edits, so
typing re-measures one line. Tabs are expanded for display (`DisplayLine` maps columns both ways).
Every per-keystroke cost is O(document size) or less with small constants: string copy, line index,
prefix/suffix line comparison, and widest-line scan.

**Input.** A transparent `BasicTextField` exposes the editor's full bounds to accessibility while
its text layout is placed over the lines it mirrors. The drawing layer hides its redundant scroll
semantics so it cannot obscure the input field in the native accessibility tree. IME candidate
windows and iOS marked text follow the mirrored caret. Its
`InputTransformation`:

1. diffs the field's new text against the mirrored window;
2. classifies the change as a typed character, newline, backspace, forward delete, or raw
   replacement (paste or IME composition);
3. runs the matching editing command on the document;
4. rewrites the buffer to the new window inside the same transaction.

Selections spanning more than 60 lines detach (the field holds only the caret line and the first
keystroke replaces the selection). Key events for navigation and commands are intercepted at the
editor root, before the field, so the field only sees text input. A pointer layer above the field
owns mouse and touch: click, double and triple click, drag selection with auto-scroll, touch tap
and long press. Toolbar anchors are translated from text-area coordinates to root coordinates.
`Modifier.scrollable` handles wheel, trackpad, and fling. During active IME composition, key events
stay with the native field and marked-text updates preserve their selection without code helpers.
Keyboard folding and localized accessibility actions share the state holder's range selection;
collapsing relocates a selection that would otherwise become hidden.

**Overlays.** The completion list and hover are non-focusable `Popup`s anchored at the caret rect.
The find bar is an in-editor overlay with its own focus.

## RSyntaxTextArea engine

`RstaEditorPanel` intercepts document mutations with a `DocumentFilter` and records each replacement
in `CodeEditorState.apply` as one history entry and one notification. It pushes state changes back
as minimal `doc.replace` calls; those bypass input normalization. Re-entrancy flags stop the two directions from echoing each other.
RSTA's undo/redo, Ctrl+Space, Cmd/Ctrl+F, Enter, Up/Down, and Tab are rebound in the text area's
`InputMap` so that history, completion, find, and the single-line behaviors use the shared
implementations. The language is installed by building an `RSyntaxDocument` with a
`TokenMakerFactory` that returns `SharedLexerTokenMaker`, and by registering `SharedFoldParser`
under the style key `text/x-kmpcodeeditor-<name>`. See the README for why this engine is opt-in.
