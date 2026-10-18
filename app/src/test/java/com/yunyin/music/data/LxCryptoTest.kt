package com.yunyin.music.data

import com.yunyin.music.data.lxsource.LxHostUtilities
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Known-answer tests for `lx.utils.crypto` — the part of the host where being subtly wrong is invisible.
 *
 * An AES implementation with the wrong mode or padding still returns *bytes*; an RSA one with the wrong padding
 * still returns *bytes*. Only a fixed vector, or a decryption with an independent implementation, catches it.
 * These run on the JVM with no QuickJS: the JS side only converts Buffers to hex and back.
 */
class LxCryptoTest {

    private val util = LxHostUtilities

    // ------------------------------------------------------------------ AES

    /** NIST SP 800-38A, F.2.1 CBC-AES128.Encrypt — first block. */
    private val nistKey = "2b7e151628aed2a6abf7158809cf4f3c"
    private val nistIv = "000102030405060708090a0b0c0d0e0f"
    private val nistPlain = "6bc1bee22e409f96e93d7e117393172a"
    private val nistCbcCipher = "7649abac8119b246cee98e9b12e9197d"

    /** NIST SP 800-38A, F.1.1 ECB-AES128.Encrypt — first block. */
    private val nistEcbCipher = "3ad77bb40d7a3660a89ecaf32466ef97"

    @Test
    fun `aes-128-cbc matches the NIST vector and adds PKCS7 padding`() {
        val ciphertext = util.aesEncrypt("aes-128-cbc", nistKey, nistIv, nistPlain)
        // One 16-byte block of plaintext becomes two blocks: the vector block plus the PKCS#7 padding block.
        assertEquals(64, ciphertext.length)
        assertEquals(nistCbcCipher, ciphertext.substring(0, 32))
    }

    @Test
    fun `aes-128-ecb matches the NIST vector`() {
        val ciphertext = util.aesEncrypt("aes-128-ecb", nistKey, "", nistPlain)
        assertEquals(64, ciphertext.length)
        assertEquals(nistEcbCipher, ciphertext.substring(0, 32))
    }

    @Test
    fun `a full-block plaintext still gets a whole padding block, like Node`() {
        // Node's createCipheriv defaults to PKCS7, which always pads; a source decrypting on the other side
        // relies on that. Returning 16 bytes here would break it.
        assertEquals(64, util.aesEncrypt("aes-128-cbc", nistKey, nistIv, nistPlain).length)
        assertEquals(64, util.aesEncrypt("aes-128-cbc", nistKey, nistIv, nistPlain).length)
    }

    @Test
    fun `aes round-trips through an independent decryptor`() {
        val plaintext = "歌词 lyric payload \u0000\u0001\u00ff".toByteArray(Charsets.UTF_8)
        val ciphertext = util.aesEncrypt("aes-128-cbc", nistKey, nistIv, hexOf(plaintext))
        val decrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(hex(nistKey), "AES"), IvParameterSpec(hex(nistIv)))
            doFinal(hex(ciphertext))
        }
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `aes-256-cbc works with a 32-byte key`() {
        val key = "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4"
        val iv = "000102030405060708090a0b0c0d0e0f"
        val plain = util.utf8ToHex("hello aes-256")
        val ciphertext = util.aesEncrypt("aes-256-cbc", key, iv, plain)
        val decrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(hex(key), "AES"), IvParameterSpec(hex(iv)))
            doFinal(hex(ciphertext))
        }
        assertEquals("hello aes-256", String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun `an unsupported aes mode is a named error, not silent garbage`() {
        val error = runCatching { util.aesEncrypt("aes-128-ctr", nistKey, nistIv, nistPlain) }.exceptionOrNull()
        assertTrue(error is LxHostUtilities.LxUnsupportedCryptoException)
        assertTrue(error!!.message!!.contains("aes-128-ctr"))
        assertTrue(error.message!!.contains("aes-128-cbc"))
    }

    @Test
    fun `a wrong key size is refused with both sizes named`() {
        val error = runCatching { util.aesEncrypt("aes-128-cbc", "00112233", nistIv, nistPlain) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("16-byte key"))
    }

    @Test
    fun `cbc without a 16-byte iv is refused`() {
        val error = runCatching { util.aesEncrypt("aes-128-cbc", nistKey, "0011", nistPlain) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("iv"))
    }

    @Test
    fun `ecb ignores the iv entirely`() {
        assertEquals(
            util.aesEncrypt("aes-128-ecb", nistKey, "", nistPlain),
            util.aesEncrypt("aes-128-ecb", nistKey, nistIv, nistPlain),
        )
    }

    // ------------------------------------------------------------------ RSA

    @Test
    fun `rsa is no-padding over zero-left-padded input, like LX`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val pem = publicPem(keyPair.public as RSAPublicKey)
        val data = ByteArray(16) { (it + 1).toByte() }

        val ciphertext = util.rsaEncrypt(hexOf(data), pem)
        // A 1024-bit key means a 128-byte block, which LX hardcodes (`Buffer.alloc(128 - buffer.length)`).
        assertEquals(128 * 2, ciphertext.length)

        val decrypted = Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, keyPair.private)
            doFinal(hexBytes(ciphertext))
        }
        // Zero-left-padded: the payload sits at the tail of the block.
        assertArrayEquals(data, decrypted.copyOfRange(decrypted.size - data.size, decrypted.size))
        assertTrue(decrypted.copyOfRange(0, decrypted.size - data.size).all { it == 0.toByte() })
    }

    @Test
    fun `rsa pads to the key size rather than a hardcoded 128 bytes`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = publicPem(keyPair.public as RSAPublicKey)
        val data = "session key!!".toByteArray(Charsets.UTF_8) // 13 bytes

        val ciphertext = util.rsaEncrypt(hexOf(data), pem)
        assertEquals(2048 / 8 * 2, ciphertext.length)

        val decrypted = Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, keyPair.private)
            doFinal(hexBytes(ciphertext))
        }
        assertArrayEquals(data, decrypted.copyOfRange(decrypted.size - data.size, decrypted.size))
    }

    @Test
    fun `rsa input larger than the modulus is refused`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val pem = publicPem(keyPair.public as RSAPublicKey)
        val error = runCatching { util.rsaEncrypt("ab".repeat(200), pem) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("128"))
    }

    @Test
    fun `an unparseable pem is a named error`() {
        val error = runCatching { util.rsaEncrypt("00", "not a pem") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("rsaEncrypt"))
    }

    // ------------------------------------------------------------------ helpers

    private fun publicPem(key: RSAPublicKey): String {
        val base64 = Base64.getEncoder().encodeToString(key.encoded)
        return buildString {
            append("-----BEGIN PUBLIC KEY-----\n")
            base64.chunked(64).forEach { append(it).append('\n') }
            append("-----END PUBLIC KEY-----")
        }
    }

    private fun hex(value: String): ByteArray = hexBytes(value)

    private fun hexBytes(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
