package com.seraf.vpnsweep.importer

import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val MAX_CONFIG_BYTES = 4 * 1024 * 1024

internal fun readImportedConfig(input: InputStream?): ByteArray {
    val bytes = input?.use { it.readNBytes(MAX_CONFIG_BYTES + 1) } ?: error("file_unavailable")
    check(bytes.size <= MAX_CONFIG_BYTES)
    return bytes
}

internal class ConfigImportCoordinator {
    private val mutex = Mutex()
    private var latest: Any? = null

    suspend fun begin(): Any {
        val context = currentCoroutineContext()
        return mutex.withLock {
            context.ensureActive()
            Any().also { latest = it }
        }
    }

    suspend fun <T> complete(
        token: Any,
        read: () -> T,
        validate: (T) -> Unit,
        write: (T, () -> Unit) -> Unit,
    ): Boolean {
        val context = currentCoroutineContext()
        context.ensureActive()
        val value = read()
        context.ensureActive()
        validate(value)
        context.ensureActive()
        return mutex.withLock {
            context.ensureActive()
            if (latest !== token) {
                false
            } else {
                write(value) { context.ensureActive() }
                true
            }
        }
    }
}

internal class ImportUiOwner {
    private var current: Any? = null

    fun begin(): Any = Any().also { current = it }

    fun isCurrent(owner: Any): Boolean = current === owner

    fun finish(owner: Any): Boolean {
        if (!isCurrent(owner)) return false
        current = null
        return true
    }
}

internal val configImportCoordinator = ConfigImportCoordinator()
