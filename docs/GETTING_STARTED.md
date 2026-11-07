# Getting started

## Install

```kotlin
implementation("com.arjangconsulting.codeeditor:code-editor:<version>")
```

Targets: `android` (minSdk 26), `jvm`, `iosArm64`, `iosSimulatorArm64`. Dependencies: Compose
runtime/foundation/ui, Material 3 (only for color defaults), kotlinx-coroutines, and on desktop
RSyntaxTextArea 4.0.1 + fifesoft autocomplete 4.0.0 (BSD-3-Clause).

To consume an unreleased checkout, see [Development](DEVELOPMENT.md#using-a-local-checkout).

## Hoisted state

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

`language` selects highlighting and smart typing; `languageService` is optional and adds completion,
diagnostics, hover, and formatting. Highlighting works without either service. See
[Languages](LANGUAGES.md) and [Language services](LANGUAGE_SERVICES.md).

## Plain string

```kotlin
var text by remember { mutableStateOf("") }
CodeEditor(value = text, onValueChange = { text = it }, language = KotlinRules)
```

`onValueChange` reports editor changes, including typing, completion acceptance, undo, and redo.
Supplying an external `value` replaces the text as one undoable change and moves the caret to the
end without calling `onValueChange`. Delayed echoes of recently reported values do not overwrite
newer typing.

## A REPL prompt

```kotlin
val replService = remember { KeywordLanguageService(KotlinRules) }
var sessionRevision by remember { mutableStateOf(0L) }
CodeEditor(
    value = command,
    onValueChange = { command = it },
    singleLine = true,
    language = KotlinRules,
    languageService = replService,
    languageServiceRevision = sessionRevision,
    onSubmit = {
        run(it)
        sessionRevision++
        command = ""
    },
    onHistory = { delta -> command = history.step(delta) },
)
```

Use the same rules with `CodeSnippet(command, language = KotlinRules)` for highlighted submitted
commands. Keep ordinary runtime output as text. The sample REPL includes command, Kotlin, Swift,
and shell modes, highlighted transcript entries that retain their original language, and a session reset.

## Read-only snippets

For lists, use one `BasicText` with no editor machinery:

```kotlin
CodeSnippet(toolCall.argumentsJson, language = JsonRules, maxLines = 3, overflow = TextOverflow.Ellipsis)
val annotated = highlightedText(code, KotlinRules, CodeEditorDefaults.colorsFrom(scheme))
```

## Generated-test line highlights

```kotlin
CodeEditor(
    state = state,
    readOnly = true,
    language = SwiftRules,
    lineHighlights = listOf(LineHighlight(lines = 4..6, role = LineHighlightRole.SELECTED)),
)
```

Lines are 0-based. Roles are `SELECTED`, `HOVER`, `WARNING`, `ERROR`, and `INFO`. The sample's read-only Swift test highlights lines from a clickable step list.

## Next steps

- [Public API](API.md) for every type and `CodeEditor` parameter.
- [Colors and themes](THEMING.md) to pick or customize a syntax palette.
- [Formatting, saving, and validation](FORMATTING.md) for JSON and YAML.
- [Engines](ENGINES.md) for soft wrap, large documents, and the desktop RSyntaxTextArea option.
- [Try the sample app](DEVELOPMENT.md#try-it) to see every feature.
