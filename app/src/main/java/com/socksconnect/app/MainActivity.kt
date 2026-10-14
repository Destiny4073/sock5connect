package com.socksconnect.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var dropProtocol: MaterialAutoCompleteTextView
    private lateinit var addressLayout: TextInputLayout
    private lateinit var addressInput: TextInputEditText
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var textStatus: TextView

    private var pendingHost: String? = null
    private var pendingPort = 0

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notif_perm_hint, Toast.LENGTH_LONG).show()
            }
            doStart()
        }

    private val stateListener = ConnectorService.Listener { updateUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_main)

        dropProtocol = findViewById(R.id.drop_protocol)
        addressLayout = findViewById(R.id.address_layout)
        addressInput = findViewById(R.id.edit_address)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        textStatus = findViewById(R.id.text_status)

        dropProtocol.setSimpleItems(ConnectorService.PROTOCOLS)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedProto = prefs.getString(KEY_PROTOCOL, null)
            ?.takeIf { ConnectorService.PROTOCOLS.contains(it) }
        dropProtocol.setText(savedProto ?: "SOCKS5", false)
        addressInput.setText(prefs.getString(KEY_ADDRESS, "") ?: "")

        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener {
            startService(
                Intent(this, ConnectorService::class.java)
                    .setAction(ConnectorService.ACTION_DISCONNECT)
            )
        }

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        updateUi()
    }

    override fun onStart() {
        super.onStart()
        ConnectorService.addListener(stateListener)
        updateUi()
    }

    override fun onStop() {
        super.onStop()
        ConnectorService.removeListener(stateListener)
    }

    private fun onStartClicked() {
        val text = addressInput.text?.toString()?.trim().orEmpty()
        val parsed = parseAddress(text)
        if (parsed == null) {
            addressLayout.error = getString(R.string.error_invalid_address)
            return
        }
        addressLayout.error = null
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_PROTOCOL, currentProtocol())
            .putString(KEY_ADDRESS, text)
            .apply()
        pendingHost = parsed.first
        pendingPort = parsed.second
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            doStart()
        }
    }

    private fun doStart() {
        val host = pendingHost ?: return
        val port = pendingPort
        pendingHost = null
        val intent = Intent(this, ConnectorService::class.java)
            .setAction(ConnectorService.ACTION_START)
            .putExtra(ConnectorService.EXTRA_PROTOCOL, currentProtocol())
            .putExtra(ConnectorService.EXTRA_HOST, host)
            .putExtra(ConnectorService.EXTRA_PORT, port)
        ContextCompat.startForegroundService(this, intent)
        updateUi()
    }

    private fun currentProtocol(): String =
        dropProtocol.text?.toString()?.takeIf { ConnectorService.PROTOCOLS.contains(it) }
            ?: "SOCKS5"

    private fun updateUi() {
        val st = ConnectorService.state
        val addr = ConnectorService.address
        val idle = st == ConnectorService.State.DISCONNECTED
        btnStart.isEnabled = idle
        btnStop.isEnabled = !idle
        dropProtocol.isEnabled = idle
        addressInput.isEnabled = idle
        addressLayout.isEnabled = idle
        textStatus.text = when (st) {
            ConnectorService.State.DISCONNECTED -> getString(R.string.status_disconnected)
            ConnectorService.State.CONNECTING ->
                getString(R.string.status_connecting, addr.orEmpty())
            ConnectorService.State.CONNECTED -> getString(
                R.string.status_connected,
                addr.orEmpty(),
                SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date(ConnectorService.connectedAt))
            )
            ConnectorService.State.RECONNECTING -> buildString {
                append(getString(R.string.status_reconnecting))
                ConnectorService.lastError?.let { append('\n').append(it) }
            }
        }
    }

    /** 支持 "1.2.3.4:1080"、"example.com:8080"、"[::1]:1080" 三种写法 */
    private fun parseAddress(text: String): Pair<String, Int>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            if (end <= 0) return null
            val host = t.substring(1, end)
            val rest = t.substring(end + 1)
            if (!rest.startsWith(":")) return null
            val port = rest.substring(1).toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535) return null
            return host to port
        }
        val idx = t.lastIndexOf(':')
        if (idx <= 0) return null
        val host = t.substring(0, idx)
        val port = t.substring(idx + 1).toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_ADDRESS = "address"
    }
}
