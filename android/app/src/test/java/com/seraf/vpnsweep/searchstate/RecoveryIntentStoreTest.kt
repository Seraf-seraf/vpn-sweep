package com.seraf.vpnsweep.searchstate

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryIntentStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val stamp = RecoveryStamp("11111111-1111-4111-8111-111111111111")

    @Test fun durableCreateAndRemoveRoundTrip(): Unit {
        val root = File(temporary.root, "fence")
        val synced = mutableListOf<File>()
        val store = RecoveryIntentStore(root) { synced.add(it) }
        assertEquals(RecoveryIntentState.Absent, store.read())
        assertTrue(store.create(stamp))
        assertEquals(RecoveryIntentState.Armed(stamp), store.read())
        assertEquals(temporary.root, synced.first())
        assertTrue(synced.count { it == root } >= 3)
        assertFalse(store.create(stamp))
        assertTrue(store.finish())
        assertEquals(RecoveryIntentState.Done, store.read())
    }

    @Test fun incompleteAndCorruptIntentRemainUnknown(): Unit {
        val root = File(temporary.root, "fence")
        var calls = 0
        val interrupted = RecoveryIntentStore(root) {
            calls++
            if (calls == 2) throw IOException("synthetic")
        }
        assertFalse(interrupted.create(stamp))
        val store = RecoveryIntentStore(root) { }
        assertEquals(RecoveryIntentState.Unknown, store.read())
        File(root, "intent").writeText("malformed")
        assertEquals(RecoveryIntentState.Unknown, store.read())
        File(root, "intent").writeBytes(ByteArray(257))
        assertEquals(RecoveryIntentState.Unknown, store.read())
    }

    @Test fun unexpectedPathKindsAreUnknown(): Unit {
        val root = File(temporary.root, "fence")
        root.writeText("not a directory")
        assertEquals(RecoveryIntentState.Unknown, RecoveryIntentStore(root) { }.read())
        assertTrue(root.delete())
        assertTrue(root.mkdir())
        Files.createSymbolicLink(File(root, "intent").toPath(), File(root, "missing").toPath())
        assertEquals(RecoveryIntentState.Unknown, RecoveryIntentStore(root) { }.read())
    }

    @Test fun failedDoneSyncRequiresNewProcessToSyncAndValidateWitness(): Unit {
        val root = File(temporary.root, "fence")
        var fail = false
        val store = RecoveryIntentStore(root) { if (fail) throw IOException("synthetic") }
        assertTrue(store.create(stamp))
        fail = true
        assertFalse(store.finish())
        assertTrue(File(root, "intent").isFile)
        val failedRestart = RecoveryIntentStore(root) { throw IOException("synthetic") }
        assertEquals(RecoveryIntentState.Unknown, failedRestart.read())
        val confirmedRestart = RecoveryIntentStore(root) { }
        assertEquals(RecoveryIntentState.Done, confirmedRestart.read())
        val next = RecoveryStamp("22222222-2222-4222-8222-222222222222")
        assertTrue(confirmedRestart.create(next))
        assertEquals(RecoveryIntentState.Armed(next), RecoveryIntentStore(root) { }.read())
    }

    @Test fun partialRearmAndMissingIntentRemainBlockedAfterRestart(): Unit {
        val root = File(temporary.root, "fence")
        var fail = false
        val store = RecoveryIntentStore(root) { if (fail) throw IOException("synthetic") }
        assertTrue(store.create(stamp))
        assertTrue(store.finish())
        fail = true
        assertFalse(store.create(RecoveryStamp("22222222-2222-4222-8222-222222222222")))
        assertEquals(RecoveryIntentState.Unknown, RecoveryIntentStore(root) { }.read())
        val empty = temporary.newFolder("empty-fence")
        assertEquals(RecoveryIntentState.Unknown, RecoveryIntentStore(empty) { }.read())
    }

    @Test fun orphanedTemporaryRecordCannotOverrideArmedIntent(): Unit {
        val root = File(temporary.root, "fence")
        val store = RecoveryIntentStore(root) { }
        assertTrue(store.create(stamp))
        File(root, "pending-orphan").writeText("incomplete")
        assertEquals(RecoveryIntentState.Armed(stamp), RecoveryIntentStore(root) { }.read())
    }

    @Test fun kernelIdParsingIsStrictAndBounded(): Unit {
        assertEquals(stamp, parseKernelBootId((stamp.bootId + "\n").toByteArray(Charsets.US_ASCII)))
        for (text in listOf("", stamp.bootId, "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA\n", stamp.bootId + "\nextra", "00000000-0000-0000-0000-000000000000\n")) {
            assertNull(parseKernelBootId(text.toByteArray(Charsets.US_ASCII)))
        }
    }
}
