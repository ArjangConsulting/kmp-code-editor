package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Immutable
import it.krzeminski.snakeyaml.engine.kmp.api.DumpSettings
import it.krzeminski.snakeyaml.engine.kmp.api.LoadSettings
import it.krzeminski.snakeyaml.engine.kmp.api.lowlevel.Parse
import it.krzeminski.snakeyaml.engine.kmp.api.lowlevel.Present
import it.krzeminski.snakeyaml.engine.kmp.events.Event
import it.krzeminski.snakeyaml.engine.kmp.exceptions.MarkedYamlEngineException
import it.krzeminski.snakeyaml.engine.kmp.exceptions.YamlEngineException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Localizable YAML syntax validation messages.
 *
 * @property invalidSyntax The document cannot be parsed as YAML.
 * @property nestingLimit The configured collection nesting limit was exceeded.
 * @property documentTooLarge The document exceeds the parser's size limit.
 * @property source Source label attached to diagnostics.
 */
@Immutable
public data class YamlLanguageServiceLabels(
    val invalidSyntax: String = "Invalid YAML syntax",
    val nestingLimit: String = "YAML nesting limit exceeded",
    val documentTooLarge: String = "YAML document size limit exceeded",
    val source: String = "yaml",
)

/**
 * Local YAML syntax validation and explicit formatting for `.yaml` and `.yml` on every platform.
 *
 * Parses and emits YAML events without constructing application objects or expanding aliases.
 * Preserves scalar values, mapping order, duplicate keys, comments, anchors, aliases, tags, and
 * document boundaries. Scalar spelling and whitespace may be normalized; output ends with a line
 * break using the input's first line-ending style. Empty or comment-only input is left unchanged.
 * This is syntax validation, not schema validation or alias resolution.
 *
 * YAML requires spaces for indentation. [format] returns null for tab indentation, indentation
 * outside 1..10 columns, invalid syntax, or documents exceeding the resource limits. Successful
 * formatting participates in the usual [CodeEditorState.format] undo and stale-result checks.
 *
 * @param labels Localized diagnostic messages and source label.
 * @param maxNestingDepth Maximum collection nesting, between 1 and 128 inclusive.
 */
public class YamlLanguageService(
    private val labels: YamlLanguageServiceLabels = YamlLanguageServiceLabels(),
    private val maxNestingDepth: Int = 128,
) : LanguageService {
    init {
        require(maxNestingDepth in 1..128) { "maxNestingDepth must be between 1 and 128" }
    }

    /** Returns the first syntax or resource-limit error, or an empty list for valid YAML. */
    override suspend fun diagnostics(doc: DocumentSnapshot): List<Diagnostic> = try {
        events(doc)
        emptyList()
    } catch (failure: YamlDocumentFailure) {
        val start = doc.positionOf(failure.offset)
        val end = doc.positionOf(nextCodePoint(doc.text, failure.offset))
        listOf(Diagnostic(TextRange(start, end), failure.message, source = labels.source))
    }

    /** Returns a minimal replacement, an empty list if unchanged, or null when unavailable. */
    override suspend fun format(doc: DocumentSnapshot, options: FormattingOptions): List<TextEdit>? {
        if (!options.insertSpaces || options.tabSize !in 1..10) return null
        val parsed = try {
            events(doc)
        } catch (failure: YamlDocumentFailure) {
            return null
        }
        if (parsed.none { it.eventId == Event.ID.DocumentStart }) return emptyList()
        val context = currentCoroutineContext()
        val newlineOffset = doc.text.indexOfFirst { it == '\r' || it == '\n' }
        val newline = when {
            newlineOffset < 0 || doc.text[newlineOffset] == '\n' -> "\n"
            doc.text.getOrNull(newlineOffset + 1) == '\n' -> "\r\n"
            else -> "\r"
        }
        val settings = DumpSettings(
            indent = options.tabSize,
            dumpComments = true,
            isSplitLines = false,
            bestLineBreak = newline,
        )
        val output = Present(settings).emitToString(parsed.asSequence().onEach { context.ensureActive() }.iterator())
        context.ensureActive()
        val diff = diffText(doc.text, output)
        if (diff.isNoOp) return emptyList()
        return listOf(TextEdit(TextRange(doc.positionOf(diff.start), doc.positionOf(diff.oldEnd)), diff.newText))
    }

    private suspend fun events(doc: DocumentSnapshot): List<Event> {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (doc.text.length > YAML_SIZE_LIMIT) throw YamlDocumentFailure(0, labels.documentTooLarge)
        var depth = 0
        return try {
            Parse(LoadSettings(parseComments = true, codePointLimit = YAML_SIZE_LIMIT)).parse(doc.text).map { event ->
                context.ensureActive()
                when (event.eventId) {
                    Event.ID.MappingStart, Event.ID.SequenceStart -> {
                        depth++
                        if (depth > maxNestingDepth) {
                            val offset = markOffset(doc, event.startMark?.line, event.startMark?.column)
                            throw YamlDocumentFailure(offset, labels.nestingLimit)
                        }
                    }
                    Event.ID.MappingEnd, Event.ID.SequenceEnd -> depth--
                    else -> Unit
                }
                event
            }
        } catch (failure: MarkedYamlEngineException) {
            val offset = markOffset(doc, failure.problemMark?.line, failure.problemMark?.column)
            throw YamlDocumentFailure(offset, labels.invalidSyntax)
        } catch (failure: YamlEngineException) {
            throw YamlDocumentFailure(0, labels.invalidSyntax)
        }
    }
}

private const val YAML_SIZE_LIMIT = 3 * 1024 * 1024

private class YamlDocumentFailure(val offset: Int, override val message: String) : Exception(message)

private fun markOffset(doc: DocumentSnapshot, line: Int?, column: Int?): Int {
    val lineIndex = (line ?: 0).coerceIn(0, doc.lineCount - 1)
    val content = doc.line(lineIndex)
    var utf16Column = 0
    repeat((column ?: 0).coerceAtMost(content.length)) {
        utf16Column = nextCodePoint(content, utf16Column)
    }
    return doc.offsetOf(TextPosition(lineIndex, utf16Column))
}
