package com.seraf.vpnsweep.searchstorage

import java.io.IOException

internal interface RevisionStorage {
    fun read(): ResultRevision?
    fun write(revision: ResultRevision): Unit
}

internal sealed interface CurrentRevision {
    data object Absent : CurrentRevision
    data object Unknown : CurrentRevision
    data class Present(val revision: ResultRevision) : CurrentRevision
}

internal class CurrentRevisionStore(private val storage: RevisionStorage) {
    private var uncertain = false

    @Synchronized
    fun markUncertain(): Unit { uncertain = true }

    @Synchronized
    fun readCurrent(): CurrentRevision {
        if (uncertain) return CurrentRevision.Unknown
        return try {
            val revision = storage.read() ?: return CurrentRevision.Absent
            if (revision.source.isEmpty() || revision.selection.isEmpty()) CurrentRevision.Unknown
            else CurrentRevision.Present(revision)
        } catch (_: Exception) { CurrentRevision.Unknown }
        catch (_: LinkageError) { CurrentRevision.Unknown }
    }

    @Synchronized
    fun load(): ResultRevision? = (readCurrent() as? CurrentRevision.Present)?.revision

    @Synchronized
    fun update(revision: ResultRevision): Boolean {
        if (uncertain || revision.source.isEmpty() || revision.selection.isEmpty()) return false
        return try {
            storage.write(revision)
            true
        } catch (_: IOException) {
            uncertain = true
            false
        }
    }
}
