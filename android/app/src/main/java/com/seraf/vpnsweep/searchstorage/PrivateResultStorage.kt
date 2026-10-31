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
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class PrivateResultStorage(context: Context) : ResultStorage {
    private val root = File(context.noBackupFilesDir, "search-results")
    private val manifestFile = File(root, "current")

    override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String {
        ensureRoot()
        val id = UUID.randomUUID().toString()
        val directory = File(root, id)
        if (!directory.mkdir()) throw IOException("generation_create")
        try {
            val result = File(directory, "result")
            FileOutputStream(result).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
            writeRecord(File(directory, "metadata"), ResultManifest(id, revision, sha256))
            syncDirectory(directory)
            syncDirectory(root)
            return id
        } catch (failure: IOException) {
            try {
                deleteGeneration(directory)
            } catch (cleanup: IOException) {
                failure.addSuppressed(cleanup)
                throw PreparationCleanupUnknownException(failure)
            }
            throw failure
        }
    }

    override fun discard(generation: String): Unit {
        if (!validGeneration(generation)) throw IOException("generation_invalid")
        if (readManifest()?.generation == generation) throw IOException("generation_published")
        deleteGeneration(File(root, generation))
    }

    private fun deleteGeneration(directory: File): Unit {
        for (name in listOf("result", "metadata")) {
            val file = File(directory, name)
            if (file.exists() && !file.delete()) throw IOException("generation_cleanup")
        }
        if (directory.exists() && !directory.delete()) throw IOException("generation_cleanup")
        syncDirectory(root)
    }

    override fun readGeneration(generation: String): StoredResult? {
        if (!validGeneration(generation)) return null
        val directory = File(root, generation)
        val metadata = readRecord(File(directory, "metadata")) ?: return null
        if (metadata.generation != generation) return null
        val result = File(directory, "result")
        if (!result.isFile) return null
        return StoredResult(metadata.revision, metadata.sha256, FileInputStream(result))
    }

    override fun readManifest(): ResultManifest? {
        if (!root.exists()) return null
        syncDirectory(root)
        return readRecord(manifestFile)
    }

    override fun publish(manifest: ResultManifest): Unit {
        ensureRoot()
        val temporary = File(root, "manifest-${UUID.randomUUID()}")
        try {
            writeRecord(temporary, manifest)
            try {
                Files.move(temporary.toPath(), manifestFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                syncDirectory(root)
            } catch (failure: IOException) {
                throw DurabilityUnknownException(failure)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun ensureRoot(): Unit {
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) throw IOException("storage_unavailable")
        syncDirectory(root.parentFile ?: throw IOException("storage_unavailable"))
    }

    private fun writeRecord(file: File, record: ResultManifest): Unit {
        FileOutputStream(file).use { output ->
            val data = DataOutputStream(output)
            data.writeInt(RECORD_VERSION)
            data.writeUTF(record.generation)
            data.writeUTF(record.revision.source)
            data.writeUTF(record.revision.selection)
            data.writeUTF(record.sha256)
            data.flush()
            output.fd.sync()
        }
    }

    private fun readRecord(file: File): ResultManifest? {
        if (!file.exists()) return null
        return DataInputStream(FileInputStream(file)).use { data ->
            if (data.readInt() != RECORD_VERSION) throw IOException("record_version")
            val id = data.readUTF()
            val source = data.readUTF()
            val selection = data.readUTF()
            val sha = data.readUTF()
            if (!validGeneration(id) || source.isEmpty() || selection.isEmpty() ||
                !sha.matches(SHA256_PATTERN) || data.read() != -1) throw IOException("record_invalid")
            ResultManifest(id, ResultRevision(source, selection), sha)
        }
    }

    private fun validGeneration(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun syncDirectory(directory: File): Unit {
        try {
            val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw IOException("directory_invalid")
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (failure: ErrnoException) {
            throw IOException("directory_sync", failure)
        }
    }
}

private const val RECORD_VERSION = 1
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
