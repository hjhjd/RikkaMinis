package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalSessionShellResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `fresh Alpine rootfs falls back to bin sh`() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        java.io.File(rootfs, "bin").mkdirs()

        assertEquals("/bin/sh", TerminalSession.resolveInteractiveShell(rootfs))
    }

    @Test
    fun `installed bash is preferred`() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val bin = java.io.File(rootfs, "bin").apply { mkdirs() }
        java.io.File(bin, "bash").writeText("")

        assertEquals("/bin/bash", TerminalSession.resolveInteractiveShell(rootfs))
    }
}
