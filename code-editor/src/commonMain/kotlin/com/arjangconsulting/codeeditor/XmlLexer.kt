package com.arjangconsulting.codeeditor

private const val XML_TEXT = 0
private const val XML_TAG = 1
private const val XML_TAG_NAME = 2
private const val XML_SINGLE_QUOTED = 3
private const val XML_DOUBLE_QUOTED = 4
private const val XML_COMMENT = 5
private const val XML_CDATA = 6
private const val XML_PROCESSING = 7
private const val XML_DECLARATION = 8
private const val XML_MODE_MASK = 15
private const val XML_CONTEXT_SHIFT = 4

internal fun tokenizeXmlLine(line: String, state: LexerState): LexedLine = XmlLineLexer(line, state).run()

private class XmlLineLexer(private val line: String, private val startState: LexerState) {
    private val tokens = ArrayList<Token>()
    private var position = 0
    private var mode = startState.value and XML_MODE_MASK
    private var quoteContext = startState.value ushr XML_CONTEXT_SHIFT

    fun run(): LexedLine {
        while (position < line.length) {
            when (mode) {
                XML_COMMENT -> delimited("-->", TokenKind.COMMENT)
                XML_CDATA -> delimited("]]>", TokenKind.STRING)
                XML_PROCESSING -> delimited("?>", TokenKind.PREPROCESSOR)
                XML_SINGLE_QUOTED, XML_DOUBLE_QUOTED -> quoted(continuation = true)
                XML_TAG, XML_TAG_NAME, XML_DECLARATION -> tag()
                else -> text()
            }
        }
        return LexedLine(tokens, LexerState(mode or (quoteContext shl XML_CONTEXT_SHIFT)), startState)
    }

    private fun add(start: Int, end: Int, kind: TokenKind) {
        if (end > start) tokens += Token(start, end, kind)
    }

    private fun text() {
        val start = position
        when {
            line.startsWith("<!--", position) -> {
                mode = XML_COMMENT
                position += 4
                delimited("-->", TokenKind.COMMENT, start)
            }
            line.startsWith("<![CDATA[", position) -> {
                mode = XML_CDATA
                position += 9
                delimited("]]>", TokenKind.STRING, start)
            }
            line.startsWith("<?", position) -> {
                mode = XML_PROCESSING
                position += 2
                delimited("?>", TokenKind.PREPROCESSOR, start)
            }
            line.startsWith("<!", position) -> {
                position += 2
                mode = XML_DECLARATION
                add(start, position, TokenKind.PREPROCESSOR)
            }
            line[position] == '<' -> {
                position++
                if (line.getOrNull(position) == '/') position++
                add(start, position, TokenKind.PUNCTUATION)
                mode = XML_TAG_NAME
            }
            line[position] == '&' -> entity()
            else -> position++
        }
    }

    private fun delimited(close: String, kind: TokenKind, start: Int = position) {
        val end = line.indexOf(close, position)
        position = if (end < 0) line.length else end + close.length
        add(start, position, kind)
        if (end >= 0) mode = XML_TEXT
    }

    private fun entity(): Boolean {
        val start = position
        var end = start + 1
        while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '#')) end++
        val valid = end > start + 1 && line.getOrNull(end) == ';'
        position = if (valid) end + 1 else start + 1
        if (valid) add(start, position, TokenKind.ESCAPE)
        return valid
    }

    private fun quoted(continuation: Boolean = false) {
        val quote = if (mode == XML_SINGLE_QUOTED) '\'' else '"'
        var segment = position
        if (!continuation) position++
        while (position < line.length) {
            when (line[position]) {
                quote -> {
                    position++
                    add(segment, position, TokenKind.STRING)
                    mode = quoteContext
                    quoteContext = 0
                    return
                }
                '&' -> {
                    add(segment, position, TokenKind.STRING)
                    val start = position
                    if (!entity()) add(start, position, TokenKind.STRING)
                    segment = position
                }
                else -> position++
            }
        }
        add(segment, position, TokenKind.STRING)
    }

    private fun tag() {
        val start = position
        val character = line[position]
        when {
            character.isWhitespace() -> position++
            character == '>' || line.startsWith("/>", position) -> {
                position += if (character == '/') 2 else 1
                add(start, position, TokenKind.PUNCTUATION)
                mode = XML_TEXT
            }
            character == '\'' || character == '"' -> {
                quoteContext = mode
                mode = if (character == '\'') XML_SINGLE_QUOTED else XML_DOUBLE_QUOTED
                quoted()
            }
            character == '=' -> {
                position++
                add(start, position, TokenKind.OPERATOR)
            }
            character.isLetterOrDigit() || character in "_:.-" -> {
                while (position < line.length && (line[position].isLetterOrDigit() || line[position] in "_:.-")) position++
                add(start, position, when (mode) {
                    XML_TAG_NAME -> TokenKind.TYPE
                    XML_DECLARATION -> TokenKind.PREPROCESSOR
                    else -> TokenKind.ATTRIBUTE
                })
                if (mode == XML_TAG_NAME) mode = XML_TAG
            }
            else -> position++
        }
    }
}
