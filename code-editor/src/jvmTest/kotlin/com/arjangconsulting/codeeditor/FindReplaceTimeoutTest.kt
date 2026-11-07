package com.arjangconsulting.codeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * The backtracking guard relies on the regex engine reading input through `CharSequence.get`, and
 * stack overflows are recoverable only on the JVM and Android, so these run on the JVM only.
 */
class FindReplaceTimeoutTest {
    private val catastrophic = "(.*a){12}b"
    private val input = "a".repeat(40)
    private val regex = FindOptions(caseSensitive = true, regex = true)

    @Test
    fun `catastrophic patterns time out instead of hanging`() {
        val result = findInText(input, catastrophic, regex, budget = 50.milliseconds)
        assertEquals(FindError.TIMED_OUT, result.error)
        assertEquals(emptyList(), result.matches)
    }

    @Test
    fun `deeply recursive matches report too complex instead of crashing`() {
        val longLine = "ab".repeat(200_000)
        val result = findInText(longLine, "(a|b)*c", regex, budget = 10_000.milliseconds)
        assertEquals(FindError.TOO_COMPLEX, result.error)
        val (replacements, error) = replaceAllReplacements(longLine, "(a|b)*c", regex, "x", budget = 10_000.milliseconds)
        assertEquals(FindError.TOO_COMPLEX, error)
        assertEquals(emptyList(), replacements)
    }

    @Test
    fun `replace all never applies a partial result after a timeout`() {
        val (replacements, error) = replaceAllReplacements(input, catastrophic, regex, "x", budget = 50.milliseconds)
        assertEquals(FindError.TIMED_OUT, error)
        assertEquals(emptyList(), replacements)
    }
}
