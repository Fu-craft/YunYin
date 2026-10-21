package com.yunyin.music.data.net

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NetEase **weapi** request encryption, implemented locally.
 *
 * ## Why we own this
 *
 * The self-hosted NeteaseCloudMusicApi is a stub for reporting: `/scrobble` answers `{"code":200,"data":"success"}`
 * with no parameters and for a nonexistent song, so no write can ever land through it. The only way to report a play
 * is to speak NetEase's own `weapi` protocol directly, which needs this double-AES + RSA envelope.
 *
 * ## Recipe (same shape as NeteaseCloudMusicApi's `util/crypto.js`; we wrote the code ourselves)
 *
 * 1. `params = base64(AES-CBC-PKCS7(base64(AES-CBC-PKCS7(json, presetKey, iv)), secretKey, iv))`
 * 2. `encSecKey = hex(RSA-NO_PADDING(reverse(secretKey), modulus, 0x010001))`, padded to 128 bytes
 *
 * The constants below are the published weapi constants (`presetKey`, fixed IV, 1024-bit modulus/exponent). They are
 * protocol constants, not code; the AES/RSA operations use the JCE primitives the LX host path already uses.
 *
 * Pure and unit-tested against an independently generated Python/Node transcription of the same recipe, because a
 * silently wrong `encSecKey` looks exactly like "the server ignored the report".
 */
object WeapiCrypto {

    const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    const val IV = "0102030405060708"

    /** Standard weapi RSA public key (modulus, hex) from NeteaseCloudMusicApi `util/crypto.js`. */
    private const val MODULUS_HEX =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e4" +
            "17629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee2559325" +
            "75cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"

    /** Standard weapi RSA exponent (hex). */
    private const val EXPONENT_HEX = "010001"

    private const val SECRET_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    data class Encrypted(val params: String, val encSecKey: String)

    fun randomSecretKey(): String = buildString(16) {
        repeat(16) { append(SECRET_CHARS[random.nextInt(SECRET_CHARS.length)]) }
    }

    /** Encrypts a JSON payload with [secretKey] (16 alphanumeric chars) into the weapi form fields. */
    fun encrypt(json: String, secretKey: String): Encrypted {
        require(secretKey.length == 16) { "weapi secret key must be 16 chars" }
        val first = aesCbcBase64(json, PRESET_KEY, IV)
        val params = aesCbcBase64(first, secretKey, IV)
        return Encrypted(params = params, encSecKey = encSecKey(secretKey))
    }

    /** One AES-CBC/PKCS7 pass, base64-encoded (the reference chains two of these). */
    fun aesCbcBase64(text: String, key: String, iv: String = IV): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    /**
     * RSA/ECB/NoPadding over `reverse(secretKey)` encoded as ASCII, as 256 lowercase hex chars.
     *
     * The reversal is part of the protocol: `RSA.encrypt` in the reference reverses the text before the raw modPow.
     */
    fun encSecKey(secretKey: String): String {
        val reversed = secretKey.reversed()
        val message = BigInteger(1, reversed.toByteArray(Charsets.US_ASCII))
        val modulus = BigInteger(MODULUS_HEX, 16)
        val exponent = BigInteger(EXPONENT_HEX, 16)
        return message.modPow(exponent, modulus).toString(16).padStart(256, '0')
    }
}
