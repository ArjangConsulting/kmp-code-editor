package com.arjangconsulting.codeeditor.sample

import com.arjangconsulting.codeeditor.CompletionItem
import com.arjangconsulting.codeeditor.CompletionItemKind
import com.arjangconsulting.codeeditor.CompletionList
import com.arjangconsulting.codeeditor.CompletionTrigger
import com.arjangconsulting.codeeditor.Diagnostic
import com.arjangconsulting.codeeditor.DiagnosticSeverity
import com.arjangconsulting.codeeditor.DocumentSnapshot
import com.arjangconsulting.codeeditor.Hover
import com.arjangconsulting.codeeditor.InsertTextFormat
import com.arjangconsulting.codeeditor.LanguageService
import com.arjangconsulting.codeeditor.MarkupContent
import com.arjangconsulting.codeeditor.TextEdit
import com.arjangconsulting.codeeditor.TextPosition
import com.arjangconsulting.codeeditor.TextRange
import com.arjangconsulting.codeeditor.TokenKind
import com.arjangconsulting.codeeditor.tokenRules
import kotlinx.coroutines.delay

/** A tiny device-automation command language, defined with the TokenRules DSL. */
val CommandRules = tokenRules("commands") {
    keywords("tap", "type", "swipe", "scroll", "wait", "assert", "launch", "screenshot")
    constants("true", "false", "up", "down", "left", "right")
    attributeAssignments()
    lineComment("#")
    string("\"")
    numbers()
    operators("=")
    pattern("@[a-z]+", TokenKind.ANNOTATION)
    autoClose('"' to '"')
}

/** An attribute of a command. */
data class CommandAttribute(val name: String, val doc: String, val required: Boolean = false, val values: List<String> = emptyList())

/** A command with documentation and attributes. */
data class CommandSpec(val name: String, val doc: String, val attributes: List<CommandAttribute>)

val CommandSpecs = listOf(
    CommandSpec("tap", "Taps the element matching `id` or `text`.", listOf(CommandAttribute("id", "Accessibility identifier."), CommandAttribute("text", "Visible label."), CommandAttribute("count", "Number of taps."))),
    CommandSpec("type", "Types `text` into the focused field.", listOf(CommandAttribute("text", "Text to type.", required = true), CommandAttribute("submit", "Press return afterwards.", values = listOf("true", "false")))),
    CommandSpec("swipe", "Swipes in a `direction`.", listOf(CommandAttribute("direction", "Swipe direction.", required = true, values = listOf("up", "down", "left", "right")), CommandAttribute("id", "Element to swipe on."))),
    CommandSpec("scroll", "Scrolls until `id` is visible.", listOf(CommandAttribute("id", "Target element.", required = true), CommandAttribute("direction", "Scroll direction.", values = listOf("up", "down")))),
    CommandSpec("wait", "Waits for `seconds` or until `id` appears.", listOf(CommandAttribute("seconds", "Duration in seconds."), CommandAttribute("id", "Element to wait for."))),
    CommandSpec("assert", "Asserts that `id` is visible (or has `value`).", listOf(CommandAttribute("id", "Element.", required = true), CommandAttribute("value", "Expected value."))),
    CommandSpec("launch", "Launches the app under test.", listOf(CommandAttribute("bundle", "Bundle identifier or package name.", required = true))),
    CommandSpec("screenshot", "Captures a screenshot.", listOf(CommandAttribute("name", "File name."))),
)

private val wordPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * A demo [LanguageService] for [CommandRules] with simulated network latency: command and attribute
 * completion (as snippets), value completion for enumerated attributes, diagnostics for unknown
 * commands/attributes and missing required attributes, and hover documentation.
 */
class CommandLanguageService(private val latencyMillis: () -> Long) : LanguageService {
    override val triggerCharacters: Set<Char> = setOf(' ', '=')

    override suspend fun complete(doc: DocumentSnapshot, position: TextPosition, trigger: CompletionTrigger): CompletionList {
        delay(latencyMillis())
        val before = doc.line(position.line).take(position.character)
        if (before.trimStart().startsWith("#")) return CompletionList.Empty
        val words = before.trimStart().split(' ')
        if (words.size <= 1) {
            return CompletionList(
                CommandSpecs.map { spec ->
                    CompletionItem(
                        label = spec.name,
                        kind = CompletionItemKind.FUNCTION,
                        detail = spec.attributes.filter { it.required }.joinToString(" ") { "${it.name}=" }.ifEmpty { null },
                        documentation = MarkupContent.markdown(spec.doc),
                    )
                },
            )
        }
        val spec = CommandSpecs.firstOrNull { it.name == words.first() } ?: return CompletionList.Empty
        val current = words.last()
        val eq = current.indexOf('=')
        if (eq >= 0) {
            val attribute = spec.attributes.firstOrNull { it.name == current.substring(0, eq) } ?: return CompletionList.Empty
            return CompletionList(attribute.values.map { CompletionItem(it, kind = CompletionItemKind.ENUM_MEMBER) })
        }
        val used = words.dropLast(1).mapNotNull { it.substringBefore('=', "").ifEmpty { null } }.toSet()
        return CompletionList(
            spec.attributes.filter { it.name !in used }.map { attribute ->
                CompletionItem(
                    label = "${attribute.name}=",
                    filterText = attribute.name,
                    kind = CompletionItemKind.PROPERTY,
                    detail = if (attribute.required) "required" else null,
                    documentation = MarkupContent.plain(attribute.doc),
                    insertText = if (attribute.values.isEmpty()) "${attribute.name}=\"$1\"" else "${attribute.name}=$1",
                    insertTextFormat = InsertTextFormat.SNIPPET,
                    sortText = (if (attribute.required) "0" else "1") + attribute.name,
                )
            },
        )
    }

    override suspend fun diagnostics(doc: DocumentSnapshot): List<Diagnostic> {
        delay(latencyMillis())
        val result = ArrayList<Diagnostic>()
        for (line in 0 until doc.lineCount) {
            val text = doc.line(line)
            val trimmed = text.trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = text.length - trimmed.length
            val name = wordPattern.find(trimmed)?.takeIf { it.range.first == 0 }?.value ?: continue
            val nameRange = TextRange(TextPosition(line, indent), TextPosition(line, indent + name.length))
            val spec = CommandSpecs.firstOrNull { it.name == name }
            if (spec == null) {
                result += Diagnostic(nameRange, "Unknown command '$name'", source = "commands", code = "E001")
                continue
            }
            val attributes = Regex("([A-Za-z_]+)=").findAll(trimmed).map { it.groupValues[1] to it.range.first }.toList()
            for ((attribute, column) in attributes) {
                if (spec.attributes.none { it.name == attribute }) {
                    val start = indent + column
                    result += Diagnostic(
                        TextRange(TextPosition(line, start), TextPosition(line, start + attribute.length)),
                        "'$name' has no attribute '$attribute'",
                        DiagnosticSeverity.WARNING,
                        source = "commands",
                    )
                }
            }
            spec.attributes.filter { it.required && attributes.none { (a, _) -> a == it.name } }.forEach {
                result += Diagnostic(nameRange, "'$name' requires '${it.name}='", source = "commands", code = "E002")
            }
        }
        return result
    }

    override suspend fun hover(doc: DocumentSnapshot, position: TextPosition): Hover? {
        delay(latencyMillis() / 2)
        val line = doc.line(position.line)
        val match = wordPattern.findAll(line).firstOrNull { position.character in it.range.first..it.range.last + 1 } ?: return null
        val range = TextRange(TextPosition(position.line, match.range.first), TextPosition(position.line, match.range.last + 1))
        CommandSpecs.firstOrNull { it.name == match.value }?.let { spec ->
            val attributes = spec.attributes.joinToString("\n") { "- `${it.name}`${if (it.required) " (required)" else ""}: ${it.doc}" }
            return Hover(MarkupContent.markdown("**${spec.name}**\n\n${spec.doc}\n\n$attributes"), range)
        }
        val command = wordPattern.find(line.trimStart())?.value
        val attribute = CommandSpecs.firstOrNull { it.name == command }?.attributes?.firstOrNull { it.name == match.value } ?: return null
        return Hover(MarkupContent.plain(attribute.doc), range)
    }
}

/** A field in the tiny JSON schema. */
data class SchemaField(val name: String, val doc: String, val values: List<String> = emptyList(), val type: String = "string")

/** The schema for the JSON demo: a mock-server configuration with a `routes` array. */
object MockServerSchema {
    val root = listOf(
        SchemaField("name", "Display name of the mock server."),
        SchemaField("port", "TCP port to listen on.", type = "number"),
        SchemaField("mode", "How requests are handled.", listOf("mock", "proxy", "record")),
        SchemaField("routes", "Route definitions.", type = "array"),
    )
    val route = listOf(
        SchemaField("path", "Request path, e.g. /users/{id}."),
        SchemaField("method", "HTTP method.", listOf("GET", "POST", "PUT", "DELETE")),
        SchemaField("status", "Response status code.", type = "number"),
        SchemaField("body", "Response body.", type = "object"),
    )
}

/**
 * JSON completion and validation from [MockServerSchema]: key completion inside objects (with the
 * right fields for the root vs. route objects), enum value completion, syntax errors, and unknown-key
 * warnings.
 */
class JsonSchemaLanguageService(private val latencyMillis: () -> Long) : LanguageService {
    override val triggerCharacters: Set<Char> = setOf('"', ':')

    override suspend fun complete(doc: DocumentSnapshot, position: TextPosition, trigger: CompletionTrigger): CompletionList {
        delay(latencyMillis())
        val offset = doc.offsetOf(position)
        val text = doc.text
        val context = JsonContext.at(text, offset)
        val fields = if (context.path.lastOrNull() == "routes") MockServerSchema.route else MockServerSchema.root
        if (context.isKey) {
            val start = context.tokenStart
            val range = TextRange(doc.positionOf(start), position)
            return CompletionList(
                fields.filter { it.name !in context.existingKeys }.map { field ->
                    val value = when (field.type) {
                        "number" -> "\${1:0}"
                        "array" -> "[\$1]"
                        "object" -> "{\$1}"
                        else -> "\"\$1\""
                    }
                    CompletionItem(
                        label = field.name,
                        kind = CompletionItemKind.PROPERTY,
                        detail = field.type,
                        documentation = MarkupContent.plain(field.doc),
                        textEdit = TextEdit(range, "\"${field.name}\": $value"),
                        filterText = "\"${field.name}",
                        insertTextFormat = InsertTextFormat.SNIPPET,
                    )
                },
            )
        }
        val key = context.currentKey ?: return CompletionList.Empty
        val field = fields.firstOrNull { it.name == key } ?: return CompletionList.Empty
        val range = TextRange(doc.positionOf(context.tokenStart), position)
        return CompletionList(
            field.values.map {
                CompletionItem(it, kind = CompletionItemKind.ENUM_MEMBER, textEdit = TextEdit(range, "\"$it\""), filterText = "\"$it")
            },
        )
    }

    override suspend fun diagnostics(doc: DocumentSnapshot): List<Diagnostic> {
        delay(latencyMillis())
        val error = MiniJson.validate(doc.text)
        if (error != null) {
            val position = doc.positionOf(error.second)
            return listOf(Diagnostic(TextRange(position, TextPosition(position.line, position.character + 1)), error.first, source = "json"))
        }
        val result = ArrayList<Diagnostic>()
        Regex("\"([A-Za-z_]+)\"\\s*:").findAll(doc.text).forEach { match ->
            val key = match.groupValues[1]
            val path = JsonContext.at(doc.text, match.range.first).path
            val fields = if (path.lastOrNull() == "routes") MockServerSchema.route else if (path.isEmpty()) MockServerSchema.root else return@forEach
            if (fields.none { it.name == key }) {
                result += Diagnostic(
                    TextRange(doc.positionOf(match.range.first), doc.positionOf(match.range.first + key.length + 2)),
                    "Unknown property '$key'",
                    DiagnosticSeverity.WARNING,
                    source = "schema",
                )
            }
        }
        return result
    }

    override suspend fun hover(doc: DocumentSnapshot, position: TextPosition): Hover? {
        val line = doc.line(position.line)
        val match = Regex("\"([A-Za-z_]+)\"\\s*:").findAll(line).firstOrNull { position.character in it.range } ?: return null
        val field = (MockServerSchema.root + MockServerSchema.route).firstOrNull { it.name == match.groupValues[1] } ?: return null
        return Hover(MarkupContent.markdown("`${field.name}`: ${field.type}\n\n${field.doc}"))
    }
}

/** Where the caret is in a JSON text: object path, key vs. value position, and current token. */
private data class JsonContext(val path: List<String>, val isKey: Boolean, val tokenStart: Int, val currentKey: String?, val existingKeys: Set<String>) {
    companion object {
        fun at(text: String, offset: Int): JsonContext {
            val stack = ArrayList<Pair<Char, String?>>()
            var lastKey: String? = null
            val keysPerLevel = ArrayList<MutableSet<String>>().apply { add(HashSet()) }
            var i = 0
            var lastSignificant = ' '
            var inString = false
            var stringStart = -1
            while (i < offset && i < text.length) {
                val c = text[i]
                if (inString) {
                    if (c == '\\') {
                        i += 2
                        continue
                    }
                    if (c == '"') {
                        inString = false
                        val value = text.substring(stringStart + 1, i)
                        val next = text.substring(i + 1).trimStart().firstOrNull()
                        if (next == ':') {
                            lastKey = value
                            keysPerLevel.last().add(value)
                        }
                        lastSignificant = '"'
                    }
                } else {
                    when (c) {
                        '"' -> {
                            inString = true
                            stringStart = i
                        }
                        '{', '[' -> {
                            stack += c to lastKey
                            keysPerLevel.add(HashSet())
                            lastSignificant = c
                        }
                        '}', ']' -> {
                            if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                            if (keysPerLevel.size > 1) keysPerLevel.removeAt(keysPerLevel.size - 1)
                            lastSignificant = c
                        }
                        ',', ':' -> lastSignificant = c
                        else -> if (!c.isWhitespace()) lastSignificant = c
                    }
                }
                i++
            }
            val tokenStart = if (inString) stringStart else offset
            val significantBeforeToken = if (inString) {
                text.substring(0, stringStart).trimEnd().lastOrNull() ?: ' '
            } else {
                lastSignificant
            }
            val path = stack.filter { it.first == '{' || it.first == '[' }.mapNotNull { it.second }
            val inObject = stack.lastOrNull()?.first == '{'
            val isKey = inObject && (significantBeforeToken == '{' || significantBeforeToken == ',')
            return JsonContext(path, isKey, tokenStart, lastKey.takeIf { significantBeforeToken == ':' }, keysPerLevel.last())
        }
    }
}

/** A minimal JSON validator that reports the first syntax error and its offset. */
private object MiniJson {
    fun validate(text: String): Pair<String, Int>? {
        val parser = Parser(text)
        return try {
            parser.skip()
            if (parser.pos < text.length) parser.value()
            parser.skip()
            if (parser.pos < text.length) throw Failure("Unexpected '${text[parser.pos]}'", parser.pos)
            null
        } catch (failure: Failure) {
            failure.message.orEmpty() to failure.offset.coerceIn(0, maxOf(0, text.length - 1))
        }
    }

    private class Failure(message: String, val offset: Int) : Exception(message)

    private class Parser(val text: String) {
        var pos = 0

        fun skip() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun value() {
            skip()
            if (pos >= text.length) throw Failure("Unexpected end of input", pos)
            when (val c = text[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> if (c == '-' || c.isDigit()) number() else throw Failure("Unexpected '$c'", pos)
            }
        }

        fun obj() {
            pos++
            skip()
            if (text.getOrNull(pos) == '}') {
                pos++
                return
            }
            while (true) {
                skip()
                if (text.getOrNull(pos) != '"') throw Failure("Expected a property name", pos)
                string()
                skip()
                if (text.getOrNull(pos) != ':') throw Failure("Expected ':'", pos)
                pos++
                value()
                skip()
                when (text.getOrNull(pos)) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return
                    }
                    else -> throw Failure("Expected ',' or '}'", pos)
                }
            }
        }

        fun array() {
            pos++
            skip()
            if (text.getOrNull(pos) == ']') {
                pos++
                return
            }
            while (true) {
                value()
                skip()
                when (text.getOrNull(pos)) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return
                    }
                    else -> throw Failure("Expected ',' or ']'", pos)
                }
            }
        }

        fun string() {
            val start = pos
            pos++
            while (pos < text.length && text[pos] != '"') {
                if (text[pos] == '\n') throw Failure("Unterminated string", start)
                if (text[pos] == '\\') pos++
                pos++
            }
            if (pos >= text.length) throw Failure("Unterminated string", start)
            pos++
        }

        fun number() {
            val start = pos
            if (text[pos] == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            if (text.substring(start, pos).toDoubleOrNull() == null) throw Failure("Invalid number", start)
        }

        fun literal(word: String) {
            if (!text.startsWith(word, pos)) throw Failure("Unexpected '${text[pos]}'", pos)
            pos += word.length
        }
    }
}
