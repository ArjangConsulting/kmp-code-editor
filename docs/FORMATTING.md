# Formatting, saving, and validation

Highlighting is the core feature; services are optional plugins. A service can implement only
`format`, only `diagnostics`, or both, without providing completion or other IDE features. The
library bundles JSON and YAML syntax validation and formatting. Kotlin, Swift, and shell have highlighting
and smart typing, but no bundled formatter or validator. Host adapters may supply local tools or
LSP-backed implementations. There is no bundled LSP client or server process.

## Format on an explicit action

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
cannot format this document (including invalid JSON/YAML), `FAILED` means an exception/timeout/invalid
edit response, and `UNCHANGED` means no change was needed. Cancellation propagates to the caller.

## Format before save

The host can use this sequence inside its UI coroutine; `saveDocument` and `showFormattingOutcome`
are host callbacks:

```kotlin
val result = state.format(service, FormattingOptions(tabSize = 2))
when (result) {
    FormatResult.APPLIED, FormatResult.UNCHANGED -> saveDocument(state.text)
    else -> showFormattingOutcome(result)
}
```

## JSON

`JsonLanguageService` validates any JSON root value, reports the first syntax error, and does not
perform JSON Schema validation. Invalid input is never formatted. Formatting changes only whitespace
between tokens: property order, duplicate keys, exact numbers, and string escapes are preserved.
The first line-ending style and presence of a final line ending are retained; empty collections
stay compact. Nesting is limited to 128 objects/arrays by default, configurable downward. Use
`JsonLanguageServiceLabels` to localize diagnostic messages.

## YAML

Use `language = YamlRules` and `languageService = remember { YamlLanguageService() }`, then call
`state.format(service, FormattingOptions(tabSize = 2))` as above. No filename detection is
required: the same service handles `.yaml` and `.yml`. The formatter uses
[SnakeYAML Engine KMP](https://github.com/krzema12/snakeyaml-engine-kmp)'s parser/emitter events,
preserving scalar values, mapping order, duplicate keys, comments, anchors/aliases, tags, and
multiple documents without constructing objects or expanding aliases. It normalizes indentation
and spacing while retaining flow/block collection styles; scalar spelling, quoting, and block
indicators may be normalized. Output uses the first input line-ending style and ends with a line
break; empty and comment-only input is unchanged. Edits use the smallest single replacement, so
selection inside the reformatted region may move to its end.

YAML indentation must use spaces: requests with `insertSpaces = false` or `tabSize` outside 1..10
return `UNAVAILABLE`, as does invalid syntax. Syntax validation does not resolve aliases or check
schemas or duplicate keys. Documents are limited to 3 Mi UTF-16 code units, and collection nesting
to 128 levels by default (configurable downward). Use `YamlLanguageServiceLabels` to localize errors.

## Diagnostics and validation

The editor requests `diagnostics` after edits when a service is installed; a host can also explicitly
call `service.diagnostics(state.snapshot())` to validate before saving. Reject stale results when
managing validation yourself. For multiple validators, combine their diagnostics in your service,
or pass a combined list through the editor's `diagnostics` parameter. Direct diagnostics are owned
by the host and do not receive the controller's version checks. The sample layers mock-server schema
checks over the built-in JSON service. JSON and YAML demonstrate Format, Validate, and an in-memory Save snapshot.
