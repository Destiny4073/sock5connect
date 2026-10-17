package com.socksconnect.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.Socket
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
        const val EXTRA_FAKEIP = "extra_fakeip"

        /** 连接成功且自检通过后要自动打开的应用包名；空串表示不自动打开 */
        const val EXTRA_AUTOLAUNCH = "extra_auto_launch"

        val PROTOCOLS = arrayOf("HTTP", "HTTPS", "SOCKS5")

        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val LOCAL_SOCKS_PORT = 10808
        private const val TUN_ADDRESS = "198.18.0.1"
        private const val TUN_MTU = 8500
        private const val INITIAL_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val RETRY_AFTER_STOP_MS = 3_000L
        private const val RAM_REFRESH_MS = 3_000L

        /** fake-IP 模式自检用的域名：走完整「假 IP → 域名 → 上游 CONNECT」链路 */
        private const val SELF_TEST_DOMAIN = "www.google.com"

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

        /** 连接成功后的链路自检结果（"自检: 代理通道✓ DNS✓" 或失败原因） */
        @Volatile
        var selfTestResult: String? = null
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

    /** 服务运行期间每 3 秒重建一次通知，刷新内存占用数字 */
    private val notificationTicker = object : Runnable {
        override fun run() {
            if (worker == null) return
            runCatching {
                notificationManager?.notify(NOTIFICATION_ID, buildNotification(state))
            }
            mainHandler.postDelayed(this, RAM_REFRESH_MS)
        }
    }

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
                val fakeIp = intent.getBooleanExtra(EXTRA_FAKEIP, true)
                val autoLaunch = intent.getStringExtra(EXTRA_AUTOLAUNCH)
                    ?.takeIf { it.isNotEmpty() }
                if (host.isNullOrEmpty() || port !in 1..65535) {
                    stopWorker()
                    return START_NOT_STICKY
                }
                val same = worker?.let { !it.cancelled && it.matches(proto, host, port, fakeIp) } == true
                protocol = proto
                address = "$host:$port"
                lastError = null
                if (!same) {
                    state = State.CONNECTING
                    startWorker(proto, host, port, fakeIp, autoLaunch)
                }
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildNotification(state),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                mainHandler.removeCallbacks(notificationTicker)
                mainHandler.postDelayed(notificationTicker, RAM_REFRESH_MS)
            }
            ACTION_DISCONNECT -> stopWorker()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker?.cancel()
        worker = null
        mainHandler.removeCallbacks(notificationTicker)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onRevoke() {
        // 系统收回 VPN（用户在设置里断开或启动了其他 VPN）
        stopWorker()
    }

    private fun startWorker(proto: String, host: String, port: Int, fakeIp: Boolean, autoLaunch: String?) {
        worker?.cancelAndWait()
        val w = VpnWorker(proto, host, port, fakeIp, autoLaunch)
        val t = Thread(w, "VpnWorker")
        w.thread = t
        worker = w
        t.start()
    }

    private fun stopWorker() {
        worker?.cancelAndWait()
        worker = null
        mainHandler.removeCallbacks(notificationTicker)
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
        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        val ramMb = mem.totalPss / 1024
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
            .setContentText(getString(R.string.notif_ram, ramMb) + " · " + addr)
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
                        .bigText(getString(R.string.notif_detail, time, addr, ramMb))
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
        private val port: Int,
        private val fakeIp: Boolean,
        private val autoLaunchPkg: String?
    ) : Runnable {

        /** 仅在本次连接首次自检通过时自动打开目标应用；断线重连不重复拉起 */
        @Volatile
        private var autoLaunchPending = !autoLaunchPkg.isNullOrEmpty()

        @Volatile
        var cancelled = false
            private set

        var thread: Thread? = null

        private var tunnel: ParcelFileDescriptor? = null
        private var server: LocalSocksServer? = null

        fun matches(p: String, h: String, pt: Int, fp: Boolean) =
            proto == p && host == h && port == pt && fakeIp == fp

        fun cancel() {
            cancelled = true
            runCatching { HevTunnel.TProxyStopService() }
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
                } catch (t: Throwable) {
                    // 捕获 Throwable：JNI 类初始化失败等 Error 不允许杀死进程
                    lastError = "启动异常：${t.message ?: t.javaClass.simpleName}"
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
            selfTestResult = null
            FakeIpTable.clear()
            val srv = LocalSocksServer(LOCAL_SOCKS_PORT, proto, host, port, fakeIp)
            server = srv
            srv.start()
            val fd = establishTunnel()
                ?: throw IOException(getString(R.string.err_vpn_establish))
            tunnel = fd
            val cfg = File(cacheDir, "tunnel.yml")
            val hevLog = File(cacheDir, "hev.log")
            runCatching { hevLog.delete() }
            cfg.writeText(buildConfig(hevLog.absolutePath))
            val started = try {
                HevTunnel.TProxyStartService(cfg.absolutePath, fd.fd)
            } catch (t: Throwable) {
                lastError = "hev 启动异常：${t.message ?: t.javaClass.simpleName}"
                return false
            }
            if (!started) {
                lastError = "hev 启动失败（内部线程创建失败）"
                return false
            }
            // 轮询等待进入稳定运行态；配置或网卡出错时内部线程会立即退出
            var waited = 0
            while (!cancelled && HevTunnel.TProxyIsRunning() && waited < 1500) {
                sleepWhile(150)
                waited += 150
            }
            if (cancelled) return false
            if (!HevTunnel.TProxyIsRunning()) {
                lastError = describeHevFailure(hevLog)
                return false
            }
            connectedAt = System.currentTimeMillis()
            lastError = null
            publishState(State.CONNECTED)
            Thread({
                val (selfTestOk, selfTestText) = runSelfTest()
                if (!cancelled) {
                    selfTestResult = selfTestText
                    if (selfTestOk && autoLaunchPending) {
                        autoLaunchPending = false
                        mainHandler.post { launchAutoApp() }
                    }
                    mainHandler.post { notifyListeners() }
                }
            }, "SelfTest").start()
            // 库内部线程退出即隧道结束（被停止或运行出错）
            while (!cancelled && HevTunnel.TProxyIsRunning()) {
                sleepWhile(250)
            }
            if (!cancelled) {
                lastError = describeHevFailure(hevLog)
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

        /** 汇总失败原因：hev 日志末尾会包含配置解析、网卡设置等错误详情 */
        private fun describeHevFailure(log: File): String =
            buildString {
                append("hev 隧道已退出")
                runCatching {
                    val text = log.readText().trim()
                    if (text.isNotEmpty()) append('\n').append(text.takeLast(400))
                }
            }

        /** 连接建立后自检链路：
         *  fake-IP 模式：把自检域名分配一个假 IP 后按完整链路 CONNECT——本地中转
         *  会把它还原为域名、按域名转发上游（上游应答 CONNECT 成功即代表节点出口
         *  已按域名解析并连上目标），DNS 与代理通道一次全验；
         *  传统模式：分别测代理通道（CONNECT 1.1.1.1:443）与 DNS（DoH）。
         *  @return 自检是否全部通过 与 展示文本 */
        private fun runSelfTest(): Pair<Boolean, String> {
            var result: String
            val ok: Boolean
            if (fakeIp) {
                val fakeIpAddr = FakeIpTable.allocate(SELF_TEST_DOMAIN)
                val proxy = testConnect(fakeIpAddr, 443)
                ok = proxy.second
                result = "自检: $proxy " + if (ok) "DNS✓(fake-IP)" else "DNS✗(链路未通)"
            } else {
                val proxy = testConnect("1.1.1.1", 443)
                val dns = testDns()
                ok = proxy.second && dns.second
                result = "自检: $proxy $dns"
            }
            if (!ok) {
                runCatching {
                    val text = File(cacheDir, "hev.log").readText().trim()
                    if (text.isNotEmpty()) result += "\n" + text.takeLast(300)
                }
            }
            return ok to result
        }

        /** 自检通过后自动打开用户选择的应用（每次全新连接仅一次，重连不重复拉起） */
        private fun launchAutoApp() {
            val pkg = autoLaunchPkg ?: return
            val pm = packageManager
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull() ?: pkg
            val launch = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
            if (launch == null) {
                Toast.makeText(applicationContext, R.string.autoapp_open_failed, Toast.LENGTH_LONG).show()
                return
            }
            runCatching {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                Toast.makeText(
                    applicationContext, getString(R.string.autoapp_opened, label), Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                Toast.makeText(applicationContext, R.string.autoapp_open_failed, Toast.LENGTH_LONG).show()
            }
        }

        /** 通过本地 SOCKS5 中转发起一次 CONNECT，返回「代理通道✓/✗(原因)」 */
        private fun testConnect(targetHost: String, targetPort: Int): Pair<String, Boolean> {
            var s: Socket? = null
            return try {
                s = Socket("127.0.0.1", LOCAL_SOCKS_PORT).apply { soTimeout = 8000 }
                val out = s.getOutputStream()
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                val greet = ByteArray(2)
                input.readFully(greet)
                if (greet[1] != 0x00.toByte()) {
                    return "代理通道✗(本地中转拒绝)" to false
                }
                val addr = Upstreams.encodeSocksAddress(targetHost, targetPort)
                val req = ByteArray(3 + addr.size)
                req[0] = 0x05
                req[1] = 0x01
                req[2] = 0x00
                addr.copyInto(req, 3)
                out.write(req)
                val rep = ByteArray(4)
                input.readFully(rep)
                val skip = when (rep[3].toInt()) {
                    0x01 -> 6
                    0x04 -> 18
                    0x03 -> input.read() + 2
                    else -> 0
                }
                if (skip > 0) {
                    input.readFully(ByteArray(skip))
                }
                if (rep[1] != 0x00.toByte()) {
                    // 转发失败的具体原因已由 LocalSocksServer 写入 lastError
                    "代理通道✗(${VpnConnectorService.lastError ?: "上游拒绝"})" to false
                } else {
                    "代理通道✓" to true
                }
            } catch (e: Exception) {
                "代理通道✗(${e.message ?: e.javaClass.simpleName})" to false
            } finally {
                runCatching { s?.close() }
            }
        }

        private fun testDns(): Pair<String, Boolean> {
            val resolver = DohResolver(proto, host, port)
            return if (resolver.query(buildDnsQuery("www.google.com")) != null) {
                "DNS✓" to true
            } else {
                "DNS✗(${resolver.lastError ?: "无响应"})" to false
            }
        }

        private fun buildDnsQuery(name: String): ByteArray {
            val header = byteArrayOf(
                0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            )
            val labels = ByteArrayOutputStream()
            for (label in name.split('.')) {
                labels.write(label.length)
                labels.write(label.toByteArray(Charsets.US_ASCII))
            }
            labels.write(0)
            return header + labels.toByteArray() + byteArrayOf(0x00, 0x01, 0x00, 0x01)
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
