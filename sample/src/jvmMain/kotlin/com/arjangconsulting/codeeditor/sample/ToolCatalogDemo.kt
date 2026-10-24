package com.arjangconsulting.codeeditor.sample

import com.arjangconsulting.codeeditor.BackendLanguageService
import com.arjangconsulting.codeeditor.CompletionBackend
import com.arjangconsulting.codeeditor.CompletionContext
import com.arjangconsulting.codeeditor.CompletionProtocol
import com.arjangconsulting.codeeditor.ToolCommandCatalog
import com.arjangconsulting.codeeditor.ToolCompletionBackend
import com.arjangconsulting.codeeditor.ToolValueProvider
import kotlinx.coroutines.delay

/** Offline subset of amoo's public MCP tools/list metadata, captured on 2026-10-23. */
val ToolDemoCatalog: ToolCommandCatalog = ToolCommandCatalog.fromMcpToolsJson(
    checkNotNull(CommandLanguageService::class.java.getResourceAsStream("/amoo-tools.sample.json"))
        .bufferedReader().use { it.readText() },
)

/** Highlighting for the externally discovered tool catalog. */
val ToolDemoRules = ToolDemoCatalog.languageRules("tool-catalog")

private val catalogBackend = ToolCompletionBackend(
    catalog = ToolDemoCatalog,
    valueProvider = ToolValueProvider { request ->
        if (request.parameter.name == "id" || request.parameter.name == "element_id") {
            delay(150)
            listOf("sign-in", "username", "checkout", "Account menu")
                .filter { it.startsWith(request.prefix, ignoreCase = true) }
        } else {
            null
        }
    },
)

private val jsonBoundary = CompletionBackend { request ->
    val received = CompletionProtocol.decodeRequest(CompletionProtocol.encodeRequest(request))
    val response = catalogBackend.complete(received)
    CompletionProtocol.decodeResponse(CompletionProtocol.encodeResponse(response))
}

/** Demonstrates an external provider's JSON boundary with fixture runtime values and no tool execution. */
fun toolDemoService(sessionId: String, revision: Long = 0): BackendLanguageService = BackendLanguageService(
    backend = jsonBoundary,
    languageId = ToolDemoRules.name,
    context = CompletionContext(sessionId, revision),
)
