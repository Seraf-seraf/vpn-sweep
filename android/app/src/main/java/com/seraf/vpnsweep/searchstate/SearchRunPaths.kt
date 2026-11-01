package com.seraf.vpnsweep.searchstate

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

internal data class SearchRunPaths(val state: File, val staging: File)

internal class SearchRunPathAllocator(
    private val root: File,
    private val syncDirectory: (File) -> Unit,
    private val nextId: () -> String = { UUID.randomUUID().toString() },
) {
    fun reserve(): SearchRunPaths {
        val parent = root.parentFile ?: throw IOException("search_paths")
        val stateRoot = File(parent, "search-state")
        val stagingRoot = File(parent, "search-staging")
        ensureDirectory(root)
        ensureDirectory(stateRoot)
        ensureDirectory(stagingRoot)
        repeat(MAX_ATTEMPTS) {
            val id = nextId()
            if (!RecoveryStamp(id).valid()) throw IOException("search_paths")
            val candidate = File(root, id)
            try {
                Files.createDirectory(candidate.toPath(), DIRECTORY_MODE)
            } catch (_: FileAlreadyExistsException) {
                return@repeat
            }
            syncDirectory(candidate)
            syncDirectory(root)
            val state = File(stateRoot, id)
            val staging = File(stagingRoot, id)
            try { Files.createDirectory(state.toPath(), DIRECTORY_MODE) } catch (_: FileAlreadyExistsException) { return@repeat }
            syncDirectory(state)
            syncDirectory(stateRoot)
            try { Files.createDirectory(staging.toPath(), DIRECTORY_MODE) } catch (_: FileAlreadyExistsException) { return@repeat }
            syncDirectory(staging)
            syncDirectory(stagingRoot)
            if (!privateDirectory(state) || !privateDirectory(staging)) throw IOException("search_paths")
            return SearchRunPaths(state, staging)
        }
        throw IOException("search_paths")
    }

    private fun ensureDirectory(directory: File): Unit {
        if (attributes(directory) == null) {
            try { Files.createDirectory(directory.toPath(), DIRECTORY_MODE) } catch (_: FileAlreadyExistsException) { }
        }
        if (!privateDirectory(directory)) throw IOException("search_paths")
        syncDirectory(directory.parentFile ?: throw IOException("search_paths"))
    }

    private fun privateDirectory(directory: File): Boolean = attributes(directory)?.isDirectory == true &&
        Files.getPosixFilePermissions(directory.toPath(), LinkOption.NOFOLLOW_LINKS) == DIRECTORY_PERMISSIONS

    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private companion object {
        const val MAX_ATTEMPTS = 16
        val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        val DIRECTORY_MODE = PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS)
    }
}
