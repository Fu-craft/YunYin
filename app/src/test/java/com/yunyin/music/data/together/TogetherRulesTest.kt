package com.yunyin.music.data.together

import org.junit.Assert.*
import org.junit.Test

class TogetherRulesTest {
    private fun cmd(type: String, target: String = "42", seq: Long = 7, account: String = "9") =
        TogetherCommand(account, type, "1", target, 1000, true, seq)

    private fun track(id: Long) = com.yunyin.music.core.Track(
        id = id, name = "t$id", artists = listOf("a"), albumName = "", albumId = 0L, coverUrl = null, durationMs = 1000L,
    )

    @Test fun `expected position and seek detection ignore natural advance`() {
        val expected = TogetherProgressRules.expectedPosition(10_000, 1_000_000, 1_005_000, playing = true, buffering = false)
        assertEquals(15_000L, expected)
        assertEquals(10_000L, TogetherProgressRules.expectedPosition(10_000, 1_000_000, 1_005_000, playing = false, buffering = false))
        assertEquals(10_000L, TogetherProgressRules.expectedPosition(10_000, 1_000_000, 1_005_000, playing = true, buffering = true))
        // 5 s of natural advance looks exactly like the expected position -> no report, no seek.
        assertFalse(TogetherProgressRules.isRealSeek(15_000, expected))
        assertFalse(TogetherProgressRules.shouldSeekRemote(15_500, expected))
        // A 10 s jump is a real user seek.
        assertTrue(TogetherProgressRules.isRealSeek(25_000, expected))
        assertTrue(TogetherProgressRules.shouldSeekRemote(25_000, expected))
        assertTrue(TogetherProgressRules.inSettleWindow(1_000, 2_000))
        assertFalse(TogetherProgressRules.inSettleWindow(3_000, 2_000))
    }

    @Test fun `local reporting is suppressed during and after apply and for own commands`() {
        assertTrue(TogetherRules.shouldReportLocal(applying = false, applyFailed = false, isOwnCommand = false))
        assertFalse(TogetherRules.shouldReportLocal(applying = true, applyFailed = false, isOwnCommand = false))
        assertFalse(TogetherRules.shouldReportLocal(applying = false, applyFailed = true, isOwnCommand = false))
        assertFalse(TogetherRules.shouldReportLocal(applying = false, applyFailed = false, isOwnCommand = true))
    }

    @Test fun `oscillation valve suppresses repeated identical commands`() {
        val now = 100_000L
        assertFalse(TogetherRules.shouldSuppressOscillation(listOf(now - 1000, now - 500), now))
        assertTrue(TogetherRules.shouldSuppressOscillation(listOf(now - 1000, now - 500, now - 100), now))
        assertFalse(TogetherRules.shouldSuppressOscillation(listOf(now - 20_000, now - 19_000, now - 18_000), now))
    }

    @Test fun `own commands never produce an incoming apply even with unknown account id`() {
        val sent = cmd("PLAY", target = "42", account = "")
        val sentSignature = TogetherRules.commandSignature(sent)
        val sentState = TogetherRules.stateSignature("PLAY", true, "42")
        assertTrue(TogetherRules.isOwnEcho(sent, "", sentSignature, sentState, 1000L, 1500L))
        assertTrue(TogetherRules.isOwnEcho(sent, "0", sentSignature, sentState, 1000L, 1500L))
        assertTrue(TogetherRules.isOwnEcho(sent, "", "", sentState, 1000L, 1500L))
        assertFalse(TogetherRules.isOwnEcho(sent, "", "", sentState, 1000L, 3500L))
        val peer = cmd("PLAY", target = "99", account = "7")
        assertFalse(TogetherRules.isOwnEcho(peer, "", "", "", 0L, 0L))
    }

    @Test fun `empty status body is unknown not not-in-room`() {
        assertFalse(TogetherRules.statusInRoomKnown(null, httpOk = true))
        assertFalse(TogetherRules.statusInRoomKnown(true, httpOk = false))
        assertTrue(TogetherRules.statusInRoomKnown(true, httpOk = true))
        assertTrue(TogetherRules.statusInRoomKnown(false, httpOk = true))
    }

    @Test fun `threading failures are classified distinctly`() {
        assertEquals("threading", TogetherThreading.classify("MediaController method is called from a wrong thread. See javadoc"))
        assertEquals("threading", TogetherThreading.classify("must run on main thread"))
        assertNull(TogetherThreading.classify("HTTP 500"))
        assertNull(TogetherThreading.classify("room not found"))
        assertTrue(TogetherThreading.shouldWarnOffMain(isMainThread = false))
        assertFalse(TogetherThreading.shouldWarnOffMain(isMainThread = true))
    }

    @Test fun `fake snapshot hydrates 30 remote ids in remote order with the command target`() {
        val remote = (1..30).map { it.toString() }
        val local = (1..3).map { track(it.toLong()) }.associateBy { it.id.toString() }
        val fetched = (4..30).map { track(it.toLong()) }.associateBy { it.id.toString() }
        assertEquals(27, TogetherHydration.missingIds(remote, local.keys).size)
        val tracks = TogetherHydration.ordered(remote, local, fetched)
        assertEquals(30, tracks.size)
        assertEquals(remote.map { it.toLong() }, tracks.map { it.id })
        assertEquals(12, TogetherHydration.targetIndex(remote, "13"))
        assertEquals(0, TogetherHydration.targetIndex(remote, "999"))
        assertTrue(TogetherHydration.ordered(remote, local, fetched - "7").size < 30)
    }

    @Test fun `seed payloads and creator-joiner entry rules`() {
        val playlist = TogetherSeedRules.playlistParam(listOf("11", "22"), userId = 7L, version = 3L)
        assertEquals("REPLACE", playlist.getString("commandType"))
        assertEquals(-1, playlist.getInt("anchorPosition"))
        assertEquals("", playlist.getString("anchorSongId"))
        assertEquals(2, playlist.getJSONArray("displayList").length())
        assertEquals(2, playlist.getJSONArray("randomList").length())
        assertEquals(7L, playlist.getJSONArray("version").getJSONObject(0).getLong("userId"))
        val command = TogetherSeedRules.commandInfo("GOTO", 1234L, true, "0", "22", 99L)
        assertEquals("GOTO", command.getString("commandType"))
        assertEquals("PLAY", command.getString("playStatus"))
        assertEquals("22", command.getString("targetSongId"))
        assertEquals(99L, command.getLong("clientSeq"))
        assertTrue(TogetherRules.creatorEnters("r1"))
        assertFalse(TogetherRules.creatorEnters(""))
        assertFalse(TogetherRules.joinerEnters("r1", 0))
        assertTrue(TogetherRules.joinerEnters("r1", 30))
        assertTrue(TogetherRules.shouldRetrySeed(0, 1))
        assertFalse(TogetherRules.shouldRetrySeed(0, 2))
        assertFalse(TogetherRules.shouldRetrySeed(30, 1))
    }

    @Test fun `room id extraction recurses into nested, string and patterned values`() {
        val nested = org.json.JSONObject("{\"data\":{\"room\":{\"roomInfo\":{\"roomId\":\"abc123\"}}}}")
        assertEquals("abc123", TogetherRoomParser.extract(nested).roomId)
        val embedded = org.json.JSONObject("{\"data\":{\"roomInfo\":\"{\\\"roomId\\\":\\\"str9\\\"}\"}}")
        assertEquals("str9", TogetherRoomParser.extract(embedded).roomId)
        val pattern = org.json.JSONObject("{\"data\":{\"x\":\"b3434a7f1e500c59c0b00bba75aba8f0_1792722426\"}}")
        assertTrue(TogetherRoomParser.extract(pattern).candidates.contains("b3434a7f1e500c59c0b00bba75aba8f0_1792722426"))
    }

    @Test fun `end requires explicit action and a room id`() {
        assertFalse(TogetherRules.canEnd(false, "r1"))
        assertFalse(TogetherRules.canEnd(true, ""))
        assertFalse(TogetherRules.canEnd(true, null))
        assertTrue(TogetherRules.canEnd(true, "r1"))
        assertEquals("s2", TogetherRules.adoptRoomId("", "s2", 0))
        assertEquals("c1", TogetherRules.adoptRoomId("c1", "s2", 0))
        assertTrue(TogetherRules.preferSnapshot(30, false))
        assertFalse(TogetherRules.preferSnapshot(0, false))
    }

    @Test fun `create requires a room id and room labels are explicit`() {
        assertFalse(TogetherRules.canEnter(""))
        assertTrue(TogetherRules.canEnter("r1"))
        assertTrue(TogetherRules.roomLabel(null).startsWith("none"))
        assertTrue(TogetherRules.roomLabel(null).contains("no-room-yet"))
        assertTrue(TogetherRules.roomLabel("").startsWith("none"))
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
