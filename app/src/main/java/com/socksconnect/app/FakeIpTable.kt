package com.socksconnect.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake-IP 映射表：为每个域名分配一个 198.18.0.0/15 段内的假 IP，
 * 并在应用携带假 IP 发起连接时还原出域名，使本地中转能按域名向上游转发。
 * 仅维护内存映射，不做任何真实 DNS 解析；真实解析由上游代理在出口完成。
 */
internal object FakeIpTable {

    // 可分配范围（避开网络地址 198.18.0.0 与 TUN 地址 198.18.0.1）
    private val POOL_START = 0xC6120002.toInt() // 198.18.0.2
    private val POOL_END = 0xC613FFFE.toInt() // 198.19.255.254
    private val POOL_SIZE = POOL_END - POOL_START + 1

    private const val ENTRY_TTL_MS = 10 * 60_000L

    private class Entry(val domain: String, @Volatile var expiresAt: Long)

    private val counter = AtomicInteger(0)
    private val byIp = ConcurrentHashMap<Int, Entry>()
    private val byDomain = ConcurrentHashMap<String, Int>()

    /** 清空全部映射（每次启动隧道时调用，避免复用上次会话的假 IP） */
    @Synchronized
    fun clear() {
        byIp.clear()
        byDomain.clear()
    }

    /** 为域名分配（或复用）假 IP，返回点分十进制文本 */
    @Synchronized
    fun allocate(domain: String): String {
        val now = System.currentTimeMillis()
        byDomain[domain]?.let { ip ->
            val entry = byIp[ip]
            if (entry != null) {
                entry.expiresAt = now + ENTRY_TTL_MS
                return intToIp(ip)
            }
            byDomain.remove(domain)
        }
        while (true) {
            val ip = POOL_START + Math.floorMod(counter.getAndIncrement(), POOL_SIZE)
            // 池回绕时可能撞上仍存活的旧条目：直接逐出（池足够大，正常使用撞不上）
            val old = byIp.remove(ip)
            if (old != null) byDomain.remove(old.domain)
            byIp[ip] = Entry(domain, now + ENTRY_TTL_MS)
            byDomain[domain] = ip
            return intToIp(ip)
        }
    }

    /** 查询假 IP 对应的域名；不是本表分配的 IP 或已过期返回 null */
    fun lookup(ipText: String): String? {
        val ip = ipToInt(ipText) ?: return null
        val entry = byIp[ip] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) return null
        return entry.domain
    }

    private fun intToIp(v: Int): String =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

    private fun ipToInt(text: String): Int? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n
        }
        return v
    }
}
