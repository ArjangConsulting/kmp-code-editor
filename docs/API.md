# Public API

Every public declaration has KDoc, and the generated API reference is published at
<https://arjangconsulting.github.io/kmp-code-editor/>. This page maps the main types to their
purpose; start with [Getting started](GETTING_STARTED.md) for usage.

| Type | Purpose |
| --- | --- |
| `CodeEditor(state, …)` / `CodeEditor(value, onValueChange, …)` | The editor. Parameters: `language`, `languageService`, `engine`, `readOnly`, `singleLine`, `onSubmit`, `onHistory`, `showLineNumbers`, `folding`, `lineHighlights`, `diagnostics`, `revealLine`, `revealRange`, `onCaretChange`, `onLineClick`, `tabBehavior`, `colors`, `textStyle`, `labels`, `options`, `serviceOptions`, `logger`, `languageServiceRevision`. |
| `CodeEditorState`, `rememberCodeEditorState` | Text, selection, monotonically increasing `version`, folding, undo/redo, `visibleLines`, `revealLine`/`revealRange`, `edit(List<TextEdit>)`, `snapshot()`. `selections` lists every selection (primary first) and `setSelections` sets several; `selection`, `caret`, `selectedText`, and `insert` refer to the primary one. |
| `TextPosition`, `TextRange`, `TextEdit`, `applyTextEdits`, `DocumentSnapshot` | LSP-shaped text model: 0-based lines, UTF-16 characters. `TextRange` is position based; it is not `androidx.compose.ui.text.TextRange`. |
| `LanguageService` | `triggerCharacters`, `complete`, `resolveCompletionItem`, `diagnostics`, `format`, `hover`, `semanticTokens`, `foldingRanges`. All default to no-ops. |
| `KeywordLanguageService(language)` | Local completion from the highlighting vocabulary, including keywords, constants, and known types. |
| `CompletionBackend`, `BackendLanguageService` | Adapts native tool endpoints and external add-ons to completion in both engines. |
| `CompletionContext`, `CompletionRequest`, `CompletionResponse`, `CompletionProtocol` | Versioned, correlated completion messages and a transport-independent JSON codec. |
| `ToolCommandCatalog`, `ToolCommand`, `ToolParameter`, `ToolValueKind` | External command metadata, MCP `tools/list` import, and matching highlighting rules. |
| `ToolCompletionBackend`, `ToolValueProvider`, `ToolValueRequest`, `ToolCompletionLabels` | Command/argument/enum suggestions plus optional runtime value completion. |
| `FormattingOptions`, `FormatResult`, `CodeEditorState.format` | Explicit, asynchronous formatting with timeout, stale-result rejection, and one undo step. |
| `JsonLanguageService`, `JsonLanguageServiceLabels` | Local JSON syntax validation and whitespace-only formatting, with localizable diagnostic messages. |
| `YamlLanguageService`, `YamlLanguageServiceLabels` | Local YAML syntax validation and formatting for `.yaml` and `.yml`, with localizable diagnostic messages. |
| `CompletionItem`, `CompletionList`, `CompletionTrigger`, `Diagnostic`, `Hover`, `MarkupContent`, `SemanticToken`, `FoldingRange` | Mirrors of the LSP types. |
| `LineHighlight(lines, role)` | Full-width line tint: `SELECTED`, `HOVER`, `WARNING`, `ERROR`, `INFO`. |
| `TokenRules`, `tokenRules { }`, `PlainText`, `JsonRules`, `YamlRules`, `KotlinRules`, `SwiftRules`, `ShellRules` | Language definitions: lexer rules plus brackets, comment prefix, indentation. |
| `highlightedText`, `rememberHighlightedText`, `CodeSnippet` | Lightweight read-only highlighting. |
| `FuzzyMatcher`, `filterAndRankCompletions` | The client-side completion filter, reusable elsewhere. |
| `CodeEditorSyntaxTheme` (`Default`, `HighContrast`), `CodeEditorSyntaxColors`, `contrastRatio`, `isDarkBackground` | Token color palettes; see [Colors and themes](THEMING.md). |
| `CodeEditorColors` / `CodeEditorDefaults.colors(syntaxTheme)` / `colorsFrom(scheme, syntaxTheme)`, `CodeEditorLabels`, `CodeEditorOptions`, `LanguageServiceOptions`, `CodeEditorLogger`, `TabBehavior`, `CodeEditorEngine` | Configuration; `CodeEditorOptions.softWrap` defaults to `false`. Find and replace strings (`findRegex`, `findInvalidPattern`, `findTimedOut`, `findTooComplex`, `cursorCount`, `findToggleReplace`, `replacePlaceholder`, `replace`, `replaceAll`, `replacedCount`) are in `CodeEditorLabels`. |

Enum entries use SCREAMING_CASE (house style), e.g. `DiagnosticSeverity.ERROR`.
