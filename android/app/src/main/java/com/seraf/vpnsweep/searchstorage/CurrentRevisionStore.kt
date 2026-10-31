package com.seraf.vpnsweep.searchstorage

import java.io.IOException

internal interface RevisionStorage {
    fun read(): ResultRevision?
    fun write(revision: ResultRevision): Unit
}

internal class CurrentRevisionStore(private val storage: RevisionStorage) {
    private var uncertain = false

    @Synchronized
    fun load(): ResultRevision? {
        if (uncertain) return null
        return try {
            storage.read()?.takeIf { it.source.isNotEmpty() && it.selection.isNotEmpty() }
        } catch (_: IOException) {
            null
        }
    }

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
