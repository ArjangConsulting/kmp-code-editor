package com.arjangconsulting.codeeditor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import androidx.compose.ui.text.TextRange as ComposeTextRange

/** How the find query is interpreted. */
internal data class FindOptions(val caseSensitive: Boolean = false, val regex: Boolean = false)

/** Why a search produced no usable matches. */
internal enum class FindError {
    INVALID_PATTERN,
    TIMED_OUT,

    /** The match recursed too deeply for the text, for example `(a|b)*` over a very long line. */
    TOO_COMPLEX,
}

/** Non-empty matches in ascending order, or the [error] that stopped the search (with no matches). */
internal data class FindResult(val matches: List<IntRange>, val error: FindError? = null) {
    companion object {
        val Empty: FindResult = FindResult(emptyList())
    }
}

/** At most this many matches are highlighted and navigated; replace all is not capped. */
internal const val FIND_MATCH_LIMIT: Int = 10_000

/** Time a regular-expression search may take before it is abandoned as timed out. */
internal val FIND_TIME_BUDGET: Duration = 250.milliseconds

/** Time budget for replace all, an explicit action that may take longer than incremental search. */
internal val REPLACE_ALL_TIME_BUDGET: Duration = 1.seconds

private class SearchTimeout : RuntimeException()

/**
 * A view of [text] that aborts a regular-expression search once [deadline] passes, so a pattern
 * with catastrophic backtracking can't hang the UI thread.
 */
private class DeadlineCharSequence(
    private val text: CharSequence,
    private val deadline: TimeSource.Monotonic.ValueTimeMark,
) : CharSequence {
    private var reads = 0

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        reads++
        if (reads and DEADLINE_CHECK_MASK == 0 && deadline.hasPassedNow()) throw SearchTimeout()
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

    override fun toString(): String = text.toString()

    private companion object {
        const val DEADLINE_CHECK_MASK = 0x3FF
    }
}

/**
 * Compiles [query] as a multi-line regular expression (`^` and `$` match at line boundaries), or
 * returns null when the pattern is invalid.
 */
internal fun compileFindRegex(query: String, caseSensitive: Boolean): Regex? = try {
    val options = if (caseSensitive) setOf(RegexOption.MULTILINE) else setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
    Regex(query, options)
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Runs a regular-expression [search], converting a time-out or a recoverable stack overflow into a
 * [FindError]. Any other throwable propagates.
 */
private inline fun <T> guardedRegex(search: () -> T): Pair<T?, FindError?> = try {
    search() to null
} catch (_: SearchTimeout) {
    null to FindError.TIMED_OUT
} catch (error: Throwable) {
    if (!isRecoverableStackOverflow(error)) throw error
    null to FindError.TOO_COMPLEX
}

/**
 * Collects non-empty matches of [regex] in [text] through [transform]. Zero-length matches are
 * skipped. Returns the error instead when the search exceeds [budget] or overflows the stack.
 */
private inline fun <T> collectRegexMatches(
    text: CharSequence,
    regex: Regex,
    limit: Int,
    budget: Duration,
    crossinline transform: (MatchResult) -> T,
): Pair<List<T>, FindError?> {
    val input = DeadlineCharSequence(text, TimeSource.Monotonic.markNow() + budget)
    val (matches, error) = guardedRegex {
        val result = ArrayList<T>()
        var match = regex.find(input)
        while (match != null && result.size < limit) {
            if (!match.range.isEmpty()) result += transform(match)
            match = match.next()
        }
        result
    }
    return (matches ?: emptyList()) to error
}

/** Finds [query] in [text] as literal text or, with [FindOptions.regex], as a regular expression. */
internal fun findInText(
    text: String,
    query: String,
    options: FindOptions,
    limit: Int = FIND_MATCH_LIMIT,
    budget: Duration = FIND_TIME_BUDGET,
): FindResult {
    if (query.isEmpty()) return FindResult.Empty
    if (!options.regex) return FindResult(findMatches(text, query, options.caseSensitive, limit))
    val regex = compileFindRegex(query, options.caseSensitive) ?: return FindResult(emptyList(), FindError.INVALID_PATTERN)
    val (matches, error) = collectRegexMatches(text, regex, limit, budget) { it.range }
    return FindResult(if (error == null) matches else emptyList(), error)
}

/**
 * Expands a regular-expression replacement [template] for [match]. `$0`–`$99` and `${name}` insert
 * groups (an unmatched optional group inserts nothing), `$$` inserts a dollar sign, and `\n`, `\t`,
 * and `\\` are escapes; a backslash before any other character inserts that character. A reference
 * to a group that doesn't exist is kept literally.
 */
internal fun expandReplacement(template: String, match: MatchResult): String {
    val out = StringBuilder(template.length)
    val groupCount = match.groupValues.size
    var i = 0
    while (i < template.length) {
        val c = template[i]
        when {
            c == '\\' && i + 1 < template.length -> {
                out.append(
                    when (val next = template[i + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> next
                    },
                )
                i += 2
            }
            c == '$' && i + 1 < template.length && template[i + 1] == '$' -> {
                out.append('$')
                i += 2
            }
            c == '$' && i + 1 < template.length && template[i + 1].isAsciiDigit() -> {
                val twoDigits = if (i + 2 < template.length && template[i + 2].isAsciiDigit()) template.substring(i + 1, i + 3).toInt() else -1
                val oneDigit = template[i + 1] - '0'
                when {
                    twoDigits in 0 until groupCount -> {
                        out.append(match.groups[twoDigits]?.value.orEmpty())
                        i += 3
                    }
                    oneDigit < groupCount -> {
                        out.append(match.groups[oneDigit]?.value.orEmpty())
                        i += 2
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            c == '$' && i + 1 < template.length && template[i + 1] == '{' -> {
                val close = template.indexOf('}', i + 2)
                val name = if (close > i + 2) template.substring(i + 2, close) else null
                val group = name?.let { namedGroupValue(match, it) }
                if (group != null) {
                    out.append(group)
                    i = close + 1
                } else {
                    out.append(c)
                    i++
                }
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/** The named group's value, empty for an unmatched group, or null when no group has that name. */
private fun namedGroupValue(match: MatchResult, name: String): String? = try {
    match.groups[name]?.value.orEmpty()
} catch (_: IllegalArgumentException) {
    null
} catch (_: UnsupportedOperationException) {
    null
}

/**
 * The replacement for the match that occupies exactly [range] in [text], or null when [range] is
 * not a match of [query] (for example after the document changed).
 */
internal fun replacementForMatch(
    text: String,
    range: IntRange,
    query: String,
    options: FindOptions,
    template: String,
    budget: Duration = FIND_TIME_BUDGET,
): Replacement? {
    if (query.isEmpty() || range.isEmpty() || range.first < 0 || range.last >= text.length) return null
    val end = range.last + 1
    if (!options.regex) {
        val matches = end - range.first == query.length &&
            text.regionMatches(range.first, query, 0, query.length, ignoreCase = !options.caseSensitive)
        return if (matches) Replacement(range.first, end, template) else null
    }
    val regex = compileFindRegex(query, options.caseSensitive) ?: return null
    val input = DeadlineCharSequence(text, TimeSource.Monotonic.markNow() + budget)
    val match = guardedRegex { regex.find(input, range.first) }.first
    if (match == null || match.range != range) return null
    return Replacement(range.first, end, expandReplacement(template, match))
}

/**
 * Replacements for every match of [query] in [text] (uncapped), in ascending order. Returns a
 * [FindError] instead when the search can't complete, so a partial replace never happens.
 */
internal fun replaceAllReplacements(
    text: String,
    query: String,
    options: FindOptions,
    template: String,
    budget: Duration = REPLACE_ALL_TIME_BUDGET,
): Pair<List<Replacement>, FindError?> {
    if (query.isEmpty()) return emptyList<Replacement>() to null
    if (!options.regex) {
        val replacements = findMatches(text, query, options.caseSensitive, Int.MAX_VALUE).map { Replacement(it.first, it.last + 1, template) }
        return replacements to null
    }
    val regex = compileFindRegex(query, options.caseSensitive) ?: return emptyList<Replacement>() to FindError.INVALID_PATTERN
    val (replacements, error) = collectRegexMatches(text, regex, Int.MAX_VALUE, budget) {
        Replacement(it.range.first, it.range.last + 1, expandReplacement(template, it))
    }
    return if (error == null) replacements to null else emptyList<Replacement>() to error
}

/** Escapes regular-expression metacharacters so [text] matches literally. */
internal fun escapeRegexLiteral(text: String): String = buildString(text.length) {
    for (c in text) {
        if (c in REGEX_METACHARACTERS) append('\\')
        append(c)
    }
}

private const val REGEX_METACHARACTERS = "\\^$.|?*+()[]{}"

/**
 * Find and replace state shared by both engines: the query and replacement fields, options, the
 * current match, and cached results. Engines supply the document side through [Host]. UI-thread only.
 */
@Stable
internal class FindSession(private val host: Host) {
    /** The engine operations a find session needs. */
    interface Host {
        val state: CodeEditorState
        val readOnly: Boolean

        /** Selects `start until end` and scrolls it into view. */
        fun select(start: Int, end: Int)

        /** Applies [outcome] as one undoable edit; returns true when the text changed. */
        fun apply(outcome: EditOutcome): Boolean

        fun focusEditor()
    }

    /** Which field a focus request targets; [id] makes each request distinct. */
    data class FocusRequest(val id: Int, val replace: Boolean)

    var visible: Boolean by mutableStateOf(false)
        private set
    val query: TextFieldState = TextFieldState()
    val replacement: TextFieldState = TextFieldState()
    var caseSensitive: Boolean by mutableStateOf(false)
    var regex: Boolean by mutableStateOf(false)
    var replaceVisible: Boolean by mutableStateOf(false)
    var index: Int by mutableIntStateOf(-1)
        private set
    var focusRequest: FocusRequest by mutableStateOf(FocusRequest(0, replace = false))
        private set

    /** Matches replaced by the last replace all, shown until the next search step. */
    var replacedCount: Int? by mutableStateOf(null)
        private set

    /** Why the last replace all did nothing, shown until the next search step. */
    var replaceError: FindError? by mutableStateOf(null)
        private set

    val options: FindOptions get() = FindOptions(caseSensitive, regex)

    private var cacheKey: Any? = null
    private var cache: FindResult = FindResult.Empty

    /** The current search result; empty while the bar is closed. Cached per query, options, and version. */
    fun result(): FindResult {
        if (!visible) return FindResult.Empty
        val text = query.text.toString()
        val key = Triple(text, options, host.state.version)
        if (key != cacheKey) {
            cache = findInText(host.state.text, text, options)
            cacheKey = key
        }
        return cache
    }

    fun matches(): List<IntRange> = result().matches

    /**
     * Opens the bar, seeding the query from a single-line selection. With [replace], also shows the
     * replace row (unless read-only) and focuses it when the query is already filled.
     */
    fun open(replace: Boolean = false) {
        val selected = host.state.selectedText
        if (selected.isNotEmpty() && '\n' !in selected) {
            val seeded = if (regex) escapeRegexLiteral(selected) else selected
            query.edit {
                replace(0, length, seeded)
                selection = ComposeTextRange(0, length)
            }
        } else {
            query.edit { selection = ComposeTextRange(0, length) }
        }
        val showReplace = replace && !host.readOnly
        if (showReplace) replaceVisible = true
        visible = true
        index = -1
        clearNotices()
        focusRequest = FocusRequest(focusRequest.id + 1, replace = showReplace && query.text.isNotEmpty())
    }

    fun close() {
        visible = false
        index = -1
        clearNotices()
        host.focusEditor()
    }

    fun toggleReplace() {
        replaceVisible = !replaceVisible && !host.readOnly
        focusRequest = FocusRequest(focusRequest.id + 1, replace = replaceVisible)
    }

    /** Moves to the next ([direction] = 1) or previous match relative to the selection and selects it. */
    fun step(direction: Int) {
        if (!visible) {
            open()
            return
        }
        clearNotices()
        val matches = matches()
        if (matches.isEmpty()) {
            index = -1
            return
        }
        val selection = host.state.selectionInternal
        val next = if (index in matches.indices && matches[index].first == selection.start && matches[index].last + 1 == selection.end) {
            (index + direction + matches.size) % matches.size
        } else if (direction > 0) {
            matches.indexOfFirst { it.first >= selection.end }.takeIf { it >= 0 } ?: 0
        } else {
            matches.indexOfLast { it.last + 1 <= selection.start }.takeIf { it >= 0 } ?: (matches.size - 1)
        }
        selectMatch(matches, next)
    }

    /** Selects the first match at or after the selection start while the query is typed. */
    fun incremental() {
        clearNotices()
        val matches = matches()
        if (matches.isEmpty()) {
            index = -1
            return
        }
        val from = host.state.selectionInternal.start
        selectMatch(matches, matches.indexOfFirst { it.first >= from }.takeIf { it >= 0 } ?: 0)
    }

    /** Replaces the selected match (if the selection is one) and moves to the next match. */
    fun replaceCurrent() {
        if (host.readOnly || !visible) return
        val text = query.text.toString()
        if (text.isEmpty()) return
        val selection = host.state.selectionInternal
        if (!selection.collapsed) {
            val template = replacement.text.toString()
            val edit = replacementForMatch(host.state.text, selection.start until selection.end, text, options, template)
            if (edit != null) {
                host.apply(EditOutcome(listOf(edit), Selection(edit.start + edit.text.length), EditKind.OTHER))
                index = -1
            }
        }
        step(1)
    }

    /** Replaces every match as one undoable edit. */
    fun replaceAll() {
        if (host.readOnly || !visible) return
        val text = query.text.toString()
        if (text.isEmpty()) return
        val (replacements, error) = replaceAllReplacements(host.state.text, text, options, replacement.text.toString())
        index = -1
        replaceError = error
        if (error != null) {
            replacedCount = null
            return
        }
        if (replacements.isNotEmpty()) {
            val selection = shiftSelection(host.state.selectionInternal, replacements)
            host.apply(EditOutcome(replacements, selection, EditKind.OTHER))
        }
        replacedCount = replacements.size
    }

    private fun clearNotices() {
        replacedCount = null
        replaceError = null
    }

    private fun selectMatch(matches: List<IntRange>, index: Int) {
        this.index = index
        val match = matches[index]
        host.select(match.first, match.last + 1)
    }
}
