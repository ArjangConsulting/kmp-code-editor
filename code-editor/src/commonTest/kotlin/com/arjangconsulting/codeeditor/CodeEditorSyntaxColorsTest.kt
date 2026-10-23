package com.arjangconsulting.codeeditor

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeEditorSyntaxColorsTest {
    private val lightBackgrounds = listOf(Color.White, Color(0xFFF7F2FA), Color(0xFFFFDAD6), Color(0xFFD6D6D6))
    private val darkBackgrounds = listOf(Color(0xFF0F0D13), Color(0xFF1E1E1E), Color(0xFF404040))

    @Test
    fun `default theme meets AA on light and dark editor backgrounds`() {
        val theme = CodeEditorSyntaxTheme.Default
        lightBackgrounds.forEach { assertEquals(emptyList(), theme.light.lowContrastKinds(it, 4.5f), "on $it") }
        darkBackgrounds.forEach { assertEquals(emptyList(), theme.dark.lowContrastKinds(it, 4.5f), "on $it") }
    }

    @Test
    fun `high contrast theme meets AAA`() {
        val theme = CodeEditorSyntaxTheme.HighContrast
        listOf(Color.White, Color(0xFFF5F5F5), Color(0xFFD6D6D6)).forEach {
            assertEquals(emptyList(), theme.light.lowContrastKinds(it, 7f), "on $it")
        }
        listOf(Color.Black, Color(0xFF0F0D13), Color(0xFF2E2E2E)).forEach {
            assertEquals(emptyList(), theme.dark.lowContrastKinds(it, 7f), "on $it")
        }
    }

    @Test
    fun `the variant follows the background`() {
        val theme = CodeEditorSyntaxTheme.Default
        assertEquals(theme.light, theme.colorsFor(Color.White))
        assertEquals(theme.dark, theme.colorsFor(Color(0xFF1E1E1E)))
        assertTrue(isDarkBackground(Color(0xFF333333)))
        assertFalse(isDarkBackground(Color(0xFFEBEBEB)))
        assertEquals(theme.dark, CodeEditorDefaults.colorsFrom(darkColorScheme()).syntax)
        assertEquals(theme.light, CodeEditorDefaults.colorsFrom(lightColorScheme()).syntax)
        assertEquals(
            CodeEditorSyntaxTheme.HighContrast.light,
            CodeEditorDefaults.colorsFrom(lightColorScheme(), CodeEditorSyntaxTheme.HighContrast).syntax,
        )
    }

    @Test
    fun `a custom background switches the syntax variant`() {
        val colors = CodeEditorDefaults.colorsFrom(lightColorScheme())
        assertEquals(CodeEditorSyntaxTheme.Default.light, colors.syntax)
        assertEquals(CodeEditorSyntaxTheme.Default.dark, colors.copy(background = Color(0xFF161B22)).syntax)
    }

    @Test
    fun `with replaces one role and get reads it back`() {
        val edited = CodeEditorSyntaxTheme.Default.light.with(TokenKind.STRING, Color.Red)
        assertEquals(Color.Red, edited[TokenKind.STRING])
        assertEquals(CodeEditorSyntaxTheme.Default.light.keyword, edited[TokenKind.KEYWORD])
        TokenKind.entries.filter { it != TokenKind.PLAIN }.forEach { kind ->
            assertEquals(Color.Blue, edited.with(kind, Color.Blue)[kind], "$kind")
        }
        assertFailsWith<IllegalArgumentException> { edited.with(TokenKind.PLAIN, Color.Red) }
    }

    @Test
    fun `unspecified roles and plain text use the foreground`() {
        val colors = CodeEditorDefaults.colorsFrom(lightColorScheme())
        assertEquals(colors.foreground, colors.tokenColor(TokenKind.PLAIN))
        assertEquals(colors.foreground, colors.tokenColor(TokenKind.VARIABLE))
        val noKeywordColor = colors.syntax.with(TokenKind.KEYWORD, Color.Unspecified)
        val custom = colors.copy(syntaxTheme = CodeEditorSyntaxTheme(noKeywordColor))
        assertEquals(colors.foreground, custom.tokenColor(TokenKind.KEYWORD))
    }

    @Test
    fun `low contrast picks are reported`() {
        val edited = CodeEditorSyntaxTheme.Default.light.with(TokenKind.COMMENT, Color(0xFFEEEEEE))
        assertEquals(listOf(TokenKind.COMMENT), edited.lowContrastKinds(Color.White))
    }

    @Test
    fun `contrast ratio matches WCAG reference values`() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, contrastRatio(Color.White, Color.White), 0.01f)
        assertEquals(4.54f, contrastRatio(Color(0xFF767676), Color.White), 0.01f)
        assertEquals(1f, contrastRatio(Color.Black.copy(alpha = 0f), Color.White), 0.01f)
    }
}
