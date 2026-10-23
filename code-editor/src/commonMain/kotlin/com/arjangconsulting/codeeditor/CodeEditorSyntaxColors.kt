package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance

/**
 * The color of each [TokenKind] except [TokenKind.PLAIN], which always uses
 * [CodeEditorColors.foreground]. A role set to [Color.Unspecified] also falls back to the foreground;
 * [variable] does by default.
 *
 * Start from a [CodeEditorSyntaxTheme] variant and change roles with `copy` or [with], for example
 * to apply colors a person picked in settings. Check edits with [lowContrastKinds].
 */
@Immutable
public data class CodeEditorSyntaxColors(
    val keyword: Color,
    val type: Color,
    val function: Color,
    val property: Color,
    val attribute: Color,
    val string: Color,
    val escape: Color,
    val number: Color,
    val constant: Color,
    val comment: Color,
    val annotation: Color,
    val operator: Color,
    val punctuation: Color,
    val preprocessor: Color,
    val error: Color,
    val variable: Color = Color.Unspecified,
) {
    /** The color for [kind]; [Color.Unspecified] for [TokenKind.PLAIN] and roles that use the foreground. */
    public operator fun get(kind: TokenKind): Color = when (kind) {
        TokenKind.PLAIN -> Color.Unspecified
        TokenKind.KEYWORD -> keyword
        TokenKind.TYPE -> type
        TokenKind.FUNCTION -> function
        TokenKind.VARIABLE -> variable
        TokenKind.PROPERTY -> property
        TokenKind.ATTRIBUTE -> attribute
        TokenKind.STRING -> string
        TokenKind.ESCAPE -> escape
        TokenKind.NUMBER -> number
        TokenKind.CONSTANT -> constant
        TokenKind.COMMENT -> comment
        TokenKind.ANNOTATION -> annotation
        TokenKind.OPERATOR -> operator
        TokenKind.PUNCTUATION -> punctuation
        TokenKind.PREPROCESSOR -> preprocessor
        TokenKind.ERROR -> error
    }

    /**
     * A copy that draws [kind] in [color]; pass [Color.Unspecified] to use the foreground.
     *
     * @throws IllegalArgumentException for [TokenKind.PLAIN], which is always the foreground.
     */
    public fun with(kind: TokenKind, color: Color): CodeEditorSyntaxColors = when (kind) {
        TokenKind.PLAIN -> throw IllegalArgumentException("PLAIN tokens use CodeEditorColors.foreground")
        TokenKind.KEYWORD -> copy(keyword = color)
        TokenKind.TYPE -> copy(type = color)
        TokenKind.FUNCTION -> copy(function = color)
        TokenKind.VARIABLE -> copy(variable = color)
        TokenKind.PROPERTY -> copy(property = color)
        TokenKind.ATTRIBUTE -> copy(attribute = color)
        TokenKind.STRING -> copy(string = color)
        TokenKind.ESCAPE -> copy(escape = color)
        TokenKind.NUMBER -> copy(number = color)
        TokenKind.CONSTANT -> copy(constant = color)
        TokenKind.COMMENT -> copy(comment = color)
        TokenKind.ANNOTATION -> copy(annotation = color)
        TokenKind.OPERATOR -> copy(operator = color)
        TokenKind.PUNCTUATION -> copy(punctuation = color)
        TokenKind.PREPROCESSOR -> copy(preprocessor = color)
        TokenKind.ERROR -> copy(error = color)
    }

    /**
     * The kinds whose color has less than [minimumRatio] WCAG 2 contrast against [background].
     * 4.5 is the WCAG AA minimum for text and 7 the AAA minimum. Unspecified roles are skipped.
     */
    public fun lowContrastKinds(background: Color, minimumRatio: Float = 4.5f): List<TokenKind> =
        TokenKind.entries.filter { kind ->
            val color = get(kind)
            color.isSpecified && contrastRatio(color, background) < minimumRatio
        }
}

/**
 * A syntax palette with a [light] variant for light backgrounds and a [dark] variant for dark ones.
 * [colorsFor] picks the variant from the actual editor background, so a custom background keeps
 * readable token colors. Built-in themes: [Default] and [HighContrast].
 */
@Immutable
public data class CodeEditorSyntaxTheme(val light: CodeEditorSyntaxColors, val dark: CodeEditorSyntaxColors) {
    /** One palette for every background. */
    public constructor(colors: CodeEditorSyntaxColors) : this(colors, colors)

    /** [dark] when [background] is dark, otherwise [light]. */
    public fun colorsFor(background: Color): CodeEditorSyntaxColors = if (isDarkBackground(background)) dark else light

    public companion object {
        /**
         * Distinct hues with at least 4.5:1 (WCAG AA) contrast on white through `#D6D6D6` and on
         * `#0F0D13` through `#404040`, which covers Material 3 surfaces, tinted containers, and
         * common editor backgrounds.
         */
        public val Default: CodeEditorSyntaxTheme = CodeEditorSyntaxTheme(
            light = CodeEditorSyntaxColors(
                keyword = Color(0xFF9D2F80),
                type = Color(0xFF01685B),
                function = Color(0xFF6B45B5),
                property = Color(0xFF04657B),
                attribute = Color(0xFF904904),
                string = Color(0xFF016B1A),
                escape = Color(0xFF755704),
                number = Color(0xFF2356BC),
                constant = Color(0xFF2356BC),
                comment = Color(0xFF575C64),
                annotation = Color(0xFF755704),
                operator = Color(0xFF4A5059),
                punctuation = Color(0xFF4A5059),
                preprocessor = Color(0xFF9D2F80),
                error = Color(0xFFAE2926),
            ),
            dark = CodeEditorSyntaxColors(
                keyword = Color(0xFFE990CC),
                type = Color(0xFF15C2AB),
                function = Color(0xFFB79FFA),
                property = Color(0xFF52B9D7),
                attribute = Color(0xFFEF985A),
                string = Color(0xFF71BE74),
                escape = Color(0xFFD3A63E),
                number = Color(0xFF81ADFF),
                constant = Color(0xFF81ADFF),
                comment = Color(0xFFA9ADB4),
                annotation = Color(0xFFD3A63E),
                operator = Color(0xFFBABFC7),
                punctuation = Color(0xFFBABFC7),
                preprocessor = Color(0xFFE990CC),
                error = Color(0xFFF99084),
            ),
        )

        /**
         * The [Default] hues with at least 7:1 (WCAG AAA) contrast on white through `#D6D6D6` and
         * on black through `#2E2E2E`.
         */
        public val HighContrast: CodeEditorSyntaxTheme = CodeEditorSyntaxTheme(
            light = CodeEditorSyntaxColors(
                keyword = Color(0xFF7B0262),
                type = Color(0xFF00493F),
                function = Color(0xFF5516A4),
                property = Color(0xFF024757),
                attribute = Color(0xFF673200),
                string = Color(0xFF024C10),
                escape = Color(0xFF533D01),
                number = Color(0xFF01369E),
                constant = Color(0xFF01369E),
                comment = Color(0xFF3B4149),
                annotation = Color(0xFF533D01),
                operator = Color(0xFF2A303A),
                punctuation = Color(0xFF2A303A),
                preprocessor = Color(0xFF7B0262),
                error = Color(0xFF86020B),
            ),
            dark = CodeEditorSyntaxColors(
                keyword = Color(0xFFFF9BDF),
                type = Color(0xFF08D3B9),
                function = Color(0xFFC3B0FE),
                property = Color(0xFF4BCAEE),
                attribute = Color(0xFFFFA769),
                string = Color(0xFF74D078),
                escape = Color(0xFFE8B430),
                number = Color(0xFF98BCFE),
                constant = Color(0xFF98BCFE),
                comment = Color(0xFFB7BCC4),
                annotation = Color(0xFFE8B430),
                operator = Color(0xFFCED4DE),
                punctuation = Color(0xFFCED4DE),
                preprocessor = Color(0xFFFF9BDF),
                error = Color(0xFFFFA499),
            ),
        )
    }
}

/**
 * The WCAG 2 contrast ratio between [foreground] and [background], from 1 to 21. A translucent
 * [foreground] is composited over [background] first.
 */
public fun contrastRatio(foreground: Color, background: Color): Float {
    val opaqueBackground = background.copy(alpha = 1f)
    val a = foreground.compositeOver(opaqueBackground).luminance()
    val b = opaqueBackground.luminance()
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

/**
 * Whether [background] is dark enough that light text reads better on it: its luminance is below
 * the point where black and white text have equal contrast.
 */
public fun isDarkBackground(background: Color): Boolean = background.luminance() < DARK_LUMINANCE_THRESHOLD

private const val DARK_LUMINANCE_THRESHOLD = 0.179f
