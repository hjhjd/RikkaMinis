package com.openminis.app.execplane.protocol

object ExecPlaneCapabilities {
    const val EXEC = "exec"
    const val STATUS = "status"
    const val FS_STAT = "fs.stat"
    const val FS_LIST = "fs.list"
    const val FS_READ = "fs.read"
    const val FS_WRITE = "fs.write"
    const val FS_MKDIR = "fs.mkdir"
    const val FS_REMOVE = "fs.remove"
    const val FS_MOVE = "fs.move"
    const val TRANSFER_PUSH = "transfer.push"
    const val TRANSFER_PULL = "transfer.pull"
    const val ENV_INJECT = "env.inject"

    val FILE = setOf(FS_STAT, FS_LIST, FS_READ, FS_WRITE, FS_MKDIR, FS_REMOVE, FS_MOVE)
    val TRANSFER = setOf(TRANSFER_PUSH, TRANSFER_PULL)
    val ALL = setOf(EXEC, STATUS) + FILE + TRANSFER + ENV_INJECT
}

enum class CapabilityGroup { COMMAND, FILES, TRANSFER, ENVIRONMENT }

fun Set<String>.capabilityGroups(): Set<CapabilityGroup> = buildSet {
    if (ExecPlaneCapabilities.EXEC in this@capabilityGroups) add(CapabilityGroup.COMMAND)
    if (containsAll(ExecPlaneCapabilities.FILE)) add(CapabilityGroup.FILES)
    if (containsAll(ExecPlaneCapabilities.TRANSFER)) add(CapabilityGroup.TRANSFER)
    if (ExecPlaneCapabilities.ENV_INJECT in this@capabilityGroups) add(CapabilityGroup.ENVIRONMENT)
}
