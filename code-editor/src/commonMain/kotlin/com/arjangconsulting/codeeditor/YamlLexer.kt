package com.arjangconsulting.codeeditor

private const val YAML_SINGLE_QUOTED = 1
private const val YAML_DOUBLE_QUOTED = 2
private const val YAML_BLOCK_SCALAR = 3
private const val YAML_MODE_MASK = 3
private const val YAML_MODE_BITS = 2
private const val YAML_INFER_INDENTATION = 4
private const val YAML_INDENTATION_BITS = 3

private val yamlNumber = Regex(
    "[-+]?(?:0x[0-9a-fA-F]+|0o[0-7]+|[0-9]+(?:\\.[0-9]*)?(?:[eE][-+]?[0-9]+)?|" +
        "\\.[0-9]+(?:[eE][-+]?[0-9]+)?|\\.(?:inf|Inf|INF))|\\.(?:nan|NaN|NAN)",
)
private val yamlConstants = setOf("true", "True", "TRUE", "false", "False", "FALSE", "null", "Null", "NULL", "~")

internal fun tokenizeYamlLine(line: String, state: LexerState): LexedLine = YamlLineLexer(line, state).run()

private class YamlLineLexer(private val line: String, private val startState: LexerState) {
    private val tokens = ArrayList<Token>()
    private var position = 0
    private var state = LexerState.Initial
    private val startMode = startState.value and YAML_MODE_MASK
    private var flowDepth = if (startMode == YAML_BLOCK_SCALAR) 0 else startState.value ushr YAML_MODE_BITS

    fun run(): LexedLine {
        val indentation = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
        if (startMode == YAML_BLOCK_SCALAR) {
            val minimumIndentation = startState.value ushr YAML_INDENTATION_BITS
            if (line.isBlank() || indentation >= minimumIndentation) {
                add(0, line.length, TokenKind.STRING)
                val inferredState = if (startState.value and YAML_INFER_INDENTATION != 0 && !line.isBlank()) {
                    LexerState((indentation shl YAML_INDENTATION_BITS) or YAML_BLOCK_SCALAR)
                } else {
                    startState
                }
                return LexedLine(tokens, inferredState, startState)
            }
        } else if (startMode == YAML_SINGLE_QUOTED || startMode == YAML_DOUBLE_QUOTED) {
            quoted(if (startMode == YAML_SINGLE_QUOTED) '\'' else '"', continuation = true)
        }
        while (position < line.length) {
            val character = line[position]
            when {
                character.isWhitespace() -> position++
                character == '#' -> {
                    add(position, line.length, TokenKind.COMMENT)
                    position = line.length
                }
                position == 0 && character == '%' -> {
                    add(position, line.length, TokenKind.PREPROCESSOR)
                    position = line.length
                }
                position == 0 && (line.startsWith("---") || line.startsWith("...")) && separated(3) -> {
                    add(position, 3, TokenKind.PREPROCESSOR)
                    position = 3
                }
                character == '\'' || character == '"' -> quoted(character)
                character in "[{" -> {
                    flowDepth++
                    punctuation()
                }
                character in "]}" -> {
                    flowDepth = (flowDepth - 1).coerceAtLeast(0)
                    punctuation()
                }
                character == ',' || character == ':' && (flowDepth > 0 || separated(position + 1)) ||
                    character in "-?" && separated(position + 1) -> punctuation()
                character in "&*!" -> reference()
                character in "|>" && flowDepth == 0 -> blockScalar(indentation)
                else -> plain()
            }
        }
        if (state == LexerState.Initial) state = LexerState(flowDepth shl YAML_MODE_BITS)
        return LexedLine(tokens, state, startState)
    }

    private fun separated(offset: Int): Boolean =
        offset >= line.length || line[offset].isWhitespace() || line[offset] in ",[]{}"

    private fun add(start: Int, end: Int, kind: TokenKind) {
        if (end > start) tokens += Token(start, end, kind)
    }

    private fun punctuation() {
        add(position, position + 1, TokenKind.PUNCTUATION)
        position++
    }

    private fun quoted(quote: Char, continuation: Boolean = false) {
        val start = position
        if (!continuation) position++
        var segment = start
        var closed = false
        while (position < line.length) {
            when {
                quote == '"' && line[position] == '\\' -> {
                    add(segment, position, TokenKind.STRING)
                    val end = (position + 2).coerceAtMost(line.length)
                    add(position, end, TokenKind.ESCAPE)
                    position = end
                    segment = end
                }
                line[position] == quote -> {
                    position++
                    if (quote == '\'' && line.getOrNull(position) == '\'') {
                        position++
                    } else {
                        closed = true
                        break
                    }
                }
                else -> position++
            }
        }
        add(segment, position, TokenKind.STRING)
        val after = skipSpaces(position)
        if (closed && line.getOrNull(after) == ':' && (flowDepth > 0 || separated(after + 1))) {
            for (index in tokens.indices) {
                val token = tokens[index]
                if (token.start >= start && token.kind == TokenKind.STRING) {
                    tokens[index] = token.copy(kind = TokenKind.PROPERTY)
                }
            }
        }
        if (!closed) {
            val quoteMode = if (quote == '\'') YAML_SINGLE_QUOTED else YAML_DOUBLE_QUOTED
            state = LexerState((flowDepth shl YAML_MODE_BITS) or quoteMode)
        }
    }

    private fun reference() {
        val start = position++
        if (line[start] == '!' && line.getOrNull(position) == '<') {
            while (position < line.length && line[position] != '>') position++
            if (position < line.length) position++
        } else {
            while (position < line.length && !line[position].isWhitespace() && line[position] !in ",[]{}") position++
        }
        add(start, position, if (line[start] == '!') TokenKind.ANNOTATION else TokenKind.VARIABLE)
    }

    private fun blockScalar(indentation: Int) {
        val start = position++
        var explicitIndentation = 0
        while (position < line.length && (line[position] in "+-" || line[position] in '1'..'9')) {
            if (line[position] in '1'..'9') explicitIndentation = line[position].digitToInt()
            position++
        }
        add(start, position, TokenKind.STRING)
        val sequencePrefix = line.substring(indentation, start).takeWhile { it == '-' || it == ' ' }
        val sequenceMapping = sequencePrefix.startsWith("- ") &&
            line.substring(indentation, start).trimEnd().endsWith(":")
        val sequenceIndentation = if (sequenceMapping) sequencePrefix.length else 0
        val minimumIndentation = indentation + sequenceIndentation + explicitIndentation.coerceAtLeast(1)
        val inferIndentation = if (explicitIndentation == 0) YAML_INFER_INDENTATION else 0
        state = LexerState((minimumIndentation shl YAML_INDENTATION_BITS) or YAML_BLOCK_SCALAR or inferIndentation)
    }

    private fun plain() {
        val start = position
        while (position < line.length) {
            val character = line[position]
            if (character == '#' && (position == 0 || line[position - 1].isWhitespace())) break
            if (character == ':' && separated(position + 1)) break
            if (flowDepth > 0 && character in ",[]{}") break
            position++
        }
        if (position == start) {
            position++
            return
        }
        var end = position
        while (end > start && line[end - 1].isWhitespace()) end--
        val value = line.substring(start, end)
        val kind = when {
            line.getOrNull(position) == ':' -> TokenKind.PROPERTY
            value in yamlConstants -> TokenKind.CONSTANT
            yamlNumber.matches(value) -> TokenKind.NUMBER
            else -> TokenKind.STRING
        }
        add(start, end, kind)
    }

    private fun skipSpaces(from: Int): Int {
        var offset = from
        while (offset < line.length && line[offset].isWhitespace()) offset++
        return offset
    }
}
