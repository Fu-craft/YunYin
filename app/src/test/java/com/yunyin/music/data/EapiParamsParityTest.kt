package com.yunyin.music.data

import com.yunyin.music.data.net.Eapi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The encoded request must be the same **size** as the reference implementation's.
 *
 * NetEase recomputes the MD5 digest from the decrypted payload and answers an **empty body** when it does
 * not match — which reaches the user as "响应无法解析", indistinguishable from a broken server. A previous
 * version padded the plaintext by hand *and* let `AES/ECB/PKCS5Padding` pad it again; that extra full block
 * is invisible in the plaintext and in the digest, so the only outward sign is the encoded length.
 *
 * Length is therefore the thing to pin, not the bytes: the payload is built from a `JSONObject`, whose key
 * order is not guaranteed, so two correct encoders can differ byte for byte while both being valid — the
 * server compares the digest against the body *it received*, so order does not matter to it.
 *
 * The expected length comes from the independently-written Python encoder
 * (`tools/make_expected_params.py`) for the same fixed input.
 */
class EapiParamsParityTest {

    @Test
    fun `the encoded request has the reference length`() {
        val data = mapOf<String, Any?>("s" to "起风了", "type" to 1, "limit" to 1, "offset" to 0)
        val params = Eapi.sign("/api/search/get", data, "deviceId=probe").first

        // 640 hex characters = 320 bytes = 20 AES blocks. A double-padded request is 672/21.
        assertEquals("encoded length differs from the reference implementation", EXPECTED_LENGTH, params.length)
        assertEquals("only uppercase hex is accepted", params, params.uppercase())
    }

    private companion object {
        /** `len(params)` from the reference encoder for this exact path, header and data. */
        const val EXPECTED_LENGTH = 640
    }
}
