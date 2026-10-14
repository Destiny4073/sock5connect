package com.socksconnect.app

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 SOCKS5 服务器（127.0.0.1），作为 hev-socks5-tunnel 的上游：
 * - CONNECT：转发到用户配置的 HTTP/HTTPS/SOCKS5 代理
 * - UDP ASSOCIATE：仅转发目标端口 53 的 DNS 查询（经上游 DoH 解析后回包），其余 UDP 丢弃
 */
internal class LocalSocksServer(
    private val port: Int,
    private val proto: String,
    private val upstreamHost: String,
    private val upstreamPort: Int
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val connections = Executors.newFixedThreadPool(MAX_SESSIONS)
    private val dns = Executors.newFixedThreadPool(DNS_THREADS)
    private val sessionLimit = Semaphore(MAX_SESSIONS)
    private val tcpSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val udpSockets = ConcurrentHashMap.newKeySet<DatagramSocket>()
    private val doh = DohResolver(proto, upstreamHost, upstreamPort)

    fun start() {
        check(running.compareAndSet(false, true)) { "server already running" }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 128)
        serverSocket = ss
        Thread({ acceptLoop(ss) }, "SocksServer-Accept").start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        tcpSockets.forEach { runCatching { it.close() } }
        udpSockets.forEach { runCatching { it.close() } }
        connections.shutdownNow()
        dns.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val client = try {
                ss.accept()
            } catch (_: IOException) {
                break
            }
            connections.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        sessionLimit.acquire()
        try {
            runCatching { client.tcpNoDelay = true }
            val input = DataInputStream(BufferedInputStream(client.getInputStream(), 4096))
            if (input.readUnsignedByte() != 5) return
            val methods = ByteArray(input.readUnsignedByte())
            input.readFully(methods)
            val out = client.getOutputStream()
            if (!methods.contains(0x00.toByte())) {
                out.write(byteArrayOf(0x05, 0xFF.toByte()))
                return
            }
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()
            if (input.readUnsignedByte() != 5) return
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            val target = readAddress(input)
            when (cmd) {
                CMD_CONNECT -> handleConnect(client, out, target)
                CMD_UDP_ASSOCIATE -> handleUdpAssociate(client, out, input)
                else -> out.write(replyBytes(0x07, "0.0.0.0", 0))
            }
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
            sessionLimit.release()
        }
    }

    private fun handleConnect(client: Socket, out: OutputStream, target: Pair<String, Int>) {
        val upstream = try {
            Upstreams.connectThrough(proto, upstreamHost, upstreamPort, target.first, target.second)
        } catch (e: Exception) {
            VpnConnectorService.lastError = Upstreams.describeError(e)
            runCatching { out.write(replyBytes(0x04, "0.0.0.0", 0)) }
            return
        }
        tcpSockets.add(upstream)
        VpnConnectorService.lastError = null
        runCatching {
            out.write(replyBytes(0x00, "0.0.0.0", 0))
            out.flush()
        }
        // 上行在本线程阻塞转发（handle 的 finally 会等它结束），
        // 下行交给线程池；任一方向结束都会关闭两端 socket
        connections.execute { pump(upstream, client) }
        pump(client, upstream)
    }

    private fun handleUdpAssociate(client: Socket, out: OutputStream, input: InputStream) {
        val udp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        udpSockets.add(udp)
        runCatching {
            out.write(replyBytes(0x00, "127.0.0.1", udp.localPort))
            out.flush()
        }
        connections.execute {
            val buf = ByteArray(MAX_UDP_PACKET)
            try {
                while (running.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    udp.receive(packet)
                    if ((buf[2].toInt() and 0xFF) != 0) continue // 不支持分片
                    // 立即快照整份数据报，避免与 DoH 应答任务竞争接收缓冲区
                    val datagram = buf.copyOf(packet.length)
                    val header = parseUdpHeader(datagram, datagram.size)
                    if (header.dstPort != DNS_PORT) continue // 仅转发 DNS
                    val payload = datagram.copyOfRange(header.payloadStart, datagram.size)
                    val addr = packet.address
                    val port = packet.port
                    dns.execute {
                        val resp = doh.query(payload) ?: return@execute
                        val respPacket = datagram.copyOf(header.payloadStart) + resp
                        runCatching {
                            udp.send(DatagramPacket(respPacket, respPacket.size, addr, port))
                        }
                    }
                }
            } catch (_: IOException) {
            } finally {
                runCatching { udp.close() }
            }
        }
        // 控制连接关闭（或 stop 关闭 client）则结束 UDP 会话
        try {
            while (input.read() >= 0) {
                // 保活，等待对端关闭
            }
        } catch (_: IOException) {
        }
        runCatching { udp.close() }
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
            }
        } catch (_: IOException) {
        } finally {
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    /** @return 目标 (host, port)，host 为 IP 或域名文本 */
    private fun readAddress(input: DataInputStream): Pair<String, Int> {
        return when (val atyp = input.readUnsignedByte()) {
            0x01 -> {
                val b = ByteArray(4)
                input.readFully(b)
                InetAddress.getByAddress(b).hostAddress.orEmpty() to readPort(input)
            }
            0x03 -> {
                val name = ByteArray(input.readUnsignedByte())
                input.readFully(name)
                String(name, Charsets.US_ASCII) to readPort(input)
            }
            0x04 -> {
                val b = ByteArray(16)
                input.readFully(b)
                InetAddress.getByAddress(b).hostAddress.orEmpty() to readPort(input)
            }
            else -> throw IOException("不支持的地址类型 $atyp")
        }
    }

    private fun readPort(input: DataInputStream): Int =
        (input.readUnsignedByte() shl 8) or input.readUnsignedByte()

    private class UdpHeader(val dstPort: Int, val payloadStart: Int)

    private fun parseUdpHeader(buf: ByteArray, length: Int): UdpHeader {
        if (length < 4) throw IOException("UDP 报文过短")
        var offset = 4 // RSV(2) + FRAG(1) + ATYP(1)
        when (buf[3].toInt() and 0xFF) {
            0x01 -> offset += 4
            0x04 -> offset += 16
            0x03 -> offset += 1 + (buf[4].toInt() and 0xFF)
            else -> throw IOException("UDP 地址类型错误")
        }
        val dstPort = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
        offset += 2
        if (offset > length) throw IOException("UDP 报文过短")
        return UdpHeader(dstPort, offset)
    }

    private fun replyBytes(rep: Int, bndAddr: String, bndPort: Int): ByteArray {
        val addr = runCatching { InetAddress.getByName(bndAddr).address }.getOrDefault(
            byteArrayOf(0, 0, 0, 0)
        )
        val reply = ByteArray(6 + addr.size)
        reply[0] = 0x05
        reply[1] = rep.toByte()
        reply[3] = if (addr.size == 16) 0x04 else 0x01
        addr.copyInto(reply, 4)
        reply[4 + addr.size] = ((bndPort shr 8) and 0xFF).toByte()
        reply[5 + addr.size] = (bndPort and 0xFF).toByte()
        return reply
    }

    companion object {
        private const val CMD_CONNECT = 0x01
        private const val CMD_UDP_ASSOCIATE = 0x03
        private const val DNS_PORT = 53
        private const val MAX_SESSIONS = 128
        private const val DNS_THREADS = 4
        private const val BUFFER_SIZE = 16 * 1024
        private const val MAX_UDP_PACKET = 64 * 1024
    }
}
