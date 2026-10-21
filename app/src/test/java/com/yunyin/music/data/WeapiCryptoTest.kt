package com.yunyin.music.data

import com.yunyin.music.data.net.WeapiCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Known-answer tests for the weapi envelope.
 *
 * The vectors were generated **independently** (Node `crypto.createCipheriv` + `BigInt.modPow` transcription of the
 * published NeteaseCloudMusicApi recipe, not by calling this Kotlin code), for a fixed secret key and payload. If a
 * future refactor changes AES mode, padding, chaining, key reversal or RSA padding, these fail loudly.
 */
class WeapiCryptoTest {

    private val secret = "0123456789abcdef"
    private val payload =
        """{"logs":[{"action":"play","json":{"download":0,"end":"playend","id":186016,"sourceId":0,"time":60,"type":"song","wifi":0}}]}"""

    @Test
    fun `params match the independent reference vector`() {
        val encrypted = WeapiCrypto.encrypt(payload, secret)
        assertEquals(
            "FE4yj0NaAUi5mH5Pmc23j9tYqpcqYSynY+ox0imanNRd1PweK8I+hOOAKu/C5C4LHKD5TktGcMVrLF2ANtUrchEGPhJ/" +
                "yU8V94txk9fa0d45B47zof/jiNzbbM/9KWHLOuC38HUD+4RYjhsJ1ypyAKt+YYk/K5fePC57xPUdXeqyIduvfbCSPg1BLuYM" +
                "0ssSBD5eB9kYs91po4gP1J6atdoDgBpqaGpLgL5DmejwGi4=",
            encrypted.params,
        )
    }

    @Test
    fun `encSecKey matches the independent reference vector`() {
        assertEquals(
            "35701388baf89fed412e11269b9c76625d095ecaf17f03fa018abe19ea2d38b949debf242ee39a71ca1f6cda71b1b86" +
                "a45aa909ee27f7e78e267d34e732f0de948206c3340a788d0003372183e2f753c1f78b66ac23d134ac1fc9b9931565" +
                "20ea826b8aa89a962d4491b4b8d7e08738e1da9b07aa39bf4a7ef0b1c210728cd52",
            WeapiCrypto.encSecKey(secret),
        )
    }

    @Test
    fun `a different secret changes both fields`() {
        val a = WeapiCrypto.encrypt(payload, secret)
        val b = WeapiCrypto.encrypt(payload, "fedcba9876543210")
        assertNotEquals(a.params, b.params)
        assertNotEquals(a.encSecKey, b.encSecKey)
    }

    @Test
    fun `the secret must be 16 chars`() {
        try {
            WeapiCrypto.encrypt(payload, "short")
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
