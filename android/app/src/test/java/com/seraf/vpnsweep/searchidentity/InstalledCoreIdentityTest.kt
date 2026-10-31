package com.seraf.vpnsweep.searchidentity

import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstalledCoreIdentityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun acceptsOnlyTheDefiningLoaderWithoutInitializingTheClass(): Unit {
        LoaderProbeState.initialized = false
        val probe = LoaderProbe::class.java
        val definingLoader = requireNotNull(probe.classLoader)
        val delegatingLoader = object : ClassLoader(definingLoader) {}
        assertTrue(classIsDefinedBy(definingLoader, probe.name))
        assertFalse(classIsDefinedBy(delegatingLoader, probe.name))
        assertFalse(classIsDefinedBy(definingLoader, "synthetic.missing.LoaderProbe"))
        assertFalse(LoaderProbeState.initialized)
    }

    @Test
    fun hashesExactStoredEntryInAnInstalledApk(): Unit {
        val apk = apk("base.apk", stored = true, bytes = LIBRARY_BYTES)
        assertEquals(EXPECTED_SHA256, sha256ResolvedCoreLibrary("${apk.absolutePath}!/$APK_ENTRY", listOf(apk), null))
    }

    @Test
    fun rejectsUnknownApkEntryAndCompressedOrEmptyLibrary(): Unit {
        val installed = apk("installed.apk", stored = true, bytes = LIBRARY_BYTES)
        val foreign = apk("foreign.apk", stored = true, bytes = LIBRARY_BYTES)
        val compressed = apk("compressed.apk", stored = false, bytes = LIBRARY_BYTES)
        val empty = apk("empty.apk", stored = true, bytes = ByteArray(0))
        assertNull(sha256ResolvedCoreLibrary("${foreign.absolutePath}!/$APK_ENTRY", listOf(installed), null))
        assertNull(sha256ResolvedCoreLibrary("${installed.absolutePath}!/lib/other/libgojni.so", listOf(installed), null))
        assertNull(sha256ResolvedCoreLibrary("${compressed.absolutePath}!/$APK_ENTRY", listOf(compressed), null))
        assertNull(sha256ResolvedCoreLibrary("${empty.absolutePath}!/$APK_ENTRY", listOf(empty), null))
    }

    @Test
    fun hashesOnlyTheResolvedFileInsideTheInstalledNativeDirectory(): Unit {
        val directory = folder.newFolder("native")
        val library = File(directory, "libgojni.so").apply { writeBytes(LIBRARY_BYTES) }
        assertEquals(EXPECTED_SHA256, sha256ResolvedCoreLibrary(library.absolutePath, emptyList(), directory))
        assertNull(sha256ResolvedCoreLibrary(library.absolutePath, emptyList(), folder.newFolder("other")))
        assertNull(sha256ResolvedCoreLibrary(File(directory, "missing.so").absolutePath, emptyList(), directory))
        assertNull(sha256ResolvedCoreLibrary("libgojni.so", emptyList(), directory))
    }

    @Test
    fun rejectsSymlinkAndEmptyNativeFile(): Unit {
        val directory = folder.newFolder("native-links")
        val target = folder.newFile("target").apply { writeBytes(LIBRARY_BYTES) }
        val link = File(directory, "libgojni.so")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertNull(sha256ResolvedCoreLibrary(link.absolutePath, emptyList(), directory))
        val emptyDirectory = folder.newFolder("native-empty")
        val empty = File(emptyDirectory, "libgojni.so").apply { createNewFile() }
        assertNull(sha256ResolvedCoreLibrary(empty.absolutePath, emptyList(), emptyDirectory))
    }

    private fun apk(name: String, stored: Boolean, bytes: ByteArray): File {
        val file = folder.newFile(name)
        ZipOutputStream(file.outputStream()).use { output ->
            val entry = ZipEntry(APK_ENTRY)
            if (stored) {
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = entry.size
                entry.crc = CRC32().apply { update(bytes) }.value
            }
            output.putNextEntry(entry)
            output.write(bytes)
            output.closeEntry()
        }
        return file
    }
}

private const val APK_ENTRY = "lib/arm64-v8a/libgojni.so"
private val LIBRARY_BYTES = "synthetic-native-library".toByteArray()
private const val EXPECTED_SHA256 = "dfaacd1e61ed5381dec722559694f8ade506b949613f3f8eb0c631d48b744d16"

private object LoaderProbeState { var initialized: Boolean = false }
private class LoaderProbe {
    companion object { init { LoaderProbeState.initialized = true } }
}
