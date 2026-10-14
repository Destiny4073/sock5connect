package com.socksconnect.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class ConnectorService : Service() {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    companion object {
        const val ACTION_START = "com.socksconnect.app.action.START"
        const val ACTION_DISCONNECT = "com.socksconnect.app.action.DISCONNECT"
        const val EXTRA_PROTOCOL = "extra_protocol"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"

        val PROTOCOLS = arrayOf("HTTP", "HTTPS", "SOCKS5")

        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val INITIAL_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val RETRY_AFTER_DROP_MS = 3_000L

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

        fun interface Listener {
            fun onStateChanged()
        }

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
    private var worker: Worker? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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

    private fun startWorker(proto: String, host: String, port: Int) {
        worker?.cancelAndWait()
        val w = Worker(proto, host, port)
        worker = w
        w.start()
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
            NotificationManager.IMPORTANCE_LOW
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
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(connectedAt))
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
            Intent(this, ConnectorService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private inner class Worker(
        private val proto: String,
        private val host: String,
        private val port: Int
    ) : Thread("ProxyConnector") {

        @Volatile
        var cancelled = false
            private set

        private var socket: Socket? = null
        private val socketLock = Any()

        fun matches(p: String, h: String, pt: Int) = proto == p && host == h && port == pt

        fun cancel() {
            cancelled = true
            synchronized(socketLock) { runCatching { socket?.close() } }
        }

        fun cancelAndWait() {
            cancel()
            join(1500)
        }

        override fun run() {
            var backoff = INITIAL_BACKOFF_MS
            while (!cancelled) {
                publishState(State.CONNECTING)
                val s = tryConnect()
                if (cancelled) {
                    runCatching { s?.close() }
                    break
                }
                if (s == null) {
                    publishState(State.RECONNECTING)
                    if (sleepWhile(backoff)) break
                    backoff = minOf(backoff * 2, MAX_BACKOFF_MS)
                    continue
                }
                backoff = INITIAL_BACKOFF_MS
                connectedAt = System.currentTimeMillis()
                lastError = null
                publishState(State.CONNECTED)
                monitor(s)
                runCatching { s.close() }
                if (cancelled) break
                lastError = null
                publishState(State.RECONNECTING)
                sleepWhile(RETRY_AFTER_DROP_MS)
            }
        }

        /** 阻塞读取以探测连接存活：对端正常关闭返回 -1，异常关闭抛出，均视为断开 */
        private fun monitor(s: Socket) {
            try {
                val input = s.getInputStream()
                val buf = ByteArray(256)
                while (!cancelled) {
                    if (input.read(buf) < 0) return
                }
            } catch (_: IOException) {
            }
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

        private fun tryConnect(): Socket? {
            var s: Socket? = null
            try {
                val raw = Socket()
                s = raw
                synchronized(socketLock) { socket = raw }
                raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                raw.tcpNoDelay = true
                raw.keepAlive = true
                raw.soTimeout = CONNECT_TIMEOUT_MS
                val result = when (proto) {
                    "HTTPS" -> {
                        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                        val tls = factory.createSocket(raw, host, port, true) as SSLSocket
                        tls.startHandshake()
                        synchronized(socketLock) { socket = tls }
                        tls.soTimeout = 0
                        tls
                    }
                    "SOCKS5" -> {
                        socksHandshake(raw)
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
                lastError = describeError(e)
                runCatching { s?.close() }
                synchronized(socketLock) { socket = null }
                return null
            }
        }

        /** SOCKS5 免认证（NO-AUTH）方式协商 */
        private fun socksHandshake(s: Socket) {
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

        private fun readFully(input: InputStream, buf: ByteArray) {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) throw IOException(getString(R.string.err_not_socks5))
                off += n
            }
        }

        private fun describeError(e: Exception): String = when (e) {
            is UnknownHostException -> getString(R.string.err_unknown_host)
            is SocketTimeoutException -> getString(R.string.err_timeout)
            is ConnectException -> getString(R.string.err_refused)
            is SSLException -> getString(R.string.err_tls)
            else -> getString(R.string.err_io, e.message ?: e.javaClass.simpleName)
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
