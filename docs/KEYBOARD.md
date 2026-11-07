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
| Add / remove a cursor | ⌥-click (⌥-drag adds a selection) | Alt+click (Alt+drag) |
| Add next occurrence | ⌘D | Ctrl+D |
| Select all occurrences | ⇧⌘L, or ⌥Enter in the find field (all matches) | Ctrl+Shift+L, or Alt+Enter in the find field |
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
pattern, a search that runs longer than 250 ms (one second for replace all), or a match that
recurses too deeply for a very long line shows a message instead of results, and nothing is
replaced.

## Multiple cursors (Compose engine)

Typing, Backspace/Delete (including word and line deletion), Enter with auto-indent, Tab/Shift+Tab,
toggling comments, paste, and cut apply at every cursor as one undoable step; consecutive typing at
several cursors coalesces like single-caret typing, and undo restores every cursor. Arrow, word,
Home/End, and page movement (with Shift to extend) move each cursor by its own column; cursors that
meet merge. Copy joins the selections with line breaks, and pasting the same number of lines as
there are cursors puts one line at each. Add next occurrence first selects the word at the caret,
then adds the next match of the selected text (case-sensitive, wrapping). Esc closes the find bar
first, then collapses to the primary (most recently added) cursor; a plain click also collapses.
Completion, snippets, hover, and bracket matching use the primary cursor, and other cursors are
mapped through the accepted edit. Up to 10,000 cursors are kept.

## Editing helpers

Auto-indent (and splitting `{|}` onto three lines), auto-closing and overtyping of brackets and
quotes, wrapping a selection in a pair, re-indenting a closing bracket typed on a blank line,
Backspace by indent stop, and deleting an empty pair at once.

## Touch

On mobile, tap places the caret, long press selects a word and shows the platform text toolbar, and
drag scrolls.
