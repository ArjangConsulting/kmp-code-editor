# Code editor architecture

```
CodeEditor (public composable)
 ├─ CodeEditorState ─── text, selections (primary + secondary), version, folds, EditHistory (undo/redo)
 ├─ LanguageServiceController ── debounce · cancel · stale-drop · filter/rank ── LanguageService
 ├─ DocumentObserver / FoldingRangeUpdater (effects feeding the controller and fold ranges)
 └─ engine
     ├─ ComposeCodeEditor (all targets)
     │    EditorModel ── metrics, scroll, Highlighter, VisualLayout, tile cache, commands, FindSession
     │    InputBridge ── hidden BasicTextField ↔ document window
     │    Gutter / TextArea draw passes, CompletionPopup, HoverPopup, FindReplaceBar, Scrollbars
     └─ RstaCodeEditor (desktop) ── RstaEditorPanel (RSyntaxTextArea in SwingPanel)
          SharedLexerTokenMaker · DiagnosticsParser · SharedFoldParser · ServiceAutoCompletion
```

## Layers

| File | Role | Tested in |
| --- | --- | --- |
| `CodeEditor.kt` | The controlled-value overload suppresses its synchronous text callback only while applying an external value. Editor changes still notify the host and enter a bounded queue of reported values that protects newer typing from delayed parent echoes. | `ControlledValueEditorUiTest` |
| `TextModel.kt` | `TextPosition`/`TextRange`/`TextEdit`, `DocumentSnapshot` with a lazy `LineIndex` (LSP line breaks: `\n`, `\r\n`, `\r`), `applyTextEdits`, minimal `diffText`, surrogate-pair helpers. | `TextModelTest` |
| `TokenRules.kt`, `TokenRulesPresets.kt` | DSL and a line lexer, with an internal tokenizer hook for YAML. Default multi-line state is an `Int` (`(ruleIndex + 1) << 8 | nestingDepth`), so the Compose engine caches it per line and RSyntaxTextArea stores it as a negative token type. | `LexerTest` |
| `YamlLexer.kt` | Dedicated incremental YAML scanner selected by `YamlRules`. Positive line state packs flow depth/quote mode or block scalar indentation/inference mode; both engines consume it through `TokenRules.tokenizeLine`. | `YamlLexerTest`, `RstaEditorPanelTest` |
| `Highlighter.kt` | Incremental, lazy line cache. An update keeps the common prefix lines, carries the common suffix as candidates, and re-lexes from the first changed line until a re-lexed line ends in the state its old successor started in. | `LexerTest` (`lexedLineCount`) |
| `EditingOps.kt` | Pure editing commands returning `EditOutcome` (replacements in original coordinates plus the resulting selection): newline with auto-indent, typing with auto-close/overtype/wrap/outdent, backspace, indent/outdent, toggle comment, word boundaries, bracket matching, folding computation, `FoldLayout` (row ↔ line), find, snippets. | `EditingOpsTest`, `FoldingTest` |
| `VisualLayout.kt` | Sparse advance checkpoints, bounded glyph tiles, logical-to-visual row mapping, tab expansion, Unicode cluster boundaries, fold header wrapping, and reuse of unchanged line geometry. | `VisualLayoutTest`, `LargeDocumentEditorUiTest` |
| `FindReplace.kt` | Literal and regular-expression search (`findInText`), replacement template expansion, single and replace-all `Replacement`s, and `FindSession`, the find bar state shared by both engines through a small host interface. Regex searches go through `expect fun regexSearch`: on the JVM and Android, Kotlin `Regex` reads the text through a deadline-checking `CharSequence` (a runaway pattern times out) and a stack overflow is caught as "too complex"; on iOS, `NSRegularExpression` (ICU) is used because Kotlin/Native's engine overflows the thread stack, unrecoverably, on lines of a few thousand characters, while ICU reports an internal error and its progress callback enforces the time budget; replace all fails as a whole rather than applying a partial result. | `FindReplaceTest`, `FindReplaceTimeoutTest` |
| `MultiCursor.kt` | `Selections` (primary plus secondary), normalization that merges overlapping or meeting selections, `combineOutcomes` (runs an editing command at every selection against one snapshot, skips edits that would overlap an earlier selection's, and shifts each result by earlier length changes), add-next-occurrence, and caret toggling. | `MultiCursorTest`, `MultiCursorEditorUiTest` |
| `EditHistory.kt` | Undo stack of `(start, removed, inserted)` fragments with typing/deletion coalescing; entries store every selection before and after, and a multi-cursor step coalesces when each change continues its counterpart. | `CodeEditorStateTest`, `MultiCursorTest` |
| `CodeEditorState.kt` | Snapshot-state holder. Setting the primary selection alone drops secondary selections, so single-caret code paths collapse multi-cursor state by default. `apply(outcome)` is the single mutation path: it records history, bumps `version`, remaps folds, and notifies listeners synchronously (the value overload depends on that). | `CodeEditorStateTest` |
| `LanguageServiceController.kt` | Per-editor orchestration on the composition's scope. One job each for completion, resolve, analysis (diagnostics + semantic tokens + folding ranges), and hover. Service replacement or `languageServiceRevision` changes advance an internal generation, cancel jobs, clear published results, and refresh analysis. Requests check their generation and document version before publishing to a `StateFlow`; completion also checks the caret. | `LanguageServiceControllerTest` (virtual time) |
| `CompletionEdits.kt` | Strict in-document position check for backend candidates, plus the shared overlap check. The controller offers only candidates whose mapped replacements (including the implicit prefix replacement) would apply, and acceptance revalidates resolved candidates the same way before either engine changes text. Rejected acceptance closes the popup, applies nothing, consumes the key, and logs an error. | `LanguageServiceControllerTest`, `BackendLanguageServiceTest` |
| `KeywordLanguageService.kt` | Stateless local completion over `TokenRules.keywordKinds`, shared with highlighting. Replays lexer state through the caret line and probes the insertion position to suppress strings/comments/escapes; suppresses member-access prefixes. Returns the full vocabulary for controller filtering. | `KeywordLanguageServiceTest`, `ReplEditorUiTest` |
| `CompletionProtocol.kt`, `TextRangeSerializer.kt` | Versioned JSON envelopes reuse serializable completion/text models. Requests carry session/runtime identity and full document coordinates; responses echo correlation fields. The range serializer rejects reversed wire ranges. Transport and process lifecycle stay in the host. | `CompletionProtocolTest` |
| `BackendLanguageService.kt` | Captures runtime context, generates request IDs, calls native/add-on providers, verifies correlation, rejects invalid edit candidates, and optionally falls back on provider failures. Cancellation propagates; the existing controller handles document/caret/generation staleness. | `BackendLanguageServiceTest`, `ToolReplEditorUiTest` |
| `ToolCommandCatalog.kt`, `ToolCompletionBackend.kt` | External metadata models and MCP tools/list import; a partial-input scanner for command/argument/enum completion with quoted values and an optional runtime value provider. Submission/execution is independent. | `ToolCompletionBackendTest`, `ToolReplEditorUiTest` |
| `Formatting.kt` | Host-triggered `CodeEditorState.format`: snapshot, timed provider call off the UI thread, version check, edit preflight, and one undoable mutation on the caller's thread. Formatting is independent of automatic analysis. | `FormattingTest` |
| `JsonLanguageService.kt` | Shared strict JSON scanner for syntax diagnostics and formatting. Whitespace edits preserve all original tokens and selection positions within them. Cancellation checkpoints and a bounded nesting depth limit work on every target. Diagnostic messages are supplied through labels. | `JsonLanguageServiceTest` |
| `YamlLanguageService.kt` | SnakeYAML Engine KMP parser/emitter events for explicit YAML formatting and syntax diagnostics, without object construction or alias expansion. Retains comments, scalar values, tags, anchors, and document boundaries; emits a minimal single replacement. Event checkpoints support cancellation; input length and nesting are bounded. Diagnostic marks are converted from code points to UTF-16. | `YamlLanguageServiceTest` |
| `FuzzyMatcher.kt` | Subsequence matching with boundary, prefix, consecutive, and case bonuses; ranking. | `FuzzyMatcherTest` |
| `EditorKeymap.kt` | Key chord → command, platform aware. | `EditorKeymapTest` |
| `HighlightedText.kt` | `highlightedText`, `CodeSnippet`. | `HighlightedTextTest`, UI test |
| `CodeEditorSyntaxColors.kt` | Token palettes: `CodeEditorSyntaxColors` (one color per `TokenKind`), `CodeEditorSyntaxTheme` (light + dark variants chosen by background luminance), WCAG `contrastRatio`. Built-in palette values are generated offline to meet their contrast targets; the test enforces them. | `CodeEditorSyntaxColorsTest` |

## Compose engine

**Rendering.** `FoldLayout` first removes hidden logical lines; `VisualLayout` maps the remaining
lines to fixed-height visual rows. Wrapping uses glyph advances and logical tab stops, without
changing UTF-16 source coordinates. Sparse checkpoints every 256 units bound hit-testing work;
row and line prefix indices support binary search. Double-precision advances avoid accumulating
rounding error along multi-megabyte lines. Unchanged lines share immutable geometry arrays across
versions; rebased line objects reference only the current source. Resize invalidates wrapping.

Only tiles intersecting the viewport are shaped into `TextLayoutResult`s. Selection, find,
diagnostics, brackets, folding placeholders, caret placement, and pointer mapping share the same
row geometry. The bounded cache keys include version, source slice, syntax, semantics, and style.
Ligatures and kerning are disabled so measured advances match tile boundaries. Complex bidi
shaping across tiles and oversized grapheme sequences remain limitations.

Long lines (>4,096 units) and documents over 10,000 lines use an incremental background
highlighter guarded by a mutex. Replacement cancels obsolete work; cooperative lexer checkpoints
and version/language checks prevent stale publication. Initial tiles can render plain text until
colors arrive. Folding uses `collectLatest`, a background dispatcher for large documents, lexer
checkpoints, and version checks. Normal typing avoids unnecessary token lookup when the character
cannot trigger smart pairing. Small documents retain synchronous incremental highlighting.

Text remains string-backed. A mutation assembles a builder and immutable result in one forward
pass, builds the primitive line index lazily, and compares line content for geometry/highlighting
reuse. Multi-edit application and
history replay append fragments in one pass rather than shifting/replacing a full string for each
formatting edit. Explicit formatting validates and applies once. The benchmark suite records both
model operation latency and an actual captured first Compose frame.

**Input.** A transparent `BasicTextField` exposes the editor's full bounds to accessibility while
its native caret is placed over the rendered caret, including wrapping and horizontal scrolling. The drawing layer hides its redundant scroll
semantics so it cannot obscure the input field in the native accessibility tree. IME candidate
windows and iOS marked text follow the mirrored caret. Its
`InputTransformation`:

1. diffs the field's new text against the mirrored window;
2. classifies the change as a typed character, newline, backspace, forward delete, or raw
   replacement (paste or IME composition);
3. runs the matching editing command on the document;
4. rewrites the buffer to the new window inside the same transaction.

The window is capped at 4,096 UTF-16 units with surrogate-boundary adjustments. It stays anchored
while the caret is safely inside, preserving native marked text during composition. Selections
spanning more than 60 lines or the character budget detach (the field holds caret context and the first
keystroke replaces the selection). Key events for navigation and commands are intercepted at the
editor root, before the field, so the field only sees text input. A pointer layer above the field
owns mouse and touch: click, double and triple click, drag selection with auto-scroll, touch tap
and long press. Toolbar anchors are translated from text-area coordinates to root coordinates.
`Modifier.scrollable` handles wheel, trackpad, and fling. During active IME composition, key events
stay with the native field and marked-text updates preserve their selection without code helpers.
Keyboard folding and localized accessibility actions share the state holder's range selection;
collapsing relocates a selection that would otherwise become hidden.

**Overlays.** The completion list and hover are non-focusable `Popup`s anchored at the caret rect.
**Multiple cursors.** The primary selection drives the input window, IME, completion, hover, and
scrolling; secondary selections exist only in the state. Editing commands go through
`combineOutcomes`, movement maps every selection, and drawing finds the selections in each visible
row by binary search. Text from the native field that replaces the primary selection is inserted at
every selection; IME composition and other raw field edits apply at the primary caret with the
others mapped through them (composition collapses them).

The find and replace bar is an in-editor overlay with its own focus. Replace all is one
`EditOutcome`, so it is a single mutation and a single undo entry.

## RSyntaxTextArea engine

`RstaEditorPanel` intercepts document mutations with a `DocumentFilter` and records each replacement
in `CodeEditorState.apply` as one history entry and one notification. It pushes state changes back
as minimal `doc.replace` calls; those bypass input normalization. Re-entrancy flags stop the two directions from echoing each other.
RSTA's undo/redo, Ctrl+Space, Cmd/Ctrl+F, Enter, Up/Down, and Tab are rebound in the text area's
`InputMap` so that history, completion, find, and the single-line behaviors use the shared
implementations. The language is installed by building an `RSyntaxDocument` with a
`TokenMakerFactory` that returns `SharedLexerTokenMaker`, and by registering `SharedFoldParser`
under the style key `text/x-kmpcodeeditor-<name>`. See [Engines](ENGINES.md) for why this engine is opt-in.

## Sample host

The unpublished `sample` module depends on KMPComponents for its theme, theme modes, spacing
tokens, and section cards. The sample uses the library's `ThemeModeSelector` to select the
`KmpTheme` mode.
`KmpTheme` supplies Material roles that flow into `CodeEditorDefaults.colors`; syntax palettes
remain editor-specific. KMPComponents is absent from the published `code-editor` dependency graph.
The sample uses the catalog artifact by default; `useLocalKmpComponents` enables composite-build
substitution for a sibling checkout, with `kmpComponentsDir` overriding its location.
