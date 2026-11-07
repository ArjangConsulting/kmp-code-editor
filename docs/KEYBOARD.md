# Keyboard and touch (Compose engine)

| Action | macOS / iOS | Windows / Linux / Android |
| --- | --- | --- |
| Word left/right | ⌥←/→ | Ctrl+←/→ |
| Line start/end | ⌘←/→, Home/End, ⌃A/⌃E | Home/End (smart Home) |
| Document start/end | ⌘↑/↓ | Ctrl+Home/End |
| Extend selection | add ⇧ | add Shift |
| Undo / Redo | ⌘Z / ⇧⌘Z | Ctrl+Z / Ctrl+Y, Ctrl+Shift+Z |
| Copy / Cut / Paste | ⌘C/X/V (whole line when nothing is selected) | Ctrl+C/X/V |
| Find / next / previous | ⌘F / ⌘G / ⇧⌘G, F3 | Ctrl+F / F3 / Shift+F3 |
| Complete | ⌃Space | Ctrl+Space |
| Toggle line comment | ⌘/ | Ctrl+/ |
| Indent / outdent | Tab / ⇧Tab (or focus traversal with `TabBehavior.MOVE_FOCUS`) | same |
| Fold / unfold region | ⌘⌥[ / ⌘⌥] | Ctrl+Shift+[ / Ctrl+Shift+] |
| Completion popup | ↑/↓, PageUp/PageDown, Enter/Tab accept, Esc closes | same |
| REPL (`singleLine`) | Enter submits when no suggestion is selected; ↑/↓ browse history when the popup is closed | same |

## Editing helpers

Auto-indent (and splitting `{|}` onto three lines), auto-closing and overtyping of brackets and
quotes, wrapping a selection in a pair, re-indenting a closing bracket typed on a blank line,
Backspace by indent stop, and deleting an empty pair at once.

## Touch

On mobile, tap places the caret, long press selects a word and shows the platform text toolbar, and
drag scrolls.
