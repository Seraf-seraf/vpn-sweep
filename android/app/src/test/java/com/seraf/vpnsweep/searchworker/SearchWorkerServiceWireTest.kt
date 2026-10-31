package com.seraf.vpnsweep.searchworker

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal class SearchWorkerServiceWireTest {
    @Test
    fun openFrameReadsExactPrivatePayload(): Unit {
        val bytes = frame {
            writeLong(200)
            writeInt(2)
            writeInt(2)
            write(byteArrayOf(1, 2))
            write(byteArrayOf(3, 4))
        }
        val decoded = readWorkerOpenFrame(ByteArrayInputStream(bytes), bytes.size.toLong()) { 100 }
        assertEquals(200L, decoded.attemptDeadline)
        assertArrayEquals(byteArrayOf(1, 2), decoded.profile)
        assertArrayEquals(byteArrayOf(3, 4), decoded.parameters)
    }

    @Test
    fun openFrameRejectsInvalidLengthsWithoutAllocatingPayload(): Unit {
        for ((profile, parameters, length) in listOf(
            Triple(0, 1, 17L),
            Triple(8 * 1024 * 1024 + 1, 1, 18L),
            Triple(1, 0, 17L),
            Triple(1, 1, 19L),
            Triple(Int.MAX_VALUE, Int.MAX_VALUE, 16L),
        )) {
            val bytes = frame { writeLong(200); writeInt(profile); writeInt(parameters) }
            assertThrows(IOException::class.java) {
                readWorkerOpenFrame(ByteArrayInputStream(bytes), length) { 100 }
            }
        }
    }

    @Test
    fun openFrameRejectsTruncationAndExpiredDeadline(): Unit {
        val bytes = frame { writeLong(200); writeInt(1); writeInt(1); writeByte(1) }
        assertThrows(IOException::class.java) {
            readWorkerOpenFrame(ByteArrayInputStream(bytes), 18) { 100 }
        }
        val expired = frame { writeLong(100); writeInt(1); writeInt(1); writeByte(1); writeByte(2) }
        assertThrows(IOException::class.java) {
            readWorkerOpenFrame(ByteArrayInputStream(expired), 18) { 100 }
        }
    }

    @Test
    fun callUsesZeroBasedProbeRoundAndOneBasedWorkerRound(): Unit {
        for (probeRound in 0..1) {
            val bytes = frame { writeLong(200); writeInt(probeRound); writeInt(1); writeByte(42) }
            val decoded = readWorkerCallFrame(ByteArrayInputStream(bytes), bytes.size.toLong(), probeRound + 1) { 100 }
            assertEquals(probeRound, decoded.round)
            assertArrayEquals(byteArrayOf(42), decoded.request)
        }
    }

    @Test
    fun callRejectsMismatchedGenerationRoundAndTruncatedInput(): Unit {
        val bytes = frame { writeLong(200); writeInt(0); writeInt(1); writeByte(42) }
        assertThrows(IOException::class.java) {
            readWorkerCallFrame(ByteArrayInputStream(bytes), bytes.size.toLong(), 2) { 100 }
        }
        assertThrows(IOException::class.java) {
            readWorkerCallFrame(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)), bytes.size.toLong(), 1) { 100 }
        }
    }

    @Test
    fun expiryDuringReadCannotReturnFrame(): Unit {
        val bytes = frame { writeLong(200); writeInt(0); writeInt(1); writeByte(42) }
        var current = 100L
        assertThrows(IOException::class.java) {
            readWorkerCallFrame(ByteArrayInputStream(bytes), bytes.size.toLong(), 1) {
                current.also { current = 200 }
            }
        }
    }

    @Test
    fun expiredDuringProbeAcquisitionDoesNotDispatchCall(): Unit {
        var now = 100L
        var calls = 0
        assertThrows(IOException::class.java) {
            callWithWorkerDeadline(150, { now }, { now = 151; Unit }) { _, _ -> calls++ }
        }
        assertEquals(0, calls)
    }

    @Test
    fun callUsesOnlyBudgetRemainingAfterProbeAcquisition(): Unit {
        var now = 100L
        val budget = callWithWorkerDeadline(150, { now }, { now = 149; Unit }) { _, remaining -> remaining }
        assertEquals(1L, budget)
    }

    @Test
    fun exactDeadlineDuringProbeAcquisitionDoesNotDispatchCall(): Unit {
        var now = 100L
        var calls = 0
        assertThrows(IOException::class.java) {
            callWithWorkerDeadline(150, { now }, { now = 150; Unit }) { _, _ -> calls++ }
        }
        assertEquals(0, calls)
    }

    private fun frame(write: DataOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream -> write(stream) }
        return output.toByteArray()
    }
}
