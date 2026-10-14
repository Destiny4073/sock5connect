package com.socksconnect.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 前台 VPN 服务：建立 TUN 网卡并启动 hev-socks5-tunnel，把整机流量经
 * 本地 SOCKS5 中转（LocalSocksServer）转发到用户配置的 HTTP/HTTPS/SOCKS5 代理。
 */
class VpnConnectorService : VpnService() {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    interface Listener {
        fun onStateChanged()
    }

    companion object {
        const val ACTION_START = "com.socksconnect.app.action.START"
        const val ACTION_DISCONNECT = "com.socksconnect.app.action.DISCONNECT"
        const val EXTRA_PROTOCOL = "extra_protocol"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"

        val PROTOCOLS = arrayOf("HTTP", "HTTPS", "SOCKS5")

        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val LOCAL_SOCKS_PORT = 10808
        private const val TUN_ADDRESS = "198.18.0.1"
        private const val TUN_MTU = 8500
        private const val INITIAL_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val RETRY_AFTER_STOP_MS = 3_000L

        // 供界面读取的连接状态（单实例服务，随进程存活）
        @Volatile
        var state: State = State.DISCONNECTED
            internal set

        @Volatile
        var protocol: String? = null
            internal set

        @Volatile
        var address: String? = null
            internal set

        @Volatile
        var connectedAt: Long = 0L
            internal set

        @Volatile
        var lastError: String? = null
            internal set

        private val listeners = CopyOnWriteArrayList<Listener>()

        fun addListener(listener: Listener) {
            listeners.add(listener)
        }

        fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }

        internal fun notifyListeners() {
            for (l in listeners) l.onStateChanged()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var notificationManager: NotificationManager? = null
    private var worker: VpnWorker? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val proto = (intent.getStringExtra(EXTRA_PROTOCOL) ?: "SOCKS5")
                    .takeIf { PROTOCOLS.contains(it) } ?: "SOCKS5"
                val host = intent.getStringExtra(EXTRA_HOST)
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                if (host.isNullOrEmpty() || port !in 1..65535) {
                    stopWorker()
                    return START_NOT_STICKY
                }
                val same = worker?.let { !it.cancelled && it.matches(proto, host, port) } == true
                protocol = proto
                address = "$host:$port"
                lastError = null
                if (!same) {
                    state = State.CONNECTING
                    startWorker(proto, host, port)
                }
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildNotification(state),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            ACTION_DISCONNECT -> stopWorker()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker?.cancel()
        worker = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onRevoke() {
        // 系统收回 VPN（用户在设置里断开或启动了其他 VPN）
        stopWorker()
    }

    private fun startWorker(proto: String, host: String, port: Int) {
        worker?.cancelAndWait()
        val w = VpnWorker(proto, host, port)
        val t = Thread(w, "VpnWorker")
        w.thread = t
        worker = w
        t.start()
    }

    private fun stopWorker() {
        worker?.cancelAndWait()
        worker = null
        state = State.DISCONNECTED
        lastError = null
        notifyListeners()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.description = getString(R.string.notif_channel_desc)
        notificationManager?.createNotificationChannel(channel)
    }

    private fun buildNotification(st: State): Notification {
        val addr = address.orEmpty()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                when (st) {
                    State.CONNECTED -> getString(R.string.notif_connected, protocol.orEmpty())
                    State.CONNECTING -> getString(R.string.notif_connecting)
                    State.RECONNECTING -> getString(R.string.notif_reconnecting)
                    State.DISCONNECTED -> getString(R.string.app_name)
                }
            )
            .setContentText(addr)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.action_disconnect), disconnectIntent)
        when (st) {
            State.CONNECTED -> {
                builder.setWhen(connectedAt).setUsesChronometer(true)
                val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(connectedAt))
                builder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.notif_detail, time, addr))
                )
            }
            State.RECONNECTING -> lastError?.let {
                builder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.notif_detail_fail, it, addr))
                )
            }
            else -> Unit
        }
        return builder.build()
    }

    private val contentIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private val disconnectIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this, 0,
            Intent(this, VpnConnectorService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // 实现 Runnable 而非继承 Thread：Thread 自带的 State 枚举和 getState() 属性
    // 会遮蔽本类的 State 枚举与 companion 里的状态变量
    private inner class VpnWorker(
        private val proto: String,
        private val host: String,
        private val port: Int
    ) : Runnable {

        @Volatile
        var cancelled = false
            private set

        var thread: Thread? = null

        private var tunnel: ParcelFileDescriptor? = null
        private var server: LocalSocksServer? = null

        fun matches(p: String, h: String, pt: Int) = proto == p && host == h && port == pt

        fun cancel() {
            cancelled = true
            runCatching { HevTunnel.stop() }
            runCatching { server?.stop() }
            closeTunnel()
        }

        fun cancelAndWait() {
            cancel()
            thread?.join(3000)
        }

        override fun run() {
            var backoff = INITIAL_BACKOFF_MS
            while (!cancelled) {
                publishState(State.CONNECTING)
                var started = false
                try {
                    started = runOnce()
                } catch (e: Exception) {
                    lastError = Upstreams.describeError(e)
                }
                closeTunnel()
                runCatching { server?.stop() }
                if (cancelled) break
                if (started) {
                    backoff = INITIAL_BACKOFF_MS
                    publishState(State.RECONNECTING)
                    if (sleepWhile(RETRY_AFTER_STOP_MS)) break
                } else {
                    publishState(State.RECONNECTING)
                    if (sleepWhile(backoff)) break
                    backoff = minOf(backoff * 2, MAX_BACKOFF_MS)
                }
            }
        }

        /** @return true 表示 hev 隧道成功运行过（本次运行已结束） */
        private fun runOnce(): Boolean {
            val srv = LocalSocksServer(LOCAL_SOCKS_PORT, proto, host, port)
            server = srv
            srv.start()
            val fd = establishTunnel()
                ?: throw IOException(getString(R.string.err_vpn_establish))
            tunnel = fd
            val cfg = File(cacheDir, "tunnel.yml")
            val hevLog = File(cacheDir, "hev.log")
            runCatching { hevLog.delete() }
            cfg.writeText(buildConfig(hevLog.absolutePath))
            var exitCode = -1
            var thrown: String? = null
            val hevThread = Thread({
                try {
                    exitCode = HevTunnel.start(cfg.absolutePath, fd.fd)
                } catch (t: Throwable) {
                    thrown = t.message ?: t.javaClass.simpleName
                }
            }, "HevTunnel")
            hevThread.start()
            // 轮询等待 hev 进入事件循环；配置或网卡出错时它会立即退出
            var waited = 0
            while (!cancelled && hevThread.isAlive && waited < 1500) {
                sleepWhile(150)
                waited += 150
            }
            if (cancelled) return false
            if (!hevThread.isAlive) {
                lastError = describeHevFailure(exitCode, thrown, hevLog)
                return false
            }
            connectedAt = System.currentTimeMillis()
            lastError = null
            publishState(State.CONNECTED)
            try {
                hevThread.join()
            } catch (_: InterruptedException) {
                cancelled = true
            }
            if (exitCode != 0 || thrown != null) {
                lastError = describeHevFailure(exitCode, thrown, hevLog)
            }
            return true
        }

        private fun establishTunnel(): ParcelFileDescriptor? {
            // Builder 是 VpnService 的内部类，须以服务实例为接收者创建
        val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
            // 本应用流量绕过 VPN，避免本地 SOCKS5 到上游代理的连接形成回环
            runCatching { builder.addDisallowedApplication(packageName) }
            return builder.establish()
        }

        // 仅使用官方示例中的配置键，最小化解析失败的可能；
        // log-file 用于把 hev 的错误暴露到界面状态栏
        private fun buildConfig(logPath: String): String = """
            tunnel:
              mtu: $TUN_MTU
              ipv4: $TUN_ADDRESS

            socks5:
              port: $LOCAL_SOCKS_PORT
              address: 127.0.0.1
              udp: 'udp'

            misc:
              log-file: $logPath
              log-level: debug
        """.trimIndent()

        /** 汇总 hev 的退出码、异常信息与日志末尾，便于在状态栏定位失败原因 */
        private fun describeHevFailure(code: Int, thrown: String?, log: File): String =
            buildString {
                append(
                    if (thrown != null) "hev 启动异常：$thrown"
                    else "hev 隧道退出（代码 $code）"
                )
                runCatching {
                    val text = log.readText().trim()
                    if (text.isNotEmpty()) append('\n').append(text.takeLast(400))
                }
            }

        private fun closeTunnel() {
            runCatching { tunnel?.close() }
            tunnel = null
        }

        private fun sleepWhile(ms: Long): Boolean {
            var remaining = ms
            while (remaining > 0 && !cancelled) {
                val step = minOf(500L, remaining)
                try {
                    Thread.sleep(step)
                } catch (_: InterruptedException) {
                    cancelled = true
                }
                remaining -= step
            }
            return cancelled
        }

        private fun publishState(st: State) {
            if (cancelled) return
            state = st
            // 通知与监听回调统一走主线程，避免与停止/移除通知的操作竞争
            mainHandler.post {
                if (cancelled) return@post
                notifyListeners()
                notificationManager?.notify(NOTIFICATION_ID, buildNotification(st))
            }
        }
    }
}
