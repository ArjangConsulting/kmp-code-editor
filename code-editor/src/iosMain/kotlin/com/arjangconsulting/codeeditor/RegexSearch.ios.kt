package com.arjangconsulting.codeeditor

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMatchingInternalError
import platform.Foundation.NSMatchingReportCompletion
import platform.Foundation.NSMatchingReportProgress
import platform.Foundation.NSMatchingWithTransparentBounds
import platform.Foundation.NSMatchingWithoutAnchoringBounds
import platform.Foundation.NSNotFound
import platform.Foundation.NSRegularExpression
import platform.Foundation.NSRegularExpressionAnchorsMatchLines
import platform.Foundation.NSRegularExpressionCaseInsensitive
import platform.Foundation.NSTextCheckingResult
import platform.Foundation.enumerateMatchesInString
import platform.Foundation.numberOfRanges
import platform.Foundation.rangeAtIndex
import platform.Foundation.rangeWithName
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Searches with `NSRegularExpression` (ICU). Kotlin/Native's own regex engine recurses per character
 * for quantified groups and overflows the thread stack (an unrecoverable crash) on lines of a few
 * thousand characters; ICU keeps its backtracking stack on the heap and reports an internal error
 * instead, mapped to [FindError.TOO_COMPLEX]. Progress callbacks enforce [budget] during a match.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun regexSearch(
    text: String,
    query: String,
    caseSensitive: Boolean,
    from: Int,
    limit: Int,
    budget: Duration,
    onMatch: (RegexMatch) -> Unit,
): FindError? {
    val options = if (caseSensitive) NSRegularExpressionAnchorsMatchLines else NSRegularExpressionAnchorsMatchLines or NSRegularExpressionCaseInsensitive
    val expression = NSRegularExpression.regularExpressionWithPattern(query, options, null) ?: return FindError.INVALID_PATTERN
    if (from !in 0..text.length) return null
    val deadline = TimeSource.Monotonic.markNow() + budget
    var error: FindError? = null
    var count = 0
    val matching = NSMatchingReportProgress or NSMatchingReportCompletion or NSMatchingWithTransparentBounds or NSMatchingWithoutAnchoringBounds
    expression.enumerateMatchesInString(text, matching, NSMakeRange(from.toULong(), (text.length - from).toULong())) { result, flags, stop ->
        if (flags and NSMatchingInternalError != 0uL) {
            error = FindError.TOO_COMPLEX
        } else if (result != null) {
            val match = IcuRegexMatch(text, result, query)
            if (!match.range.isEmpty()) {
                onMatch(match)
                count++
            }
        }
        if (error == null && count < limit && deadline.hasPassedNow()) error = FindError.TIMED_OUT
        if (error != null || count >= limit) stop?.pointed?.value = true
    }
    return error
}

@OptIn(ExperimentalForeignApi::class)
private class IcuRegexMatch(
    private val text: String,
    private val result: NSTextCheckingResult,
    private val pattern: String,
) : RegexMatch {
    override val range: IntRange = result.range.useContents { location.toInt() until (location + length).toInt() }

    override val groupCount: Int get() = result.numberOfRanges.toInt()

    override fun group(index: Int): String? {
        if (index !in 0 until groupCount) return null
        return result.rangeAtIndex(index.toULong()).useContents {
            if (location == NSNotFound.toULong()) null else text.substring(location.toInt(), (location + length).toInt())
        }
    }

    override fun namedGroup(name: String): String? {
        // NSTextCheckingResult can't tell a missing group from an unmatched one, so check the pattern.
        if ("(?<$name>" !in pattern) return null
        return result.rangeWithName(name).useContents {
            if (location == NSNotFound.toULong()) "" else text.substring(location.toInt(), (location + length).toInt())
        }
    }
}
