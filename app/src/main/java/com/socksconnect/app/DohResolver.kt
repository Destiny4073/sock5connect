package com.socksconnect.app

import android.util.Base64
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 通过上游代理建立到 1.1.1.1:443 的 TLS 隧道，以 DoH(RFC 8484) 转发 DNS 报文。
 * 之所以用 DoH 而不是 DNS-over-TCP:53：所有 HTTP/HTTPS/SOCKS5 代理都放行 443 端口。
 */
internal class DohResolver(
    private val proto: String,
    private val upstreamHost: String,
    private val upstreamPort: Int
) {
    private val cache = LinkedHashMap<String, Pair<Long, ByteArray>>()

    /** 返回原始 DNS 响应报文，失败返回 null（调用方静默丢弃，客户端会重试） */
    fun query(dnsPayload: ByteArray): ByteArray? {
        val key = Base64.encodeToString(dnsPayload, Base64.NO_WRAP)
        synchronized(cache) {
            cache[key]?.let { (at, resp) ->
                if (System.currentTimeMillis() - at < CACHE_TTL_MS) return resp
                cache.remove(key)
            }
        }
        val resp = try {
            doQuery(dnsPayload)
        } catch (_: Exception) {
            return null
        }
        synchronized(cache) {
            if (cache.size >= CACHE_MAX_ENTRIES) {
                val it = cache.keys.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
            cache[key] = System.currentTimeMillis() to resp
        }
        return resp
    }

    private fun doQuery(payload: ByteArray): ByteArray {
        val tunnel = Upstreams.connectThrough(proto, upstreamHost, upstreamPort, DOH_HOST, 443)
        val tls = try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            factory.createSocket(tunnel, DOH_HOST, 443, true) as SSLSocket
        } catch (e: Exception) {
            runCatching { tunnel.close() }
            throw e
        }
        try {
            tls.startHandshake()
            tls.soTimeout = DOH_TIMEOUT_MS
            val b64 = Base64.encodeToString(
                payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val req = "GET /dns-query?dns=$b64 HTTP/1.1\r\n" +
                "Host: $DOH_HOST\r\n" +
                "Accept: application/dns-message\r\n" +
                "Connection: close\r\n\r\n"
            tls.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
            tls.getOutputStream().flush()
            val input = BufferedInputStream(tls.getInputStream())
            val headers = readHeaders(input)
            val status = headers.substringBefore("\r\n").split(" ").getOrNull(1).orEmpty()
            if (!status.startsWith("2")) throw IOException("DoH $status")
            val len = headers.lineSequence()
                .firstOrNull { it.startsWith("Content-Length", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
            return if (len != null && len > 0) readFully(input, len) else input.readBytes()
        } finally {
            runCatching { tls.close() }
        }
    }

    private fun readHeaders(input: InputStream): String {
        val buf = StringBuilder()
        var count = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("DoH 连接中断")
            buf.append(b.toChar())
            if (buf.endsWith("\r\n\r\n")) break
            if (++count > 16384) throw IOException("DoH 响应头过大")
        }
        return buf.toString()
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buf = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = input.read(buf, off, size - off)
            if (n < 0) throw IOException("DoH 响应不完整")
            off += n
        }
        return buf
    }

    companion object {
        private const val DOH_HOST = "1.1.1.1"
        private const val DOH_TIMEOUT_MS = 10_000
        private const val CACHE_TTL_MS = 5 * 60_000L
        private const val CACHE_MAX_ENTRIES = 128
    }
}
