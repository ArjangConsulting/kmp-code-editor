# Completion providers and add-ons

The same `CompletionBackend` supports a tool's native completion endpoint and an external add-on
that uses public APIs or metadata. The editor consumes `LanguageService`; `BackendLanguageService`
bridges the provider and checks request, document, session, and runtime identities. Both editor
engines use this path, including single-line REPL prompts.

| Available tool surface | Add-on capabilities | Tool source change needed |
| --- | --- | --- |
| MCP schemas, public command schemas, or CLI help | Commands, argument names, enums, documentation | No |
| Existing device/session/query APIs | Element IDs, labels, paths, runtime values exposed by those APIs | No |
| Existing completion or reflection endpoint | Variables, members, tool-specific language analysis | No |
| Binary with no metadata or introspection | Manually supplied catalog and known values | No, with limited knowledge |
| Unexposed runtime/type information | Native runtime completion/introspection | Yes, to expose that information |

Product-specific tool definitions and live API adapters belong in the consuming application or an
external add-on. This library supplies the generic contract, metadata importer, scanner, and editor
integration. It does not execute submitted commands or manage tool processes and sessions.

## amoo without source changes

The installed amoo 0.3.0 binary was inspected through `amoo help`, `amoo device`, and the public MCP
initialize/`tools/list` exchange. It exposed 67 tools with input schemas. Discovery required no
device session, app launch, or tool execution. The sample contains an offline subset for
`tap_element` and `swipe_in_direction`, captured on 2026-10-23.

Export the complete catalog using Python 3.11+ and an installed amoo binary:

```bash
python3 examples/completion/export_mcp_tools.py --output /tmp/amoo-tools.json -- amoo mcp serve
```

The exporter starts the specified MCP stdio server, initializes it, follows `tools/list` pagination,
writes the catalog, and stops its server process. It sends no `tools/call` requests. It also works
with another MCP stdio server command. An application with an existing MCP client can instead pass
the `tools/list` result directly to `ToolCommandCatalog.fromMcpToolsJson`.

Load the exported JSON in the host and use it in a composable:

```kotlin
val catalog = remember(schemaJson) { ToolCommandCatalog.fromMcpToolsJson(schemaJson) }
val rules = remember(catalog) { catalog.languageRules("amoo-tools") }
val backend = remember(catalog) { ToolCompletionBackend(catalog) }
val context = CompletionContext(sessionId, runtimeRevision)
val service = remember(backend, context) {
    BackendLanguageService(
        backend = backend,
        languageId = rules.name,
        context = context,
        fallback = KeywordLanguageService(rules),
    )
}
CodeEditor(
    state = promptState,
    language = rules,
    languageService = service,
    languageServiceRevision = runtimeRevision,
    singleLine = true,
    onSubmit = executeSubmittedCommand,
)
```

`schemaJson`, session state, prompt state, and execution callbacks in this example are supplied by
the host. Prompts use bare tool names, for example `tap_element id="sign-in" scope=app`. The host
can dispatch a parsed submission through its existing MCP client or CLI integration. This prompt
grammar does not imply a new amoo CLI subcommand or replace Swift/Kotlin runtime completion.

The importer uses schema property names, descriptions, required fields, enums, const values, and
scalar types. For example, the inspected amoo `swipe_in_direction.direction` schema provides
`up`, `down`, `left`, and `right`; `tap_element.scope` provides `app` and `system`. Some amoo
boolean/numeric arguments are declared as strings and described in prose, so those arguments do
not automatically get boolean/numeric suggestions. More precise schemas would improve those
suggestions but are not required for the add-on.

## Runtime values through existing APIs

`ToolValueProvider` supplies values for the argument under the caret. It receives the full request,
command, parameter, decoded value prefix, and replacement range. Return raw values; the backend
handles quoting and replacement of the complete old value, including any suffix after the caret.

```kotlin
val values = ToolValueProvider { request ->
    when (request.parameter.name) {
        "id", "element_id" -> observedElementIds.filter {
            it.startsWith(request.prefix, ignoreCase = true)
        }
        else -> null
    }
}
val backend = ToolCompletionBackend(catalog, valueProvider = values)
```

`observedElementIds` is a host-owned snapshot from existing inspection APIs. For amoo, a host can
obtain it from its established session's `find_elements` results or hierarchy data. Keep live
session calls in that owning integration: the installed amoo server identifies live sessions with
its owning process, so a separate metadata-export process is not a live-session attachment.
Refresh snapshots after execution or screen/device changes, advance the runtime revision, and
recreate the provider/service for the new snapshot. Cache acquisition in the host when querying
the runtime on every keystroke would be expensive.

Return null from a value provider to use schema enums, or an empty list to deliberately show no
values. Dynamic lists are incomplete and are re-requested as the prefix changes. Returned values
are inserted as plain text with quoting when needed; command/argument completions may use snippets.

## Native or remote providers

A native implementation can implement `CompletionBackend.complete` directly, using its own parser
or runtime. An external add-on can wrap existing APIs and implement exactly the same interface.
Use `request.response(completions)` to construct an envelope with matching correlation fields.
Providers may be written in any language when connected through the JSON contract below.

For a remote implementation, the host supplies a suspending `exchange` function for its transport:

```kotlin
val backend = CompletionBackend { request ->
    val responseJson = exchange(CompletionProtocol.encodeRequest(request))
    CompletionProtocol.decodeResponse(responseJson)
}
```

`exchange` owns addressing, authentication, framing, connection reuse, and cancellation. The codec
does not prescribe HTTP paths, launch a subprocess, or create an LSP/MCP runtime. HTTP can carry one
request/response per call; a persistent stdio or WebSocket transport can correlate concurrent calls
by `requestId`. Bridge coroutine cancellation to the transport. Include item documentation in the
completion response; protocol version 1 has no lazy item-resolution operation. Implement other
`LanguageService` methods separately when needed.

## JSON contract, version 1

All field names are case-sensitive. Requests and responses include `protocolVersion: 1`. Unknown
fields are ignored for additive evolution; incompatible major versions are rejected. Enum names
use SCREAMING_CASE. Versions and revisions are nonnegative integers; IDs are opaque strings.

The request contains only the editable document, excluding transcript/output:

```json
{
  "protocolVersion": 1,
  "requestId": "request-1",
  "context": {
    "sessionId": "session-1",
    "revision": 3,
    "attributes": {"deviceId": "device-1"}
  },
  "languageId": "amoo-tools",
  "text": "tap_element id=si",
  "documentVersion": 7,
  "position": {"line": 0, "character": 17},
  "trigger": {"kind": "INVOKED", "character": null}
}
```

`trigger.kind` is `INVOKED`, `TRIGGER_CHARACTER`, or `TRIGGER_FOR_INCOMPLETE_COMPLETIONS`.
`trigger.character` contains one character for trigger-character requests. `context.attributes`
is an optional string map for routing information; credentials belong in the transport. Session
identity and runtime revision are independent of document version: the same text may have different
valid suggestions after execution, a device switch, or a workspace update.

A response echoes the original identity and returns a `CompletionList`:

```json
{
  "protocolVersion": 1,
  "requestId": "request-1",
  "sessionId": "session-1",
  "contextRevision": 3,
  "documentVersion": 7,
  "completions": {
    "isIncomplete": false,
    "items": [
      {
        "label": "sign-in",
        "kind": "VALUE",
        "documentation": {"value": "Sign-in button", "kind": "PLAIN_TEXT"},
        "textEdit": {
          "range": {
            "start": {"line": 0, "character": 15},
            "end": {"line": 0, "character": 17}
          },
          "newText": "sign-in"
        }
      }
    ]
  }
}
```

Lines and character offsets are zero-based; characters count UTF-16 code units. Range ends are
exclusive. Every edit uses the original request's coordinates. Use `textEdit` to replace a whole
identifier or quoted argument, including suffix text after the caret. Without it, the editor
replaces the identifier prefix with `insertText`, or `label` if insertion text is absent.
`additionalTextEdits` share the same document and undo step and must not overlap the main edit.

Items also support `detail`, `filterText`, `sortText`, `insertTextFormat`, `preselect`,
`commitCharacters`, and opaque `id`, matching the public `CompletionItem` model. Documentation
kinds are `PLAIN_TEXT` and `MARKDOWN`; insertion formats are `PLAIN_TEXT` and `SNIPPET`. Supported
snippets insert the first placeholder and do not provide full tab-stop navigation. Omitted optional
fields use the model defaults; the bundled encoder emits defaults explicitly.

`isIncomplete: false` allows the editor to filter the full candidate set locally as an identifier
prefix grows. Use true when candidates depend on the evolving prefix or results are truncated.
The editor still applies local filtering with `filterText` or `label`.

## Lifetime, fallback, and limitations

Create one context-bound service per editor/session or share only across identical immutable
contexts. `BackendLanguageService` copies context attributes and generates unique request IDs.
It discards responses with a different request ID, protocol version, session ID, context revision,
or document version, and discards candidates with out-of-bounds or overlapping edits.

Replace the service after runtime changes. Service replacement invalidates pending results; also
advance `languageServiceRevision` when retaining a mutable provider. The existing controller checks
the document version, caret, and service generation before publishing. The host advances runtime
revision after execution completes or after a newly observed runtime snapshot becomes available.

An explicit empty completion list is authoritative. A null backend response, `completions: null`,
ordinary provider exception, or identity mismatch invokes optional fallback completion. Fallback
is not merged with successful backend items. Cancellation propagates, and an overall editor timeout
ends the request. Error logging records categories without document/exception contents. Fallback's
other language-service operations are not delegated.

Schema-based completion is for a `command name=value` grammar, with quoted/escaped scalar arguments
and one command per line. Shell expansion, positional arguments, nested JSON property completion,
multiword command trees, and type/member inference need a custom provider. The MCP importer is not
a full JSON Schema evaluator: `$ref`, unions, and nested constraints are not resolved. It does not
connect to MCP or subscribe to catalog changes; the host reloads a changed catalog and replaces the
service. Runtime inspection and command execution remain host-owned.
