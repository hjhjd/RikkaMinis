package com.openminis.app.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Imports user-selected avatars into Agent-private, stable storage. */
class AgentAvatarStore(private val context: Context) {
    fun file(agentId: String): File =
        File(context.filesDir, "minis-agents/${safeId(agentId)}/avatar.webp")

    fun import(agentId: String, uri: Uri, zoom: Float = 1f): String {
        val resolver = context.contentResolver
        val source = resolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: error("Unable to decode selected image")
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        val oriented = if (rotation == 0f) source else {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(rotation) }, true)
                .also { source.recycle() }
        }
        val edge = minOf(oriented.width, oriented.height)
        val cropEdge = (edge / zoom.coerceIn(1f, 3f)).toInt().coerceAtLeast(1)
        val square = Bitmap.createBitmap(
            oriented,
            (oriented.width - cropEdge) / 2,
            (oriented.height - cropEdge) / 2,
            cropEdge,
            cropEdge,
        )
        if (square !== oriented) oriented.recycle()
        val output = if (edge > MAX_EDGE) {
            Bitmap.createScaledBitmap(square, MAX_EDGE, MAX_EDGE, true).also { square.recycle() }
        } else square

        val target = file(agentId)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".avatar-${System.nanoTime()}.tmp")
        try {
            temp.outputStream().buffered().use { stream ->
                check(output.compress(Bitmap.CompressFormat.WEBP, 88, stream)) { "Avatar compression failed" }
            }
            if (target.exists() && !target.delete()) error("Unable to replace old avatar")
            if (!temp.renameTo(target)) error("Unable to store avatar")
        } finally {
            output.recycle()
            temp.delete()
        }
        return target.relativeTo(context.filesDir).path
    }

    fun resolve(relativePath: String?): File? = relativePath
        ?.takeIf { it.isNotBlank() }
        ?.let { File(context.filesDir, it) }
        ?.takeIf { it.isFile }

    fun delete(agentId: String) {
        file(agentId).delete()
    }

    private fun safeId(id: String): String {
        require(id.isNotBlank() && id.none { it == '/' || it == '\\' } && id != "." && id != "..")
        return id
    }

    private companion object {
        const val MAX_EDGE = 512
    }
}
