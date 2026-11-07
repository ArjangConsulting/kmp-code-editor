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
| Find and replace | ⌥⌘F | Ctrl+H |
| Complete | ⌃Space | Ctrl+Space |
| Toggle line comment | ⌘/ | Ctrl+/ |
| Indent / outdent | Tab / ⇧Tab (or focus traversal with `TabBehavior.MOVE_FOCUS`) | same |
| Fold / unfold region | ⌘⌥[ / ⌘⌥] | Ctrl+Shift+[ / Ctrl+Shift+] |
| Completion popup | ↑/↓, PageUp/PageDown, Enter/Tab accept, Esc closes | same |
| REPL (`singleLine`) | Enter submits when no suggestion is selected; ↑/↓ browse history when the popup is closed | same |

## Find and replace

In the find field, Enter moves to the next match and Shift+Enter to the previous one. In the replace
field, Enter replaces the selected match and moves on; ⌘Enter (Ctrl+Enter) replaces all matches as
one undoable edit. Read-only editors hide the replace row. Esc closes the bar.

`Aa` toggles case sensitivity and `.*` regular expressions. In regex mode, `^` and `$` match at
line boundaries, and zero-length matches are skipped. Opening the bar with a single-line selection
seeds the query, escaped when regex mode is on. Replacement text is literal unless regex mode is on;
then `$0`–`$99` and `${name}` insert groups, `$$` inserts `$`, `\n` and `\t` insert a line break and a
tab, and `\` escapes the next character. A reference to a missing group is kept as typed. An invalid
pattern, a search that runs longer than 250 ms (one second for replace all), or, on desktop and
Android, a match that recurses too deeply for a very long line shows a message instead of results,
and nothing is replaced.

## Editing helpers

Auto-indent (and splitting `{|}` onto three lines), auto-closing and overtyping of brackets and
quotes, wrapping a selection in a pair, re-indenting a closing bracket typed on a blank line,
Backspace by indent stop, and deleting an empty pair at once.

## Touch

On mobile, tap places the caret, long press selects a word and shows the platform text toolbar, and
drag scrolls.
