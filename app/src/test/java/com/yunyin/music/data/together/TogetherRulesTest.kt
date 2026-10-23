package com.yunyin.music.data.together

import org.junit.Assert.*
import org.junit.Test

class TogetherRulesTest {
    private fun cmd(type: String, target: String = "42", seq: Long = 7, account: String = "9") =
        TogetherCommand(account, type, "1", target, 1000, true, seq)

    @Test fun `create requires a room id and room labels are explicit`() {
        assertFalse(TogetherRules.canEnter(""))
        assertTrue(TogetherRules.canEnter("r1"))
        assertEquals("none", TogetherRules.roomLabel(null))
        assertEquals("none", TogetherRules.roomLabel(""))
        assertEquals("r1", TogetherRules.roomLabel("r1"))
        assertNull(TogetherRules.antiCheatHeader(""))
        assertEquals("tok", TogetherRules.antiCheatHeader("tok"))
    }

    @Test fun `eapi payload carries e_r and the identity header`() {
        val payload = TogetherTransport.eapiPayload(
            "/api/listen/together/room/create",
            org.json.JSONObject("{\"refer\":\"songplay_more\"}"),
            "MUSIC_U=abc; __csrf=tok; os=android",
        )
        assertFalse(payload.getBoolean("e_r"))
        val header = payload.getJSONObject("header")
        assertEquals("android", header.getString("os"))
        assertEquals("abc", header.getString("MUSIC_U"))
        assertTrue(header.getString("requestId").contains("_"))
        assertTrue(header.has("deviceId") && header.has("appver") && header.has("channel"))
    }

    @Test fun `joined only when the snapshot carries room data`() {
        assertFalse(TogetherRules.joinedFromSnapshot(emptyList()))
        assertTrue(TogetherRules.joinedFromSnapshot(listOf("1")))
    }

    @Test fun `room parser reads roomInfo roomId and creatorId and falls back`() {
        val nested = org.json.JSONObject(
            "{\"code\":200,\"data\":{\"roomInfo\":{\"roomId\":\"r123\",\"creatorId\":\"456\"}}}",
        )
        val room = TogetherRoomParser.parse(nested)
        assertEquals("r123", room.roomId)
        assertEquals("456", room.creatorId)
        val flat = org.json.JSONObject("{\"code\":200,\"data\":{\"roomId\":\"r9\",\"creatorId\":\"7\"}}")
        assertEquals("r9", TogetherRoomParser.parse(flat).roomId)
        assertEquals("r7", TogetherRoomParser.parse(org.json.JSONObject("{\"roomId\":\"\"}"), fallbackId = "r7").roomId)
        assertTrue(TogetherRoomParser.reason(org.json.JSONObject("{\"code\":404,\"msg\":\"已结束\"}")).contains("404"))
    }

    @Test fun `invite link round-trips the creator id from the room`() {
        val room = TogetherRoomParser.parse(org.json.JSONObject("{\"roomId\":\"r1\",\"creatorId\":\"42\"}"))
        val link = "qplayer://listen-together?provider=netease&roomId=${room.roomId}&inviterId=${room.creatorId}"
        assertEquals("r1" to "42", TogetherRules.parseInvite(link).getOrThrow())
    }

    @Test fun `available follows the app real-session rule`() {
        assertTrue(TogetherRules.available("MUSIC_U=x", anonymous = false))
        assertFalse(TogetherRules.available("MUSIC_U=x", anonymous = true))
        assertFalse(TogetherRules.available("", anonymous = false))
        assertFalse(TogetherRules.available("", anonymous = null))
    }

    @Test fun `eapi envelope is hex and deterministic`() {
        val path = "/api/listen/together/room/create"
        val json = "{\"refer\":\"songplay_more\"}"
        val params = TogetherTransport.eapiParams(path, json)
        assertTrue(params.length > 100 && params.length % 2 == 0)
        assertTrue(params.all { it in "0123456789ABCDEF" })
        assertEquals(params, TogetherTransport.eapiParams(path, json))
    }

    @Test fun `freshness ignores own account and stale sequence`() {
        assertFalse(TogetherRules.isFresh(cmd("PLAY", account = "5"), "", 0, "5"))
        assertTrue(TogetherRules.isFresh(cmd("PLAY", account = "8"), "", 0, "9"))
        val sig = TogetherRules.commandSignature(cmd("PLAY"))
        assertFalse(TogetherRules.isFresh(cmd("PLAY"), sig, 7, "9"))
        assertFalse(TogetherRules.isFresh(cmd("PLAY", seq = 3), "", 7, "9"))
    }

    @Test fun `local change maps to replace goto progress and play-pause`() {
        assertTrue(TogetherRules.planLocalChange(listOf("1"), listOf("1", "2"), "1", "1", 0, 0, false, false, "9", 1, 10) is TogetherAction.ReplaceQueue)
        assertTrue(TogetherRules.planLocalChange(listOf("1"), listOf("1"), "1", "2", 0, 0, true, true, "9", 1, 10) is TogetherAction.Command)
        val progress = TogetherRules.planLocalChange(listOf("1"), listOf("1"), "1", "1", 0, 2000, true, true, "9", 1, 10) as TogetherAction.Command
        assertEquals("PROGRESS", progress.type)
        val pause = TogetherRules.planLocalChange(listOf("1"), listOf("1"), "1", "1", 0, 0, true, false, "9", 1, 10) as TogetherAction.Command
        assertEquals("PAUSE", pause.type)
    }

    @Test fun `leader falls back creator then lowest then self`() {
        assertEquals("8", TogetherRules.leaderId("8", listOf("9"), "9"))
        assertEquals("3", TogetherRules.leaderId("", listOf("9", "3", "12"), "9"))
        assertEquals("9", TogetherRules.leaderId("", emptyList(), "9"))
        assertEquals(3500L, TogetherRules.LEADER_FALLBACK_MS)
    }

    @Test fun `backoff is 30 60 120 then hard stop`() {
        assertEquals(30000L, TogetherRules.backoffMs(1))
        assertEquals(60000L, TogetherRules.backoffMs(2))
        assertEquals(120000L, TogetherRules.backoffMs(3))
        assertNull(TogetherRules.backoffMs(4))
        assertTrue(TogetherRules.isRateLimited("429 Too Many Requests"))
        assertTrue(TogetherRules.isRateLimited("操作频繁"))
        assertFalse(TogetherRules.isRateLimited("network down"))
    }

    @Test fun `invite parser accepts qplayer links and bare ids`() {
        assertEquals("abc123" to "42", TogetherRules.parseInvite("qplayer://listen-together?provider=netease&roomId=abc123&inviterId=42").getOrThrow())
        assertEquals("abc-123" to "", TogetherRules.parseInvite("abc-123").getOrThrow())
        assertTrue(TogetherRules.parseInvite("https://other.example/x?provider=qq&roomId=1").isFailure)
        assertTrue(TogetherRules.parseInvite("bad room id!").isFailure)
    }
}
