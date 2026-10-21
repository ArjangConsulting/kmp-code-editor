package com.arjangconsulting.codeeditor

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuzzyMatcherTest {
    @Test
    fun `matches subsequences case insensitively`() {
        assertNotNull(FuzzyMatcher.match("gtv", "getValue"))
        assertNotNull(FuzzyMatcher.match("GETV", "getValue"))
        assertNull(FuzzyMatcher.match("vg", "getValue"))
        assertNull(FuzzyMatcher.match("toolong", "tool"))
    }

    @Test
    fun `first character must start a word`() {
        assertNull(FuzzyMatcher.match("e", "get"))
        assertNotNull(FuzzyMatcher.match("v", "getValue"))
        assertNotNull(FuzzyMatcher.match("n", "first_name"))
    }

    @Test
    fun `reports boundary matched indices`() {
        assertContentEquals(intArrayOf(0, 3), FuzzyMatcher.match("gv", "getValue")?.matchedIndices)
    }

    @Test
    fun `empty pattern matches everything with zero score`() {
        assertEquals(0, FuzzyMatcher.match("", "anything")?.score)
    }

    @Test
    fun `prefix and exact case beat scattered matches`() {
        val prefix = FuzzyMatcher.match("get", "getValue")!!.score
        val scattered = FuzzyMatcher.match("get", "goodEnoughTime")!!.score
        assertTrue(prefix > scattered)
        val exact = FuzzyMatcher.match("Val", "Value")!!.score
        val wrongCase = FuzzyMatcher.match("Val", "value")!!.score
        assertTrue(exact > wrongCase)
    }

    @Test
    fun `equal matches fall back to sort text rather than length`() {
        val items = listOf(CompletionItem("identifierWithAVeryLongName", sortText = "a"), CompletionItem("id", sortText = "b"))
        assertEquals("identifierWithAVeryLongName", filterAndRankCompletions(items, "id").first().item.label)
    }

    @Test
    fun `ranking orders by score then preselect then sort text`() {
        val items = listOf(
            CompletionItem("scrollTo", sortText = "2"),
            CompletionItem("swipe", sortText = "1"),
            CompletionItem("screenshot", sortText = "0"),
            CompletionItem("tap"),
        )
        val ranked = filterAndRankCompletions(items, "sc").map { it.item.label }
        assertEquals(listOf("screenshot", "scrollTo"), ranked)
        val all = filterAndRankCompletions(items, "").map { it.item.label }
        assertEquals(listOf("screenshot", "swipe", "scrollTo", "tap"), all)
    }

    @Test
    fun `filter text is used instead of the label`() {
        val items = listOf(CompletionItem("Tap element", filterText = "tap"), CompletionItem("Swipe", filterText = "swipe"))
        assertEquals(listOf("Tap element"), filterAndRankCompletions(items, "ta").map { it.item.label })
    }

    @Test
    fun `preselected items lead among equals and limit is applied`() {
        val items = listOf(CompletionItem("alpha"), CompletionItem("alpine", preselect = true), CompletionItem("alto"))
        assertEquals("alpine", filterAndRankCompletions(items, "").first().item.label)
        assertEquals(2, filterAndRankCompletions(items, "al", limit = 2).size)
    }
    @Test
    fun `a later word boundary cannot hide a valid subsequence`() {
        val match = kotlin.test.assertNotNull(FuzzyMatcher.match("abc", "abxcB"))
        kotlin.test.assertContentEquals(intArrayOf(0, 1, 3), match.matchedIndices)
    }

}
