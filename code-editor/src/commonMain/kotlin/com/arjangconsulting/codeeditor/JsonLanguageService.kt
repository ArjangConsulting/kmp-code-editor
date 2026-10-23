package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Localizable JSON validation messages.
 *
 * @property expectedValue A value is missing or has an invalid starting character.
 * @property expectedKey An object property must start with a quoted string.
 * @property expectedColon A property name must be followed by a colon.
 * @property expectedSeparator A collection requires a comma or its closing delimiter.
 * @property invalidString A string is unterminated or contains an unescaped control character.
 * @property invalidEscape A string contains an invalid escape sequence.
 * @property invalidNumber A number does not follow JSON's number grammar.
 * @property unexpectedContent Content follows the root value.
 * @property nestingLimit The configured nesting limit was exceeded.
 * @property source Source label attached to diagnostics.
 */
@Immutable
public data class JsonLanguageServiceLabels(
    val expectedValue: String = "Expected a JSON value",
    val expectedKey: String = "Expected a quoted property name",
    val expectedColon: String = "Expected ':' after the property name",
    val expectedSeparator: String = "Expected a comma or closing bracket",
    val invalidString: String = "Unterminated string or unescaped control character",
    val invalidEscape: String = "Invalid JSON string escape",
    val invalidNumber: String = "Invalid JSON number",
    val unexpectedContent: String = "Unexpected content after the JSON value",
    val nestingLimit: String = "JSON nesting limit exceeded",
    val source: String = "json",
)

/**
 * Local JSON syntax validation and explicit pretty-printing, available on every platform.
 *
 * Accepts any JSON root value and reports the first syntax error. It does not perform schema or
 * application-specific validation. Formatting changes only whitespace between tokens, preserving
 * property order, duplicate keys, number precision, and string escapes. Invalid input returns null
 * from [format] and is never rewritten. The first line-ending style and presence of a final line
 * ending are preserved; empty collections stay on one line.
 *
 * @param labels Localized diagnostic messages and source label.
 * @param maxNestingDepth Maximum number of nested objects/arrays, default 128. Deeper input reports
 *   a resource-limit diagnostic rather than risking stack exhaustion. Must be between 1 and 128.
 */
public class JsonLanguageService(
    private val labels: JsonLanguageServiceLabels = JsonLanguageServiceLabels(),
    private val maxNestingDepth: Int = 128,
) : LanguageService {
    init {
        require(maxNestingDepth in 1..128) { "maxNestingDepth must be between 1 and 128" }
    }

    /** Returns the first JSON syntax error, or an empty list for valid input. */
    override suspend fun diagnostics(doc: DocumentSnapshot): List<Diagnostic> {
        val context = currentCoroutineContext()
        return try {
            JsonDocumentParser(doc, null, maxNestingDepth) { context.ensureActive() }.parse()
            emptyList()
        } catch (failure: JsonSyntaxFailure) {
            val start = doc.positionOf(failure.offset)
            val end = doc.positionOf(nextCodePoint(doc.text, failure.offset))
            listOf(Diagnostic(TextRange(start, end), message(failure.problem), source = labels.source))
        }
    }

    /** Returns whitespace edits, an empty list if unchanged, or null if input is invalid. */
    override suspend fun format(doc: DocumentSnapshot, options: FormattingOptions): List<TextEdit>? {
        val context = currentCoroutineContext()
        return try {
            JsonDocumentParser(doc, options, maxNestingDepth) { context.ensureActive() }.parse()
        } catch (failure: JsonSyntaxFailure) {
            null
        }
    }

    private fun message(problem: JsonProblem): String = when (problem) {
        JsonProblem.VALUE -> labels.expectedValue
        JsonProblem.KEY -> labels.expectedKey
        JsonProblem.COLON -> labels.expectedColon
        JsonProblem.SEPARATOR -> labels.expectedSeparator
        JsonProblem.STRING -> labels.invalidString
        JsonProblem.ESCAPE -> labels.invalidEscape
        JsonProblem.NUMBER -> labels.invalidNumber
        JsonProblem.TRAILING -> labels.unexpectedContent
        JsonProblem.DEPTH -> labels.nestingLimit
    }
}

private enum class JsonProblem { VALUE, KEY, COLON, SEPARATOR, STRING, ESCAPE, NUMBER, TRAILING, DEPTH }

private class JsonSyntaxFailure(val offset: Int, val problem: JsonProblem) : Exception()

private class JsonDocumentParser(
    private val doc: DocumentSnapshot,
    private val options: FormattingOptions?,
    private val maxDepth: Int,
    private val checkCancellation: () -> Unit,
) {
    private val text = doc.text
    private var offset = 0
    private var previousEnd = 0
    private val edits = ArrayList<TextEdit>()
    private val indent = when {
        options == null -> ""
        options.insertSpaces -> " ".repeat(options.tabSize)
        else -> "\t"
    }
    private val newline = text.indexOfFirst { it == '\r' || it == '\n' }.let { index ->
        when {
            index < 0 || text[index] == '\n' -> "\n"
            text.getOrNull(index + 1) == '\n' -> "\r\n"
            else -> "\r"
        }
    }

    fun parse(): List<TextEdit> {
        checkCancellation()
        value(0, "")
        whitespace()
        if (offset != text.length) fail(JsonProblem.TRAILING)
        val hasFinalNewline = (previousEnd until text.length).any { text[it] == '\r' || text[it] == '\n' }
        replaceWhitespace(previousEnd, text.length, if (hasFinalNewline) newline else "")
        return edits
    }

    private fun value(depth: Int, prefix: String) {
        whitespace()
        when (text.getOrNull(offset)) {
            '{' -> collection(depth, prefix, true)
            '[' -> collection(depth, prefix, false)
            '"' -> string(prefix)
            't' -> literal("true", prefix)
            'f' -> literal("false", prefix)
            'n' -> literal("null", prefix)
            '-', in '0'..'9' -> number(prefix)
            else -> fail(JsonProblem.VALUE)
        }
    }

    private fun collection(depth: Int, prefix: String, isObject: Boolean) {
        if (depth >= maxDepth) fail(JsonProblem.DEPTH)
        token(offset, offset + 1, prefix)
        offset++
        whitespace()
        val close = if (isObject) '}' else ']'
        if (text.getOrNull(offset) == close) {
            token(offset, offset + 1, "")
            offset++
            return
        }
        while (true) {
            val itemPrefix = if (options == null) "" else newline + indent.repeat(depth + 1)
            if (isObject) {
                if (text.getOrNull(offset) != '"') fail(JsonProblem.KEY)
                string(itemPrefix)
                whitespace()
                if (text.getOrNull(offset) != ':') fail(JsonProblem.COLON)
                token(offset, offset + 1, "")
                offset++
                value(depth + 1, " ")
            } else {
                value(depth + 1, itemPrefix)
            }
            whitespace()
            when (text.getOrNull(offset)) {
                close -> {
                    token(offset, offset + 1, if (options == null) "" else newline + indent.repeat(depth))
                    offset++
                    return
                }
                ',' -> {
                    token(offset, offset + 1, "")
                    offset++
                    whitespace()
                }
                else -> fail(JsonProblem.SEPARATOR)
            }
        }
    }

    private fun string(prefix: String) {
        val start = offset++
        while (offset < text.length) {
            checkpoint()
            val character = text[offset++]
            when {
                character == '"' -> {
                    token(start, offset, prefix)
                    return
                }
                character < ' ' -> fail(JsonProblem.STRING, offset - 1)
                character == '\\' -> {
                    val escape = text.getOrNull(offset) ?: fail(JsonProblem.ESCAPE)
                    offset++
                    if (escape == 'u') {
                        repeat(4) {
                            val digit = text.getOrNull(offset)
                            if (digit == null || digit !in '0'..'9' && digit !in 'a'..'f' && digit !in 'A'..'F') fail(JsonProblem.ESCAPE)
                            offset++
                        }
                    } else if (escape !in "\"\\/bfnrt") {
                        fail(JsonProblem.ESCAPE, offset - 1)
                    }
                }
            }
        }
        fail(JsonProblem.STRING)
    }

    private fun number(prefix: String) {
        val start = offset
        if (text.getOrNull(offset) == '-') offset++
        when (text.getOrNull(offset)) {
            '0' -> offset++
            in '1'..'9' -> digits()
            else -> fail(JsonProblem.NUMBER)
        }
        if (text.getOrNull(offset) == '.') {
            offset++
            requireDigits()
        }
        if (text.getOrNull(offset) == 'e' || text.getOrNull(offset) == 'E') {
            offset++
            if (text.getOrNull(offset) == '+' || text.getOrNull(offset) == '-') offset++
            requireDigits()
        }
        token(start, offset, prefix)
    }

    private fun requireDigits() {
        if (text.getOrNull(offset) !in '0'..'9') fail(JsonProblem.NUMBER)
        digits()
    }

    private fun digits() {
        while (text.getOrNull(offset) in '0'..'9') {
            checkpoint()
            offset++
        }
    }

    private fun literal(expected: String, prefix: String) {
        val start = offset
        for (character in expected) {
            if (text.getOrNull(offset) != character) fail(JsonProblem.VALUE)
            offset++
        }
        token(start, offset, prefix)
    }

    private fun whitespace() {
        while (text.getOrNull(offset)?.let { it == ' ' || it == '\t' || it == '\r' || it == '\n' } == true) {
            checkpoint()
            offset++
        }
    }

    private fun token(start: Int, end: Int, prefix: String) {
        checkCancellation()
        replaceWhitespace(previousEnd, start, prefix)
        previousEnd = end
    }

    private fun replaceWhitespace(start: Int, end: Int, replacement: String) {
        if (options != null && text.substring(start, end) != replacement) {
            edits += TextEdit(TextRange(doc.positionOf(start), doc.positionOf(end)), replacement)
        }
    }

    private fun checkpoint() {
        if (offset % 1024 == 0) checkCancellation()
    }

    private fun fail(problem: JsonProblem, at: Int = offset): Nothing = throw JsonSyntaxFailure(at, problem)
}
