package com.openminis.app.execplane

import android.content.Context
import android.util.Base64
import com.openminis.app.sandbox.PRootKernel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SandboxTransferService(private val bridge: ExecPlaneBridge) {
    data class Result(val path: String, val size: Long, val sha256: String, val directory: Boolean)
    private val chunkSize = 256 * 1024

    suspend fun push(context: Context, sessionId: String, sandbox: String, source: String, destination: String, overwrite: String): Result {
        val local = PRootKernel.resolveSessionHostPath(sessionId, source, context)
            ?: throw IllegalArgumentException("Cannot resolve local path: $source")
        require(local.exists()) { "Local source not found: $source" }
        val directory = local.isDirectory
        val payload = if (directory) zipDirectory(local) else local
        try {
            val digest = sha256(payload)
            val transferId = UUID.randomUUID().toString()
            val opened = checked(sandbox, "transfer.open", buildJsonObject {
                put("transferId", transferId); put("direction", "push"); put("path", destination)
                put("type", if (directory) "directory" else "file"); put("size", payload.length())
                put("sha256", digest); put("overwrite", overwrite)
            })
            var sequence = opened["nextSequence"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            FileInputStream(payload).use { input ->
                input.channel.position(sequence.toLong() * chunkSize)
                val buffer = ByteArray(chunkSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val bytes = buffer.copyOf(read)
                    checked(sandbox, "transfer.chunk", buildJsonObject {
                        put("transferId", transferId); put("sequence", sequence)
                        put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)); put("chunkSha256", sha256(bytes))
                    })
                    sequence++
                }
            }
            val committed = checked(sandbox, "transfer.commit", buildJsonObject { put("transferId", transferId) })
            return Result(destination, committed["size"]!!.jsonPrimitive.content.toLong(), committed["sha256"]!!.jsonPrimitive.content, directory)
        } finally { if (payload !== local) payload.delete() }
    }

    suspend fun pull(context: Context, sessionId: String, sandbox: String, source: String, destination: String, overwrite: String, directory: Boolean): Result {
        if (PRootKernel.isLinuxPathUnderReadOnlyMount(destination)) error("Destination is inside a read-only mounted folder")
        val local = PRootKernel.resolveSessionHostPath(sessionId, destination, context)
            ?: throw IllegalArgumentException("Cannot resolve local path: $destination")
        val transferId = UUID.randomUUID().toString()
        val opened = checked(sandbox, "transfer.open", buildJsonObject {
            put("transferId", transferId); put("direction", "pull"); put("path", source)
            put("type", if (directory) "directory" else "file"); put("overwrite", overwrite)
        })
        val size = opened["size"]!!.jsonPrimitive.content.toLong()
        val expected = opened["sha256"]!!.jsonPrimitive.content
        val temp = File(local.parentFile, ".${local.name}.minis-$transferId.part")
        temp.parentFile?.mkdirs()
        var sequence = if (temp.exists()) (temp.length() / chunkSize).toInt() else 0
        FileOutputStream(temp, sequence > 0).use { output ->
            while (true) {
                val chunk = checked(sandbox, "transfer.chunk", buildJsonObject { put("transferId", transferId); put("sequence", sequence) })
                val bytes = Base64.decode(chunk["data"]!!.jsonPrimitive.content, Base64.DEFAULT)
                require(sha256(bytes) == chunk["chunkSha256"]!!.jsonPrimitive.content) { "Chunk checksum mismatch" }
                output.write(bytes); sequence++
                if (chunk["eof"]?.jsonPrimitive?.content == "true") break
            }
        }
        require(temp.length() == size && sha256(temp) == expected) { "Final checksum mismatch" }
        if (local.exists()) {
            require(overwrite != "fail") { "Destination exists: $destination" }
            if (local.isDirectory) local.deleteRecursively() else local.delete()
        }
        if (directory) { unzipSafe(temp, local); temp.delete() } else require(temp.renameTo(local)) { "Atomic move failed" }
        checked(sandbox, "transfer.commit", buildJsonObject { put("transferId", transferId) })
        return Result(destination, size, expected, directory)
    }

    private suspend fun checked(sandbox: String, method: String, params: JsonObject): JsonObject {
        val response = bridge.request(sandbox, method, params, 1_800_000)
        if (!response.ok) error("${response.error?.code}: ${response.error?.message}")
        return response.result?.jsonObject ?: JsonObject(emptyMap())
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun zipDirectory(root: File): File = File.createTempFile("minis-push-", ".zip").also { out ->
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(relative)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
    private fun unzipSafe(zipFile: File, destination: File) {
        destination.mkdirs(); val root = destination.canonicalFile
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(destination, entry.name).canonicalFile
                require(output.path == root.path || output.path.startsWith(root.path + File.separator)) { "Archive path escape" }
                if (entry.isDirectory) output.mkdirs() else { output.parentFile?.mkdirs(); output.outputStream().use { zip.copyTo(it) } }
                zip.closeEntry()
            }
        }
    }
}
