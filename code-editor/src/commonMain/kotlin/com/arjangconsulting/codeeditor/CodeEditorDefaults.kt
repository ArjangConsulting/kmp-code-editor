package com.arjangconsulting.codeeditor

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * Every color the editor draws. Build the defaults with [CodeEditorDefaults.colors] and override
 * roles with `copy`. Material 3 has no warning role, so [diagnosticWarning] and
 * [lineHighlightWarning] default to the tertiary palette; apps with a warning color should pass it.
 *
 * Token colors come from [syntaxTheme], resolved against [background] into [syntax]: a
 * `copy(background = panel)` with a dark panel switches to the theme's dark variant automatically.
 */
@Immutable
public data class CodeEditorColors(
    val background: Color,
    val foreground: Color,
    val gutterBackground: Color,
    val gutterForeground: Color,
    val gutterActiveForeground: Color,
    val currentLineBackground: Color,
    val selectionBackground: Color,
    val caret: Color,
    val matchingBracketBackground: Color,
    val findMatchBackground: Color,
    val findCurrentMatchBackground: Color,
    val foldPlaceholderBackground: Color,
    val foldPlaceholderForeground: Color,
    val lineHighlightSelected: Color,
    val lineHighlightHover: Color,
    val lineHighlightWarning: Color,
    val lineHighlightError: Color,
    val lineHighlightInfo: Color,
    val diagnosticError: Color,
    val diagnosticWarning: Color,
    val diagnosticInformation: Color,
    val diagnosticHint: Color,
    val popupBackground: Color,
    val popupForeground: Color,
    val popupSecondaryForeground: Color,
    val popupSelectedBackground: Color,
    val popupMatchForeground: Color,
    val popupBorder: Color,
    val scrollbar: Color,
    val syntaxTheme: CodeEditorSyntaxTheme,
) {
    /** The [syntaxTheme] variant for [background]. */
    public val syntax: CodeEditorSyntaxColors = syntaxTheme.colorsFor(background)

    /** The color for tokens of [kind]: its [syntax] color, or [foreground] when that is unspecified. */
    public fun tokenColor(kind: TokenKind): Color = syntax[kind].takeOrElse { foreground }

    /** The squiggle and gutter marker color for [severity]. */
    public fun diagnosticColor(severity: DiagnosticSeverity): Color = when (severity) {
        DiagnosticSeverity.ERROR -> diagnosticError
        DiagnosticSeverity.WARNING -> diagnosticWarning
        DiagnosticSeverity.INFORMATION -> diagnosticInformation
        DiagnosticSeverity.HINT -> diagnosticHint
    }

    /** The background for a [LineHighlight] with [role]. */
    public fun lineHighlightColor(role: LineHighlightRole): Color = when (role) {
        LineHighlightRole.SELECTED -> lineHighlightSelected
        LineHighlightRole.HOVER -> lineHighlightHover
        LineHighlightRole.WARNING -> lineHighlightWarning
        LineHighlightRole.ERROR -> lineHighlightError
        LineHighlightRole.INFO -> lineHighlightInfo
    }
}

/**
 * User-visible strings and accessibility labels. Defaults are English; pass translated values to
 * localize. Functions build strings that need numbers.
 */
@Immutable
public data class CodeEditorLabels(
    val editorDescription: String = "Code editor",
    val findPlaceholder: String = "Find",
    val findPrevious: String = "Previous match",
    val findNext: String = "Next match",
    val findClose: String = "Close find",
    val findMatchCase: String = "Match case",
    val findNoResults: String = "No results",
    val findMatchCount: (current: Int, total: Int) -> String = { current, total -> "$current of $total" },
    val noSuggestions: String = "No suggestions",
    val completionListDescription: String = "Suggestions",
    val fold: String = "Fold",
    val unfold: String = "Unfold",
    val foldedPlaceholder: String = "⋯",
    val caretDescription: (line: Int, column: Int) -> String = { line, column -> "Line $line, column $column" },
    val severityLabel: (DiagnosticSeverity) -> String = {
        when (it) {
            DiagnosticSeverity.ERROR -> "Error"
            DiagnosticSeverity.WARNING -> "Warning"
            DiagnosticSeverity.INFORMATION -> "Info"
            DiagnosticSeverity.HINT -> "Hint"
        }
    },
    val foldAll: String = "Fold all",
    val unfoldAll: String = "Unfold all",
)

/** What the Tab key does when no completion popup is open. */
public enum class TabBehavior {
    /** Inserts indentation (or indents the selected lines); Shift+Tab outdents. */
    INSERT_INDENT,

    /** Moves focus to the next (Shift+Tab: previous) focusable element, like a normal text field. */
    MOVE_FOCUS,
}

/**
 * Which implementation renders the editor.
 *
 * [DEFAULT] picks the platform's recommended engine: [COMPOSE] everywhere (see the module README
 * for why the desktop default is not RSyntaxTextArea). [RSYNTAX_TEXT_AREA] is desktop-only and falls
 * back to [COMPOSE] on other platforms.
 */
public enum class CodeEditorEngine {
    DEFAULT,

    /** The pure Compose editor, available on every target. */
    COMPOSE,

    /**
     * RSyntaxTextArea hosted in a `SwingPanel` (desktop only). It is a heavyweight component: Compose
     * popups and dialogs can draw underneath it unless the app enables the window-layer flags
     * described in the README.
     */
    RSYNTAX_TEXT_AREA,
}

/**
 * Editing behavior switches.
 *
 * @property autoIndent Enter copies indentation and indents after an opening bracket.
 * @property autoCloseBrackets Typing an opening bracket or quote inserts its partner.
 * @property highlightCurrentLine Tints the caret's line.
 * @property matchBrackets Highlights the bracket matching the one at the caret.
 * @property completionOnTyping Opens completion while typing identifiers (trigger characters and
 *   Ctrl+Space always work).
 * @property hover Requests hover on pointer rest and shows diagnostic tooltips.
 * @property tabSize Display width of a tab character, in columns.
 */
@Immutable
public data class CodeEditorOptions(
    val autoIndent: Boolean = true,
    val autoCloseBrackets: Boolean = true,
    val highlightCurrentLine: Boolean = true,
    val matchBrackets: Boolean = true,
    val completionOnTyping: Boolean = true,
    val hover: Boolean = true,
    val tabSize: Int = 4,
) {
    init {
        require(tabSize > 0) { "tabSize must be positive" }
    }
}

/** Defaults for [CodeEditor]. */
public object CodeEditorDefaults {
    /**
     * Chrome colors derived from `MaterialTheme.colorScheme`, with token colors from [syntaxTheme]
     * matched to the editor background.
     */
    @Composable
    @ReadOnlyComposable
    public fun colors(syntaxTheme: CodeEditorSyntaxTheme = CodeEditorSyntaxTheme.Default): CodeEditorColors =
        colorsFrom(MaterialTheme.colorScheme, syntaxTheme)

    /** Like [colors], from an explicit [scheme]; useful outside a Material theme. */
    public fun colorsFrom(
        scheme: ColorScheme,
        syntaxTheme: CodeEditorSyntaxTheme = CodeEditorSyntaxTheme.Default,
    ): CodeEditorColors = CodeEditorColors(
        background = scheme.surfaceContainerLowest,
        foreground = scheme.onSurface,
        gutterBackground = scheme.surfaceContainerLow,
        gutterForeground = scheme.onSurfaceVariant.copy(alpha = 0.7f),
        gutterActiveForeground = scheme.onSurface,
        currentLineBackground = scheme.onSurface.copy(alpha = 0.05f),
        selectionBackground = scheme.primary.copy(alpha = 0.28f),
        caret = scheme.primary,
        matchingBracketBackground = scheme.tertiary.copy(alpha = 0.25f),
        findMatchBackground = scheme.tertiaryContainer.copy(alpha = 0.7f),
        findCurrentMatchBackground = scheme.tertiary.copy(alpha = 0.45f),
        foldPlaceholderBackground = scheme.secondaryContainer,
        foldPlaceholderForeground = scheme.onSecondaryContainer,
        lineHighlightSelected = scheme.primaryContainer.copy(alpha = 0.6f),
        lineHighlightHover = scheme.secondaryContainer.copy(alpha = 0.5f),
        lineHighlightWarning = scheme.tertiaryContainer.copy(alpha = 0.6f),
        lineHighlightError = scheme.errorContainer.copy(alpha = 0.6f),
        lineHighlightInfo = scheme.secondaryContainer.copy(alpha = 0.35f),
        diagnosticError = scheme.error,
        diagnosticWarning = scheme.tertiary,
        diagnosticInformation = scheme.secondary,
        diagnosticHint = scheme.outline,
        popupBackground = scheme.surfaceContainerHigh,
        popupForeground = scheme.onSurface,
        popupSecondaryForeground = scheme.onSurfaceVariant,
        popupSelectedBackground = scheme.secondaryContainer,
        popupMatchForeground = scheme.primary,
        popupBorder = scheme.outlineVariant,
        scrollbar = scheme.onSurface.copy(alpha = 0.25f),
        syntaxTheme = syntaxTheme,
    )

    /** Monospace text based on `MaterialTheme.typography.bodyMedium`. */
    @Composable
    @ReadOnlyComposable
    public fun textStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
}
