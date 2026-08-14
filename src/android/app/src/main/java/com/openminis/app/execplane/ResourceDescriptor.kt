package com.openminis.app.execplane

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class ResourceReference(
    val resourceId: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val mimeType: String? = null,
)

/** Business-verb-independent metadata for bytes crossing the WS boundary. */
data class ResourceDescriptor(
    val resourceId: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val mimeType: String? = null,
) {
    init {
        require(resourceId.isNotBlank() && name.isNotBlank())
        require(size in 0..MAX_RESOURCE_BYTES)
        require(sha256.matches(Regex("^[0-9a-f]{64}$")))
    }

    fun reference() = ResourceReference(resourceId, name, size, sha256, mimeType)

    companion object {
        const val MAX_RESOURCE_BYTES = 256L * 1024 * 1024
        fun fromFile(file: File, mimeType: String? = null): ResourceDescriptor {
            require(file.isFile && file.length() <= MAX_RESOURCE_BYTES)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return ResourceDescriptor(UUID.randomUUID().toString(), file.name, file.length(),
                digest.digest().joinToString("") { "%02x".format(it) }, mimeType)
        }
    }
}
