package com.socksconnect.app

/**
 * Fake-IP 模式下的 DNS 本地代答：解析 53 端口 UDP 查询中的域名，
 * 不发起任何网络请求直接合成应答——
 * - A 查询：回假 IP（记录进 FakeIpTable，连接时还原域名）
 * - AAAA / HTTPS：回 NODATA，阻止应用拿到真实或 IPv6 地址绕过隧道
 * - 其余类型（MX/TXT/SRV 等）：返回 null 交由调用方回退 DoH 取真实答案
 */
internal object FakeDns {

    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val TYPE_HTTPS = 65
    private const val ANSWER_TTL = 60

    /** @return 合成的 DNS 应答报文；null 表示无法本地处理（调用方回退 DoH） */
    fun tryAnswer(query: ByteArray): ByteArray? {
        if (query.size < 17) return null // 头(12) + 至少一个标签 + QTYPE(2) + QCLASS(2)
        if (readU16(query, 4) != 1) return null // 仅处理单问题查询

        var off = 12
        val labels = ArrayList<String>(8)
        while (true) {
            if (off >= query.size) return null
            val len = query[off].toInt() and 0xFF
            if (len == 0) {
                off++
                break
            }
            if (len and 0xC0 != 0) return null // 问题段不应出现压缩指针，保守回退
            if (off + 1 + len > query.size) return null
            labels.add(String(query, off + 1, len, Charsets.US_ASCII))
            off += 1 + len
            if (labels.size > 16) return null
        }
        if (labels.isEmpty() || off + 4 > query.size) return null
        val qType = readU16(query, off)
        val questionEnd = off + 4
        val domain = labels.joinToString(".")

        return when (qType) {
            TYPE_A -> answerA(query, questionEnd, domain)
            TYPE_AAAA, TYPE_HTTPS -> answerNoData(query, questionEnd)
            else -> null
        }
    }

    /** A 应答：问题段原样回显 + 一条指向假 IP 的 A 记录 */
    private fun answerA(query: ByteArray, questionEnd: Int, domain: String): ByteArray {
        val ipBytes = FakeIpTable.allocate(domain)
            .split('.').map { it.toInt().toByte() }.toByteArray()
        val resp = ByteArray(questionEnd + 16)
        query.copyInto(resp, 0, 0, 4) // 保留事务 ID
        resp[2] = 0x81.toByte() // QR=1, RD=1
        resp[3] = 0x80.toByte() // RA=1
        resp[4] = 0; resp[5] = 1 // QDCOUNT=1
        resp[6] = 0; resp[7] = 1 // ANCOUNT=1
        query.copyInto(resp, 12, 12, questionEnd)
        var o = questionEnd
        resp[o++] = 0xC0.toByte(); resp[o++] = 0x0C // NAME 指针指向问题段
        resp[o++] = 0; resp[o++] = TYPE_A.toByte()
        resp[o++] = 0; resp[o++] = 1 // IN
        resp[o++] = 0; resp[o++] = 0; resp[o++] = 0; resp[o++] = ANSWER_TTL.toByte()
        resp[o++] = 0; resp[o++] = 4 // RDLENGTH
        ipBytes.copyInto(resp, o)
        return resp
    }

    /** NODATA 应答：NOERROR 且无回答记录，客户端会回落到 A 查询 */
    private fun answerNoData(query: ByteArray, questionEnd: Int): ByteArray {
        val resp = ByteArray(questionEnd)
        query.copyInto(resp, 0, 0, 4)
        resp[2] = 0x81.toByte()
        resp[3] = 0x80.toByte()
        resp[4] = 0; resp[5] = 1 // QDCOUNT=1
        resp[6] = 0; resp[7] = 0 // ANCOUNT=0
        query.copyInto(resp, 12, 12, questionEnd)
        return resp
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
}
