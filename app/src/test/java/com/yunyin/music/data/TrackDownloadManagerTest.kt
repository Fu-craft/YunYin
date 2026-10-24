// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.core.Track
import com.yunyin.music.data.download.DownloadPhase
import com.yunyin.music.data.download.DownloadProbeVerdict
import com.yunyin.music.data.download.DownloadPreviewGate
import com.yunyin.music.data.download.DownloadSink
import com.yunyin.music.data.download.SavedDownload
import com.yunyin.music.data.download.TrackDownloadManager
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.net.NeteaseClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Download queueing, notices and cancellation, without Android.
 *
 * The user's "nothing downloaded" report had a UI cause: a sheet closes on tap and a changed row label was the only
 * feedback. These tests pin the replacement contract: [TrackDownloadManager.download] is non-blocking, launches on
 * the manager's own scope, and emits a notice **before** any work so a tap can never be silent.
 */
class TrackDownloadManagerTest {

    private fun track(id: Long = 42L, name: String = "晴天"): Track = Track(
        id = id,
        name = name,
        artists = listOf("周杰伦"),
        albumName = "叶惠美",
        albumId = 1L,
        coverUrl = null,
        durationMs = 269_000L,
    )

    private fun log(): LxSourceLog {
        val dir = Files.createTempDirectory("download-manager").toFile()
        return LxSourceLog(File(dir, "last-error.txt"))
    }

    private class FakeSink(private val gate: CompletableDeferred<Unit>? = null) : DownloadSink {
        var calls = 0
            private set
        var lastUrl: String? = null
            private set

        override suspend fun save(
            track: Track,
            url: String,
            onProgress: (downloaded: Long, total: Long?) -> Unit,
        ): SavedDownload {
            calls++
            lastUrl = url
            onProgress(1, 2)
            gate?.await()
            onProgress(2, 2)
            return SavedDownload("content://audio/1", "周杰伦 - 晴天.mp3", "Music/云音")
        }
    }

    private suspend fun awaitPhase(
        manager: TrackDownloadManager,
        trackId: Long,
        phase: DownloadPhase,
        timeoutMs: Long = 5_000,
    ) {
        withTimeout(timeoutMs) {
            while (manager.stateFor(trackId).phase != phase) delay(10)
        }
    }

    @Test
    fun `enqueue returns immediately, emits a start notice and finishes on the manager scope`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = TrackDownloadManager(
            resolve = { _, _ -> NeteaseClient.SongUrl("https://cdn/x.mp3") },
            preferredQuality = { "exhigh" },
            sink = FakeSink(gate),
            log = log(),
            scope = scope,
            probe = { _, _ -> DownloadProbeVerdict.FULL to 9_000_000L },
            maxConcurrent = 1,
        )
        val notices = java.util.Collections.synchronizedList(mutableListOf<String>())
        val collector = scope.launch { manager.notices.collect { notices += it } }
        try {
            // Give the collector a beat to subscribe: a replayless SharedFlow drops emissions before collection.
            delay(100)
            val accepted = manager.download(track())
            assertTrue(accepted)
            // The start notice exists before the sink has completed, which is what makes a tap visibly do something.
            withTimeout(2_000) { while (notices.none { it.startsWith("正在下载") }) delay(10) }
            assertTrue(notices.any { it == "正在下载「晴天」（极高）" })
            withTimeout(2_000) {
                while (manager.stateFor(42L).phase != DownloadPhase.DOWNLOADING) delay(10)
            }
            gate.complete(Unit)
            awaitPhase(manager, 42L, DownloadPhase.DONE)
            assertTrue(notices.any { it == "已保存到 Music/云音：周杰伦 - 晴天.mp3" })
        } finally {
            collector.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `a cancelled caller scope does not cancel the download`() = runBlocking {
        // The sheet's rememberCoroutineScope dies when the sheet closes; the manager must not share that lifetime.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = TrackDownloadManager(
            resolve = { _, _ -> NeteaseClient.SongUrl("https://cdn/x.mp3") },
            preferredQuality = { "exhigh" },
            sink = FakeSink(),
            log = log(),
            scope = scope,
            probe = { _, _ -> DownloadProbeVerdict.UNKNOWN to null },
        )
        try {
            val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // UNDISPATCHED runs the enqueue call now, then the caller scope is cancelled: the download must continue
            // on the manager's scope, which is the property a closing sheet depends on.
            caller.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { manager.download(track()) }
            caller.cancel()
            awaitPhase(manager, 42L, DownloadPhase.DONE)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a preview clip is refused with the specific message and never reaches the sink`() = runBlocking {
        val sink = FakeSink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = TrackDownloadManager(
            resolve = { _, _ -> NeteaseClient.SongUrl("https://cdn/x.mp3") },
            preferredQuality = { "exhigh" },
            sink = sink,
            log = log(),
            scope = scope,
            probe = { _, _ -> DownloadProbeVerdict.PREVIEW_CLIP to 300_000L },
            maxConcurrent = 1,
        )
        val notices = java.util.Collections.synchronizedList(mutableListOf<String>())
        val collector = scope.launch { manager.notices.collect { notices += it } }
        try {
            delay(100)
            manager.download(track())
            awaitPhase(manager, 42L, DownloadPhase.FAILED)
            assertEquals(DownloadPreviewGate.MESSAGE, manager.stateFor(42L).message)
            assertEquals(0, sink.calls)
            assertTrue(notices.contains(DownloadPreviewGate.MESSAGE))
        } finally {
            collector.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `an unknown probe proceeds to the sink`() = runBlocking {
        val sink = FakeSink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = TrackDownloadManager(
            resolve = { _, _ -> NeteaseClient.SongUrl("https://cdn/x.mp3") },
            preferredQuality = { "exhigh" },
            sink = sink,
            log = log(),
            scope = scope,
            probe = { _, _ -> DownloadProbeVerdict.UNKNOWN to null },
        )
        try {
            manager.download(track())
            awaitPhase(manager, 42L, DownloadPhase.DONE)
            assertEquals(1, sink.calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancelling a running download reports it and leaves a retryable state`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = TrackDownloadManager(
            resolve = { _, _ -> NeteaseClient.SongUrl("https://cdn/x.mp3") },
            preferredQuality = { "exhigh" },
            sink = FakeSink(gate),
            log = log(),
            scope = scope,
            probe = { _, _ -> DownloadProbeVerdict.FULL to 9_000_000L },
        )
        try {
            manager.download(track())
            withTimeout(2_000) {
                while (manager.stateFor(42L).phase != DownloadPhase.DOWNLOADING) delay(10)
            }
            manager.cancel(42L)
            awaitPhase(manager, 42L, DownloadPhase.FAILED)
            assertEquals("已取消下载", manager.stateFor(42L).message)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an already downloaded track is not downloaded again`() = runBlocking {
        val sink = FakeSink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = TrackDownloadManager(
            resolve = { _, _ -> NeteaseClient.SongUrl("https://cdn/x.mp3") },
            preferredQuality = { "exhigh" },
            sink = sink,
            log = log(),
            scope = scope,
            probe = { _, _ -> DownloadProbeVerdict.FULL to 9_000_000L },
        )
        try {
            manager.download(track())
            awaitPhase(manager, 42L, DownloadPhase.DONE)
            assertFalse(manager.download(track()))
            assertEquals(1, sink.calls)
        } finally {
            scope.cancel()
        }
    }
}
