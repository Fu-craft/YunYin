package com.yunyin.music.data

import com.yunyin.music.data.lxsource.LxSourceMirrorRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub → jsDelivr rewrite rules.
 *
 * The rewrite exists because raw.githubusercontent.com is commonly blocked while the same file on jsDelivr works;
 * getting the path mapping wrong would silently import a different file (or 404 all mirrors), so each supported URL
 * form is pinned here.
 */
class LxSourceMirrorRulesTest {

    private val raw = "https://raw.githubusercontent.com/pdone/lx-music-source/main/lx/latest.js"

    @Test
    fun `the original address is always the first candidate`() {
        val candidates = LxSourceMirrorRules.candidates(raw)
        assertEquals(3, candidates.size)
        assertEquals(LxSourceMirrorRules.ORIGINAL_LABEL, candidates[0].label)
        assertEquals(raw, candidates[0].url)
        assertEquals(false, candidates[0].mirrored)
    }

    @Test
    fun `raw githubusercontent is rewritten to jsDelivr then fastly`() {
        val candidates = LxSourceMirrorRules.candidates(raw)
        assertEquals(
            "https://cdn.jsdelivr.net/gh/pdone/lx-music-source@main/lx/latest.js",
            candidates[1].url,
        )
        assertEquals("https://fastly.jsdelivr.net/gh/pdone/lx-music-source@main/lx/latest.js", candidates[2].url)
        assertTrue(candidates[1].mirrored && candidates[2].mirrored)
        assertEquals(LxSourceMirrorRules.JSDELIVR_LABEL, candidates[1].label)
        assertEquals(LxSourceMirrorRules.FASTLY_LABEL, candidates[2].label)
    }

    @Test
    fun `github dot com raw and blob forms are rewritten`() {
        assertEquals(
            "pdone/lx-music-source@master/src/x.js",
            LxSourceMirrorRules.githubPath(
                "https://github.com/pdone/lx-music-source/raw/master/src/x.js",
            ),
        )
        assertEquals(
            "owner/repo@dev/a/b/c.js",
            LxSourceMirrorRules.githubPath("https://github.com/owner/repo/blob/dev/a/b/c.js?raw=true"),
        )
    }

    @Test
    fun `the git suffix is dropped and query is ignored`() {
        assertEquals(
            "owner/repo@main/x.js",
            LxSourceMirrorRules.githubPath("https://raw.githubusercontent.com/owner/repo.git/main/x.js"),
        )
        assertEquals(
            "owner/repo@main/x.js",
            LxSourceMirrorRules.githubPath("https://raw.githubusercontent.com/owner/repo/main/x.js?token=1"),
        )
    }

    @Test
    fun `non-github URLs have no mirror`() {
        assertTrue(LxSourceMirrorRules.mirrors("https://example.com/source.js").isEmpty())
        assertTrue(LxSourceMirrorRules.mirrors("https://cdn.jsdelivr.net/gh/owner/repo@main/x.js").isEmpty())
        assertTrue(LxSourceMirrorRules.mirrors("ftp://github.com/owner/repo/raw/main/x.js").isEmpty())
        // A private address is not rewritten, so it can only be judged by the policy (and refused there).
        assertTrue(LxSourceMirrorRules.mirrors("http://" + "192.168." + "1.1/x.js").isEmpty())
    }

    @Test
    fun `a path traversal segment produces no mirror rather than a guess`() {
        assertTrue(LxSourceMirrorRules.mirrors("https://raw.githubusercontent.com/owner/repo/main").isEmpty())
        assertTrue(LxSourceMirrorRules.mirrors("https://github.com/owner/repo/tree/main/x.js").isEmpty())
        assertTrue(LxSourceMirrorRules.mirrors("https://raw.githubusercontent.com/owner/../main/x.js").isEmpty())
    }
}
