# KMP Code Editor

A Compose Multiplatform code editor for Android, desktop (JVM), and iOS with syntax highlighting,
folding, find, undo/redo, and pluggable language intelligence shaped after the Language Server
Protocol (LSP) 3.17.

```kotlin
implementation("com.arjangconsulting.codeeditor:code-editor:<version>")
```

Targets: `android` (minSdk 26), `jvm`, `iosArm64`, `iosSimulatorArm64`.

## Quick start

```kotlin
val state = rememberCodeEditorState("""{ "port": 8080 }""")
CodeEditor(
    state = state,
    modifier = Modifier.fillMaxSize(),
    language = JsonRules,
    languageService = JsonLanguageService(),
)
```

Or bind a plain string:

```kotlin
var text by remember { mutableStateOf("") }
CodeEditor(value = text, onValueChange = { text = it }, language = KotlinRules)
```

Run the desktop sample to see every feature: `./gradlew :sample:run`.

## Features

- Syntax highlighting for JSON, YAML, Kotlin, Swift, shell, XML/HTML/SVG, and plain text, plus a `tokenRules { }`
  DSL for your own languages.
- Read-only `CodeSnippet`s and full-width line highlights for generated tests.
- Pluggable `LanguageService`: completion, diagnostics, hover, semantic tokens, folding, formatting.
- Local JSON and YAML validation and explicit, undoable formatting. No bundled LSP runtime.
- Completion from tool endpoints and source-free add-ons through a versioned JSON contract.
- Folding, find and replace (literal or regular expression), multiple cursors, undo/redo, soft wrap,
  smart typing, placeholders, Tab completion, and growing single-line or multi-line REPL inputs.
- Light, dark, and high-contrast syntax palettes that follow the editor background.
- Compose engine on every platform; opt-in RSyntaxTextArea engine on desktop.

## Documentation

| Topic | What it covers |
| --- | --- |
| [Getting started](docs/GETTING_STARTED.md) | Install, hoisted state vs. plain string, read-only snippets, line highlights |
| [REPL prompts](docs/REPL.md) | Submission, undo boundaries, host history, completion, runtime revisions, transcripts |
| [Public API](docs/API.md) | Every main type and the `CodeEditor` parameters |
| [Languages](docs/LANGUAGES.md) | Presets, YAML and XML notes, custom `tokenRules`, smart typing |
| [Colors and themes](docs/THEMING.md) | Syntax palettes, contrast guarantees, user-editable token colors |
| [Language services](docs/LANGUAGE_SERVICES.md) | Completion, request orchestration, tool backends, adapting an LSP server |
| [Formatting and validation](docs/FORMATTING.md) | Format on demand, format before save, JSON and YAML services, diagnostics |
| [Completion providers](docs/COMPLETION_PROTOCOL.md) | Native providers, add-ons, and the JSON contract |
| [Engines](docs/ENGINES.md) | Compose engine, soft wrap, large documents, RSyntaxTextArea |
| [Keyboard and touch](docs/KEYBOARD.md) | Shortcuts and editing helpers |
| [Accessibility](docs/ACCESSIBILITY.md) | Semantics, screen readers, IME, localization |
| [Development](docs/DEVELOPMENT.md) | Sample app, build and test, local checkout, releases |
| [Architecture](docs/ARCHITECTURE.md) | Internals |
| [Releasing](docs/RELEASING.md) | Credential setup, versioning, preflight |

The generated API reference is at <https://arjangconsulting.github.io/kmp-code-editor/>. Contributor
and AI-agent conventions live in [AGENTS.md](AGENTS.md).

## Known gaps

- Multiple cursors are Compose-engine only (RSyntaxTextArea uses the primary selection) and need a
  hardware keyboard or mouse. Completion, snippets, and hover act at the primary caret; starting IME
  composition (for example, CJK input) collapses to the primary caret.
- Find highlights and navigates the first 10,000 matches; replace all is uncapped.
- Regular expressions use the platform engine (`java.util.regex` on desktop and Android,
  `NSRegularExpression` (ICU) on iOS), so advanced syntax can differ slightly. A pattern that recurses
  too deeply for a very long line (such as `((a)|(b))+` over minified JSON) reports "too complex"
  instead of crashing on every platform.
- Snippet repeated stops are not mirrored, and choice/variable syntax remains literal.
- Mobile has no selection handles. Long press shows the platform text toolbar instead.
- IME composition text is drawn inline without an underline.
- Hover is mouse-only in the Compose engine; touch has no hover.
- In the RSyntaxTextArea engine, AutoCompletion's popup can't be shown headless, so tests cover the
  feed and acceptance, not the popup window.
- With finite line limits, RSTA sizes from logical lines; Compose sizes from wrapped visual rows.
- XML/HTML/SVG has lexical highlighting only; embedded languages and full DTD parsing are unsupported.
- Engine differences are listed in [Engines](docs/ENGINES.md).
