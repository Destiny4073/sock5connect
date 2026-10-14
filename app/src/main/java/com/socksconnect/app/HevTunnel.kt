package com.socksconnect.app

/** JNI 桥接，实现见 native/tunnel_jni.c（链接 hev-socks5-tunnel） */
internal object HevTunnel {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /** 阻塞运行隧道事件循环，直到 [stop] 被调用或出错；返回 true 表示正常退出 */
    external fun start(configPath: String, tunFd: Int): Boolean

    /** 请求停止运行中的隧道（可在其他线程调用） */
    external fun stop()
}
