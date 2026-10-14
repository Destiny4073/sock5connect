package com.socksconnect.app

/**
 * hev-socks5-tunnel 的 JNI 接口。
 * 库内的 src/hev-jni.c 在 JNI_OnLoad 时把下列 TProxy* 静态方法注册到本类
 * （类名由 native/Application.mk 的 PKGNAME/CLSNAME 宏指定），方法名不可更改。
 */
internal object HevTunnel {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /** 启动隧道（库内部自建线程运行，立即返回）；false 表示启动失败或已在运行 */
    @JvmStatic
    external fun TProxyStartService(configPath: String, tunFd: Int): Boolean

    /** 停止隧道并等待内部线程退出 */
    @JvmStatic
    external fun TProxyStopService(): Boolean

    /** 隧道是否运行中 */
    @JvmStatic
    external fun TProxyIsRunning(): Boolean

    /** [txPackets, txBytes, rxPackets, rxBytes] */
    @JvmStatic
    external fun TProxyGetStats(): LongArray?
}
