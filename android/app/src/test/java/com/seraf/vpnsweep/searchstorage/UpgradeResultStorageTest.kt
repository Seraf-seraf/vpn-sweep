package com.seraf.vpnsweep.searchstorage

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpgradeResultStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun corruptMissingOrReusedPriorResultNeverPassesUpgradeCheck(): Unit {
        val root = File(temporary.root, "results")
        val storage = PrivateResultStorage(root) { }
        val revision = ResultRevision("1", "2")
        val bytes = "synthetic".toByteArray()
        val checksum = digest(bytes.inputStream())
        val generation = storage.prepare(revision, checksum, bytes.inputStream())
        storage.publish(ResultManifest(generation, revision, checksum))
        assertFalse(storage.safeForUpgrade(revision))
        assertTrue(storage.safeForUpgrade(ResultRevision("1", "3")))
        val payload = File(root, "$generation/result")
        payload.writeText("changed")
        assertFalse(storage.safeForUpgrade(ResultRevision("1", "3")))
        assertTrue(payload.delete())
        assertFalse(storage.safeForUpgrade(ResultRevision("1", "3")))
    }
}
