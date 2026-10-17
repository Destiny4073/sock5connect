package com.socksconnect.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var dropProtocol: MaterialAutoCompleteTextView
    private lateinit var addressLayout: TextInputLayout
    private lateinit var addressInput: TextInputEditText
    private lateinit var autoAppLayout: TextInputLayout
    private lateinit var dropAutoApp: MaterialAutoCompleteTextView
    private lateinit var switchFakeIp: MaterialSwitch
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var textStatus: TextView
    private lateinit var textRam: TextView

    private val ramHandler = Handler(Looper.getMainLooper())
    private val ramTicker = object : Runnable {
        override fun run() {
            updateRam()
            ramHandler.postDelayed(this, 2000)
        }
    }

    private var pendingHost: String? = null
    private var pendingPort = 0
    private var pendingFakeIp = true

    /** 「连接成功后自动打开」选中的应用包名；null 表示不自动打开 */
    private var selectedAutoPkg: String? = null

    /** 下拉框条目（首项为「无」），与自动打开下拉适配器共用 */
    private val autoAppItems = mutableListOf<AutoAppItem>()

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                prepareVpnAndStart()
            } else {
                showNotificationDisabledDialog()
            }
        }

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                doStart()
            } else {
                Toast.makeText(this, R.string.vpn_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val stateListener = object : VpnConnectorService.Listener {
        override fun onStateChanged() {
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.init(this)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_main)

        dropProtocol = findViewById(R.id.drop_protocol)
        addressLayout = findViewById(R.id.address_layout)
        addressInput = findViewById(R.id.edit_address)
        switchFakeIp = findViewById(R.id.switch_fakeip)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        textStatus = findViewById(R.id.text_status)
        textRam = findViewById(R.id.text_ram)

        dropProtocol.setSimpleItems(VpnConnectorService.PROTOCOLS)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedProto = prefs.getString(KEY_PROTOCOL, null)
            ?.takeIf { VpnConnectorService.PROTOCOLS.contains(it) }
        dropProtocol.setText(savedProto ?: "SOCKS5", false)
        addressInput.setText(prefs.getString(KEY_ADDRESS, "") ?: "")
        switchFakeIp.isChecked = prefs.getBoolean(KEY_FAKEIP, true)

        autoAppLayout = findViewById(R.id.autoapp_layout)
        dropAutoApp = findViewById(R.id.drop_autoapp)
        selectedAutoPkg = prefs.getString(KEY_AUTOLAUNCH, null)
        dropAutoApp.setText(getString(R.string.autoapp_none), false)
        dropAutoApp.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            val item = autoAppItems.getOrNull(pos) ?: return@OnItemClickListener
            selectedAutoPkg = item.pkg.ifEmpty { null }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_AUTOLAUNCH, selectedAutoPkg)
                .apply()
            dropAutoApp.setText(item.toString(), false)
        }
        loadInstalledApps()

        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener {
            startService(
                Intent(this, VpnConnectorService::class.java)
                    .setAction(VpnConnectorService.ACTION_DISCONNECT)
            )
        }

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // 沉浸式：内容绘制到状态栏/导航栏之后（各版本统一处理，图标颜色随深浅色主题）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val lightBars =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
                Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = lightBars
        insetsController.isAppearanceLightNavigationBars = lightBars

        updateUi()
    }

    override fun onStart() {
        super.onStart()
        VpnConnectorService.addListener(stateListener)
        updateUi()
    }

    override fun onStop() {
        super.onStop()
        VpnConnectorService.removeListener(stateListener)
    }

    override fun onResume() {
        super.onResume()
        ramTicker.run()
    }

    override fun onPause() {
        super.onPause()
        ramHandler.removeCallbacks(ramTicker)
    }

    private fun updateRam() {
        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        textRam.text = getString(R.string.app_ram, mem.totalPss / 1024)
    }

    private fun onStartClicked() {
        val text = addressInput.text?.toString()?.trim().orEmpty()
        val parsed = parseAddress(text)
        if (parsed == null) {
            addressLayout.error = getString(R.string.error_invalid_address)
            return
        }
        addressLayout.error = null
        pendingFakeIp = switchFakeIp.isChecked
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_PROTOCOL, currentProtocol())
            .putString(KEY_ADDRESS, text)
            .putBoolean(KEY_FAKEIP, pendingFakeIp)
            .apply()
        pendingHost = parsed.first
        pendingPort = parsed.second
        requestNotificationPermissionThenStart()
    }

    private fun requestNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        ensureNotificationsEnabledThenStart()
    }

    private fun ensureNotificationsEnabledThenStart() {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            prepareVpnAndStart()
        } else {
            // Android 12 及国产 ROM 常默认关闭通知，只能引导用户去系统设置开启
            showNotificationDisabledDialog()
        }
    }

    private fun showNotificationDisabledDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notif_disabled_title)
            .setMessage(R.string.notif_disabled_msg)
            .setPositiveButton(R.string.notif_go_settings) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
            .setNegativeButton(R.string.notif_continue) { _, _ -> prepareVpnAndStart() }
            .show()
    }

    private fun prepareVpnAndStart() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            vpnConsent.launch(consent)
        } else {
            doStart()
        }
    }

    private fun doStart() {
        val host = pendingHost ?: return
        val port = pendingPort
        pendingHost = null
        val intent = Intent(this, VpnConnectorService::class.java)
            .setAction(VpnConnectorService.ACTION_START)
            .putExtra(VpnConnectorService.EXTRA_PROTOCOL, currentProtocol())
            .putExtra(VpnConnectorService.EXTRA_HOST, host)
            .putExtra(VpnConnectorService.EXTRA_PORT, port)
            .putExtra(VpnConnectorService.EXTRA_FAKEIP, pendingFakeIp)
            .putExtra(VpnConnectorService.EXTRA_AUTOLAUNCH, selectedAutoPkg ?: "")
        ContextCompat.startForegroundService(this, intent)
        updateUi()
    }

    private fun currentProtocol(): String =
        dropProtocol.text?.toString()?.takeIf { VpnConnectorService.PROTOCOLS.contains(it) }
            ?: "SOCKS5"

    private fun updateUi() {
        val st = VpnConnectorService.state
        val addr = VpnConnectorService.address
        val idle = st == VpnConnectorService.State.DISCONNECTED
        btnStart.isEnabled = idle
        btnStop.isEnabled = !idle
        dropProtocol.isEnabled = idle
        addressInput.isEnabled = idle
        addressLayout.isEnabled = idle
        autoAppLayout.isEnabled = idle
        dropAutoApp.isEnabled = idle
        switchFakeIp.isEnabled = idle
        textStatus.text = when (st) {
            VpnConnectorService.State.DISCONNECTED -> getString(R.string.status_disconnected)
            VpnConnectorService.State.CONNECTING ->
                getString(R.string.status_connecting, addr.orEmpty())
            VpnConnectorService.State.CONNECTED -> buildString {
                append(
                    getString(
                        R.string.status_connected,
                        addr.orEmpty(),
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            .format(Date(VpnConnectorService.connectedAt))
                    )
                )
                VpnConnectorService.lastError?.let { append('\n').append(it) }
                append('\n')
                append(VpnConnectorService.selfTestResult ?: getString(R.string.self_testing))
            }
            VpnConnectorService.State.RECONNECTING -> buildString {
                append(getString(R.string.status_reconnecting))
                VpnConnectorService.lastError?.let { append('\n').append(it) }
            }
        }
    }

    /** 后台加载手机上所有带桌面入口的应用（含系统自带），按名称排序后填充下拉框 */
    private fun loadInstalledApps() {
        val noneIcon = AppCompatResources.getDrawable(this, R.drawable.ic_no_app)?.mutate()?.apply {
            DrawableCompat.setTint(
                this,
                MaterialColors.getColor(
                    dropAutoApp, com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )
        }
        val noneItem = AutoAppItem(getString(R.string.autoapp_none), "", noneIcon)
        thread(name = "AppListLoader") {
            val pm = packageManager
            val selfPkg = packageName
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val items = mutableListOf<AutoAppItem>()
            val seen = HashSet<String>()
            runCatching {
                val collator = Collator.getInstance(Locale.getDefault())
                pm.queryIntentActivities(query, 0)
                    .asSequence()
                    .mapNotNull { info ->
                        val pkg = info.activityInfo.packageName
                        if (pkg == selfPkg) return@mapNotNull null
                        val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                        if (label.isEmpty()) null else Triple(pkg, label, info.loadIcon(pm))
                    }
                    .sortedWith(compareBy(collator) { it.second })
                    .forEach { (pkg, label, icon) ->
                        // 同一应用可能注册多个桌面入口，仅保留一条
                        if (seen.add(pkg)) items.add(AutoAppItem(label, pkg, icon))
                    }
            }
            runOnUiThread {
                autoAppItems.clear()
                autoAppItems.add(noneItem)
                autoAppItems.addAll(items)
                dropAutoApp.setAdapter(AutoAppAdapter())
                resolveAutoAppSelection()
            }
        }
    }

    /** 应用列表就绪后回显已保存的选择；应用已卸载则回退为「无」并清除记录 */
    private fun resolveAutoAppSelection() {
        val pkg = selectedAutoPkg
        val item = autoAppItems.firstOrNull { it.pkg == pkg }
        if (item == null) {
            if (!pkg.isNullOrEmpty()) {
                // 所选应用已卸载：清除记录
                selectedAutoPkg = null
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .remove(KEY_AUTOLAUNCH)
                    .apply()
            }
            dropAutoApp.setText(getString(R.string.autoapp_none), false)
        } else {
            dropAutoApp.setText(item.toString(), false)
        }
    }

    /** 「自动打开应用」下拉条目：pkg 为空表示「无」（不自动打开），展示为 应用名 (包名) */
    private data class AutoAppItem(val label: String, val pkg: String, val icon: Drawable?) {
        override fun toString(): String = if (pkg.isEmpty()) label else "$label ($pkg)"
    }

    private inner class AutoAppAdapter :
        ArrayAdapter<AutoAppItem>(this@MainActivity, R.layout.item_autoapp, autoAppItems) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_autoapp, parent, false)
            val item = getItem(position) ?: return row
            row.findViewById<ImageView>(R.id.autoapp_icon).setImageDrawable(item.icon)
            row.findViewById<TextView>(R.id.autoapp_text).text = item.toString()
            return row
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
        private const val KEY_FAKEIP = "fake_ip"
        private const val KEY_AUTOLAUNCH = "auto_launch_pkg"
    }
}
