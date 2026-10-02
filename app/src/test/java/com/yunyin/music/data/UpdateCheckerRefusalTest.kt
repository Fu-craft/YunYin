package com.yunyin.music.data

import com.yunyin.music.data.update.UpdateChecker
import com.yunyin.music.data.update.UpdateCheck
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checker's behaviour when GitHub **refuses** the request.
 *
 * This is the path that produced the reported bug: a `403` was reported to the user as "请求频繁" without
 * any check having been asked for. Two things had to be true and are pinned here —
 *
 *  - a `403` is only called rate limiting when GitHub's own body says so, because a filtered network sends
 *    `403` too, and naming the wrong cause sends the user off to wait for a limit that is not there;
 *  - a refused API call still consults the atom feed, which is not rate limited, so an update that exists
 *    is found rather than reported as a failure.
 *
 * A fake transport is used rather than the network: the point is the decision logic, and a test that needs
 * GitHub to be reachable (or to be rate limited on demand) is not a test.
 */
class UpdateCheckerRefusalTest {

    /** Replies with [code]/[body] to api.github.com and [atom] to the feed. */
    private class FakeGitHub(
        private val apiCode: Int,
        private val apiBody: String,
        private val atomBody: String? = null,
        private val atomCode: Int = 200,
    ) : Interceptor {
        val requested = mutableListOf<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val request: Request = chain.request()
            requested += request.url.toString()
            val host = request.url.host
            val (code, body) = if (host == "api.github.com") {
                apiCode to apiBody
            } else {
                atomCode to (atomBody ?: "")
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun checker(github: FakeGitHub) = UpdateChecker(
        owner = "Fu-craft",
        repo = "YunYin",
        currentVersion = "3.8.1",
        client = OkHttpClient.Builder().addInterceptor(github).build(),
    )

    @Test
    fun `a documented rate limit is reported as one`() = runBlocking {
        val github = FakeGitHub(
            apiCode = 403,
            apiBody = """{"message":"API rate limit exceeded for 1.2.3.4."}""",
            atomBody = null,
        )
        val result = checker(github).check()
        assertTrue("got $result", result is UpdateCheck.Failed)
        assertEquals("请求过于频繁，请稍后再试", (result as UpdateCheck.Failed).message)
    }

    @Test
    fun `a 403 that is not a rate limit is not described as one`() = runBlocking {
        // A middlebox or proxy refusing GitHub looks identical by status code and nothing like it in body.
        val github = FakeGitHub(
            apiCode = 403,
            apiBody = "<html><title>403 Forbidden</title></html>",
            atomBody = null,
        )
        val result = checker(github).check()
        assertTrue("got $result", result is UpdateCheck.Failed)
        val message = (result as UpdateCheck.Failed).message
        assertTrue("must not blame frequency: $message", !message.contains("频繁"))
        assertTrue("should name the status: $message", message.contains("403"))
    }

    @Test
    fun `a refused API call still finds an update through the feed`() = runBlocking {
        val atom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Release notes from YunYin</title>
              <entry>
                <link rel="alternate" href="https://github.com/Fu-craft/YunYin/releases/tag/v3.9.0"/>
                <title>云音 3.9.0</title>
              </entry>
            </feed>
        """.trimIndent()
        val github = FakeGitHub(
            apiCode = 403,
            apiBody = """{"message":"API rate limit exceeded"}""",
            atomBody = atom,
        )
        val result = checker(github).check()
        assertTrue("got $result", result is UpdateCheck.Newer)
        val update = (result as UpdateCheck.Newer).update
        assertEquals("3.9.0", update.versionName)
        // Found without asset URLs, so it can only be opened in a browser -- not a download.
        assertEquals(null, update.apk)
        // Both paths were tried: the API first, then the feed.
        assertEquals(2, github.requested.size)
        assertTrue(github.requested[0].contains("api.github.com"))
        assertTrue(github.requested[1].contains("releases.atom"))
    }

    @Test
    fun `an unauthorised-looking refusal still falls back and can be up to date`() = runBlocking {
        // The feed resolves it: no entries means no releases, which is a success, not the API's 403.
        val github = FakeGitHub(
            apiCode = 403,
            apiBody = """{"message":"rate limit"}""",
            atomBody = """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>t</title></feed>""",
        )
        assertTrue(checker(github).check() is UpdateCheck.UpToDate)
    }
}
