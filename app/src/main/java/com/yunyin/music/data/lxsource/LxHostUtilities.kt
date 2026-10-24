// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.lxsource

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The JVM half of `lx.utils`: the byte-level helpers a script calls through the bridge.
 *
 * ## Why this is separate from the engine
 *
 * Everything here is plain JVM code — no QuickJS, no Android, no coroutines — which is deliberate: these are
 * the functions whose *encodings* have to be exactly right (a hex/base64 mix-up is a silent wrong answer, not
 * an exception), so they are the part that can be unit-tested on the JVM without a device or a native library.
 * [LxJsRuntime] only wires them to the engine.
 *
 * ## Limits
 *
 * Every size that arrives from JavaScript is attacker-or-bug controlled, and a custom source is code the user
 * imported from somewhere: a zlib bomb or a multi-megabyte random request would take the app down. Hence the
 * explicit caps, which throw rather than truncate — a truncated decompression is corrupt data reported as
 * success, which is the worse failure.
 */
internal object LxHostUtilities {

    /** A script asking for more randomness than this is a bug, not a use case. */
    const val MAX_RANDOM_BYTES = 1024

    /** Decompressed output cap; a 100-byte Deflate stream can expand to gigabytes. */
    const val MAX_INFLATE_BYTES = 8 * 1024 * 1024

    private val random = SecureRandom()

    fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).toLxHex()

    /** A hex string, as LX's `randomBytes(size)` is documented to produce a *string*. */
    fun randomHex(size: Int): String {
        require(size in 0..MAX_RANDOM_BYTES) { "randomBytes: size out of range (0..$MAX_RANDOM_BYTES)" }
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes.toLxHex()
    }

    fun utf8ToHex(text: String): String = text.toByteArray(Charsets.UTF_8).toLxHex()

    fun hexToUtf8(hex: String): String = hexToBytes(hex).toString(Charsets.UTF_8)

    fun base64ToHex(base64: String): String = Base64.getDecoder().decode(base64).toLxHex()

    fun hexToBase64(hex: String): String = Base64.getEncoder().encodeToString(hexToBytes(hex))

    /**
     * Inflate, accepting both of the formats LX sources actually carry.
     *
     * A response that was gzipped starts `1f 8b`; anything else is tried as a zlib stream and then as a raw
     * Deflate stream, because both appear in the wild and the header alone cannot always tell them apart. The
     * first attempt that yields bytes wins; the last error is rethrown when none does.
     */
    fun inflate(hex: String): String {
        val input = hexToBytes(hex)
        if (input.size >= 2 && input[0] == 0x1f.toByte() && input[1] == 0x8b.toByte()) {
            return inflateStream { GZIPInputStream(ByteArrayInputStream(input)) }
        }
        var last: Exception? = null
        for (nowrap in listOf(false, true)) {
            try {
                return inflateStream { RawInflaterStream(ByteArrayInputStream(input), nowrap) }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("zlib.inflate failed: ${last?.message ?: "unrecognised data"}", last)
    }

    /** Deflate with the zlib wrapper, which is what `lx.utils.zlib.deflate` must produce for `inflate` to undo. */
    fun deflate(hex: String): String {
        val deflater = Deflater()
        return try {
            deflater.setInput(hexToBytes(hex))
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                if (written <= 0) break
                output.write(buffer, 0, written)
                require(output.size() <= MAX_INFLATE_BYTES) { "zlib.deflate: output too large" }
            }
            output.toByteArray().toLxHex()
        } finally {
            deflater.end()
        }
    }

    private inline fun inflateStream(open: () -> java.io.InputStream): String {
        val stream = open()
        return try {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                require(output.size() <= MAX_INFLATE_BYTES) { "zlib.inflate: output too large" }
            }
            output.toByteArray().toLxHex()
        } finally {
            runCatching { stream.close() }
        }
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "invalid hex string length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val high = Character.digit(clean[i * 2], 16)
            val low = Character.digit(clean[i * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "invalid hex character" }
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    internal fun ByteArray.toLxHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xff
            if (value < 0x10) out.append('0')
            out.append(value.toString(16))
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- lx.utils.crypto

    /**
     * AES-128/192/256 in CBC or ECB mode, PKCS#7 padded — i.e. what Node's `createCipheriv(mode, key, iv)`
     * does by default, which is exactly how LX's own host implements `crypto.aesEncrypt`
     * (`src/main/modules/userApi/renderer/preload.js`: `createCipheriv(mode, key, iv)` and `cipher.final()`).
     *
     * The mobile doc narrows the supported set to `aes-128-cbc` / `aes-128-ecb`; the wider key sizes are a
     * superset that costs nothing because they are the same JCE transformation with a longer key. Anything
     * outside CBC/ECB throws a named error instead of silently producing non-Node output.
     *
     * @return the ciphertext as hex (the JS side turns it back into a Buffer).
     */
    fun aesEncrypt(mode: String, keyHex: String, ivHex: String, dataHex: String): String {
        val normalized = mode.trim().lowercase()
        val spec = AES_MODES[normalized]
            ?: throw LxUnsupportedCryptoException(
                "lx.utils.crypto.aesEncrypt: unsupported algorithm '$mode' " +
                    "(supported: ${AES_MODES.keys.joinToString(", ")})",
            )
        val key = hexToBytes(keyHex)
        if (key.size != spec.keySizeBytes) {
            throw IllegalArgumentException(
                "lx.utils.crypto.aesEncrypt: $normalized needs a ${spec.keySizeBytes}-byte key, got ${key.size}",
            )
        }
        val cipher = Cipher.getInstance(spec.transformation)
        val parameterSpec = if (spec.needsIv) {
            val iv = hexToBytes(ivHex)
            if (iv.size != AES_BLOCK_BYTES) {
                throw IllegalArgumentException(
                    "lx.utils.crypto.aesEncrypt: $normalized needs a $AES_BLOCK_BYTES-byte iv, got ${iv.size}",
                )
            }
            IvParameterSpec(iv)
        } else {
            null
        }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), parameterSpec)
        return cipher.doFinal(hexToBytes(dataHex)).toLxHex()
    }

    /**
     * RSA public-key encryption, matching LX: **no padding**, over the input zero-left-padded to the key's
     * modulus length.
     *
     * LX hardcodes a 128-byte pad (`Buffer.alloc(128 - buffer.length)`) because its purpose is NetEase's
     * 1024-bit `encSecKey`; padding to the actual modulus instead is identical for a 1024-bit key and correct
     * for any other size. `Cipher("RSA/ECB/NoPadding")` is the JCE spelling of the same operation.
     *
     * Documented limits: SPKI PEM (`-----BEGIN PUBLIC KEY-----`) and PKCS#1 (`-----BEGIN RSA PUBLIC KEY-----`)
     * are accepted; PKCS#1 v1.5 / OAEP padding is **not** used and the key must be a public key.
     */
    fun rsaEncrypt(dataHex: String, publicKeyPem: String): String {
        val key = parseRsaPublicKey(publicKeyPem)
        val modulusBytes = (key.modulus.bitLength() + 7) / 8
        val data = hexToBytes(dataHex)
        if (data.size > modulusBytes) {
            throw IllegalArgumentException(
                "lx.utils.crypto.rsaEncrypt: input is ${data.size} bytes but the key holds $modulusBytes",
            )
        }
        val padded = if (data.size == modulusBytes) data else ByteArray(modulusBytes - data.size) + data
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(padded).toLxHex()
    }

    private fun parseRsaPublicKey(pem: String): RSAPublicKey {
        val body = pem
            .replace(Regex("-----BEGIN [A-Z ]+-----"), "")
            .replace(Regex("-----END [A-Z ]+-----"), "")
            .replace(Regex("\\s"), "")
        if (body.isEmpty()) throw IllegalArgumentException("lx.utils.crypto.rsaEncrypt: empty public key")
        val der = try {
            Base64.getDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("lx.utils.crypto.rsaEncrypt: public key is not valid base64", e)
        }
        val keyFactory = KeyFactory.getInstance("RSA")
        // SPKI ("BEGIN PUBLIC KEY") is what LX sources carry; PKCS#1 ("BEGIN RSA PUBLIC KEY") is accepted too
        // because Node's publicEncrypt accepts it, so a source that worked on desktop keeps working here.
        runCatching { keyFactory.generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey }
            .getOrNull()
            ?.let { return it }
        return parsePkcs1PublicKey(der, keyFactory)
    }

    /** Minimal DER reader for PKCS#1 `RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }`. */
    private fun parsePkcs1PublicKey(der: ByteArray, keyFactory: KeyFactory): RSAPublicKey {
        try {
            var index = 0
            fun readLength(): Int {
                var length = der[index++].toInt() and 0xff
                if (length and 0x80 != 0) {
                    val count = length and 0x7f
                    length = 0
                    repeat(count) { length = (length shl 8) or (der[index++].toInt() and 0xff) }
                }
                return length
            }

            if (der[index++] != 0x30.toByte()) throw IllegalArgumentException("not a DER sequence")
            readLength()
            fun readInteger(): java.math.BigInteger {
                if (der[index++] != 0x02.toByte()) throw IllegalArgumentException("not a DER integer")
                val length = readLength()
                val bytes = der.copyOfRange(index, index + length)
                index += length
                return java.math.BigInteger(1, bytes)
            }
            val modulus = readInteger()
            val exponent = readInteger()
            return KeyFactory.getInstance("RSA")
                .generatePublic(java.security.spec.RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
        } catch (e: Exception) {
            throw IllegalArgumentException("lx.utils.crypto.rsaEncrypt: unsupported public-key encoding", e)
        }
    }

    private class AesSpec(val transformation: String, val keySizeBytes: Int, val needsIv: Boolean)

    private val AES_MODES: Map<String, AesSpec> = buildMap {
        listOf(16, 24, 32).forEach { keySize ->
            val bits = keySize * 8
            put("aes-$bits-cbc", AesSpec("AES/CBC/PKCS5Padding", keySize, needsIv = true))
            put("aes-$bits-ecb", AesSpec("AES/ECB/PKCS5Padding", keySize, needsIv = false))
        }
    }

    private const val AES_BLOCK_BYTES = 16

    /** A named, user-reportable refusal: the script asked for an algorithm this host does not implement. */
    class LxUnsupportedCryptoException(message: String) : Exception(message)

    /** A private wrapper so the auto-detecting inflate can share the bounded-copy loop. */
    private class RawInflaterStream(
        private val source: ByteArrayInputStream,
        private val nowrap: Boolean,
    ) : java.io.InputStream() {

        private val inflater = Inflater(nowrap)
        private val single = ByteArray(1)

        override fun read(): Int = read(single, 0, 1).let { if (it <= 0) -1 else single[0].toInt() and 0xff }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                if (inflater.finished()) return -1
                if (inflater.needsInput()) {
                    val chunk = ByteArray(8 * 1024)
                    val read = source.read(chunk)
                    if (read <= 0) {
                        if (inflater.needsDictionary()) throw IllegalStateException("zlib stream needs a dictionary")
                        return -1
                    }
                    inflater.setInput(chunk, 0, read)
                }
                val written = inflater.inflate(b, off, len)
                if (written > 0) return written
                if (inflater.needsDictionary()) throw IllegalStateException("zlib stream needs a dictionary")
                if (inflater.finished()) return -1
            }
        }

        override fun close() {
            runCatching { inflater.end() }
            runCatching { source.close() }
        }
    }
}
