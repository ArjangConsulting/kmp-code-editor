package com.arjangconsulting.codeeditor

import androidx.compose.ui.input.key.Key

/** Commands bound to keys in the Compose engine. */
internal enum class EditorCommand {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    WORD_LEFT,
    WORD_RIGHT,
    LINE_START,
    LINE_END,
    DOCUMENT_START,
    DOCUMENT_END,
    PAGE_UP,
    PAGE_DOWN,
    SELECT_ALL,
    BACKSPACE,
    DELETE_FORWARD,
    DELETE_WORD_BACKWARD,
    DELETE_WORD_FORWARD,
    DELETE_TO_LINE_START,
    ENTER,
    TAB,
    OUTDENT,
    UNDO,
    REDO,
    COPY,
    CUT,
    PASTE,
    FIND,
    FIND_NEXT,
    FIND_PREVIOUS,
    ESCAPE,
    TRIGGER_COMPLETION,
    TOGGLE_COMMENT,
    FOLD,
    UNFOLD,
}

/** A command plus whether it extends the selection (Shift held for movement). */
internal data class KeyBinding(val command: EditorCommand, val extend: Boolean = false)

/** Modifier state of a key press, decoupled from Compose's `KeyEvent` so the mapping is unit-testable. */
internal data class KeyChord(
    val key: Key,
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
)

/**
 * Maps a key chord to a command following platform conventions: Command-based shortcuts and
 * Option word movement on Apple platforms, Control-based shortcuts elsewhere. Returns null for keys
 * the editor doesn't handle (plain characters go through text input).
 */
internal fun mapKey(chord: KeyChord, apple: Boolean): KeyBinding? {
    val shortcut = if (apple) chord.meta else chord.ctrl
    val wordModifier = if (apple) chord.alt else chord.ctrl
    val s = chord.shift
    return when (chord.key) {
        Key.DirectionLeft -> when {
            apple && chord.meta -> KeyBinding(EditorCommand.LINE_START, s)
            wordModifier -> KeyBinding(EditorCommand.WORD_LEFT, s)
            else -> KeyBinding(EditorCommand.LEFT, s)
        }
        Key.DirectionRight -> when {
            apple && chord.meta -> KeyBinding(EditorCommand.LINE_END, s)
            wordModifier -> KeyBinding(EditorCommand.WORD_RIGHT, s)
            else -> KeyBinding(EditorCommand.RIGHT, s)
        }
        Key.DirectionUp -> if (apple && chord.meta) KeyBinding(EditorCommand.DOCUMENT_START, s) else KeyBinding(EditorCommand.UP, s)
        Key.DirectionDown -> if (apple && chord.meta) KeyBinding(EditorCommand.DOCUMENT_END, s) else KeyBinding(EditorCommand.DOWN, s)
        Key.MoveHome -> if (chord.ctrl || (apple && chord.meta)) KeyBinding(EditorCommand.DOCUMENT_START, s) else KeyBinding(EditorCommand.LINE_START, s)
        Key.MoveEnd -> if (chord.ctrl || (apple && chord.meta)) KeyBinding(EditorCommand.DOCUMENT_END, s) else KeyBinding(EditorCommand.LINE_END, s)
        Key.PageUp -> KeyBinding(EditorCommand.PAGE_UP, s)
        Key.PageDown -> KeyBinding(EditorCommand.PAGE_DOWN, s)
        Key.Backspace -> when {
            apple && chord.meta -> KeyBinding(EditorCommand.DELETE_TO_LINE_START)
            wordModifier -> KeyBinding(EditorCommand.DELETE_WORD_BACKWARD)
            else -> KeyBinding(EditorCommand.BACKSPACE)
        }
        Key.Delete -> if (wordModifier) KeyBinding(EditorCommand.DELETE_WORD_FORWARD) else KeyBinding(EditorCommand.DELETE_FORWARD)
        Key.Enter, Key.NumPadEnter -> if (shortcut || chord.alt) null else KeyBinding(EditorCommand.ENTER)
        Key.Tab -> if (chord.ctrl || chord.meta || chord.alt) null else if (s) KeyBinding(EditorCommand.OUTDENT) else KeyBinding(EditorCommand.TAB)
        Key.Escape -> KeyBinding(EditorCommand.ESCAPE)
        Key.Spacebar -> if (chord.ctrl && !chord.meta && !chord.alt) KeyBinding(EditorCommand.TRIGGER_COMPLETION) else null
        Key.F3 -> KeyBinding(if (s) EditorCommand.FIND_PREVIOUS else EditorCommand.FIND_NEXT)
        Key.A -> when {
            shortcut && !chord.alt -> KeyBinding(EditorCommand.SELECT_ALL)
            // Emacs-style line start, standard in macOS text views.
            apple && chord.ctrl && !chord.meta -> KeyBinding(EditorCommand.LINE_START, s)
            else -> null
        }
        Key.E -> if (apple && chord.ctrl && !chord.meta) KeyBinding(EditorCommand.LINE_END, s) else null
        Key.Z -> if (shortcut && !chord.alt) KeyBinding(if (s) EditorCommand.REDO else EditorCommand.UNDO) else null
        Key.Y -> if (!apple && chord.ctrl && !s) KeyBinding(EditorCommand.REDO) else null
        Key.C -> if (shortcut && !s && !chord.alt) KeyBinding(EditorCommand.COPY) else null
        Key.X -> if (shortcut && !s && !chord.alt) KeyBinding(EditorCommand.CUT) else null
        Key.V -> if (shortcut && !s && !chord.alt) KeyBinding(EditorCommand.PASTE) else null
        Key.F -> if (shortcut && !s && !chord.alt) KeyBinding(EditorCommand.FIND) else null
        Key.G -> if (apple && chord.meta) KeyBinding(if (s) EditorCommand.FIND_PREVIOUS else EditorCommand.FIND_NEXT) else null
        Key.LeftBracket, Key.RightBracket -> {
            val foldingShortcut = if (apple) {
                chord.meta && chord.alt && !chord.ctrl && !s
            } else {
                chord.ctrl && s && !chord.alt && !chord.meta
            }
            if (foldingShortcut) {
                KeyBinding(if (chord.key == Key.LeftBracket) EditorCommand.FOLD else EditorCommand.UNFOLD)
            } else {
                null
            }
        }
        Key.Slash -> if (shortcut) KeyBinding(EditorCommand.TOGGLE_COMMENT) else null
        else -> null
    }
}
