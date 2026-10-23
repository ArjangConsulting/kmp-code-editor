# KMP Code Editor

A Compose Multiplatform code editor for Android, desktop (JVM), and iOS with syntax highlighting,
folding, find, undo/redo, and pluggable language intelligence shaped after the Language Server
Protocol (LSP) 3.17.

```kotlin
implementation("com.arjangconsulting.codeeditor:code-editor:<version>")
```

Targets: `android` (minSdk 26), `jvm`, `iosArm64`, `iosSimulatorArm64`. Dependencies: Compose
runtime/foundation/ui, Material 3 (only for color defaults), kotlinx-coroutines, and on desktop
RSyntaxTextArea 4.0.1 + fifesoft autocomplete 4.0.0 (BSD-3-Clause).

Internals are described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Contributor and AI-agent
conventions live in [AGENTS.md](AGENTS.md).

## Try it

```bash
./gradlew :sample:run                 # desktop sample: every feature on one screen
verification/build-apps.sh android    # Android app with the editor (see verification/README.md)
verification/build-apps.sh ios        # iOS simulator app (needs Xcode and xcodegen)
```

In IntelliJ IDEA or Android Studio, open the repository and use the shared run configurations in
`.run/`: **Run** has *Sample (desktop)*, *Sample (desktop, window layers)* (adds
`-Dcompose.layers.type=WINDOW` for testing the RSyntaxTextArea engine with overlays), and the
Android and iOS verification builds; **Test** has *Tests (JVM)*, *Tests (all targets)*, and
*Check (CI)*.

The desktop sample has language and engine pickers, a toggle for every option, a demo
`LanguageService` for a small command language (with simulated latency), JSON completion from a
tiny schema, a read-only Swift test whose lines highlight from a clickable step list, a REPL, a
`CodeSnippet` list, light/dark themes, a syntax color editor (palette presets, per-token colors
with a contrast check), and a dialog-over-editor overlay check.

## Quick start

```kotlin
val state = rememberCodeEditorState("""{ "port": 8080 }""")
CodeEditor(
    state = state,
    modifier = Modifier.fillMaxSize(),
    language = JsonRules,
    languageService = myService,
    diagnostics = externalDiagnostics,
)
```

For a plain string:

```kotlin
var text by remember { mutableStateOf("") }
CodeEditor(value = text, onValueChange = { text = it }, language = KotlinRules)
```

A REPL prompt:

```kotlin
CodeEditor(
    value = command,
    onValueChange = { command = it },
    singleLine = true,
    language = CommandRules,
    languageService = commandService,
    onSubmit = { run(it); command = "" },
    onHistory = { delta -> command = history.step(delta) },
)
```

Read-only snippets in lists (one `BasicText`, no editor machinery):

```kotlin
CodeSnippet(toolCall.argumentsJson, language = JsonRules, maxLines = 3, overflow = TextOverflow.Ellipsis)
val annotated = highlightedText(code, KotlinRules, CodeEditorDefaults.colorsFrom(scheme))
```

## Public API

| Type | Purpose |
| --- | --- |
| `CodeEditor(state, …)` / `CodeEditor(value, onValueChange, …)` | The editor. Parameters: `language`, `languageService`, `engine`, `readOnly`, `singleLine`, `onSubmit`, `onHistory`, `showLineNumbers`, `folding`, `lineHighlights`, `diagnostics`, `revealLine`, `revealRange`, `onCaretChange`, `onLineClick`, `tabBehavior`, `colors`, `textStyle`, `labels`, `options`, `serviceOptions`, `logger`. |
| `CodeEditorState`, `rememberCodeEditorState` | Text, selection, monotonically increasing `version`, folding, undo/redo, `visibleLines`, `revealLine`/`revealRange`, `edit(List<TextEdit>)`, `snapshot()`. |
| `TextPosition`, `TextRange`, `TextEdit`, `applyTextEdits`, `DocumentSnapshot` | LSP-shaped text model: 0-based lines, UTF-16 characters. `TextRange` is position based; it is not `androidx.compose.ui.text.TextRange`. |
| `LanguageService` | `triggerCharacters`, `complete`, `resolveCompletionItem`, `diagnostics`, `format`, `hover`, `semanticTokens`, `foldingRanges`. All default to no-ops. |
| `FormattingOptions`, `FormatResult`, `CodeEditorState.format` | Explicit, asynchronous formatting with timeout, stale-result rejection, and one undo step. |
| `JsonLanguageService`, `JsonLanguageServiceLabels` | Local JSON syntax validation and whitespace-only formatting, with localizable diagnostic messages. |
| `CompletionItem`, `CompletionList`, `CompletionTrigger`, `Diagnostic`, `Hover`, `MarkupContent`, `SemanticToken`, `FoldingRange` | Mirrors of the LSP types. |
| `LineHighlight(lines, role)` | Full-width line tint: `SELECTED`, `HOVER`, `WARNING`, `ERROR`, `INFO`. |
| `TokenRules`, `tokenRules { }`, `PlainText`, `JsonRules`, `KotlinRules`, `SwiftRules`, `ShellRules` | Language definitions: lexer rules plus brackets, comment prefix, indentation. |
| `highlightedText`, `rememberHighlightedText`, `CodeSnippet` | Lightweight read-only highlighting. |
| `FuzzyMatcher`, `filterAndRankCompletions` | The client-side completion filter, reusable elsewhere. |
| `CodeEditorSyntaxTheme` (`Default`, `HighContrast`), `CodeEditorSyntaxColors`, `contrastRatio`, `isDarkBackground` | Token color palettes; see [Colors](#colors). |
| `CodeEditorColors` / `CodeEditorDefaults.colors(syntaxTheme)` / `colorsFrom(scheme, syntaxTheme)`, `CodeEditorLabels`, `CodeEditorOptions`, `LanguageServiceOptions`, `CodeEditorLogger`, `TabBehavior`, `CodeEditorEngine` | Configuration. |

Enum entries use SCREAMING_CASE (house style), e.g. `DiagnosticSeverity.ERROR`.

## Colors

Editor chrome (background, gutter, selection, popups) comes from `MaterialTheme.colorScheme`. Token
colors come from a separate syntax palette, `CodeEditorSyntaxTheme`, which has a light and a dark
variant. The variant is picked from the editor background's luminance, so tokens stay readable on any
background, including a custom one.

| Theme | Contrast |
| --- | --- |
| `CodeEditorSyntaxTheme.Default` | ≥ 4.5:1 (WCAG AA) on white through `#D6D6D6` and on `#0F0D13` through `#404040`, so also on Material's tinted containers |
| `CodeEditorSyntaxTheme.HighContrast` | ≥ 7:1 (WCAG AAA) on white through `#D6D6D6` and on black through `#2E2E2E` |

Pick a palette:

```kotlin
CodeEditor(state, colors = CodeEditorDefaults.colors(CodeEditorSyntaxTheme.HighContrast))
```

The syntax variant follows a custom background automatically:

```kotlin
val colors = CodeEditorDefaults.colors().copy(background = panel)
```

Let people recolor tokens from a settings screen, then warn about unreadable picks:

```kotlin
val theme = CodeEditorSyntaxTheme.Default.let { it.copy(dark = it.dark.with(TokenKind.STRING, picked)) }
val tooFaint = theme.dark.lowContrastKinds(background)
CodeEditor(state, colors = CodeEditorDefaults.colors(theme))
```

A role set to `Color.Unspecified` uses the editor foreground; `variable` does by default.
`CodeEditorSyntaxTheme(colors)` uses one palette for every background. Persisting
someone's choices is up to the app: store the edited `CodeEditorSyntaxTheme` however you store
other settings.

## Languages

The library includes four highlighting presets: JSON, Kotlin, Swift, and shell (sh/Bash), plus
plain text. Choose `language` explicitly; filename/content detection is left to the host. Highlighting
and generated-test line highlights work without a language service. Shell rules cover control words,
common builtins, assignments, flags, variables, comments, and quoted strings. Heredocs, nested command
substitutions, and context-sensitive shell comments are not fully modeled.

`tokenRules` builds a line-based incremental lexer. Rules are tried in order: line comments,
delimited constructs (strings and block comments, longest opening delimiter first), regex
`pattern`s, numbers, identifiers (keywords, then `name=` attributes, then `fn(` calls, then
capitalized types), operators, punctuation.

```kotlin
val CommandRules = tokenRules("commands") {
    keywords("tap", "swipe", "assert")
    constants("true", "false")
    attributeAssignments()
    lineComment("#")
    string("\"")
    numbers()
    pattern("@[a-z]+", TokenKind.ANNOTATION)
    brackets('(' to ')')
}
val Jsonc = tokenRules("jsonc", base = JsonRules) { lineComment("//") }
```

Strings and block comments may span lines (`multiline = true`, `nested = true`). Only changed lines
are re-lexed: after an edit the lexer restarts at the first changed line and stops as soon as a
line ends in the same state as before. Semantic tokens from the service (`SemanticToken`) are drawn
over lexer tokens in the Compose engine.

## Language services

The editor owns orchestration so services can be simple, slow, remote, or flaky:

- Completion is requested on a trigger character, while typing an identifier (debounced by
  `completionDebounceMillis`), and on Ctrl+Space.
- Every request carries a `DocumentSnapshot` with a `version`. In-flight requests are cancelled when
  the document changes, and results for an older version are dropped. Moving the caret cancels pending
  completion and hover requests. An open completion list is retained only for edits within its prefix;
  other edits close it. Diagnostics, semantic tokens, and service folds apply only to their version.
- While the popup is open and the list is complete (`isIncomplete = false`), typing re-filters
  locally with `FuzzyMatcher` (subsequence, word-boundary, and prefix aware). Ranking order is
  score, then `preselect`, then `sortText ?: label`. When `isIncomplete = true` the service is asked
  again on every edit.
- Diagnostics, semantic tokens, and folding ranges are requested `diagnosticsDebounceMillis` after
  the last edit; hover after the pointer rests for `hoverDelayMillis`.
- Calls run on `LanguageServiceOptions.dispatcher` (default `Dispatchers.Default`) with
  `requestTimeoutMillis`. Exceptions and timeouts go to `CodeEditorLogger.error` and are otherwise
  ignored. The logger never receives document text.
- Accepting a completion applies `textEdit` or replaces the identifier prefix with `insertText ?: label`,
  together with `additionalTextEdits`, as one undo step. Service ranges follow prefix growth or deletion,
  including additional edits after the prefix. A main edit ending at the request position follows the
  caret. Snippets support `$1`, `${1:placeholder}`, and `$0`.
- `CodeEditorState.edit` preserves list order for insertions at the same position. Swing selection
  replacement is one undo step and one value callback. Collapsed folds move with edits and undo/redo
  for LF, CRLF, and CR line endings.
- `tabSize`, request timeout, and completion item limits must be positive; delays must be nonnegative.
  Empty string and block-comment delimiters are rejected when building token rules.

### Formatting, saving, and validation

Highlighting is the core feature; services are optional plugins. A service can implement only
`format`, only `diagnostics`, or both, without providing completion or other IDE features. The
library bundles JSON syntax validation and formatting. Kotlin, Swift, and shell have highlighting
and smart typing, but no bundled formatter or validator. Host adapters may supply local tools or
LSP-backed implementations. There is no bundled LSP client or server process.

```kotlin
val state = rememberCodeEditorState("""{"port":8080,"routes":[]}""")
val service = remember { JsonLanguageService() }
val scope = rememberCoroutineScope()
CodeEditor(state = state, language = JsonRules, languageService = service)
Button(onClick = {
    scope.launch {
        state.format(service, FormattingOptions(tabSize = 2))
    }
}) { Text("Format") }
```

Formatting runs only when the host requests it, such as a Format button or a Save handler. It never
runs on typing or automatically on save; this library owns no persistence. Call `state.format` from
the UI thread. The provider runs on the configured service dispatcher, with a timeout; the result
returns to the caller before edits are applied. Newer typing makes the result `STALE`. Successful
edits are one undo step, and selection is mapped through them. `UNAVAILABLE` means the provider
cannot format this document (including invalid JSON), `FAILED` means an exception/timeout/invalid
edit response, and `UNCHANGED` means no change was needed. Cancellation propagates to the caller.

For format-before-save, the host can use this sequence inside its UI coroutine; `saveDocument` and
`showFormattingOutcome` are host callbacks:

```kotlin
val result = state.format(service, FormattingOptions(tabSize = 2))
when (result) {
    FormatResult.APPLIED, FormatResult.UNCHANGED -> saveDocument(state.text)
    else -> showFormattingOutcome(result)
}
```

`JsonLanguageService` validates any JSON root value, reports the first syntax error, and does not
perform JSON Schema validation. Invalid input is never formatted. Formatting changes only whitespace
between tokens: property order, duplicate keys, exact numbers, and string escapes are preserved.
The first line-ending style and presence of a final line ending are retained; empty collections
stay compact. Nesting is limited to 128 objects/arrays by default, configurable downward. Use
`JsonLanguageServiceLabels` to localize diagnostic messages.

The editor requests `diagnostics` after edits when a service is installed; a host can also explicitly
call `service.diagnostics(state.snapshot())` to validate before saving. Reject stale results when
managing validation yourself. For multiple validators, combine their diagnostics in your service,
or pass a combined list through the editor's `diagnostics` parameter. Direct diagnostics are owned
by the host and do not receive the controller's version checks. The sample layers mock-server schema
checks over the built-in JSON service and demonstrates Format, Validate, and an in-memory Save snapshot.

Smart typing already pairs `{` with `}`, and Enter between them creates an indented blank line.
JSON uses two spaces by default; Kotlin, Swift, and shell use four. Configure this through
`TokenRules.indentUnit` and `CodeEditorOptions.autoIndent`/`autoCloseBrackets`. This is lightweight
bracket-based indentation rather than whole-document formatting.

### Adapting an LSP server

The types map one to one; an adapter is a thin translation layer:

| LSP | Here |
| --- | --- |
| `textDocument/didChange` | Send `DocumentSnapshot.text` (full sync) with `version`. |
| `textDocument/completion` + `CompletionContext` | `complete(doc, position, trigger)`; `CompletionTriggerKind` mirrors LSP's. |
| `completionItem/resolve` | `resolveCompletionItem(item)`; carry LSP `data` in `CompletionItem.id`. |
| `textDocument/publishDiagnostics` / `textDocument/diagnostic` | `diagnostics(doc)`. For push servers, cache the last publish and return it, or pass it as the `diagnostics` parameter instead. |
| `textDocument/hover` | `hover(doc, position)`. |
| `textDocument/semanticTokens/full` | `semanticTokens(doc)`; decode the integer stream with your legend into `SemanticToken(line, startCharacter, length, kind)`. |
| `textDocument/foldingRange` | `foldingRanges(doc)`. |
| `textDocument/formatting` | `format(doc, options)`; return edits or null when unavailable. |

Positions are UTF-16 based, LSP's default `positionEncoding`.
An adapter owns JSON-RPC transport, initialization/capabilities, document URI and language ID, and
open/change/close synchronization. `DocumentSnapshot` contains text and version only. For push
diagnostics, update the host's diagnostics state when a publish arrives; returning a cached list
from `diagnostics(doc)` alone does not trigger another analysis when the push arrives later.

## Engines

`engine = CodeEditorEngine.DEFAULT` picks **Compose on every platform, including desktop**.
`RSYNTAX_TEXT_AREA` is available on desktop as an opt-in and falls back to Compose elsewhere.

### Compose engine

The Compose engine is virtualized and draws its own text. `BasicTextField` was measured first: it
re-lays out the whole text on every keystroke, about 80 ms per key for 5,000 lines and about 900 ms
once highlight spans are added through `OutputTransformation`. So the engine:

- Measures and caches one `TextLayoutResult` per visible line, keyed by the lexed line, and draws
  only the visible rows. Lines, highlights, selection, find matches, squiggles, the caret, and the
  gutter are all drawn in one pass.
- Routes platform text input (hardware keys, IME composition, soft keyboards, iOS keyboard cursor
  control) through a hidden `BasicTextField`. That field mirrors the selected lines plus one line of
  context, which lets Backspace at a line start work on Android. Edits are diffed back into the
  document.
- Handles navigation and editing keys itself (see the keymap below).

A UI test types into the end of a 5,000-line Kotlin file and asserts under 50 ms per key, including
test-harness overhead.

### RSyntaxTextArea engine (desktop, opt-in)

The RSyntaxTextArea engine generalizes moqserver's `JsonCodeEditor`. It hosts `RSyntaxTextArea`
in a `SwingPanel` with:

- a `TokenMakerBase` adapter over the shared lexer, so custom languages and Swift work;
- `addLineHighlight` for line highlights;
- a `Parser` that turns diagnostics into notices, gutter icons, and tooltips;
- fifesoft `AutoCompletion` fed by the same controller, whose popup is a separate Swing window;
- the theme mapped onto `SyntaxScheme`;
- the shared fold computation;
- the find bar as Compose above the panel.

`CodeEditorState` stays the source of truth for text, selection, and undo.

### Why RSyntaxTextArea is not the desktop default

The engine was probed in a real window under Compose Multiplatform 1.12.0. Compose renders into a
heavyweight `SkiaLayer` canvas, and the `SwingPanel` content sits in a separate Swing group inside
it. Compose `Popup`s, `Dialog`s, `DropdownMenu`s, and tooltips draw on the same canvas, so where
they overlap the editor, the editor wins. Approval dialogs and menus shown over an editor are
therefore hidden. Results by flag:

| JVM flag (set before Compose starts) | Popups and dialogs over RSTA | Notes |
| --- | --- | --- |
| none (default `compose.layers.type` = on same canvas) | **Hidden under the editor** | |
| `-Dcompose.layers.type=WINDOW` | Drawn above it: each popup and dialog becomes its own owned `JWindow` | Process-wide; changes popup windowing for the whole app. |
| `-Dcompose.layers.type=COMPONENT` | Not fixed (no separate windows) | |
| `-Dcompose.interop.blending=true` | Experimental. Not visually verified here; no separate windows. | Platform-dependent. |

Focus traversal is the second issue. Pressing Tab in a Compose text field above the panel left
AWT's focus owner `null` for several seconds before the `RSyntaxTextArea` got focus. Focus handoff
between Compose and Swing is not reliable enough for Studio's keyboard-first flows.

A library cannot set these flags safely: they must be set before Compose starts and they affect the
whole app. Compose is therefore the default everywhere. To use RSyntaxTextArea anyway:

1. Start the app with `-Dcompose.layers.type=WINDOW`, for example
   `compose.desktop.application { jvmArgs("-Dcompose.layers.type=WINDOW") }` or
   `System.setProperty("compose.layers.type", "WINDOW")` as the first line of `main`.
2. Check overlays with the sample's "Overlay check" card: `./gradlew :sample:run`, switch the
   engine to RSTA, and open the dialog and menu.

Not supported by the RSyntaxTextArea engine: semantic tokens, service folding ranges, hover
Markdown beyond escaped paragraphs, `TokenRules.indentUnit` other than tabs or spaces.

## Keyboard (Compose engine)

| Action | macOS / iOS | Windows / Linux / Android |
| --- | --- | --- |
| Word left/right | ⌥←/→ | Ctrl+←/→ |
| Line start/end | ⌘←/→, Home/End, ⌃A/⌃E | Home/End (smart Home) |
| Document start/end | ⌘↑/↓ | Ctrl+Home/End |
| Extend selection | add ⇧ | add Shift |
| Undo / Redo | ⌘Z / ⇧⌘Z | Ctrl+Z / Ctrl+Y, Ctrl+Shift+Z |
| Copy / Cut / Paste | ⌘C/X/V (whole line when nothing is selected) | Ctrl+C/X/V |
| Find / next / previous | ⌘F / ⌘G / ⇧⌘G, F3 | Ctrl+F / F3 / Shift+F3 |
| Complete | ⌃Space | Ctrl+Space |
| Toggle line comment | ⌘/ | Ctrl+/ |
| Indent / outdent | Tab / ⇧Tab (or focus traversal with `TabBehavior.MOVE_FOCUS`) | same |
| Fold / unfold region | ⌘⌥[ / ⌘⌥] | Ctrl+Shift+[ / Ctrl+Shift+] |
| Completion popup | ↑/↓, PageUp/PageDown, Enter/Tab accept, Esc closes | same |

Editing helpers: auto-indent (and splitting `{|}` onto three lines), auto-closing and overtyping of
brackets and quotes, wrapping a selection in a pair, re-indenting a closing bracket typed on a blank
line, Backspace by indent stop, and deleting an empty pair at once. On mobile, tap places the
caret, long press selects a word and shows the platform text toolbar, and drag scrolls.

## Accessibility

- The input field exposes a full editor-sized accessibility region, `contentDescription`
  (`CodeEditorLabels.editorDescription`), and `stateDescription` with the caret's line and column.
  The drawing layer is hidden from accessibility so it does not obscure the text field.
- Screen readers read the hidden input field, which holds the lines around the caret, not the whole
  document.
- The completion list and find bar controls have labels. The Compose input field exposes localized
  Fold, Unfold, Fold all, and Unfold all accessibility actions when folding is enabled. Region actions
  choose the innermost applicable range at the caret. Collapsing moves a hidden selection to its header;
  folding also works in read-only editors. Action labels are in `CodeEditorLabels`.
- Active IME composition retains native key handling and marked-text selection, including reversed
  selections. Touch selection's
  toolbar uses root coordinates, including editors offset within a larger screen.
- Hover tooltips and find result counts are polite live regions.
- All strings are in `CodeEditorLabels` for localization.

## Known gaps

- Lines longer than 10,000 characters are truncated for display (editing still works). Format huge
  minified JSON before showing it.
- No soft wrap, no multi-cursor, no regex find, and no replace.
- Snippets support only the first tab stop; there is no tab-stop navigation.
- Mobile has no selection handles. Long press shows the platform text toolbar instead.
- IME composition text is drawn inline without an underline.
- Hover is mouse-only in the Compose engine; touch has no hover.
- In the RSyntaxTextArea engine, AutoCompletion's popup can't be shown headless, so tests cover the
  feed and acceptance, not the popup window.

## Development

```bash
./gradlew :code-editor:jvmTest      # common + desktop UI + RSyntaxTextArea panel tests
./gradlew :code-editor:allTests     # also Android host and iOS simulator
./gradlew :sample:run               # desktop sample app
```

The standalone [mobile verification harness](verification/README.md) builds Android and iOS
simulator apps for native composition, keyboard, selection, toolbar, and accessibility checks.

### Using a local checkout

Include this repository as a composite build; Gradle substitutes
`com.arjangconsulting.codeeditor:code-editor` with the `:code-editor` project automatically:

```kotlin
// settings.gradle.kts of the consuming app
includeBuild("../kmp-code-editor")
```

Releases are automated by Release Please from Conventional Commit messages (`feat:`, `fix:`,
`feat!:`), with bare SemVer tags (no `v` prefix). Until 0.1.0 is released, changes are committed
directly to `main`; after that they land through pull requests.

Dependabot opens one weekly pull request for all Gradle and GitHub Actions updates, which is
merged automatically once CI passes.

Releases are **off** until the GitHub repository variable `RELEASES_ENABLED` is set to `true`
(Settings > Secrets and variables > Actions > Variables). Until then no release PR is opened and
nothing is tagged or published, so the first release (0.1.0) waits for review.
