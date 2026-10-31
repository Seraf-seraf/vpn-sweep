package com.seraf.vpnsweep.searchworker

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        val decoded = readWorkerOpenFrame(ByteArrayInputStream(bytes), bytes.size.toLong(), bytes.size) { 100 }
        assertEquals(200L, decoded.attemptDeadline)
        assertArrayEquals(byteArrayOf(1, 2), decoded.profile)
        assertArrayEquals(byteArrayOf(3, 4), decoded.parameters)
    }

    @Test
    fun openFrameRejectsInvalidLengthsWithoutAllocatingPayload(): Unit {
        for ((profile, parameters, length) in listOf(
            Triple(0, 1, 17L),
            Triple(8 * 1024 * 1024 + 1, 1, 18L),
            Triple(1, SearchWorkerServiceBounds.MAX_JSON_LEAF_BYTES + 1, 18L),
            Triple(1, 0, 17L),
            Triple(1, 1, 19L),
            Triple(Int.MAX_VALUE, Int.MAX_VALUE, 16L),
        )) {
            val bytes = frame { writeLong(200); writeInt(profile); writeInt(parameters) }
            assertThrows(IOException::class.java) {
                readWorkerOpenFrame(bytes.inputStream(), length, SearchWorkerServiceBounds.MAX_OPEN_FRAME_BYTES) { 100 }
            }
        }

        val bytes = frame { writeLong(200); writeInt(Int.MAX_VALUE); writeInt(1) }
        val input = CountingInputStream(bytes)
        assertThrows(IOException::class.java) {
            readWorkerOpenFrame(input, SearchWorkerServiceBounds.MAX_OPEN_FRAME_BYTES.toLong() + 1,
                SearchWorkerServiceBounds.MAX_OPEN_FRAME_BYTES) { 100 }
        }
        assertEquals(0, input.bytesRead)

        val invalidHeader = CountingInputStream(bytes)
        assertThrows(IOException::class.java) {
            readWorkerOpenFrame(invalidHeader, 18, SearchWorkerServiceBounds.MAX_OPEN_FRAME_BYTES) { 100 }
        }
        assertEquals(SearchWorkerBounds.FRAME_HEADER_BYTES, invalidHeader.bytesRead)

        val invalidParameters = CountingInputStream(frame {
            writeLong(200)
            writeInt(1)
            writeInt(SearchWorkerServiceBounds.MAX_JSON_LEAF_BYTES + 1)
        })
        assertThrows(IOException::class.java) {
            readWorkerOpenFrame(invalidParameters, 18, SearchWorkerServiceBounds.MAX_OPEN_FRAME_BYTES) { 100 }
        }
        assertEquals(SearchWorkerBounds.FRAME_HEADER_BYTES, invalidParameters.bytesRead)
    }

    @Test
    fun openFrameRejectsTruncationAndExpiredDeadline(): Unit {
        val bytes = frame { writeLong(200); writeInt(1); writeInt(1); writeByte(1) }
        assertThrows(IOException::class.java) {
            readWorkerOpenFrame(ByteArrayInputStream(bytes), 18, 18) { 100 }
        }
        val expired = frame { writeLong(100); writeInt(1); writeInt(1); writeByte(1); writeByte(2) }
        assertThrows(IOException::class.java) {
            readWorkerOpenFrame(ByteArrayInputStream(expired), 18, 18) { 100 }
        }
    }

    @Test
    fun openFrameRejectsTrailingBytesAfterDeclaredPayload(): Unit {
        val valid = frame { writeLong(200); writeInt(1); writeInt(1); writeByte(1); writeByte(2) }
        val withTrailingByte = valid + byteArrayOf(3)
        assertThrows(IOException::class.java) {
            readWorkerOpenFrame(ByteArrayInputStream(withTrailingByte), valid.size.toLong(), valid.size) { 100 }
        }
    }

    @Test
    fun callUsesZeroBasedProbeRoundAndOneBasedWorkerRound(): Unit {
        for (probeRound in 0..1) {
            val bytes = frame { writeLong(200); writeInt(probeRound); writeInt(1); writeByte(42) }
            val decoded = readWorkerCallFrame(ByteArrayInputStream(bytes), bytes.size.toLong(), bytes.size, probeRound + 1) { 100 }
            assertEquals(probeRound, decoded.round)
            assertArrayEquals(byteArrayOf(42), decoded.request)
        }
    }

    @Test
    fun callRejectsMismatchedGenerationRoundAndTruncatedInput(): Unit {
        val bytes = frame { writeLong(200); writeInt(0); writeInt(1); writeByte(42) }
        assertThrows(IOException::class.java) {
            readWorkerCallFrame(ByteArrayInputStream(bytes), bytes.size.toLong(), bytes.size, 2) { 100 }
        }
        assertThrows(IOException::class.java) {
            readWorkerCallFrame(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)), bytes.size.toLong(), bytes.size, 1) { 100 }
        }

        val oversizedRequest = CountingInputStream(frame {
            writeLong(200)
            writeInt(0)
            writeInt(SearchWorkerServiceBounds.MAX_JSON_LEAF_BYTES + 1)
        })
        assertThrows(IOException::class.java) {
            readWorkerCallFrame(oversizedRequest, 18, SearchWorkerServiceBounds.MAX_OPEN_FRAME_BYTES, 1) { 100 }
        }
        assertEquals(SearchWorkerBounds.FRAME_HEADER_BYTES, oversizedRequest.bytesRead)
    }

    @Test
    fun callRejectsTrailingBytesAfterDeclaredRequest(): Unit {
        val valid = frame { writeLong(200); writeInt(0); writeInt(1); writeByte(42) }
        val withTrailingByte = valid + byteArrayOf(43)
        assertThrows(IOException::class.java) {
            readWorkerCallFrame(ByteArrayInputStream(withTrailingByte), valid.size.toLong(), valid.size, 1) { 100 }
        }
    }

    @Test
    fun serviceBoundsAreInclusiveAndMatchTheSerializedResultEnvelope(): Unit {
        val bounds = SearchWorkerServiceBounds
        val actualEnvelopeOverhead = "{\"fault\":0,\"result\":".toByteArray(Charsets.UTF_8).size + 1
        assertEquals(16, bounds.FRAME_HEADER_BYTES)
        assertEquals(8 * 1024 * 1024, bounds.MAX_JSON_LEAF_BYTES)
        assertEquals(bounds.FRAME_HEADER_BYTES + bounds.MAX_JSON_LEAF_BYTES * 2, bounds.MAX_OPEN_FRAME_BYTES)
        assertEquals(bounds.FRAME_HEADER_BYTES + bounds.MAX_JSON_LEAF_BYTES, bounds.MAX_CALL_FRAME_BYTES)
        assertEquals(actualEnvelopeOverhead, bounds.RESULT_ENVELOPE_OVERHEAD_BYTES)
        assertEquals(bounds.MAX_RAW_REPLY_BYTES + actualEnvelopeOverhead, bounds.MAX_REPLY_BYTES)

        assertTrue(bounds.supports(bounds.MAX_OPEN_FRAME_BYTES, bounds.MAX_REPLY_BYTES))
        assertFalse(bounds.supports(bounds.MAX_OPEN_FRAME_BYTES + 1, bounds.MAX_REPLY_BYTES))
        assertFalse(bounds.supports(bounds.MAX_OPEN_FRAME_BYTES, bounds.MAX_REPLY_BYTES + 1))
        assertFalse(bounds.supports(bounds.MAX_OPEN_FRAME_BYTES, bounds.MIN_REPLY_BYTES - 1))

        assertTrue(bounds.jsonLeafFits(1))
        assertTrue(bounds.jsonLeafFits(bounds.MAX_JSON_LEAF_BYTES - 1))
        assertTrue(bounds.jsonLeafFits(bounds.MAX_JSON_LEAF_BYTES))
        assertFalse(bounds.jsonLeafFits(bounds.MAX_JSON_LEAF_BYTES + 1))
        assertTrue(bounds.openFrameFits(bounds.MAX_OPEN_FRAME_BYTES.toLong(), bounds.MAX_OPEN_FRAME_BYTES))
        assertFalse(bounds.openFrameFits(bounds.MAX_OPEN_FRAME_BYTES.toLong() + 1, bounds.MAX_OPEN_FRAME_BYTES))
        assertTrue(bounds.callFrameFits(bounds.MAX_CALL_FRAME_BYTES.toLong(), bounds.MAX_OPEN_FRAME_BYTES))
        assertFalse(bounds.callFrameFits(bounds.MAX_CALL_FRAME_BYTES.toLong() + 1, bounds.MAX_OPEN_FRAME_BYTES))
        assertTrue(bounds.rawReplyFits(1, bounds.MIN_REPLY_BYTES))
        assertTrue(bounds.rawReplyFits(bounds.MAX_RAW_REPLY_BYTES.toLong(), bounds.MAX_REPLY_BYTES))
        assertFalse(bounds.rawReplyFits(bounds.MAX_RAW_REPLY_BYTES.toLong() + 1, bounds.MAX_REPLY_BYTES))
        assertFalse(bounds.rawReplyFits(bounds.MAX_RAW_REPLY_BYTES.toLong(), bounds.MAX_REPLY_BYTES - 1))
    }

    @Test
    fun descriptorMustBeRegularExactAndWithinTheProvenFrameBound(): Unit {
        assertTrue(validWorkerFrameDescriptor(true, 18, 18, 18))
        assertFalse(validWorkerFrameDescriptor(false, 18, 18, 18))
        assertFalse(validWorkerFrameDescriptor(true, 17, 18, 18))
        assertFalse(validWorkerFrameDescriptor(true, 19, 19, 18))
        assertFalse(validWorkerFrameDescriptor(true, -1, -1, 18))
    }

    @Test
    fun expiryDuringReadCannotReturnFrame(): Unit {
        val bytes = frame { writeLong(200); writeInt(0); writeInt(1); writeByte(42) }
        var current = 100L
        assertThrows(IOException::class.java) {
            readWorkerCallFrame(ByteArrayInputStream(bytes), bytes.size.toLong(), bytes.size, 1) {
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
    fun provenBoundsAreExplicitInclusiveAndEnforcedBeforePayloadAllocation(): Unit {
        assertEquals(16, SearchWorkerBounds.FRAME_HEADER_BYTES)
        assertEquals(8 * 1024 * 1024, SearchWorkerBounds.MAX_PROFILE_BYTES)
        assertEquals(1, SearchWorkerBounds.MIN_REPLY_BYTES)
        assertTrue(SearchWorkerBounds.supports(16, 1))
        assertFalse(SearchWorkerBounds.supports(null, 1))
        assertFalse(SearchWorkerBounds.supports(16, null))
        assertFalse(SearchWorkerBounds.supports(15, 1))
        assertFalse(SearchWorkerBounds.supports(16, 0))
        assertTrue(SearchWorkerBounds.frameFits(16, 16))
        assertTrue(SearchWorkerBounds.frameFits(18, 18))
        assertFalse(SearchWorkerBounds.frameFits(19, 18))
        assertTrue(SearchWorkerBounds.replyFits(1, 1))
        assertFalse(SearchWorkerBounds.replyFits(2, 1))

        val open = frame { writeLong(200); writeInt(1); writeInt(1); writeByte(1); writeByte(2) }
        assertThrows(IOException::class.java) {
            readWorkerOpenFrame(ByteArrayInputStream(open), open.size.toLong(), open.size - 1) { 100 }
        }
        assertEquals(200L, readWorkerOpenFrame(ByteArrayInputStream(open), open.size.toLong(), open.size) { 100 }.attemptDeadline)

        val call = frame { writeLong(200); writeInt(0); writeInt(1); writeByte(42) }
        assertThrows(IOException::class.java) {
            readWorkerCallFrame(ByteArrayInputStream(call), call.size.toLong(), call.size - 1, 1) { 100 }
        }
        assertEquals(42, readWorkerCallFrame(ByteArrayInputStream(call), call.size.toLong(), call.size, 1) { 100 }.request.single().toInt())
    }

    @Test
    fun socketPolicyBindsXrayDialsAndKeepsLocalRolesOnLoopback(): Unit {
        val bindings = mutableListOf<Int>()
        var currentChecks = 0
        assertTrue(authorizeWorkerSocket(
            fd = 71,
            role = 1,
            network = "tcp",
            address = "198.51.100.2:443",
            isCurrent = { currentChecks++; true },
            isLoopbackEndpoint = { false },
            bindSelectedNetwork = { bindings.add(it) },
        ))
        assertEquals(listOf(71), bindings)
        assertEquals(2, currentChecks)

        currentChecks = 0
        assertFalse(authorizeWorkerSocket(
            fd = 73,
            role = 1,
            network = "udp6",
            address = "2001:db8::1:53",
            isCurrent = { ++currentChecks == 1 },
            isLoopbackEndpoint = { false },
            bindSelectedNetwork = { bindings.add(it) },
        ))
        assertEquals(listOf(71, 73), bindings)
        assertEquals(2, currentChecks)

        val localRoles = listOf(2L, 4L, 5L, 6L)
        for (role in localRoles) {
            assertTrue(authorizeWorkerSocket(
                fd = 72,
                role = role,
                network = "tcp",
                address = "127.0.0.1:1080",
                isCurrent = { true },
                isLoopbackEndpoint = { it.startsWith("127.") },
                bindSelectedNetwork = { error("local socket must not be network-bound") },
            ))
            assertFalse(authorizeWorkerSocket(
                fd = 72,
                role = role,
                network = "tcp",
                address = "198.51.100.2:1080",
                isCurrent = { true },
                isLoopbackEndpoint = { it.startsWith("127.") },
                bindSelectedNetwork = { error("denied socket must not be network-bound") },
            ))
        }
        assertFalse(authorizeWorkerSocket(72, 3, "udp", "127.0.0.1:53", { true }, { true }, { error("DNS uses QueryDNS") }))
        assertFalse(authorizeWorkerSocket(72, 1, "icmp", "198.51.100.2", { true }, { false }) { bindings.add(it) })
        assertFalse(authorizeWorkerSocket(72, 1, "udp", "198.51.100.2:53", { false }, { false }) { bindings.add(it) })
    }

    @Test
    fun socketPolicyRejectsBadFdAndBindFailure(): Unit {
        var binds = 0
        assertFalse(authorizeWorkerSocket(-1, 1, "tcp", "198.51.100.2:443", { true }, { false }) { binds++ })
        assertFalse(authorizeWorkerSocket(Int.MAX_VALUE.toLong() + 1, 1, "tcp", "198.51.100.2:443", { true }, { false }) { binds++ })
        assertFalse(authorizeWorkerSocket(71, 1, "tcp", "198.51.100.2:443", { true }, { false }) { throw IOException("bind failed") })
        assertEquals(0, binds)
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

    private class CountingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var bytesRead: Int = 0
            private set

        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
    }
}
