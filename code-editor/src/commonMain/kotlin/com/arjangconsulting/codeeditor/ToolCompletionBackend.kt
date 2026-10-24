package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Runtime argument completion context for a schema-based add-on.
 *
 * @property request Original document, session, and correlation information.
 * @property command Command being edited.
 * @property parameter Argument whose value is being edited.
 * @property prefix Decoded value before the caret, without surrounding quotes.
 * @property replacementRange Range of the entire existing value, including quotes and suffix.
 */
@Immutable
public data class ToolValueRequest(
    val request: CompletionRequest,
    val command: ToolCommand,
    val parameter: ToolParameter,
    val prefix: String,
    val replacementRange: TextRange,
)

/** Optional add-on hook for runtime values such as element IDs, paths, or session variables. */
public fun interface ToolValueProvider {
    /** Returns raw values, or null to use schema values. Empty lists deliberately suppress values. */
    public suspend fun values(request: ToolValueRequest): List<String>?
}

/** Localizable labels for schema-based tool completion. */
@Immutable
public data class ToolCompletionLabels(
    /** Detail shown for an argument required by the tool schema. */
    val required: String = "required",
)

/**
 * Source-free completion add-on driven by a [ToolCommandCatalog] and optional runtime value hook.
 *
 * Completes `command name=value` syntax, handles quoted/escaped values and partially typed input,
 * omits arguments already supplied, and replaces complete tokens when editing in their middle.
 * Runtime value lists are marked incomplete so the editor re-queries while the prefix grows.
 * This grammar is independent of the tool's execution transport; the host dispatches submissions.
 *
 * @param catalog Metadata discovered from MCP or supplied by an external add-on.
 * @param valueProvider Optional runtime completion through existing public tool APIs.
 * @param labels Localizable completion labels.
 */
public class ToolCompletionBackend(
    private val catalog: ToolCommandCatalog,
    private val valueProvider: ToolValueProvider? = null,
    private val labels: ToolCompletionLabels = ToolCompletionLabels(),
) : CompletionBackend {
    /** Completes commands, argument names, schema enums, or host-supplied runtime values. */
    override suspend fun complete(request: CompletionRequest): CompletionResponse {
        require(request.protocolVersion == CompletionProtocol.VERSION) { "Unsupported completion protocol version" }
        currentCoroutineContext().ensureActive()
        val snapshot = DocumentSnapshot(request.text, request.documentVersion)
        val lineIndex = request.position.line
        val line = snapshot.line(lineIndex)
        val caret = request.position.character
        val parsed = parseToolLine(line)
        if (parsed.commentStart != null && caret >= parsed.commentStart) return request.response(CompletionList.Empty)
        val active = parsed.words.firstOrNull { caret in it.start..it.end }
        fun range(start: Int, end: Int): TextRange =
            TextRange(TextPosition(lineIndex, start), TextPosition(lineIndex, end))
        if (parsed.words.isEmpty() || active === parsed.words.first() || caret < parsed.words.first().start) {
            val replacement = active?.let { range(it.start, it.end) } ?: range(caret, caret)
            return request.response(CompletionList(catalog.commands.map { command ->
                CompletionItem(
                    label = command.name,
                    kind = CompletionItemKind.FUNCTION,
                    documentation = command.description?.let(MarkupContent::plain),
                    textEdit = TextEdit(replacement, command.name),
                )
            }))
        }
        val command = catalog.commands.firstOrNull { it.name == parsed.words.first().raw }
            ?: return request.response(CompletionList.Empty)
        val equals = active?.raw?.indexOf('=') ?: -1
        if (active != null && equals >= 0 && caret > active.start + equals) {
            val parameter = command.parameters.firstOrNull { it.name == active.raw.substring(0, equals) }
                ?: return request.response(CompletionList.Empty)
            val valueStart = active.start + equals + 1
            val replacement = range(valueStart, active.end)
            val prefix = decodeToolValue(line.substring(valueStart, caret))
            val dynamic = valueProvider?.values(ToolValueRequest(request, command, parameter, prefix, replacement))
            currentCoroutineContext().ensureActive()
            val values = dynamic ?: parameter.values.ifEmpty {
                if (parameter.kind == ToolValueKind.BOOLEAN) listOf("true", "false") else emptyList()
            }
            return request.response(CompletionList(
                items = values.distinct().map { value ->
                    CompletionItem(
                        label = value,
                        kind = CompletionItemKind.VALUE,
                        textEdit = TextEdit(replacement, encodeToolValue(value)),
                    )
                },
                isIncomplete = dynamic != null,
            ))
        }
        val used = parsed.words.drop(1).filter { it !== active }
            .mapNotNull { it.raw.substringBefore('=', "").ifEmpty { null } }.toSet()
        val replacement = if (active != null) {
            range(active.start, if (equals >= 0) active.start + equals else active.end)
        } else {
            range(caret, caret)
        }
        return request.response(CompletionList(command.parameters.filter { it.name !in used }.map { parameter ->
            val insert = if (equals >= 0) {
                parameter.name
            } else {
                when (parameter.kind) {
                    ToolValueKind.STRING -> "${parameter.name}=\"\$1\""
                    else -> "${parameter.name}=\$1"
                }
            }
            CompletionItem(
                label = "${parameter.name}=",
                filterText = parameter.name,
                kind = CompletionItemKind.PROPERTY,
                detail = if (parameter.required) labels.required else null,
                documentation = parameter.description?.let(MarkupContent::plain),
                textEdit = TextEdit(replacement, insert),
                insertTextFormat = if (equals >= 0) InsertTextFormat.PLAIN_TEXT else InsertTextFormat.SNIPPET,
                sortText = (if (parameter.required) "0" else "1") + parameter.name,
            )
        }))
    }
}

private class ToolWord(val start: Int, val end: Int, val raw: String)
private class ToolLine(val words: List<ToolWord>, val commentStart: Int?)

private fun parseToolLine(line: String): ToolLine {
    val words = ArrayList<ToolWord>()
    var offset = 0
    while (offset < line.length) {
        while (offset < line.length && line[offset].isWhitespace()) offset++
        if (offset >= line.length) break
        if (line[offset] == '#') return ToolLine(words, offset)
        val start = offset
        var quote: Char? = null
        while (offset < line.length) {
            val character = line[offset]
            if (character == '\\' && quote != '\'') {
                offset = (offset + 2).coerceAtMost(line.length)
                continue
            }
            if (character == quote) {
                quote = null
            } else if (quote == null) {
                if (character.isWhitespace()) break
                if (character == '\'' || character == '"') quote = character
            }
            offset++
        }
        words += ToolWord(start, offset, line.substring(start, offset))
    }
    return ToolLine(words, null)
}

private fun decodeToolValue(raw: String): String = buildString {
    var quote: Char? = null
    var offset = 0
    while (offset < raw.length) {
        val character = raw[offset++]
        when {
            character == '\\' && quote != '\'' && offset < raw.length -> append(raw[offset++])
            character == quote -> quote = null
            quote == null && (character == '\'' || character == '"') -> quote = character
            else -> append(character)
        }
    }
}

private fun encodeToolValue(value: String): String {
    if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "_./:@+-" }) return value
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
