package com.seraf.vpnsweep

import android.util.AtomicFile

internal fun writeImportedConfig(file: AtomicFile, bytes: ByteArray, checkBeforeCommit: () -> Unit): Unit {
    val stream = file.startWrite()
    try {
        stream.write(bytes)
        checkBeforeCommit()
        file.finishWrite(stream)
    } catch (exception: Exception) {
        file.failWrite(stream)
        throw exception
    }
}
