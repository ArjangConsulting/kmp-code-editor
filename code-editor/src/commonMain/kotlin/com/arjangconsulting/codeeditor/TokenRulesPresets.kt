package com.arjangconsulting.codeeditor

/** No highlighting. Brackets still match and auto-close. */
public val PlainText: TokenRules = tokenRules("plaintext") {
    brackets('(' to ')', '[' to ']', '{' to '}')
}

/**
 * JSON (RFC 8259): object keys as [TokenKind.PROPERTY], strings, numbers, `true`/`false`/`null`.
 * Comments are not part of JSON; extend with `tokenRules("jsonc", base = JsonRules) { lineComment("//") }`.
 */
public val JsonRules: TokenRules = tokenRules("json") {
    indentUnit = "  "
    string("\"")
    stringPropertyKeys()
    numbers()
    constants("true", "false", "null")
    operators("-")
    punctuation(":,")
    brackets('{' to '}', '[' to ']')
    autoClose('"' to '"')
}

/** Kotlin: hard, soft, and modifier keywords, nested block comments, raw strings, templates, annotations. */
public val KotlinRules: TokenRules = tokenRules("kotlin") {
    keywords(
        "as", "break", "class", "continue", "do", "else", "for", "fun", "if", "in", "interface", "is",
        "object", "package", "return", "super", "this", "throw", "try", "typealias", "typeof", "val", "var",
        "when", "while", "by", "catch", "constructor", "delegate", "dynamic", "field", "file", "finally",
        "get", "import", "init", "param", "property", "receiver", "set", "setparam", "value", "where",
        "abstract", "actual", "annotation", "companion", "const", "crossinline", "data", "enum", "expect",
        "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline", "open",
        "operator", "out", "override", "private", "protected", "public", "reified", "sealed", "suspend",
        "tailrec", "vararg",
    )
    constants("true", "false", "null")
    types(
        "Any", "Unit", "Nothing", "String", "Int", "Long", "Short", "Byte", "Double", "Float", "Boolean",
        "Char", "Array", "List", "Map", "Set", "MutableList", "MutableMap", "MutableSet",
    )
    lineComment("//")
    blockComment("/*", "*/", nested = true)
    string("\"\"\"", escape = null, multiline = true, interpolationOpen = "\${", simpleInterpolationPrefix = '$')
    string("\"", interpolationOpen = "\${", simpleInterpolationPrefix = '$')
    string("'")
    pattern("@[A-Za-z_][A-Za-z0-9_.]*(:[A-Za-z_][A-Za-z0-9_]*)?", TokenKind.ANNOTATION)
    pattern("`[^`]+`", TokenKind.PLAIN)
    numbers()
    functionCalls()
    capitalizedIdentifiersAreTypes()
    operators("+-*/%=<>!&|?:^~.")
    punctuation(",;")
    brackets('(' to ')', '[' to ']', '{' to '}')
    autoClose('"' to '"', '\'' to '\'')
}

/** Swift: keywords, nested block comments, multi-line strings, `\(...)` interpolation, attributes, directives. */
public val SwiftRules: TokenRules = tokenRules("swift") {
    keywords(
        "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import", "init",
        "inout", "internal", "let", "open", "operator", "private", "precedencegroup", "protocol", "public",
        "rethrows", "static", "struct", "subscript", "typealias", "var", "break", "case", "catch", "continue",
        "default", "defer", "do", "else", "fallthrough", "for", "guard", "if", "in", "repeat", "return",
        "throw", "switch", "where", "while", "Any", "as", "await", "is", "self", "Self", "super", "throws",
        "try", "async", "actor", "some", "any", "convenience", "dynamic", "didSet", "final", "get", "indirect",
        "lazy", "mutating", "nonisolated", "nonmutating", "optional", "override", "required", "set",
        "unowned", "weak", "willSet", "consuming", "borrowing", "sending", "package",
    )
    constants("true", "false", "nil")
    lineComment("//")
    blockComment("/*", "*/", nested = true)
    string("\"\"\"", multiline = true, interpolationOpen = "\\(", interpolationClose = ')')
    string("\"", interpolationOpen = "\\(", interpolationClose = ')')
    pattern("@[A-Za-z_][A-Za-z0-9_]*", TokenKind.ANNOTATION)
    pattern("#(if|elseif|else|endif|available|unavailable|selector|keyPath|file|fileID|line|column|function|warning|error|Preview)\\b", TokenKind.PREPROCESSOR)
    numbers()
    functionCalls()
    capitalizedIdentifiersAreTypes()
    operators("+-*/%=<>!&|?:^~.")
    punctuation(",;")
    brackets('(' to ')', '[' to ']', '{' to '}')
    autoClose('"' to '"')
}
