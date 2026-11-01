package com.seraf.vpnsweep.searchstorage

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal class PrivateRevisionStorage(
    private val root: File,
    private val syncDirectory: (File) -> Unit,
) : RevisionStorage {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "search-revisions"), ::syncRevisionDirectory)

    private val current = File(root, "current")

    override fun read(): ResultRevision? {
        val rootAttributes = attributes(root)
        if (rootAttributes == null) {
            syncDirectory(root.parentFile ?: throw IOException("revision_storage"))
            if (attributes(root) != null) throw IOException("revision_changed")
            return null
        }
        if (!rootAttributes.isDirectory) throw IOException("revision_storage")
        syncDirectory(root)
        val currentAttributes = attributes(current)
        if (currentAttributes == null) {
            if (attributes(current) != null) throw IOException("revision_changed")
            return null
        }
        if (!currentAttributes.isRegularFile) throw IOException("revision_invalid")
        return DataInputStream(FileInputStream(current)).use { data ->
            if (data.readInt() != VERSION) throw IOException("revision_version")
            val source = data.readUTF()
            val selection = data.readUTF()
            if (source.isEmpty() || selection.isEmpty() || data.read() != -1) throw IOException("revision_invalid")
            ResultRevision(source, selection)
        }
    }

    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    override fun write(revision: ResultRevision): Unit {
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) throw IOException("revision_storage")
        syncDirectory(root.parentFile ?: throw IOException("revision_storage"))
        val temporary = File(root, "revision-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                val data = DataOutputStream(output)
                data.writeInt(VERSION)
                data.writeUTF(revision.source)
                data.writeUTF(revision.selection)
                data.flush()
                output.fd.sync()
            }
            Files.move(temporary.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(root)
        } finally {
            temporary.delete()
        }
    }

}

private fun syncRevisionDirectory(directory: File): Unit {
    try {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw IOException("directory_invalid")
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    } catch (failure: ErrnoException) {
        throw IOException("revision_sync", failure)
    }
}

private const val VERSION = 1
