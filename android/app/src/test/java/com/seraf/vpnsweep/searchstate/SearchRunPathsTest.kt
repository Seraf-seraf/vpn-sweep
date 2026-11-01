package com.seraf.vpnsweep.searchstate

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchRunPathsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun repeatedGenerationAndCollisionUseFreshPersistentDirectories(): Unit {
        val ids = ArrayDeque(listOf(
            "11111111-1111-4111-8111-111111111111",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
        ))
        val root = File(temporary.root, "runs")
        val first = SearchRunPathAllocator(root, {}, { ids.removeFirst() }).reserve()
        val identity = File(first.state.parentFile, "candidate-identity.key")
        identity.writeText("synthetic-existing-identity")
        File(first.state, "last-good").writeText("synthetic")
        val second = SearchRunPathAllocator(root, {}, { ids.removeFirst() }).reserve()
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.staging, second.staging)
        assertTrue(first.state.isDirectory)
        assertTrue(second.state.isDirectory)
        assertTrue(second.staging.isDirectory)
        assertEquals(File(temporary.root, "search-state"), second.state.parentFile)
        assertEquals(File(temporary.root, "search-staging"), second.staging.parentFile)
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(second.state.toPath()))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(second.staging.toPath()))
        assertEquals("synthetic-existing-identity", identity.readText())
        assertEquals("synthetic", File(first.state, "last-good").readText())
    }

    @Test fun preexistingStagingLeafIsNeverReusedOrRemoved(): Unit {
        val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        val stagingRoot = File(temporary.root, "search-staging")
        Files.createDirectory(stagingRoot.toPath(), permissions)
        val collision = File(stagingRoot, "11111111-1111-4111-8111-111111111111")
        Files.createDirectory(collision.toPath(), permissions)
        File(collision, "last-good").writeText("synthetic")
        val ids = ArrayDeque(listOf("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"))
        val paths = SearchRunPathAllocator(File(temporary.root, "runs"), {}, { ids.removeFirst() }).reserve()
        assertNotEquals(collision, paths.staging)
        assertEquals("synthetic", File(collision, "last-good").readText())
        assertTrue(paths.state.isDirectory)
        assertTrue(paths.staging.isDirectory)
    }

    @Test fun existingParentEntriesAreSyncedBeforeAnyNewReservation(): Unit {
        val root = File(temporary.root, "runs")
        val first = SearchRunPathAllocator(root, {}, { "11111111-1111-4111-8111-111111111111" }).reserve()
        val key = File(first.state.parentFile, "candidate-identity.key")
        key.writeText("synthetic-existing-identity")
        var generated = false
        var failed = false
        try {
            SearchRunPathAllocator(root, { if (it == temporary.root) throw java.io.IOException("synthetic") }, {
                generated = true
                "22222222-2222-4222-8222-222222222222"
            }).reserve()
        } catch (_: java.io.IOException) { failed = true }
        assertTrue(failed)
        assertFalse(generated)
        val synced = mutableListOf<File>()
        val next = SearchRunPathAllocator(root, { synced.add(it) }, { "22222222-2222-4222-8222-222222222222" }).reserve()
        assertTrue(synced.count { it == temporary.root } >= 3)
        assertEquals(first.state.parentFile, next.state.parentFile)
        assertEquals("synthetic-existing-identity", key.readText())
    }
}
