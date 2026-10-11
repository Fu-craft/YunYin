package com.yunyin.music.data

import com.yunyin.music.data.together.ltw.LtwApi
import com.yunyin.music.data.together.ltw.LtwTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves [LtwApi] against the **real deployed service**, not a mock.
 *
 * This is the one thing the app itself cannot show on a single device, and it is the reason the previous
 * transport implementations shipped broken: the read path was never exercised end to end. A room is
 * server-authoritative and its protocol is plain HTTP, so — unlike the NetEase API, which needs two signed-in
 * accounts and a live Agora channel — the *whole* flow except the WebSocket can be driven from a unit test.
 *
 * Ignored by default: it needs the network, and a unit test suite must not. Run it explicitly with
 *     gradlew :app:testDebugUnitTest --tests '*LtwLiveTest*' -Dltw.live=1
 * and it will create a room on the deployed worker, join it as a second member, and assert that a `PLAY` from
 * the controller becomes `playing` for the listener with an advanced server-computed position — which is
 * exactly the behaviour the user-facing "play/pause is inverted" bug was about, verified against the real
 * server instead of an argument.
 */
class LtwLiveTest {

    private val live: Boolean
        get() = System.getProperty("ltw.live") == "1" || System.getenv("LTW_LIVE") == "1"

    private val base = System.getProperty("ltw.base") ?: System.getenv("LTW_BASE")
        ?: "https://neriplayer-ltw.huang2216555241.workers.dev"

    private fun track(id: Long, name: String) = LtwTrack(
        stableKey = "test:$id",
        channelId = "netease",
        audioId = id.toString(),
        name = name,
        artist = "测试歌手",
        album = "测试专辑",
        durationMs = 200_000,
        coverUrl = null,
    )

    @Test
    fun `the live service drives a room from controller to listener`(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(
            "set LTW_LIVE=1 to run the live protocol check",
            live,
        )
        val api = LtwApi()
        val a = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val b = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

        val seed = LtwApi.LtwSeed(
            tracks = listOf(track(1001, "第一首"), track(1002, "第二首")),
            currentIndex = 0,
            positionMs = 0,
            playing = false,
        )
        val created = api.create(base, a, "房主", seed).getOrThrow()
        assertTrue("a room id is issued", created.roomId.isNotBlank())
        assertEquals("the creator is the controller", "controller", created.role)
        assertTrue("the join secret comes back for the invite", !created.joinSecret.isNullOrBlank())

        val joined = api.join(base, created.roomId, b, "听众", created.joinSecret, null).getOrThrow()
        assertEquals("the second member is a listener", "listener", joined.role)

        val snapshot = api.state(base, created.roomId, joined.token).getOrThrow()
        assertEquals("both members are present", 2, snapshot.state.members.size)
        assertEquals("the seed track is the room's track", "第一首", snapshot.state.track?.name)

        // The controller plays. This is the exact operation the user reported as "inverting".
        val play = api.control(
            base, created.roomId, created.token,
            org.json.JSONObject().put("type", "PLAY").put("positionMs", 5_000)
                .put("clientTimeMs", System.currentTimeMillis()),
        )
        assertTrue("PLAY is accepted", play.isSuccess)

        val afterPlay = api.state(base, created.roomId, joined.token).getOrThrow()
        assertTrue("the listener reads the room as playing", afterPlay.state.playback?.playing == true)
        assertTrue(
            "the server advances the position past where PLAY set it",
            afterPlay.expectedPositionMs > 5_000L,
        )

        // The controller pauses and the listener reads that too.
        api.control(
            base, created.roomId, created.token,
            org.json.JSONObject().put("type", "PAUSE").put("positionMs", 8_000)
                .put("clientTimeMs", System.currentTimeMillis()),
        )
        val afterPause = api.state(base, created.roomId, joined.token).getOrThrow()
        assertTrue("the listener reads the room as paused", afterPause.state.playback?.playing == false)
        assertEquals("a paused room does not advance", 8_000L, afterPause.expectedPositionMs)

        // A listener's request is arbitrated by the server **only while the controller holds a live
        // WebSocket** (`shouldAcceptRequestedControl` checks `controllerSessions().length`), so the test has
        // to open one — which is also what proves the app must keep its socket connected for member control
        // to work at all.
        val welcome = java.util.concurrent.CountDownLatch(1)
        val wsClient = okhttp3.OkHttpClient()
        val wsUrl = created.roomId.let { room ->
            base.trimEnd('/').replaceFirst("https://", "wss://") +
                "/api/rooms/$room/ws?token=" + java.net.URLEncoder.encode(created.token, "UTF-8")
        }
        val socket = wsClient.newWebSocket(
            okhttp3.Request.Builder().url(wsUrl).build(),
            object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    if (text.contains("\"welcome\"")) welcome.countDown()
                }
            },
        )
        assertTrue("the controller's socket connects", welcome.await(15, java.util.concurrent.TimeUnit.SECONDS))
        socket.send(
            org.json.JSONObject().put("type", "np_ping")
                .put("t", System.currentTimeMillis()).toString(),
        )

        val request = api.control(
            base, created.roomId, joined.token,
            org.json.JSONObject().put("type", "REQUEST_SET_TRACK")
                .put("requestTrackStableKey", "test:1002")
                .put("clientTimeMs", System.currentTimeMillis())
                .put("queue", org.json.JSONArray().put(
                    org.json.JSONObject().put("stableKey", "test:1001").put("channelId", "netease")
                        .put("audioId", "1001").put("name", "第一首").put("artist", "测试歌手"),
                ).put(
                    org.json.JSONObject().put("stableKey", "test:1002").put("channelId", "netease")
                        .put("audioId", "1002").put("name", "第二首").put("artist", "测试歌手"),
                )),
        )
        assertTrue("a listener's track request is honoured while the controller is online", request.isSuccess)
        val afterRequest = api.state(base, created.roomId, created.token).getOrThrow()
        assertEquals("the room moved to the requested track", 1, afterRequest.state.currentIndex)

        socket.close(1000, null)
        api.leave(base, created.roomId, created.token)
    }
}
