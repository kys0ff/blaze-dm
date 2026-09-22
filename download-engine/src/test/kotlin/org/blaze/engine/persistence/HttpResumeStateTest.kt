package org.blaze.engine.persistence

import kotlinx.serialization.json.Json
import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpResumeStateTest {

    @Test
    fun `completed chunks survive a round trip`() {
        val bits = BitSet().apply {
            set(0); set(1); set(7); set(33); set(63)
        }
        val restored = HttpResumeState.of(1L shl 40, 8L shl 20, validator = "\"abc\"", completed = bits)
            .toBitSet()

        for (index in 0 until 64) {
            assertEquals(bits.get(index), restored.get(index), "chunk $index changed across serialization")
        }
    }

    @Test
    fun `the sidecar stays tiny no matter how large the file is`() {
        // A 20 000-chunk plan must not turn the resume file into a list of indices: one bit per
        // chunk is what keeps a crash-restart cheap.
        val bits = BitSet()
        for (index in 0 until 20_000 step 3) bits.set(index)

        val encoded = HttpResumeState.of(1L shl 40, 1L shl 20, null, bits)

        assertTrue(encoded.completedChunks.length < 8 * 1024, "state was ${encoded.completedChunks.length} chars")
        assertEquals(bits.cardinality(), encoded.toBitSet().cardinality())
    }

    @Test
    fun `an empty plan encodes to nothing`() {
        assertEquals("", HttpResumeState.of(1L shl 40, 1L shl 20, null, BitSet()).completedChunks)
        assertEquals(0, HttpResumeState(1L, 1L, null, "").toBitSet().cardinality())
    }

    @Test
    fun `the validator survives serialization so a swapped file can be detected`() {
        val json = Json.encodeToString(HttpResumeState.serializer(), HttpResumeState(40L, 10L, "tag", "CQ=="))
        val decoded = Json.decodeFromString<HttpResumeState>(json)

        assertEquals(40L, decoded.totalBytes)
        assertEquals(10L, decoded.chunkSize)
        assertEquals("tag", decoded.validator)
        assertEquals(listOf(0, 3), decoded.toBitSet().stream().boxed().toList())
    }
}
