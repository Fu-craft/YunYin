package com.yunyin.music.data.lxsource

import java.net.InetAddress
import java.net.URI

/**
 * The URL policy for 从链接导入：what this app is willing to download.
 *
 * ## Why a policy object and not checks inside the fetcher
 *
 * The URL arrives from the user and the response is **executed as JavaScript**, so the gate has to be explicit,
 * testable and applied in several places: the initial URL, every redirect hop, and the resolved addresses. Keeping
 * it pure means the scheme/credential/private-address rules are unit-tested without a network.
 *
 * ## The SSRF guard, honestly
 *
 * A source URL could be aimed at the phone's own services (`127.0.0.1`), the LAN (`192.168.*`), or a metadata
 * endpoint. This policy rejects:
 *  - any scheme other than `http`/`https`;
 *  - URLs carrying credentials (`user:pass@`);
 *  - hosts that are IP literals in loopback/private/link-local/CGNAT/unique-local ranges, plus `localhost`;
 *  - hostnames that **resolve** to any such address ([isBlockedAddress]); this is checked before the request and
 *    again for every redirect.
 *
 * Limitation: DNS is resolved for the check and then resolved again by the HTTP client, so a hostile DNS server
 * could in principle rebind between the two (DNS rebinding). Android's cleartext/network stack offers no trust-zone
 * pinning here, and no proxy is configured; the only way to close that window fully is to dial the address returned
 * by the check, which OkHttp does not expose. The practical exposure is small (the response is only fed to an
 * in-process JS engine that has no filesystem/network APIs except `lx.request`), and refusing every hostname would
 * make the feature useless. This is stated here rather than left implicit.
 */
object LxSourceUrlPolicy {

    /** Longer URLs are refused instead of clipped: a clipped URL is a different URL. */
    const val MAX_URL_LENGTH = 2_048

    sealed interface Verdict {
        data class Allowed(val url: String, val host: String) : Verdict

        data class Rejected(val message: String) : Verdict
    }

    fun validate(raw: String): Verdict {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Verdict.Rejected("地址为空")
        if (trimmed.length > MAX_URL_LENGTH) return Verdict.Rejected("地址格式不对（地址太长）")
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return Verdict.Rejected("地址格式不对")
        }
        val scheme = uri.scheme?.lowercase()
            ?: return Verdict.Rejected("地址格式不对（需要以 http:// 或 https:// 开头）")
        if (scheme != "http" && scheme != "https") {
            return Verdict.Rejected("只支持 http(s) 地址（收到：$scheme）")
        }
        if (uri.userInfo != null) return Verdict.Rejected("地址不能包含账号密码")
        val host = uri.host?.trim()?.lowercase()
        if (host.isNullOrEmpty()) return Verdict.Rejected("地址格式不对（缺少主机名）")
        if (isBlockedHost(host)) return Verdict.Rejected("不允许内网地址")
        return Verdict.Allowed(url = uri.toString(), host = host)
    }

    /**
     * True for hosts that must not be fetched: `localhost`, a loopback/private/link-local IP literal, or a name
     * ending in `.localhost`.
     */
    fun isBlockedHost(host: String): Boolean {
        val normalized = host.removePrefix("[").removeSuffix("]")
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
        parseIpv4(normalized)?.let { return isBlockedIpv4(it) }
        if (normalized.contains(':')) return isBlockedIpv6(normalized)
        return false
    }

    /** A resolved address is blocked when it is loopback, site-local, link-local, CGNAT or unique-local. */
    fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        if (address.isAnyLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        if (address.address.size == 4) {
            val parsed = bytes.map { it.toInt() and 0xff }
            return isBlockedIpv4(parsed)
        }
        // IPv6: unique-local fc00::/7, link-local fe80::/10, IPv4-mapped 127/10 etc.
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        when {
            (first and 0xfe) == 0xfc -> return true // fc00::/7 unique-local
            first == 0xfe && (second and 0xc0) == 0x80 -> return true // fe80::/10 link-local
        }
        if (address.isLoopbackAddress) return true
        // ::ffff:127.0.0.1 style mapping (Inet4Address is handled above, but be explicit).
        if (bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        ) {
            return isBlockedIpv4((12 until 16).map { bytes[it].toInt() and 0xff })
        }
        return false
    }

    private fun isBlockedIpv4(parts: List<Int>): Boolean {
        if (parts.size != 4) return false
        val (a, b) = parts
        return when {
            a == 0 -> true // 0.0.0.0/8 "this network"
            a == 10 -> true // 10/8 private
            a == 127 -> true // loopback
            a == 169 && b == 254 -> true // link-local
            a == 172 && b in 16..31 -> true // 172.16/12 private
            a == 192 && b == 168 -> true // 192.168/16 private
            a == 100 && b in 64..127 -> true // CGNAT 100.64/10
            a == 198 && b in 18..19 -> true // benchmarking 198.18/15
            a >= 224 -> true // multicast + reserved
            else -> false
        }
    }

    private fun parseIpv4(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val values = parts.map { it.toIntOrNull() ?: return null }
        if (values.any { it !in 0..255 }) return null
        return values
    }

    private fun isBlockedIpv6(host: String): Boolean {
        val normalized = host.substringBefore('%').lowercase()
        if (normalized == "::" || normalized == "::1") return true
        if (normalized.startsWith("fc") || normalized.startsWith("fd")) return true
        if (normalized.startsWith("fe8") || normalized.startsWith("fe9") ||
            normalized.startsWith("fea") || normalized.startsWith("feb")
        ) {
            return true
        }
        // IPv4-mapped forms such as ::ffff:127.0.0.1.
        val mapped = normalized.substringAfterLast(':').takeIf { it.contains('.') }
        if (mapped != null) parseIpv4(mapped)?.let { return isBlockedIpv4(it) }
        return false
    }

    /** URL for the diagnostics log: query dropped, because it can carry tokens. */
    fun redact(url: String): String {
        val withoutQuery = url.trim().substringBefore('?').substringBefore('#')
        return if (withoutQuery.length <= 300) withoutQuery else withoutQuery.take(300) + "…"
    }
}
