package com.openminis.app.execplane

import android.content.Context
import android.util.Base64
import com.openminis.app.sandbox.PRootKernel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        require(!Files.isSymbolicLink(local.toPath())) { "Symbolic link sources are forbidden" }
        val directory = local.isDirectory
        val policy = normalizePolicy(overwrite, directory)
        val payload = if (directory) zipDirectory(local) else local
        val transferId = UUID.randomUUID().toString()
        var opened = false
        try {
            val digest = sha256(payload)
            val response = checked(sandbox, "transfer.open", buildJsonObject {
                put("transferId", transferId); put("direction", "push"); put("path", destination)
                put("type", if (directory) "directory" else "file"); put("size", payload.length())
                put("sha256", digest); put("overwrite", policy)
            })
            opened = true
            var sequence = response["nextSequence"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
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
            opened = false
            return Result(destination, committed["size"]!!.jsonPrimitive.content.toLong(), committed["sha256"]!!.jsonPrimitive.content, directory)
        } catch (error: Throwable) {
            if (opened) runCatching { checked(sandbox, "transfer.abort", buildJsonObject { put("transferId", transferId) }) }
            throw error
        } finally { if (payload !== local) payload.delete() }
    }

    suspend fun pull(context: Context, sessionId: String, sandbox: String, source: String, destination: String, overwrite: String, directory: Boolean): Result {
        if (PRootKernel.isLinuxPathUnderReadOnlyMount(destination)) error("Destination is inside a read-only mounted folder")
        val local = PRootKernel.resolveSessionHostPath(sessionId, destination, context)
            ?: throw IllegalArgumentException("Cannot resolve local path: $destination")
        val policy = normalizePolicy(overwrite, directory)
        if (local.exists()) {
            require(policy != "fail") { "Destination exists: $destination" }
            require(directory == local.isDirectory) { "Destination type conflicts with source" }
        }
        val transferId = UUID.randomUUID().toString()
        var opened = false
        var temp: File? = null
        var replacement: LocalReplacement? = null
        try {
            val response = checked(sandbox, "transfer.open", buildJsonObject {
                put("transferId", transferId); put("direction", "pull"); put("path", source)
                put("type", if (directory) "directory" else "file"); put("overwrite", policy)
            })
            opened = true
            val size = response["size"]!!.jsonPrimitive.content.toLong()
            val expected = response["sha256"]!!.jsonPrimitive.content
            temp = File(local.parentFile, ".${local.name}.minis-$transferId.part")
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
            val stage = File(local.parentFile, ".${local.name}.minis-$transferId.stage")
            deletePath(stage)
            if (directory) unzipSafe(temp, stage) else movePath(temp, stage)
            replacement = beginTransactionalReplace(stage, local, transferId)
            runCatching {
                checked(sandbox, "transfer.commit", buildJsonObject { put("transferId", transferId) })
            }.recoverCatching {
                checked(sandbox, "transfer.resume", buildJsonObject { put("transferId", transferId) }).also { state ->
                    require(state["state"]?.jsonPrimitive?.content == "COMMITTED") { "Remote commit confirmation failed" }
                }
            }.getOrThrow()
            opened = false
            replacement?.finish()
            replacement = null
            temp.delete()
            return Result(destination, size, expected, directory)
        } catch (error: Throwable) {
            replacement?.rollback()
            replacement = null
            if (opened) runCatching { checked(sandbox, "transfer.abort", buildJsonObject { put("transferId", transferId) }) }
            temp?.delete()
            deletePath(File(local.parentFile, ".${local.name}.minis-$transferId.stage"))
            throw error
        }
    }

    private fun normalizePolicy(policy: String, directory: Boolean): String = when {
        policy == "fail" -> policy
        directory && policy in setOf("replace_directory", "merge_directory") -> "replace_directory"
        !directory && policy == "replace_file" -> policy
        else -> error("Invalid overwrite policy for ${if (directory) "directory" else "file"}")
    }

    private suspend fun checked(sandbox: String, method: String, params: JsonObject): JsonObject {
        val response = bridge.request(sandbox, method, params, 1_800_000)
        if (!response.ok) throw response.remoteFailure("Remote transfer failed")
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
            root.walkTopDown().filter { it != root }.forEach { file ->
                require(!Files.isSymbolicLink(file.toPath())) { "Directory symbolic links are forbidden: ${file.path}" }
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry(relative.trimEnd('/') + "/")); zip.closeEntry()
                } else if (file.isFile) {
                    zip.putNextEntry(ZipEntry(relative)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        }
    }
    private fun unzipSafe(zipFile: File, destination: File) {
        require(destination.mkdirs()) { "Cannot create staging directory" }
        val root = destination.canonicalFile
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val output = File(destination, entry.name).canonicalFile
                    require(output.path == root.path || output.path.startsWith(root.path + File.separator)) { "Archive path escape" }
                    require(!Files.isSymbolicLink(output.toPath())) { "Archive symbolic links are forbidden" }
                    if (entry.isDirectory) {
                        require(output.mkdirs() || output.isDirectory) { "Cannot create archive directory" }
                    } else {
                        require(output.parentFile?.mkdirs() != false || output.parentFile?.isDirectory == true) { "Cannot create archive parent" }
                        output.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw error
        }
    }

    private fun beginTransactionalReplace(stage: File, target: File, transferId: String): LocalReplacement {
        val backup = File(target.parentFile, ".${target.name}.minis-$transferId.backup")
        deletePath(backup)
        val hadTarget = target.exists() || Files.isSymbolicLink(target.toPath())
        if (hadTarget) movePath(target, backup)
        try {
            movePath(stage, target)
        } catch (error: Throwable) {
            if (hadTarget && backup.exists()) movePath(backup, target)
            throw error
        }
        return LocalReplacement(target, backup, hadTarget)
    }

    private inner class LocalReplacement(
        private val target: File,
        private val backup: File,
        private val hadTarget: Boolean,
    ) {
        fun finish() = deletePath(backup)
        fun rollback() {
            deletePath(target)
            if (hadTarget && backup.exists()) movePath(backup, target)
            else deletePath(backup)
        }
    }

    private fun movePath(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        runCatching {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.recoverCatching {
            Files.move(source.toPath(), destination.toPath())
        }.getOrThrow()
    }

    private fun deletePath(file: File) {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) file.deleteRecursively()
        else Files.deleteIfExists(file.toPath())
    }
}
