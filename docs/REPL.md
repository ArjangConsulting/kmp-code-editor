# REPL prompts and transcripts

The host owns command execution, session/device identity, command history, and transport. The editor
supplies input, completion, diagnostics, and highlighting without requiring a language server.
Use the state overload so each submission can start a fresh undo history.

## Single-line prompt

```kotlin
@Composable
fun CommandPrompt(service: LanguageService, onCommand: (String) -> Unit) {
    val state = rememberCodeEditorState()
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }
    var draft by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0L) }
    CodeEditor(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        language = ShellRules,
        languageService = service,
        languageServiceRevision = revision,
        singleLine = true,
        placeholder = "Enter a command",
        tabBehavior = TabBehavior.COMPLETE,
        onSubmit = { command ->
            if (command.isNotBlank()) {
                history += command
                onCommand(command)
                revision++
            }
            historyIndex = -1
            draft = ""
            state.resetText("")
        },
        onHistory = { direction ->
            if (history.isNotEmpty()) {
                if (historyIndex == -1 && direction < 0) {
                    draft = state.text
                    historyIndex = history.lastIndex
                } else if (historyIndex >= 0) {
                    historyIndex = (historyIndex + direction).coerceAtLeast(0)
                    if (historyIndex > history.lastIndex) historyIndex = -1
                }
                val recalled = if (historyIndex == -1) draft else history[historyIndex]
                state.resetText(recalled, caret = recalled.length)
            }
        },
    )
}
```

Enter accepts a selected completion before submitting. Up/Down navigate suggestions while the popup
is open; otherwise `onHistory` receives -1 for older and +1 for newer. Pasted line breaks become
spaces. Find/replace and next/previous-find commands return unhandled so the host can route them.
The placeholder is visible and exposed to accessibility only while empty and uses
`CodeEditorColors.placeholderForeground`.

Tab requests completion with `TabBehavior.COMPLETE`, and accepts a selected suggestion when open.
Shift+Tab moves focus backward. The default single-line behavior remains `MOVE_FOCUS`; ordinary
editors default to `INSERT_INDENT`. Ctrl+Space also requests completion, subject to OS shortcuts.
Numbered snippet placeholders take priority over these Tab behaviors: Tab/Shift+Tab visit them in
numeric order, then `$0` or the implicit end. Escape, leaving/editing outside the active placeholder,
Undo/Redo, or reset ends the session. Repeated placeholders are not mirrored.

## Undo and command history

`resetText("")` clears both undo and redo, preventing Undo from bringing back a submitted command.
`resetText(command, caret = command.length)` starts a fresh undo history for a recalled command.
Store and restore an unfinished draft in the host when browsing history. `clearHistory()` is useful
when accepting the current text as a new baseline without changing selection, folds, or version.

The value overload treats every external replacement, including clearing after submission, as an
undoable edit. Use it when that behavior is wanted; use the state overload for isolated prompt
histories. The editor never executes commands on undo or redo.

## Multi-line scripts and line limits

Use `singleLine = false`, `onSubmit`, and `CodeEditorOptions(minLines = 1, maxLines = 5)` for an input
that grows to five lines and then scrolls. Enter inserts a newline; Cmd+Enter on Apple platforms or
Ctrl+Enter elsewhere submits the whole document and closes completion. This explicit submit shortcut
works in either mode, even with a suggestion open. Read-only editors do not submit. Up/Down retain
normal caret movement in multi-line mode; provide host controls for command history there.

Parent constraints take precedence over line limits: avoid a fixed height or a weighted modifier on
the editor when it should grow. Unlimited `maxLines` preserves the existing fill-bounded/fit-unbounded
behavior. Compose counts wrapped visual rows; RSTA sizes from logical lines.

## Runtime revisions and diagnostics

Increment `languageServiceRevision` after execution completes, session reset, or device change,
including when the text stays unchanged. For asynchronous execution, update it when the result
arrives as well as when dispatching if the runtime context changes at both points. This cancels
pending requests, clears service completion/diagnostics/hover/tokens/folds, and refreshes analysis.
Completion reopens on typing, Ctrl+Space, or completion Tab. Host-supplied `diagnostics` remain under
the host's control. The revision does not clear text or undo history and does not replace the document
version checks used by formatting. See [Language services](LANGUAGE_SERVICES.md) for orchestration
and [Completion providers](COMPLETION_PROTOCOL.md) for runtime adapters and schema-based add-ons.

## Transcript and generated source

Use `CodeSnippet(command, language = rules)` in a lazy transcript, retaining the rules used at
submission. Ordinary runtime output can use `Text`; JSON/YAML results can use `CodeSnippet` or a
read-only `CodeEditor` when selection, folding, or navigation is needed. Use `XmlRules` for XML,
HTML, SOAP, and SVG highlighting. Markup has no bundled validation or formatting.

For generated Swift/Kotlin source, use a read-only editor with `SwiftRules`/`KotlinRules`,
`lineHighlights`, and `revealRange` for the active generated test step. Compiler-level diagnostics
and formatting require host adapters. Explicit JSON/YAML formatting is described in
[Formatting and validation](FORMATTING.md); persistence belongs to the host.
