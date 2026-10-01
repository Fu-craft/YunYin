package com.yunyin.music.data

import com.yunyin.music.data.together.TogetherCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Room codes and the topic they map to.
 *
 * These look trivial and are not: the failure mode of a room code is two people reading digits to each
 * other over a phone call, so an alphabet containing `0` beside `O` costs a support conversation every
 * time. The same goes for the topic mapping — two different codes must never land on one topic, or two
 * unrelated rooms would hear each other.
 */
class TogetherCodeTest {

    @Test
    fun `generated codes are the expected length`() {
        repeat(50) {
            assertEquals(TogetherCode.LENGTH, TogetherCode.fresh().length)
        }
    }

    @Test
    fun `generated codes avoid the characters people misread`() {
        // The whole reason for the alphabet: 0/O and 1/I/L are indistinguishable when spoken.
        val ambiguous = setOf('0', 'O', '1', 'I', 'L')
        repeat(200) {
            val code = TogetherCode.fresh()
            assertTrue("code $code contains an ambiguous character", code.none { it in ambiguous })
        }
    }

    @Test
    fun `generated codes only use the declared alphabet`() {
        repeat(100) {
            assertTrue(TogetherCode.fresh().all { ch -> ch in TogetherCode.ALPHABET })
        }
    }

    @Test
    fun `codes are not all the same`() {
        // A seeded RNG misuse would show up here as a constant, which would make every room collide.
        val codes = (1..20).map { TogetherCode.fresh(SecureRandom()) }.toSet()
        assertTrue("codes repeated: $codes", codes.size > 15)
    }

    @Test
    fun `a topic is namespaced and derived from the code`() {
        assertEquals("yunyin-together-v1-ABCD2345", TogetherCode.topic("ABCD2345"))
    }

    @Test
    fun `the topic ignores case and separators in a pasted code`() {
        // Someone will paste "abcd-2345" or type it with a space; all of those must reach the same
        // topic the other side published to.
        val canonical = TogetherCode.topic("ABCD2345")
        assertEquals(canonical, TogetherCode.topic("abcd2345"))
        assertEquals(canonical, TogetherCode.topic("abcd-2345"))
        assertEquals(canonical, TogetherCode.topic("ABCD 2345"))
    }

    @Test
    fun `different codes never share a topic`() {
        val a = TogetherCode.topic("ABCD2345")
        val b = TogetherCode.topic("ABCD2346")
        assertTrue("distinct codes collided", a != b)
    }

    @Test
    fun `completeness rejects a half-typed code and accepts a full one`() {
        assertFalse(TogetherCode.isComplete(""))
        assertFalse(TogetherCode.isComplete("ABCD"))
        assertFalse(TogetherCode.isComplete("ABCD-234"))
        assertTrue(TogetherCode.isComplete("ABCD2345WXYZ"))
        // Separators do not count towards the length.
        assertTrue(TogetherCode.isComplete("ABCD-2345-WXYZ"))
    }
}
