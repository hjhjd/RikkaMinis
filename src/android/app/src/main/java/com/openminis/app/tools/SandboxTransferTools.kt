package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object SandboxFilePushTool {
    const val NAME = "sandbox_file_push"
    fun definition() = AgentToolDefinition(
        name = NAME,
        description = "Explicitly push a local App/PRoot file or directory into a named WebSocket sandbox. Local and WS filesystems are not shared. Transfers are resumable and checksum-verified.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise summary shown to the user."),
            "sandbox" to AgentToolParam("string", "Exact online WebSocket sandbox name."),
            "source" to AgentToolParam("string", "Local App/PRoot source path."),
            "destination" to AgentToolParam("string", "Destination path inside the WS sandbox."),
            "overwrite" to AgentToolParam("string", "Conflict policy.", enumValues = listOf("fail", "replace_file", "merge_directory", "replace_directory")),
        ),
        required = listOf("tool_title", "sandbox", "source", "destination"),
        propertyOrdering = listOf("tool_title", "sandbox", "source", "destination", "overwrite"),
    )
}

object SandboxFilePullTool {
    const val NAME = "sandbox_file_pull"
    fun definition() = AgentToolDefinition(
        name = NAME,
        description = "Explicitly pull a file or directory from a named WebSocket sandbox into the local App/PRoot file space. Only a completed checksum-verified pull can become a minis:// resource.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise summary shown to the user."),
            "sandbox" to AgentToolParam("string", "Exact online WebSocket sandbox name."),
            "source" to AgentToolParam("string", "Source path inside the WS sandbox."),
            "destination" to AgentToolParam("string", "Local App/PRoot destination path."),
            "directory" to AgentToolParam("boolean", "True when the source is a directory."),
            "overwrite" to AgentToolParam("string", "Conflict policy.", enumValues = listOf("fail", "replace_file", "merge_directory", "replace_directory")),
        ),
        required = listOf("tool_title", "sandbox", "source", "destination"),
        propertyOrdering = listOf("tool_title", "sandbox", "source", "destination", "directory", "overwrite"),
    )
}
