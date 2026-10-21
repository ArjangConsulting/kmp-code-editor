package com.arjangconsulting.codeeditor

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * Highlights [text] with [language] and returns it as an [AnnotatedString], for read-only snippets
 * where a full [CodeEditor] would be too heavy (lazy list rows, timelines, logs).
 *
 * Each non-plain token gets a color span from [colors]; plain text has no span, so it takes the
 * color of the text style it is drawn with. Multi-line strings and comments are handled like in the
 * editor. Pure and allocation-proportional to the token count, so it is safe to call off the main
 * thread or to precompute.
 */
public fun highlightedText(text: String, language: TokenRules, colors: CodeEditorColors): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    val builder = AnnotatedString.Builder(text)
    val snapshot = DocumentSnapshot(text, 0)
    var state = LexerState.Initial
    for (line in 0 until snapshot.lineCount) {
        val lexed = language.tokenizeLine(snapshot.line(line), state)
        val start = snapshot.lineStart(line)
        for (token in lexed.tokens) {
            if (token.kind != TokenKind.PLAIN) {
                builder.addStyle(SpanStyle(color = colors.tokenColor(token.kind)), start + token.start, start + token.end)
            }
        }
        state = lexed.endState
    }
    return builder.toAnnotatedString()
}

/** [highlightedText] remembered for its inputs, with colors from `MaterialTheme` by default. */
@Composable
public fun rememberHighlightedText(
    text: String,
    language: TokenRules,
    colors: CodeEditorColors = CodeEditorDefaults.colors(),
): AnnotatedString = remember(text, language, colors) { highlightedText(text, language, colors) }

/**
 * A lightweight, read-only, highlighted code snippet: a single [BasicText], no gutter, caret,
 * scrolling, or language service. Use it for many short snippets in lazy lists; use [CodeEditor]
 * for anything interactive or long. Wrap it in a `SelectionContainer` to make it selectable.
 *
 * [softWrap] defaults to false so code keeps its line structure; combine [maxLines] and
 * [overflow] to clip long snippets.
 */
@Composable
public fun CodeSnippet(
    text: String,
    modifier: Modifier = Modifier,
    language: TokenRules = PlainText,
    colors: CodeEditorColors = CodeEditorDefaults.colors(),
    textStyle: TextStyle = CodeEditorDefaults.textStyle(),
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = false,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text = rememberHighlightedText(text, language, colors),
        modifier = modifier,
        style = textStyle.copy(color = colors.foreground),
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = overflow,
    )
}
