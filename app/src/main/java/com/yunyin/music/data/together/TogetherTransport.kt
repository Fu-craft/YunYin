package com.yunyin.music.data.together

/*
 * Ported from qplayer-netease-plugin (MIT, Copyright © 2026 TIMER-err),
 * src/together.js + src/main.js + src/xeapi.js — reimplemented in Kotlin.
 */

import com.yunyin.music.data.SettingsStore
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Classic eapi + weapi envelopes for the listen-together endpoints.
 *
 * `main.js:listenTogether` uses `eapi()` for 8 calls and `weapi()` only for `listen/together/status/get`; `xeapi.js`
 * (X25519/HKDF/AES-GCM) belongs to the song-URL capability, not to together, so it is not ported here.
 */
class TogetherTransport(
    private val settings: SettingsStore,
    private val http: OkHttpClient = defaultClient(),
) {

    /** `eapiPost`: form field `params` = hex-uppercase AES-128-ECB(PKCS5) of the classic eapi payload. */
    fun eapiPost(path: String, payload: JSONObject): Result<JSONObject> = runCatching {
        val json = payload.toString()
        val params = eapiParams(path, json)
        val body = FormBody.Builder().add("params", params).build()
        val request = Request.Builder()
            .url("https://interface3.music.163.com/eapi$path")
            .header("User-Agent", EAPI_UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", settings.cookie)
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            JSONObject(text)
        }
    }

    /** Classic weapi (only the status call needs it). */
    fun weapiPost(path: String, payload: JSONObject): Result<JSONObject> = runCatching {
        val secret = randomSecret()
        val params = base64(aesCbcBytes(aesCbc(payload.toString(), PRESET, IV), secret, IV))
        val encSecKey = encSecKey(secret)
        val body = FormBody.Builder().add("params", params).add("encSecKey", encSecKey).build()
        val request = Request.Builder()
            .url("https://music.163.com/weapi$path")
            .header("User-Agent", WEB_UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", settings.cookie)
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            JSONObject(text)
        }
    }

    companion object {
        const val EAPI_KEY = "e82ckenh8dichen8"
        const val EAPI_SEPARATOR = "-36cd479b6b5-"
        const val PRESET = "0CoJUm6Qyw8W8jud"
        const val IV = "0102030405060708"
        private const val EAPI_UA =
            "Mozilla/5.0 (Linux; Android 9; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/73.0.3683.90 Mobile Safari/537.36"
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val MODULUS =
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e4" +
                "17629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee2559325" +
                "75cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()

        fun md5Hex(value: String): String =
            MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Classic eapi params: md5 digest over `nobody<path>use<json>md5forencrypt`, AES-128-ECB/PKCS5, hex upper. */
        fun eapiParams(path: String, json: String): String {
            val digest = md5Hex("nobody${path}use${json}md5forencrypt")
            val payload = "$path$EAPI_SEPARATOR$json$EAPI_SEPARATOR$digest"
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(), "AES"))
            return cipher.doFinal(payload.toByteArray()).joinToString("") { "%02X".format(it) }
        }

        fun aesCbcBytes(data: ByteArray, key: String, iv: String): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key.toByteArray(), "AES"),
                IvParameterSpec(iv.toByteArray()),
            )
            return cipher.doFinal(data)
        }

        fun aesCbc(text: String, key: String, iv: String): ByteArray = aesCbcBytes(text.toByteArray(), key, iv)

        private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        private fun randomSecret(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return (1..16).map { chars.random() }.joinToString("")
        }

        /** RSA/ECB/NoPadding over reverse(secret) as ASCII, 256 lowercase hex. */
        fun encSecKey(secret: String): String {
            val reversed = secret.reversed()
            val message = java.math.BigInteger(1, reversed.toByteArray(Charsets.US_ASCII))
            val n = java.math.BigInteger(MODULUS, 16)
            val e = java.math.BigInteger("010001", 16)
            return message.modPow(e, n).toString(16).padStart(256, '0')
        }
    }
}
