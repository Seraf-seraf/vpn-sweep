package com.seraf.vpnsweep.searchstate

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal class PrivateSessionMarker(private val directory: File, private val syncDirectory: (File) -> Unit) : SessionMarker {
    private val marker = File(directory, "current")

    @Synchronized override fun read(): MarkerState = read(requirePresent = false)
    @Synchronized fun readForRecovery(): MarkerState = try {
        if (attributes(directory)?.isDirectory != true) MarkerState.UNKNOWN else {
            syncDirectory(directory)
            read(requirePresent = true)
        }
    } catch (_: Exception) { MarkerState.UNKNOWN }

    private fun read(requirePresent: Boolean): MarkerState {
        return try {
        val root = attributes(directory)
        if (root == null) return if (requirePresent) MarkerState.UNKNOWN else MarkerState.CLEAR
        if (!root.isDirectory) return MarkerState.UNKNOWN
        val value = attributes(marker) ?: return if (requirePresent) MarkerState.UNKNOWN else MarkerState.CLEAR
        if (!value.isRegularFile || value.size() !in 1..6) return MarkerState.UNKNOWN
        when (String(FileInputStream(marker).use { it.readNBytes(7) }, Charsets.US_ASCII)) {
            ACTIVE -> MarkerState.ACTIVE
            CLEAR -> MarkerState.CLEAR
            else -> MarkerState.UNKNOWN
        }
        } catch (_: Exception) { MarkerState.UNKNOWN }
    }

    @Synchronized override fun markActive(): Boolean = write(ACTIVE)
    @Synchronized override fun markClear(): Boolean = write(CLEAR)

    private fun write(value: String): Boolean {
        return try {
        val root = attributes(directory)
        if (root == null) {
            Files.createDirectory(directory.toPath())
            syncDirectory(directory.parentFile ?: return false)
        } else if (!root.isDirectory) return false
        val temporary = File(directory, "marker-${UUID.randomUUID()}")
        FileOutputStream(temporary).use { output ->
            output.write(value.toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
        Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(directory)
        true
        } catch (_: Exception) { false }
    }

    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val CLEAR = "CLEAR"
    }
}
