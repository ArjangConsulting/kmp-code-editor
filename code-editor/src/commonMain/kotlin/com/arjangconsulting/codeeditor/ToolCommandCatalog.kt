package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Scalar value type used when inserting a command argument. */
@Serializable
public enum class ToolValueKind {
    STRING,
    BOOLEAN,
    NUMBER,
    JSON,
}

/**
 * One argument in a tool's external completion catalog.
 *
 * @property name Argument name in `name=value` syntax.
 * @property description Tool-provided documentation.
 * @property required Whether the tool schema requires this argument.
 * @property values Known raw values, commonly obtained from a schema's enum.
 * @property kind Schema value type; complex values use [ToolValueKind.JSON].
 */
@Immutable
@Serializable
public data class ToolParameter(
    val name: String,
    val description: String? = null,
    val required: Boolean = false,
    val values: List<String> = emptyList(),
    val kind: ToolValueKind = ToolValueKind.STRING,
)

/**
 * A command discovered from public metadata or supplied by a host/add-on.
 *
 * @property name Command name, without a CLI executable prefix.
 * @property description Tool-provided documentation.
 * @property parameters Named arguments supported by this command.
 */
@Immutable
@Serializable
public data class ToolCommand(
    val name: String,
    val description: String? = null,
    val parameters: List<ToolParameter> = emptyList(),
)

/**
 * Declarative completion metadata for tools whose source code is unavailable.
 *
 * Supports a lightweight `command name=value` prompt grammar. Metadata can come from MCP
 * `tools/list`, a manually maintained catalog, CLI help, or any public schema. The catalog performs
 * no command execution, device queries, process management, or full JSON Schema validation.
 *
 * @property commands Available command descriptions. Treat nested collections as immutable.
 */
@Immutable
@Serializable
public data class ToolCommandCatalog(val commands: List<ToolCommand>) {
    init {
        require(commands.map { it.name }.distinct().size == commands.size) { "Command names must be unique" }
        for (command in commands) {
            require(validName(command.name)) { "Command names must be nonempty words" }
            require(command.parameters.map { it.name }.distinct().size == command.parameters.size) {
                "Parameter names must be unique within a command"
            }
            require(command.parameters.all { validName(it.name) }) { "Parameter names must be nonempty words" }
        }
    }

    /** Builds highlighting and editing rules for this catalog; retain the result across recomposition. */
    public fun languageRules(name: String = "tools"): TokenRules = tokenRules(name) {
        keywords(*commands.map { it.name }.toTypedArray(), kind = TokenKind.FUNCTION)
        constants("true", "false", "null")
        attributeAssignments()
        lineComment("#")
        string("\"")
        string("'", escape = null)
        numbers()
        operators("=")
        brackets('{' to '}', '[' to ']')
        autoClose('"' to '"', '\'' to '\'')
    }

    public companion object {
        /**
         * Imports an MCP `tools/list` result, a full JSON-RPC response, or a raw tool array.
         * Supports top-level properties, required arguments, enums, consts, boolean types, and
         * descriptions. Nested schemas, `$ref`, and schema composition require a host adapter;
         * their properties remain available with JSON-valued placeholders where applicable.
         */
        public fun fromMcpToolsJson(value: String): ToolCommandCatalog {
            val root = Json.parseToJsonElement(value)
            val tools = when (root) {
                is JsonArray -> root
                is JsonObject -> (root["tools"] ?: (root["result"] as? JsonObject)?.get("tools")) as? JsonArray
                else -> null
            } ?: throw IllegalArgumentException("Expected an MCP tools/list result")
            return ToolCommandCatalog(tools.map { element ->
                val tool = element.jsonObject
                val name = (tool["name"] as? JsonPrimitive)?.contentOrNull
                    ?: throw IllegalArgumentException("Tool name is required")
                val schema = tool["inputSchema"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (schema["required"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                ToolCommand(
                    name = name,
                    description = (tool["description"] as? JsonPrimitive)?.contentOrNull,
                    parameters = properties.map { (parameterName, property) ->
                        val definition = property as? JsonObject ?: JsonObject(emptyMap())
                        val type = (definition["type"] as? JsonPrimitive)?.contentOrNull
                        val values = (definition["enum"] as? JsonArray)?.map { rawValue ->
                            if (rawValue is JsonPrimitive) rawValue.content else rawValue.toString()
                        } ?: definition["const"]?.let { constant ->
                            listOf(if (constant is JsonPrimitive) constant.content else constant.toString())
                        } ?: emptyList()
                        ToolParameter(
                            name = parameterName,
                            description = (definition["description"] as? JsonPrimitive)?.contentOrNull,
                            required = parameterName in required,
                            values = values,
                            kind = when (type) {
                                "boolean" -> ToolValueKind.BOOLEAN
                                "integer", "number" -> ToolValueKind.NUMBER
                                "array", "object" -> ToolValueKind.JSON
                                else -> ToolValueKind.STRING
                            },
                        )
                    },
                )
            })
        }
    }
}

private fun validName(value: String): Boolean =
    value.isNotEmpty() && value.none { it.isWhitespace() || it in "='\"\\#" }
