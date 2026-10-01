package com.yunyin.music.data

import com.yunyin.music.data.together.TogetherCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room-code length is a property of the **transport**, not a global constant.
 *
 * The two kinds genuinely differ: `server/together-relay.js` allocates codes server-side and owns the
 * registry, so six characters are plenty; a broker-based room has no registry, so the code doubles as a
 * topic name and must be long enough that a stranger cannot find it.
 *
 * Hardcoding one length broke joining outright on the other transport — a six-character relay code could
 * never satisfy a twelve-character rule, so the join button stayed disabled with no explanation. The
 * numbers are pinned here because the client and the relay have to agree on them.
 */
class TogetherCodeLengthTest {

    @Test
    fun `the relay's codes are six characters`() {
        // Kept in step with CODE_LENGTH in server/together-relay.js.
        assertEquals(6, RELAY_CODE_LENGTH)
    }

    @Test
    fun `broker codes are twelve, because there the code is also a topic name`() {
        assertEquals(12, TogetherCode.LENGTH)
    }

    @Test
    fun `a six character code is complete for the relay`() {
        assertTrue(TogetherCode.isComplete("CWJ96W", length = RELAY_CODE_LENGTH))
    }

    @Test
    fun `the same six character code is NOT complete for a broker transport`() {
        // The exact mismatch that made joining impossible: the rule has to come from the transport.
        assertFalse(TogetherCode.isComplete("CWJ96W", length = TogetherCode.LENGTH))
    }

    @Test
    fun `a twelve character code is complete for a broker transport`() {
        assertTrue(TogetherCode.isComplete("ABCD2345WXYZ", length = TogetherCode.LENGTH))
    }

    @Test
    fun `typing is capped at the transport's length`() {
        val typed = "abcdefghijklmnop"
        assertEquals("ABCDEF", TogetherCode.normalise(typed, length = RELAY_CODE_LENGTH))
        assertEquals("ABCDEFGHIJKL", TogetherCode.normalise(typed, length = TogetherCode.LENGTH))
    }

    @Test
    fun `separators do not count towards completeness`() {
        assertTrue(TogetherCode.isComplete("CWJ-96W", length = RELAY_CODE_LENGTH))
        assertTrue(TogetherCode.isComplete("ABCD-2345-WXYZ", length = TogetherCode.LENGTH))
    }

    private companion object {
        /** Mirrors TogetherTransport.RELAY_CODE_LENGTH, which is private to that file. */
        const val RELAY_CODE_LENGTH = 6
    }
}
