package com.treko.core.mcp

/**
 * Definición estándar de herramienta MCP (Model Context Protocol).
 */
data class McpTool(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
    val execute: suspend (args: Map<String, Any?>) -> String
)

/**
 * Gestor y catálogo de herramientas MCP locales de Treko.
 */
class McpDispatcher {
    private val tools = mutableMapOf<String, McpTool>()

    fun registerTool(tool: McpTool) {
        tools[tool.name] = tool
    }

    fun getToolList(): List<McpTool> = tools.values.toList()

    suspend fun callTool(name: String, args: Map<String, Any?>): String {
        val tool = tools[name] ?: return "Error: Herramienta '$name' no encontrada."
        return try {
            tool.execute(args)
        } catch (e: Exception) {
            "Error ejecutando $name: ${e.message}"
        }
    }
}
