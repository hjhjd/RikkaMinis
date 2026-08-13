package com.openminis.app.sandbox

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeHostPathResolverTest {
    @Test
    fun normalRelativePathStaysInsideRoot() {
        val root = Files.createTempDirectory("resolver").toFile()
        assertEquals(root.resolve("a/b").canonicalFile, SafeHostPathResolver.resolve(root, "a/b"))
        assertEquals(root.canonicalFile, SafeHostPathResolver.resolve(root, ""))
    }

    @Test
    fun traversalAbsoluteTailAndNulAreRejected() {
        val root = Files.createTempDirectory("resolver").toFile()
        assertNull(SafeHostPathResolver.resolve(root, "../../escape"))
        assertNull(SafeHostPathResolver.resolve(root, "/absolute"))
        assertNull(SafeHostPathResolver.resolve(root, "bad\u0000tail"))
    }

    @Test
    fun symlinkEscapeIsRejected() {
        val root = Files.createTempDirectory("resolver-root").toFile()
        val outside = Files.createTempDirectory("resolver-outside")
        val link = root.toPath().resolve("link")
        Files.createSymbolicLink(link, outside)
        assertNull(SafeHostPathResolver.resolve(root, "link/secret"))
    }

    @Test
    fun sessionIdsCannotBecomeHostPaths() {
        assertTrue(SafeHostPathResolver.validateSessionId("session-1_ok.test"))
        assertFalse(SafeHostPathResolver.validateSessionId("../escape"))
        assertFalse(SafeHostPathResolver.validateSessionId("/absolute"))
        assertFalse(SafeHostPathResolver.validateSessionId("bad/slash"))
        assertFalse(SafeHostPathResolver.validateSessionId(""))
    }
}
