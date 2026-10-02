package com.yunyin.music.data

import com.yunyin.music.data.together.OfficialTogetherRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The official room's two easy-to-lose rules: the invite is a *pair*, and a reported position is a
 * *snapshot* rather than a live value.
 *
 * Both are pinned by tests because both mistakes are silent. A mangled invite still looks like a string
 * and only fails at the server ("房间不存在"); an un-aged position still looks like a number and only
 * shows up minutes into a song, as the two devices seeking each other apart.
 */
class OfficialTogetherRulesTest {

    // ---------------------------------------------------------------- the invite

    @Test
    fun `an invite round-trips through build and parse`() {
        val room = "8f3c1d2e4b5a69788796a5b4c3d2e1f0_1758341950"
        val invite = OfficialTogetherRules.buildInvite(room, 17583419505L)
        assertEquals("$room|17583419505", invite)
        assertEquals(room to 17583419505L, OfficialTogetherRules.parseInvite(invite))
    }

    @Test
    fun `a bare room id is not a complete invite`() {
        // The id alone cannot be accepted — the server needs the inviter too — so this must not parse.
        assertNull(
            OfficialTogetherRules.parseInvite("8f3c1d2e4b5a69788796a5b4c3d2e1f0_1758341950"),
        )
    }

    @Test
    fun `a malformed inviter id is rejected rather than silently zeroed`() {
        assertNull(OfficialTogetherRules.parseInvite("abc|not-a-number"))
        assertNull(OfficialTogetherRules.parseInvite("abc|0"))
        assertNull(OfficialTogetherRules.parseInvite("|123"))
    }

    @Test
    fun `case is preserved, because a room id is lowercase hex`() {
        val room = "8F3C1D2E_1758341950"
        // Uppercasing this — the right rule for a *typed* code — would produce an id the server has
        // never heard of. The pasted invite must survive verbatim.
        assertEquals(room, OfficialTogetherRules.parseInvite("$room|123")?.first)
    }

    @Test
    fun `a separator a chat app rewrote is folded back`() {
        val room = "8f3c1d2e4b5a_1758341950"
        for (sep in listOf("|", " ", ":", "：", "\n", "\t")) {
            val normalised = OfficialTogetherRules.normaliseJoinInput("$room${sep}17583419505")
            assertEquals("separator '$sep'", "$room|17583419505", normalised)
            assertTrue(OfficialTogetherRules.parseInvite(normalised) != null)
        }
    }

    @Test
    fun `a hyphen is not treated as a separator`() {
        // A hyphen is not a legal room-id character, so splitting on one would fabricate a room that
        // does not exist instead of reporting the paste as malformed.
        val input = "8f3c1d2e-1758341950"
        assertEquals(input, OfficialTogetherRules.normaliseJoinInput(input))
    }

    // ---------------------------------------------------------------- the reported position

    @Test
    fun `a playing command ages to now`() {
        val issuedAt = 1_700_000_000_000L
        val now = issuedAt + 30_000L
        // Reported at 12s into the song, 30s ago: the peer is at 42s now, not 12s.
        assertEquals(
            42_000L,
            OfficialTogetherRules.playbackAdvance(12_000L, playing = true, serverSeq = issuedAt, nowMs = now),
        )
    }

    @Test
    fun `a paused command does not age`() {
        val issuedAt = 1_700_000_000_000L
        // A paused position does not move, so advancing it would invent drift out of nothing.
        assertEquals(
            12_000L,
            OfficialTogetherRules.playbackAdvance(12_000L, playing = false, serverSeq = issuedAt, nowMs = issuedAt + 30_000L),
        )
    }

    @Test
    fun `a value that is not an epoch is left alone rather than aged`() {
        // A small counter in the field must not become "position plus fifty years".
        assertEquals(
            5_000L,
            OfficialTogetherRules.playbackAdvance(5_000L, playing = true, serverSeq = 3L, nowMs = 1_700_000_000_000L),
        )
        assertEquals(
            5_000L,
            OfficialTogetherRules.playbackAdvance(5_000L, playing = true, serverSeq = 0L, nowMs = 1_700_000_000_000L),
        )
    }

    @Test
    fun `the advance is capped, so a stale command cannot land past the end of a song`() {
        val issuedAt = 1_700_000_000_000L
        val longAfter = issuedAt + 24 * 60 * 60 * 1000L
        assertEquals(
            5_000L + OfficialTogetherRules.MAX_ADVANCE_MS,
            OfficialTogetherRules.playbackAdvance(5_000L, playing = true, serverSeq = issuedAt, nowMs = longAfter),
        )
    }

    @Test
    fun `a clock that is behind the server does not age a position backwards`() {
        val issuedAt = 1_700_000_000_000L
        // A device whose clock lags the server would otherwise compute a negative elapsed time.
        assertEquals(
            5_000L,
            OfficialTogetherRules.playbackAdvance(5_000L, playing = true, serverSeq = issuedAt, nowMs = issuedAt - 10_000L),
        )
    }
}
