package com.yunyin.music.data

import com.yunyin.music.data.download.DownloadNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Download file naming: the name the user sees in `Music/云音`, derived from what the response actually is.
 *
 * Windows/Android-illegal characters are replaced, Chinese is preserved, the extension comes from the response (not
 * from the track), and collisions get the conventional `(1)` suffix — MediaStore does not dedupe display names.
 */
class DownloadNamingTest {

    @Test
    fun `artist and title are sanitised but Chinese is preserved`() {
        assertEquals("周杰伦 - 晴天", DownloadNaming.baseName("周杰伦", "晴天"))
        // Windows/Android-illegal characters are replaced; whitespace is collapsed.
        assertEquals("AC_DC - Back In Black", DownloadNaming.baseName("AC/DC", "Back In  Black"))
        assertEquals("a_b - c_d", DownloadNaming.baseName("a:b", " c?d "))
    }

    @Test
    fun `the extension comes from the content type first`() {
        assertEquals("flac", DownloadNaming.extensionFor("https://x/y.m4a", "audio/flac"))
        assertEquals("m4a", DownloadNaming.extensionFor("https://x/y.mp3", "audio/mp4"))
        assertEquals("mp3", DownloadNaming.extensionFor("https://x/y", "audio/mpeg"))
        assertEquals("ogg", DownloadNaming.extensionFor("https://x/y", "audio/ogg"))
        assertEquals("wav", DownloadNaming.extensionFor("https://x/y", "audio/wav"))
    }

    @Test
    fun `the URL path is the fallback and unknown responses stay null`() {
        assertEquals("flac", DownloadNaming.extensionFor("https://x/y/song.flac?x=1", null))
        assertEquals("m4a", DownloadNaming.extensionFor("https://x/y/song.M4A", "application/octet-stream"))
        assertEquals("mp3", DownloadNaming.extensionFor("https://x/y", "application/octet-stream", "filename=\"a.mp3\""))
        // Never guess: an unrecognisable response is refused by the writer with a specific message.
        assertNull(DownloadNaming.extensionFor("https://x/y/opaque", "application/octet-stream"))
        assertNull(DownloadNaming.extensionFor("https://x/y/opaque", null))
    }

    @Test
    fun `mime types match the extension`() {
        assertEquals("audio/flac", DownloadNaming.mimeFor("flac"))
        assertEquals("audio/mp4", DownloadNaming.mimeFor("m4a"))
        assertEquals("audio/mpeg", DownloadNaming.mimeFor("mp3"))
        assertEquals("audio/ogg", DownloadNaming.mimeFor("ogg"))
    }

    @Test
    fun `collisions get the conventional suffix`() {
        assertEquals("周杰伦 - 晴天.mp3", DownloadNaming.withSuffix("周杰伦 - 晴天", "mp3", 0))
        assertEquals("周杰伦 - 晴天 (1).mp3", DownloadNaming.withSuffix("周杰伦 - 晴天", "mp3", 1))
        assertEquals("周杰伦 - 晴天 (12).mp3", DownloadNaming.withSuffix("周杰伦 - 晴天", "mp3", 12))
    }

    @Test
    fun `uniqueFileName skips names that already exist`() {
        val taken = setOf("周杰伦 - 晴天.mp3", "周杰伦 - 晴天 (1).mp3")
        assertEquals(
            "周杰伦 - 晴天 (2).mp3",
            DownloadNaming.uniqueFileName("周杰伦 - 晴天", "mp3") { it in taken },
        )
        assertEquals(
            "周杰伦 - 晴天.mp3",
            DownloadNaming.uniqueFileName("周杰伦 - 晴天", "mp3") { false },
        )
    }

    @Test
    fun `the collection path is the shared Music folder`() {
        assertEquals("Music/云音", DownloadNaming.RELATIVE_PATH)
    }
}
