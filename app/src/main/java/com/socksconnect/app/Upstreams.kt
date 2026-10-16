package com.socksconnect.app

import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** 连接用户配置的上游代理（HTTP / HTTPS / SOCKS5），并建立到目标的 CONNECT 隧道 */
internal object Upstreams {

    private const val CONNECT_TIMEOUT_MS = 10_000

    /** 连接到上游代理并完成协议层握手 */
    fun connectUpstream(proto: String, host: String, port: Int): Socket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            raw.tcpNoDelay = true
            raw.keepAlive = true
            raw.soTimeout = CONNECT_TIMEOUT_MS
            val result = when (proto) {
                "HTTPS" -> {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val tls = factory.createSocket(raw, host, port, true) as SSLSocket
                    tls.startHandshake()
                    tls.soTimeout = 0
                    tls
                }
                "SOCKS5" -> {
                    socksGreeting(raw)
                    raw.soTimeout = 0
                    raw
                }
                else -> {
                    raw.soTimeout = 0
                    raw
                }
            }
            return result
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    /** 连接到上游代理并请求转发到 target，成功后返回可双向转发的 socket */
    fun connectThrough(
        proto: String, host: String, port: Int,
        targetHost: String, targetPort: Int
    ): Socket {
        val s = connectUpstream(proto, host, port)
        try {
            val out = s.getOutputStream()
            val input = s.getInputStream()
            when (proto) {
                "SOCKS5" -> {
                    val addr = encodeSocksAddress(targetHost, targetPort)
                    val req = ByteArray(3 + addr.size)
                    req[0] = 0x05
                    req[1] = 0x01 // CONNECT
                    req[2] = 0x00
                    addr.copyInto(req, 3)
                    out.write(req)
                    out.flush()
                    val head = ByteArray(4)
                    readFully(input, head)
                    if (head[1] != 0.toByte()) {
                        throw IOException(getString(R.string.err_socks_reply, head[1].toInt() and 0xFF))
                    }
                    // 丢弃 BND.ADDR + BND.PORT
                    val skip = when (head[3].toInt()) {
                        1 -> 4 + 2
                        4 -> 16 + 2
                        3 -> input.read() + 2
                        else -> throw IOException(getString(R.string.err_not_socks5))
                    }
                    val rest = ByteArray(skip)
                    readFully(input, rest)
                }
                else -> {
                    val req = "CONNECT $targetHost:$targetPort HTTP/1.1\r\n" +
                        "Host: $targetHost:$targetPort\r\n\r\n"
                    out.write(req.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    val headers = readHeaderBlock(input)
                    val status = headers.substringBefore("\r\n").split(" ").getOrNull(1).orEmpty()
                    if (!status.startsWith("2")) {
                        throw IOException(getString(R.string.err_http_connect, status))
                    }
                }
            }
            s.soTimeout = 0
            return s
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }

    /** SOCKS5 免认证（NO-AUTH）方式协商 */
    private fun socksGreeting(s: Socket) {
        val input = s.getInputStream()
        val output = s.getOutputStream()
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val head = ByteArray(2)
        readFully(input, head)
        if (head[0] != 0x05.toByte()) throw IOException(getString(R.string.err_not_socks5))
        when (head[1]) {
            0x00.toByte() -> Unit
            0xFF.toByte(), 0x02.toByte() ->
                throw IOException(getString(R.string.err_socks_auth))
            else -> throw IOException(getString(R.string.err_not_socks5))
        }
    }

    // 仅字面量 IP 才做地址解析；域名一律按 ATYP=0x03 原样透传，
    // 避免对域名调用 InetAddress.getByName 触发本地解析（fake-IP 下会解析回假 IP）
    private val IPV4_LITERAL = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")

    /** 目标地址编码为 SOCKS5 请求中的 ATYP + ADDR + PORT */
    internal fun encodeSocksAddress(host: String, port: Int): ByteArray {
        val tail = byteArrayOf(
            ((port shr 8) and 0xFF).toByte(),
            (port and 0xFF).toByte()
        )
        if (IPV4_LITERAL.matches(host)) {
            val ip = runCatching { InetAddress.getByName(host) }.getOrNull()
            if (ip is Inet4Address) return byteArrayOf(0x01) + ip.address + tail
        } else if (host.contains(':')) {
            val ip = runCatching { InetAddress.getByName(host) }.getOrNull()
            if (ip is Inet6Address) return byteArrayOf(0x04) + ip.address + tail
        }
        val name = host.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x03, name.size.toByte()) + name + tail
    }

    /** 读取到 "\r\n\r\n" 为止的头部块（含结尾空行） */
    private fun readHeaderBlock(input: InputStream): String {
        val buf = StringBuilder()
        var count = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException(getString(R.string.err_io, "连接被上游关闭"))
            buf.append(b.toChar())
            if (buf.endsWith("\r\n\r\n")) break
            if (++count > 8192) throw IOException(getString(R.string.err_io, "响应头过大"))
        }
        return buf.toString()
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException(getString(R.string.err_io, "连接被上游关闭"))
            off += n
        }
    }

    private fun getString(resId: Int, arg: Any? = null): String =
        if (arg == null) AppContext.getString(resId) else AppContext.getString(resId, arg)

    internal fun describeError(e: Exception): String = when (e) {
        is UnknownHostException -> getString(R.string.err_unknown_host)
        is SocketTimeoutException -> getString(R.string.err_timeout)
        is ConnectException -> getString(R.string.err_refused)
        is SSLException -> getString(R.string.err_tls)
        else -> getString(R.string.err_io, e.message ?: e.javaClass.simpleName)
    }
}
