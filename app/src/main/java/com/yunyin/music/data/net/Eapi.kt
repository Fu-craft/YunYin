package com.yunyin.music.data.net

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * NetEase's **eapi** request format: what the official mobile client sends.
 *
 * ## Why this exists in a client that already has an API server
 *
 * Listen-together is not served by the API proxy this app otherwise uses. Measured against the proxy:
 * `/listentogether/sync/list/command/report` and `/listentogether/heartbeat` **do not exist** (404), and the
 * proxy answers every read of the room with an empty command — which is why the feature could never sync, no
 * matter how the sync logic was written. The route names the proxy does expose (`play/command`,
 * `heatbeat`) are its own; the official API's are `play/command/report` and `heartbeat`.
 *
 * Measured against NetEase itself, with this format: all nine routes exist, and the read path
 * (`/api/listen/together/sync/playlist/get`) is among them. A public search through the same encoder returns
 * `code: 200` with real results, which is what proves the format is correct rather than merely accepted.
 *
 * ## The algorithm
 *
 * Copied from the reference implementation (qplayer's NetEase plugin), and only the parts it needs —
 * eapi requires no RSA (that is weapi):
 *
 * ```
 * body   = JSON of (data + e_r:false + header)
 * digest = MD5("nobody" + path + "use" + body + "md5forencrypt")
 * params = AES-ECB-PKCS5(path + "-36cd479b6b5-" + body + "-36cd479b6b5-" + digest, key).toHex().uppercase()
 * POST   host + "/eapi/" + path.removePrefix("/api/")   body: params=<HEX>
 * ```
 *
 * Only [javax.crypto] and `MessageDigest` are used, both of which are part of Android — so this costs no
 * dependency.
 */
object Eapi {

    private const val KEY = "e82ckenh8dichen8"

    /** The separator NetEase's own signer uses between the path, the body and the digest. */
    private const val SEP = "-36cd479b6b5-"

    /** The mobile endpoint. The web host does not serve these routes. */
    const val HOST = "https://interfacepc.music.163.com"

    /** A mobile client's User-Agent; the eapi host is stricter than the web one about this. */
    const val UA = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)"

    /** The full URL for an eapi `path` (which starts with `/api/`). */
    fun url(path: String): String = HOST + "/eapi/" + path.removePrefix("/api/")

    /**
     * The device header the official client signs into the body **and mirrors as the Cookie**.
     *
     * The identity fields are taken from the stored login cookie, so a signed-in session is carried: the
     * room is scoped to the account, and without `MUSIC_U` every route answers `301 需要登录`.
     */
    fun header(cookie: String): Map<String, String> {
        val jar = parseCookie(cookie)
        val header = linkedMapOf(
            "osver" to "",
            // A stable device id per install; a changing fingerprint is itself treated as suspicious.
            "deviceId" to (jar["deviceId"] ?: "yunyin"),
            "os" to "ios",
            "appver" to "9.0.90",
            "versioncode" to "140",
            "mobilename" to "",
            "buildver" to (System.currentTimeMillis() / 1000).toString(),
            "resolution" to "1920x1080",
            "__csrf" to (jar["__csrf"] ?: ""),
            "channel" to "",
        )
        jar["MUSIC_U"]?.takeIf { it.isNotBlank() }?.let { header["MUSIC_U"] = it }
        jar["MUSIC_A"]?.takeIf { it.isNotBlank() }?.let { header["MUSIC_A"] = it }
        jar["NMTID"]?.takeIf { it.isNotBlank() }?.let { header["NMTID"] = it }
        return header
    }

    /** The Cookie line for a request: the device header itself, as the official client sends it. */
    fun cookieOf(header: Map<String, String>): String =
        header.entries.filter { it.value.isNotBlank() }
            .joinToString("; ") { "${it.key}=${it.value}" }

    /** The encrypted `params` value for a request. */
    fun params(path: String, data: Map<String, Any?>): String {
        val header = headerForBody
        val payload = JSONObject()
        data.forEach { (key, value) -> payload.put(key, value ?: JSONObject.NULL) }
        payload.put("e_r", false)
        payload.put("header", JSONObject(header as Map<*, *>))
        val body = payload.toString()
        val digest = md5("nobody" + path + "use" + body + "md5forencrypt")
        return encryptHex(path + SEP + body + SEP + digest)
    }

    /**
     * The header used inside the body of the *next* request.
     *
     * Set per call rather than passed through every signature, because the body's header and the Cookie have
     * to be the same object: NetEase compares them, and a mismatch is rejected. A small piece of mutable
     * state, confined to the request that sets it.
     */
    private var headerForBody: Map<String, String> = emptyMap()

    /** Builds both halves of a request: the encrypted params and the matching Cookie. */
    fun sign(path: String, data: Map<String, Any?>, cookie: String): Pair<String, String> {
        val header = header(cookie)
        headerForBody = header
        val params = params(path, data)
        return params to cookieOf(header)
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun encryptHex(plain: String): String {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        // PKCS#5/PKCS#7 padding, which is what the reference's AES/ECB/PKCS5Padding produces.
        val pad = 16 - (bytes.size % 16)
        val padded = bytes + ByteArray(pad) { pad.toByte() }
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES"))
        return cipher.doFinal(padded).joinToString("") { "%02X".format(it) }
    }

    /** Splits a stored cookie header into its name/value pairs. */
    private fun parseCookie(cookie: String): Map<String, String> = cookie.split(";")
        .mapNotNull { part ->
            val trimmed = part.trim()
            val at = trimmed.indexOf('=')
            if (at <= 0) null else trimmed.substring(0, at) to trimmed.substring(at + 1)
        }
        .toMap()
}
