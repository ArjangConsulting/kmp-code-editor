# Languages

The library includes five highlighting presets: JSON, YAML, Kotlin, Swift, and shell (sh/Bash), plus
plain text. Choose `language` explicitly; filename/content detection is left to the host. Highlighting
and generated-test line highlights work without a language service. Shell rules cover control words,
common builtins, assignments, flags, variables, comments, and quoted strings. Heredocs, nested command
substitutions, and context-sensitive shell comments are not fully modeled.

## YAML

`YamlRules` supports both `.yaml` and `.yml`: block and flow mappings/sequences, keys, quoted and
plain scalars, numbers, booleans/null, comments, document markers, anchors/aliases, tags, and multiline
block strings. Both editor engines use the same indentation-aware YAML lexer. Highlighting is
lexical; complex keys and multiline plain scalar context are not fully modeled. YAML uses a
dedicated scanner, so token patterns added through `tokenRules(base = YamlRules)` are not applied;
editing metadata such as indentation and auto-closing pairs can still be customized.

## Custom languages

`tokenRules` builds a line-based incremental lexer. Rules are tried in order: line comments,
delimited constructs (strings and block comments, longest opening delimiter first), regex
`pattern`s, numbers, identifiers (keywords, then `name=` attributes, then `fn(` calls, then
capitalized types), operators, punctuation.

```kotlin
val CommandRules = tokenRules("commands") {
    keywords("tap", "swipe", "assert")
    constants("true", "false")
    attributeAssignments()
    lineComment("#")
    string("\"")
    numbers()
    pattern("@[a-z]+", TokenKind.ANNOTATION)
    brackets('(' to ')')
}
val Jsonc = tokenRules("jsonc", base = JsonRules) { lineComment("//") }
```

Strings and block comments may span lines (`multiline = true`, `nested = true`). Only changed lines
are re-lexed: after an edit the lexer restarts at the first changed line and stops as soon as a
line ends in the same state as before. Semantic tokens from the service (`SemanticToken`) are drawn
over lexer tokens in the Compose engine.

Empty string and block-comment delimiters are rejected when building token rules.

## Smart typing

Smart typing pairs `{` with `}`, and Enter between them creates an indented blank line. JSON and
YAML use two spaces by default; Kotlin, Swift, and shell use four. Configure this through
`TokenRules.indentUnit` and `CodeEditorOptions.autoIndent`/`autoCloseBrackets`. This is lightweight
bracket-based indentation rather than whole-document formatting.
