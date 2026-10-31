package com.seraf.vpnsweep

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.util.AtomicFile
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigImportRecreationTest {
    @Test
    fun cancelledAtomicWriteRestoresPreviousFile(): Unit {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val path = File(context.cacheDir, "import-test-${UUID.randomUUID()}.json")
        val file = AtomicFile(path)
        val previous = "previous".toByteArray()
        try {
            writeImportedConfig(file, previous) {}
            assertThrows(CancellationException::class.java) {
                writeImportedConfig(file, "new".toByteArray()) { throw CancellationException("cancelled") }
            }
            assertArrayEquals(previous, path.readBytes())
            assertTrue(!File(path.path + ".new").exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun oldCompositionCannotOverwriteImportAfterActivityRecreation(): Unit {
        val coordinator = ConfigImportCoordinator()
        val saved = AtomicReference("previous")
        val reading = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val newImportFinished = CountDownLatch(1)
        val scope = AtomicReference<CoroutineScope>()
        val first = AtomicReference<Job>()
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            installScope(scenario, scope)
            scenario.onActivity {
                first.set(scope.get().launch(start = CoroutineStart.UNDISPATCHED) {
                    val token = coordinator.begin()
                    withContext(Dispatchers.IO) {
                        coordinator.complete(token, {
                            reading.countDown()
                            await(releaseRead)
                            "old"
                        }, {}, { value, check -> check(); saved.set(value) })
                    }
                })
            }
            await(reading)
            scenario.recreate()
            installScope(scenario, scope)
            scenario.onActivity {
                scope.get().launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val token = coordinator.begin()
                        withContext(Dispatchers.IO) {
                            coordinator.complete(token, { "new" }, {}, { value, check -> check(); saved.set(value) })
                        }
                    } finally {
                        newImportFinished.countDown()
                    }
                }
            }
            await(newImportFinished)
            releaseRead.countDown()
            runBlocking { withTimeout(10_000) { first.get().join() } }
            assertTrue(first.get().isCancelled)
            assertEquals("new", saved.get())
        } finally {
            releaseRead.countDown()
            scenario.close()
        }
    }

    private fun installScope(scenario: ActivityScenario<ComponentActivity>, scope: AtomicReference<CoroutineScope>): Unit {
        val ready = CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.setContent {
                val currentScope = rememberCoroutineScope()
                SideEffect {
                    scope.set(currentScope)
                    ready.countDown()
                }
            }
        }
        await(ready)
    }

    private fun await(latch: CountDownLatch): Unit {
        assertTrue("Timed out waiting for test barrier", latch.await(10, TimeUnit.SECONDS))
    }
}
