package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

/** The highlighting category of a token. Each kind maps to one color in [CodeEditorColors]. */
public enum class TokenKind {
    PLAIN,
    KEYWORD,
    TYPE,
    FUNCTION,
    VARIABLE,
    PROPERTY,

    /** The `name` of a `name=value` attribute, as in a command DSL. */
    ATTRIBUTE,
    STRING,

    /** Escapes (`\n`) and interpolations (`$name`, `\(x)`) inside strings. */
    ESCAPE,
    NUMBER,

    /** Literal constants such as `true`, `false`, `null`, `nil`. */
    CONSTANT,
    COMMENT,
    ANNOTATION,
    OPERATOR,
    PUNCTUATION,

    /** Compiler directives such as `#if`. */
    PREPROCESSOR,
    ERROR,
}

/** One token inside a line. [start] and [end] are UTF-16 offsets in the line; [end] is exclusive. */
@Immutable
public data class Token(val start: Int, val end: Int, val kind: TokenKind)

/**
 * The lexer's state at a line boundary: [Initial], or continuation context such as a block comment,
 * multi-line string, or YAML flow collection / block scalar indentation.
 * The value is opaque and stable for a given [TokenRules], so engines can cache it per line.
 */
@JvmInline
public value class LexerState(public val value: Int) {
    public companion object {
        /** Not inside any multi-line construct. */
        public val Initial: LexerState = LexerState(0)
    }
}

/** The tokens of one line and the state the next line starts in. */
@Immutable
public class LexedLine internal constructor(
    public val tokens: List<Token>,
    public val endState: LexerState,
    internal val startState: LexerState,
) {
    /** The kind of the token covering [offset], or [TokenKind.PLAIN] when no token covers it. */
    public fun kindAt(offset: Int): TokenKind {
        var low = 0
        var high = tokens.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val token = tokens[mid]
            when {
                offset < token.start -> high = mid - 1
                offset >= token.end -> low = mid + 1
                else -> return token.kind
            }
        }
        return TokenKind.PLAIN
    }
}

/**
 * A language definition: lexical rules for highlighting plus the editing metadata (comments,
 * brackets, auto-closing pairs, indentation) that the editor needs for that language.
 *
 * Build one with [tokenRules]; presets include [PlainText], [JsonRules], [YamlRules], [KotlinRules],
 * [SwiftRules], and [ShellRules].
 * Instances are immutable and safe to share; keep them in a top-level `val` so the editor's caches
 * survive recomposition.
 */
@Immutable
public class TokenRules internal constructor(
    /** A short identifier, e.g. `"kotlin"`. Also used as the RSyntaxTextArea style key suffix. */
    public val name: String,
    internal val keywordKinds: Map<String, TokenKind>,
    internal val caseInsensitiveKeywords: Boolean,
    internal val lineComments: List<String>,
    internal val delimited: List<DelimitedRule>,
    internal val patterns: List<PatternRule>,
    internal val numbers: Boolean,
    internal val attributeAssignments: Boolean,
    internal val functionCalls: Boolean,
    internal val capitalizedTypes: Boolean,
    internal val propertyKeys: Boolean,
    internal val operatorChars: String,
    internal val punctuationChars: String,
    internal val identifierStart: (Char) -> Boolean,
    internal val identifierPart: (Char) -> Boolean,
    /** Bracket pairs used for matching, folding, and auto-indent. */
    public val brackets: List<Pair<Char, Char>>,
    /** Pairs closed automatically when the opening character is typed. */
    public val autoClosingPairs: List<Pair<Char, Char>>,
    /** The prefix used by "toggle line comment", or null when the language has none. */
    public val lineComment: String?,
    /** One level of indentation, e.g. four spaces. */
    public val indentUnit: String,
    internal val lineTokenizer: ((String, LexerState) -> LexedLine)?,
) {
    /** True when [c] can start an identifier (used for completion prefixes and word navigation). */
    public fun isIdentifierStart(c: Char): Boolean = identifierStart(c)

    /** True when [c] can continue an identifier. */
    public fun isIdentifierPart(c: Char): Boolean = identifierPart(c)

    /** Tokenizes one line starting in [state]. Pure; safe to call from any thread. */
    public fun tokenizeLine(line: String, state: LexerState = LexerState.Initial): LexedLine =
        lineTokenizer?.invoke(line, state) ?: Lexer(this, line, state).run()

    /** Tokenizes a whole text line by line (`\n`, `\r\n`, and `\r` all end a line). */
    public fun tokenize(text: String): List<LexedLine> {
        val snapshot = DocumentSnapshot(text, 0)
        var state = LexerState.Initial
        return List(snapshot.lineCount) { i ->
            tokenizeLine(snapshot.line(i), state).also { state = it.endState }
        }
    }

    override fun toString(): String = "TokenRules($name)"
}

/** A construct with an opening and closing delimiter: strings and block comments. */
internal class DelimitedRule(
    val open: String,
    val close: String,
    val kind: TokenKind,
    val escape: Char?,
    val multiline: Boolean,
    val nested: Boolean,
    val interpolation: Interpolation?,
)

/** How interpolations inside a string start and end, e.g. `${` .. `}` or `\(` .. `)`. */
internal class Interpolation(val open: String, val close: Char, val simplePrefix: Char?)

internal class PatternRule(val regex: Regex, val kind: TokenKind)

/**
 * Builds a [TokenRules]. Rules are tried in this order at every position: line comments, delimited
 * constructs (longest opening delimiter first), [pattern]s in declaration order, numbers,
 * identifiers (keywords, then attribute assignments, then function calls, then capitalized types),
 * operators, and punctuation. Anything else is plain text.
 *
 * ```
 * val Commands = tokenRules("commands") {
 *     keywords("tap", "swipe", "assert")
 *     constants("true", "false")
 *     attributeAssignments()
 *     lineComment("#")
 *     string("\"")
 *     numbers()
 * }
 * ```
 */
public class TokenRulesBuilder internal constructor(private val name: String, base: TokenRules?) {
    internal var lineTokenizer: ((String, LexerState) -> LexedLine)? = base?.lineTokenizer
    private val keywordKinds = LinkedHashMap<String, TokenKind>(base?.keywordKinds ?: emptyMap())
    private val lineComments = ArrayList(base?.lineComments ?: emptyList())
    private val delimited = ArrayList(base?.delimited ?: emptyList())
    private val patterns = ArrayList(base?.patterns ?: emptyList())
    private val brackets = ArrayList(base?.brackets ?: emptyList())
    private val autoClosing = ArrayList(base?.autoClosingPairs ?: emptyList())
    private var numbers = base?.numbers ?: false
    private var attributeAssignments = base?.attributeAssignments ?: false
    private var functionCalls = base?.functionCalls ?: false
    private var capitalizedTypes = base?.capitalizedTypes ?: false
    private var propertyKeys = base?.propertyKeys ?: false
    private var operatorChars = base?.operatorChars ?: ""
    private var punctuationChars = base?.punctuationChars ?: ""

    /** Keywords match whole identifiers regardless of case when true. */
    public var caseInsensitiveKeywords: Boolean = base?.caseInsensitiveKeywords ?: false

    /** One level of indentation inserted by Tab and auto-indent. */
    public var indentUnit: String = base?.indentUnit ?: "    "

    /** Characters that may start an identifier. Defaults to letters and `_`. */
    public var identifierStart: (Char) -> Boolean = base?.identifierStart ?: { it.isLetter() || it == '_' }

    /** Characters that may continue an identifier. Defaults to letters, digits, and `_`. */
    public var identifierPart: (Char) -> Boolean = base?.identifierPart ?: { it.isLetterOrDigit() || it == '_' }

    /** Highlights [words] as [kind] when they appear as whole identifiers. */
    public fun keywords(vararg words: String, kind: TokenKind = TokenKind.KEYWORD) {
        words.forEach { keywordKinds[if (caseInsensitiveKeywords) it.lowercase() else it] = kind }
    }

    /** Shorthand for [keywords] with [TokenKind.CONSTANT]. */
    public fun constants(vararg words: String): Unit = keywords(*words, kind = TokenKind.CONSTANT)

    /** Shorthand for [keywords] with [TokenKind.TYPE]. */
    public fun types(vararg words: String): Unit = keywords(*words, kind = TokenKind.TYPE)

    /** A comment from [prefix] to the end of the line. The first one is used by "toggle comment". */
    public fun lineComment(prefix: String) {
        require(prefix.isNotEmpty()) { "Line comment prefix must not be empty" }
        lineComments += prefix
    }

    /** A comment between [open] and [close] that may span lines; [nested] allows `/* /* */ */`. */
    public fun blockComment(open: String, close: String, nested: Boolean = false) {
        require(open.isNotEmpty() && close.isNotEmpty()) { "Comment delimiters must not be empty" }
        delimited += DelimitedRule(open, close, TokenKind.COMMENT, null, multiline = true, nested = nested, null)
    }

    /**
     * A string between [open] and [close] (defaulting to [open]). [escape] skips the next character;
     * [multiline] lets the string continue on following lines. An unterminated single-line string
     * ends at the end of its line.
     *
     * [interpolationOpen]/[interpolationClose] highlight embedded expressions such as `${x}` as
     * [TokenKind.ESCAPE]; [simpleInterpolationPrefix] also highlights `$name`.
     */
    public fun string(
        open: String,
        close: String = open,
        escape: Char? = '\\',
        multiline: Boolean = false,
        kind: TokenKind = TokenKind.STRING,
        interpolationOpen: String? = null,
        interpolationClose: Char = '}',
        simpleInterpolationPrefix: Char? = null,
    ) {
        require(open.isNotEmpty() && close.isNotEmpty()) { "String delimiters must not be empty" }
        val interpolation = if (interpolationOpen != null || simpleInterpolationPrefix != null) {
            Interpolation(interpolationOpen ?: "\u0000", interpolationClose, simpleInterpolationPrefix)
        } else {
            null
        }
        delimited += DelimitedRule(open, close, kind, escape, multiline, nested = false, interpolation)
    }

    /** Highlights matches of [regex] (anchored at the current position, single line) as [kind]. */
    public fun pattern(regex: String, kind: TokenKind) {
        patterns += PatternRule(Regex(regex), kind)
    }

    /** Integer, hex (`0x`), binary (`0b`), decimal, and exponent literals, with `_` separators and type suffixes. */
    public fun numbers() {
        numbers = true
    }

    /** Highlights `name` in `name=value` (but not `name==value`) as [TokenKind.ATTRIBUTE]. */
    public fun attributeAssignments() {
        attributeAssignments = true
    }

    /** Highlights an identifier followed by `(` as [TokenKind.FUNCTION]. */
    public fun functionCalls() {
        functionCalls = true
    }

    /** Highlights identifiers starting with an upper-case letter as [TokenKind.TYPE]. */
    public fun capitalizedIdentifiersAreTypes() {
        capitalizedTypes = true
    }

    /** Highlights a string followed by `:` as [TokenKind.PROPERTY], as JSON object keys. */
    public fun stringPropertyKeys() {
        propertyKeys = true
    }

    /** Characters highlighted as [TokenKind.OPERATOR]. */
    public fun operators(chars: String) {
        operatorChars += chars
    }

    /** Characters highlighted as [TokenKind.PUNCTUATION]. Bracket characters are added automatically. */
    public fun punctuation(chars: String) {
        punctuationChars += chars
    }

    /** Bracket pairs for matching, folding, and auto-indent. They are also auto-closed. */
    public fun brackets(vararg pairs: Pair<Char, Char>) {
        pairs.forEach {
            if (it !in brackets) brackets += it
            if (it !in autoClosing) autoClosing += it
        }
    }

    /** Extra pairs closed automatically when typed, such as quotes. */
    public fun autoClose(vararg pairs: Pair<Char, Char>) {
        pairs.forEach { if (it !in autoClosing) autoClosing += it }
    }

    internal fun build(): TokenRules {
        val bracketChars = brackets.joinToString("") { "${it.first}${it.second}" }
        return TokenRules(
            name = name,
            keywordKinds = keywordKinds.toMap(),
            caseInsensitiveKeywords = caseInsensitiveKeywords,
            lineComments = lineComments.toList(),
            delimited = delimited.sortedByDescending { it.open.length },
            patterns = patterns.toList(),
            numbers = numbers,
            attributeAssignments = attributeAssignments,
            functionCalls = functionCalls,
            capitalizedTypes = capitalizedTypes,
            propertyKeys = propertyKeys,
            operatorChars = operatorChars,
            punctuationChars = (punctuationChars + bracketChars).toSet().joinToString(""),
            identifierStart = identifierStart,
            identifierPart = identifierPart,
            brackets = brackets.toList(),
            autoClosingPairs = autoClosing.toList(),
            lineComment = lineComments.firstOrNull(),
            indentUnit = indentUnit,
            lineTokenizer = lineTokenizer,
        )
    }
}

/**
 * Builds a [TokenRules] named [name]. Pass [base] to extend an existing definition, e.g. JSON with
 * comments: `tokenRules("jsonc", base = JsonRules) { lineComment("//") }`.
 */
public fun tokenRules(name: String, base: TokenRules? = null, block: TokenRulesBuilder.() -> Unit): TokenRules =
    TokenRulesBuilder(name, base).apply(block).build()

private const val STATE_RULE_SHIFT = 8
private const val STATE_DEPTH_MASK = 0xFF

private class Lexer(private val rules: TokenRules, private val line: String, private val startState: LexerState) {
    private val tokens = ArrayList<Token>()
    private var pos = 0
    private var state = LexerState.Initial

    fun run(): LexedLine {
        if (startState.value != 0) continueDelimited(startState)
        while (pos < line.length) step()
        return LexedLine(tokens, state, startState)
    }

    private fun add(start: Int, end: Int, kind: TokenKind) {
        if (end > start) tokens += Token(start, end, kind)
    }

    private fun continueDelimited(state: LexerState) {
        val ruleIndex = (state.value ushr STATE_RULE_SHIFT) - 1
        val rule = rules.delimited.getOrNull(ruleIndex) ?: return
        val depth = state.value and STATE_DEPTH_MASK
        scanDelimitedBody(rule, ruleIndex, start = 0, bodyStart = 0, depth = depth.coerceAtLeast(1))
    }

    private fun step() {
        val c = line[pos]
        if (c == ' ' || c == '\t') {
            pos++
            return
        }
        for (prefix in rules.lineComments) {
            if (line.startsWith(prefix, pos)) {
                add(pos, line.length, TokenKind.COMMENT)
                pos = line.length
                return
            }
        }
        for ((index, rule) in rules.delimited.withIndex()) {
            if (line.startsWith(rule.open, pos)) {
                val start = pos
                scanDelimitedBody(rule, index, start, start + rule.open.length, depth = 1)
                return
            }
        }
        for (pattern in rules.patterns) {
            val match = pattern.regex.matchAt(line, pos)
            if (match != null && match.value.isNotEmpty()) {
                add(pos, pos + match.value.length, pattern.kind)
                pos += match.value.length
                return
            }
        }
        if (rules.numbers && (c.isDigit() || (c == '.' && line.getOrNull(pos + 1)?.isDigit() == true))) {
            val end = scanNumber(pos)
            add(pos, end, TokenKind.NUMBER)
            pos = end
            return
        }
        if (rules.identifierStart(c)) {
            scanIdentifier()
            return
        }
        when {
            c in rules.operatorChars -> add(pos, pos + 1, TokenKind.OPERATOR)
            c in rules.punctuationChars -> add(pos, pos + 1, TokenKind.PUNCTUATION)
        }
        pos++
    }

    private fun scanIdentifier() {
        val start = pos
        pos++
        while (pos < line.length && rules.identifierPart(line[pos])) pos++
        val word = line.substring(start, pos)
        val key = if (rules.caseInsensitiveKeywords) word.lowercase() else word
        val keywordKind = rules.keywordKinds[key]
        val next = nextNonBlank(pos)
        val kind = when {
            keywordKind != null -> keywordKind
            rules.attributeAssignments && next < line.length && line[next] == '=' &&
                line.getOrNull(next + 1) != '=' -> TokenKind.ATTRIBUTE
            rules.functionCalls && next < line.length && line[next] == '(' -> TokenKind.FUNCTION
            rules.capitalizedTypes && word[0].isUpperCase() -> TokenKind.TYPE
            else -> null
        }
        if (kind != null) add(start, pos, kind)
    }

    private fun nextNonBlank(from: Int): Int {
        var i = from
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return i
    }

    private fun scanNumber(from: Int): Int {
        var i = from
        val isDigitOrUnderscore = { ch: Char -> ch.isDigit() || ch == '_' }
        if (line[i] == '0' && i + 1 < line.length && (line[i + 1] == 'x' || line[i + 1] == 'X')) {
            i += 2
            while (i < line.length && (line[i].isDigit() || line[i] in 'a'..'f' || line[i] in 'A'..'F' || line[i] == '_')) i++
        } else if (line[i] == '0' && i + 1 < line.length && (line[i + 1] == 'b' || line[i + 1] == 'B')) {
            i += 2
            while (i < line.length && (line[i] == '0' || line[i] == '1' || line[i] == '_')) i++
        } else {
            while (i < line.length && isDigitOrUnderscore(line[i])) i++
            if (i + 1 < line.length && line[i] == '.' && line[i + 1].isDigit()) {
                i++
                while (i < line.length && isDigitOrUnderscore(line[i])) i++
            }
            if (i < line.length && (line[i] == 'e' || line[i] == 'E')) {
                var j = i + 1
                if (j < line.length && (line[j] == '+' || line[j] == '-')) j++
                if (j < line.length && line[j].isDigit()) {
                    i = j
                    while (i < line.length && isDigitOrUnderscore(line[i])) i++
                }
            }
        }
        while (i < line.length && line[i] in "uUlLfFdD") i++
        return i
    }

    /**
     * Scans a delimited construct whose body starts at [bodyStart]. Emits its token(s) and sets
     * [state] when the line ends inside a multi-line construct.
     */
    private fun scanDelimitedBody(rule: DelimitedRule, ruleIndex: Int, start: Int, bodyStart: Int, depth: Int) {
        var i = bodyStart
        var level = depth
        var segmentStart = start
        val interpolation = rule.interpolation
        while (i < line.length) {
            val ch = line[i]
            if (rule.escape != null && ch == rule.escape && i + 1 < line.length) {
                add(segmentStart, i, rule.kind)
                add(i, i + 2, TokenKind.ESCAPE)
                i += 2
                segmentStart = i
                if (interpolation != null && rule.escape == '\\' && interpolation.open == "\\(" && line[i - 1] == '(') {
                    // Swift-style "\(expr)": the escape consumed "\(", highlight through the matching ')'.
                    val close = findInterpolationClose(i, interpolation.close)
                    add(i, close, TokenKind.ESCAPE)
                    i = close
                    segmentStart = i
                }
                continue
            }
            if (interpolation != null && interpolation.open != "\\(" && line.startsWith(interpolation.open, i)) {
                add(segmentStart, i, rule.kind)
                val close = findInterpolationClose(i + interpolation.open.length, interpolation.close)
                add(i, close, TokenKind.ESCAPE)
                i = close
                segmentStart = i
                continue
            }
            if (interpolation?.simplePrefix != null && ch == interpolation.simplePrefix &&
                i + 1 < line.length && rules.identifierStart(line[i + 1])
            ) {
                add(segmentStart, i, rule.kind)
                var end = i + 2
                while (end < line.length && rules.identifierPart(line[end])) end++
                add(i, end, TokenKind.ESCAPE)
                i = end
                segmentStart = i
                continue
            }
            if (rule.nested && line.startsWith(rule.open, i)) {
                level++
                i += rule.open.length
                continue
            }
            if (line.startsWith(rule.close, i)) {
                level--
                i += rule.close.length
                if (level == 0) {
                    add(segmentStart, i, rule.kind)
                    if (rules.propertyKeys && rule.kind == TokenKind.STRING && !rule.multiline) {
                        val next = nextNonBlank(i)
                        if (next < line.length && line[next] == ':') retagAsProperty(start, i)
                    }
                    pos = i
                    state = LexerState.Initial
                    return
                }
                continue
            }
            i++
        }
        add(segmentStart, line.length, rule.kind)
        pos = line.length
        state = if (rule.multiline) {
            LexerState(((ruleIndex + 1) shl STATE_RULE_SHIFT) or level.coerceAtMost(STATE_DEPTH_MASK))
        } else {
            LexerState.Initial
        }
    }

    private fun retagAsProperty(start: Int, end: Int) {
        for (index in tokens.indices.reversed()) {
            val token = tokens[index]
            if (token.end <= start) break
            if (token.kind == TokenKind.STRING && token.start >= start && token.end <= end) {
                tokens[index] = token.copy(kind = TokenKind.PROPERTY)
            }
        }
    }

    private fun findInterpolationClose(from: Int, close: Char): Int {
        val open = if (close == '}') '{' else if (close == ')') '(' else null
        var depth = 1
        var i = from
        while (i < line.length) {
            val ch = line[i]
            if (open != null && ch == open) depth++
            if (ch == close) {
                depth--
                if (depth == 0) return i + 1
            }
            i++
        }
        return line.length
    }
}
