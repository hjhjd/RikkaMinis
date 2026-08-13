package com.openminis.app.data.model

import org.json.JSONObject

/**
 * Structured content parts for agent loop messages.
 * Used to represent tool_use/tool_result blocks in the conversation history
 * that providers serialize into their native format.
 */
sealed class AgentContentPart {
    data class Text(val text: String) : AgentContentPart()

    data class ToolUse(
        val id: String,
        val name: String,
        val input: JSONObject,
    ) : AgentContentPart()

    data class ToolResult(
        val id: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
        val imageData: ByteArray? = null,
        val imageMimeType: String? = null,
        /**
         * iSH-visible linux path for [imageData], if it was persisted. Used
         * by request-level image budgeting to emit a re-fetchable text
         * placeholder when the bytes are elided.
         */
        val imageLinuxPath: String? = null,
    ) : AgentContentPart() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ToolResult) return false
            return id == other.id && name == other.name && content == other.content &&
                isError == other.isError && imageData.contentEquals(other.imageData) &&
                imageMimeType == other.imageMimeType && imageLinuxPath == other.imageLinuxPath
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + content.hashCode()
            result = 31 * result + isError.hashCode()
            result = 31 * result + (imageData?.contentHashCode() ?: 0)
            result = 31 * result + (imageMimeType?.hashCode() ?: 0)
            result = 31 * result + (imageLinuxPath?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * 用户直接附加的非图片文件。文件只在会话 attachments/uploads 中保存一份，
     * Provider 在组装请求时按自身协议直接读取；不支持的类型退化为路径提示。
     */
    data class FileData(
        val fileName: String,
        val mimeType: String,
        val hostPath: String,
        val linuxPath: String,
        val size: Long,
    ) : AgentContentPart()

    data class ImageData(
        val data: ByteArray,
        val mimeType: String,
        /**
         * iSH-visible linux path the bytes were originally persisted to, if
         * any. See [LLMMessage.ImagePart.linuxPath] for usage notes.
         */
        val linuxPath: String? = null,
    ) : AgentContentPart() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageData) return false
            return data.contentEquals(other.data) && mimeType == other.mimeType &&
                linuxPath == other.linuxPath
        }

        override fun hashCode(): Int {
            var result = 31 * data.contentHashCode() + mimeType.hashCode()
            result = 31 * result + (linuxPath?.hashCode() ?: 0)
            return result
        }
    }
}
