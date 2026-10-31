package com.seraf.vpnsweep.searchidentity

import android.content.Context
import dalvik.system.BaseDexClassLoader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private const val CORE_LIBRARY_NAME = "libgojni.so"
private const val CORE_APK_ENTRY = "lib/arm64-v8a/libgojni.so"
private const val GO_SEQ_CLASS = "go.Seq"
private const val READ_BUFFER_BYTES = 64 * 1024

// Call only after acquiring the native owner lease. The class lookup does not initialize go.Seq.
internal fun installedCoreSha256(context: Context): String? {
    return try {
        val loader = context.classLoader as? BaseDexClassLoader ?: return null
        if (!classIsDefinedBy(loader, GO_SEQ_CLASS)) return null
        val resolved = loader.findLibrary("gojni") ?: return null
        val info = context.applicationInfo ?: return null
        val packages = mutableListOf<File>()
        info.sourceDir?.let { packages.add(File(it)) }
        info.splitSourceDirs?.forEach { packages.add(File(it)) }
        sha256ResolvedCoreLibrary(resolved, packages, info.nativeLibraryDir?.let(::File))
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }
}

internal fun classIsDefinedBy(loader: ClassLoader, className: String): Boolean = try {
    Class.forName(className, false, loader).classLoader === loader
} catch (_: Exception) {
    false
} catch (_: LinkageError) {
    false
}

internal fun sha256ResolvedCoreLibrary(resolved: String, packages: List<File>, nativeLibraryDir: File?): String? {
    return try {
        val separator = resolved.indexOf("!/")
        if (separator >= 0) {
            val archive = File(resolved.substring(0, separator))
            val entryName = resolved.substring(separator + 2)
            if (!archive.isAbsolute || entryName != CORE_APK_ENTRY ||
                packages.none { it.isAbsolute && it.canonicalFile == archive.canonicalFile }
            ) return null
            ZipFile(archive).use { zip ->
                var selected: ZipEntry? = null
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name == CORE_APK_ENTRY) {
                        if (selected != null) return null
                        selected = entry
                    }
                }
                val entry = selected ?: return null
                if (entry.isDirectory || entry.method != ZipEntry.STORED || entry.size <= 0) return null
                zip.getInputStream(entry).use { digestStream(it, entry.size) }
            }
        } else {
            val file = File(resolved)
            val directory = nativeLibraryDir ?: return null
            if (!file.isAbsolute || !directory.isAbsolute || resolved.contains('!') || file.name != CORE_LIBRARY_NAME ||
                Files.isSymbolicLink(file.toPath()) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                file.canonicalFile != File(directory.canonicalFile, CORE_LIBRARY_NAME)
            ) return null
            FileInputStream(file).use { input ->
                val size = input.channel.size()
                if (size <= 0) return null
                val digest = digestStream(input, size)
                if (input.channel.size() != size) null else digest
            }
        }
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }
}

private fun digestStream(input: InputStream, expectedBytes: Long): String? {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0 || total > expectedBytes - count) return null
        digest.update(buffer, 0, count)
        total += count
    }
    if (total != expectedBytes) return null
    val digits = "0123456789abcdef"
    val bytes = digest.digest()
    val output = CharArray(bytes.size * 2)
    for (index in bytes.indices) {
        val value = bytes[index].toInt() and 0xff
        output[index * 2] = digits[value ushr 4]
        output[index * 2 + 1] = digits[value and 0x0f]
    }
    return String(output)
}
