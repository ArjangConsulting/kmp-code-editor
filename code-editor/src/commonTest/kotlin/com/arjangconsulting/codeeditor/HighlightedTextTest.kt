package com.arjangconsulting.codeeditor

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HighlightedTextTest {
    private val colors = CodeEditorDefaults.colorsFrom(lightColorScheme()).copy(
        keyword = Color.Red,
        string = Color.Green,
        comment = Color.Blue,
        attribute = Color.Magenta,
        property = Color.Cyan,
        number = Color.Yellow,
    )

    private fun spans(text: String, rules: TokenRules): List<Pair<String, Color>> =
        highlightedText(text, rules, colors).spanStyles.map { text.substring(it.start, it.end) to it.item.color }

    @Test
    fun `keeps the text and colors tokens`() {
        val text = "tap id=\"login\" # note"
        val rules = tokenRules("commands") {
            keywords("tap")
            attributeAssignments()
            string("\"")
            lineComment("#")
        }
        assertEquals(text, highlightedText(text, rules, colors).text)
        assertEquals(
            listOf("tap" to Color.Red, "id" to Color.Magenta, "\"login\"" to Color.Green, "# note" to Color.Blue),
            spans(text, rules),
        )
    }

    @Test
    fun `plain text gets no spans so it inherits the style color`() {
        assertTrue(highlightedText("just words", PlainText, colors).spanStyles.isEmpty())
        assertEquals("", highlightedText("", KotlinRules, colors).text)
    }

    @Test
    fun `offsets are correct across lines and multi line comments`() {
        val text = "val a = 1 /* open\nstill */ val b"
        val result = spans(text, KotlinRules)
        assertTrue("/* open" to Color.Blue in result)
        assertTrue("still */" to Color.Blue in result)
        assertEquals(2, result.count { it == ("val" to Color.Red) })
        assertTrue("1" to Color.Yellow in result)
    }

    @Test
    fun `json keys and crlf line breaks`() {
        val text = "{\r\n  \"a\": \"b\"\r\n}"
        val result = spans(text, JsonRules)
        assertEquals(listOf("\"a\"" to Color.Cyan, "\"b\"" to Color.Green), result.filter { it.second != colors.punctuation })
    }
}
