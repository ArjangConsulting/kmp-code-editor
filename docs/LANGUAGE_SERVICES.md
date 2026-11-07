# Language services

Language services are optional plugins. A `LanguageService` can implement any subset of completion,
diagnostics, hover, semantic tokens, folding ranges, and formatting; every method defaults to a
no-op. For explicit formatting and the bundled JSON/YAML services, see
[Formatting, saving, and validation](FORMATTING.md).

## Local keyword completion

`KeywordLanguageService(rules)` supplies lexical completion without a language server, including
in single-line REPL prompts. Pass the same `rules` as the editor's `language` for matching syntax
highlighting. It returns the full vocabulary for local filtering and suppresses suggestions inside
strings, escapes, and comments, and after a member-access dot. It does not infer scopes, runtime
members, or context-specific keyword legality. Host services can call its `complete` method and
merge the returned items with their own proposals; the host owns backend failure and latency policy.

## Refreshing after runtime changes

Change `languageServiceRevision` whenever runtime context changes, such as after command execution,
session reset, or device switch. This cancels outstanding service requests and clears completion,
hover, diagnostics, semantic tokens, and service folds even when the document is unchanged.
Analysis is refreshed automatically; completion reopens on typing or Ctrl+Space. The document,
selection, undo history, and host-supplied diagnostics are preserved. The signal works in both
engines and both editor overloads. Explicit host formatting calls have their own document version
checks and are not cancelled by this signal.

## Tool backends and external add-ons

`BackendLanguageService` connects the editor to a `CompletionBackend`. The backend can live in the
tool itself, in the consuming app, or in an external completion add-on. It receives document text,
UTF-16 caret position, language ID, session identity, and runtime revision, and returns correlated
`CompletionItem`s. `CompletionProtocol` supplies a versioned JSON codec for remote implementations;
the host supplies HTTP, stdio, WebSocket, or another transport.

Tools do not need source changes when they already expose useful metadata. `ToolCommandCatalog`
imports MCP `tools/list` responses, and `ToolCompletionBackend` turns the catalog into suggestions
for a `command name=value` prompt. An optional `ToolValueProvider` adds values obtained from public
runtime APIs or host snapshots. The included amoo example uses its existing MCP schemas; the sample
REPL's **Tools (schema)** mode demonstrates command names, arguments, enums, and fixture element IDs
through a JSON boundary. It runs offline and displays submissions locally.

See [Completion providers and add-ons](COMPLETION_PROTOCOL.md) for integration code, the JSON
contract, source-free amoo discovery, capabilities, and limitations. The exporter in
`examples/completion/export_mcp_tools.py` reads paginated MCP catalogs using an installed tool binary
and Python 3.11+. Runtime variables and type members require an existing introspection/completion
surface or a native provider; a command schema supplies command and argument knowledge.

## Request orchestration

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
- Direct services follow LSP position clamping. Candidates whose edits overlap each other or the
  replaced prefix are not offered, and acceptance validates resolved edits and their mapped ranges
  before changing the document. A rejected acceptance closes the popup, applies nothing, consumes
  the key in both engines, and is logged through `CodeEditorLogger.error`.
- `CodeEditorState.edit` preserves list order for insertions at the same position. Swing selection
  replacement is one undo step and one value callback. Collapsed folds move with edits and undo/redo
  for LF, CRLF, and CR line endings.
- `tabSize`, request timeout, and completion item limits must be positive; delays must be nonnegative.

## Adapting an LSP server

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

There is no bundled LSP client or server process.
