package ernest.ascrcpy.adb.demo

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import ernest.ascrcpy.adb.transport.UsbAdbTransport
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : Activity() {
    private enum class Mode(val title: Int) {
        TCP(R.string.method_tcp),
        WIRELESS_CODE(R.string.method_wireless_code),
        WIRELESS_QR(R.string.method_wireless_qr),
        USB(R.string.method_usb),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var client: AdbClient? = null
    private var connected = false
    private var busy = false
    private var selectedMode = Mode.TCP
    private val panels = mutableMapOf<Mode, LinearLayout>()
    private val modeButtons = mutableMapOf<Mode, RadioButton>()
    private val actionButtons = mutableListOf<Button>()
    private val logLines = ArrayDeque<String>()

    private lateinit var tcpHostInput: EditText
    private lateinit var tcpPortInput: EditText
    private lateinit var pairingHostInput: EditText
    private lateinit var pairingPortInput: EditText
    private lateinit var pairingCodeInput: EditText
    private lateinit var shellButton: Button
    private lateinit var appsButton: Button
    private lateinit var filesButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var browserPanel: LinearLayout
    private lateinit var navigationPanel: LinearLayout
    private lateinit var parentButton: Button
    private lateinit var listPanel: LinearLayout
    private lateinit var pathInput: EditText
    private lateinit var statusView: TextView
    private lateinit var outputView: TextView
    private lateinit var logView: TextView
    private var qrDialog: AlertDialog? = null
    private var qrDiscovery: QrPairingDiscovery? = null
    private var qrJob: Job? = null
    private var pendingDownload: RemoteFile? = null
    private var currentPath = "/sdcard"

    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val usbPermissionAction by lazy { "$packageName.USB_PERMISSION" }
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != usbPermissionAction) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                connectUsb(device)
            } else showStatus(R.string.status_usb_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 24.dp, 24.dp, 24.dp)
        }
        val scroll = ScrollView(this).apply {
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.systemBars())
                } else {
                    @Suppress("DEPRECATION")
                    return@setOnApplyWindowInsetsListener insets.also {
                        view.setPadding(it.systemWindowInsetLeft, it.systemWindowInsetTop,
                            it.systemWindowInsetRight, it.systemWindowInsetBottom)
                    }
                }
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, IntentFilter(usbPermissionAction), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, IntentFilter(usbPermissionAction))
        }

        content.addView(heading(R.string.connection_method))
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        Mode.entries.forEach { mode ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                setText(mode.title)
            }
            modeButtons[mode] = button
            modeGroup.addView(button)
        }
        content.addView(modeGroup)
        buildTcpPanel(content)
        buildWirelessCodePanel(content)
        buildQrPanel(content)
        buildUsbPanel(content)
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = modeButtons.entries.firstOrNull { it.value.id == checkedId }?.key
            if (mode != null) showMode(mode)
        }
        val restored = savedInstanceState?.getString(STATE_MODE)
            ?.let { name -> Mode.entries.firstOrNull { it.name == name } } ?: Mode.TCP
        modeButtons.getValue(restored).isChecked = true

        content.addView(heading(R.string.device_actions))
        shellButton = button(R.string.read_model) { readDeviceModel() }
        appsButton = button(R.string.show_apps) { showApplications() }
        filesButton = button(R.string.browse_files) { openBrowser() }
        disconnectButton = button(R.string.disconnect) { disconnect() }
        content.addView(shellButton)
        content.addView(appsButton)
        content.addView(filesButton)
        content.addView(disconnectButton)
        browserPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        content.addView(browserPanel)
        navigationPanel = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pathInput = field(R.string.remote_path_hint, false).apply {
            setText(currentPath)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        navigationPanel.addView(pathInput)
        navigationPanel.addView(button(R.string.go_to_path) { loadFiles(pathInput.text.toString()) })
        browserPanel.addView(navigationPanel)
        parentButton = button(R.string.parent_folder) { loadFiles(DeviceManager.parent(currentPath)) }
        browserPanel.addView(parentButton)
        listPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        browserPanel.addView(listPanel)
        statusView = TextView(this).apply { textSize = 16f }
        content.addView(statusView)
        content.addView(heading(R.string.model_output))
        outputView = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        content.addView(outputView)
        content.addView(heading(R.string.activity_log))
        content.addView(button(R.string.clear_log) {
            logLines.clear()
            logView.text = ""
        })
        logView = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        content.addView(logView)
        showStatus(R.string.status_disconnected)
        updateButtons()
    }

    private fun buildTcpPanel(parent: LinearLayout) {
        val panel = panel(parent, Mode.TCP)
        panel.addView(description(R.string.tcp_description))
        tcpHostInput = field(R.string.host_hint, false)
        tcpPortInput = field(R.string.port_hint, true).apply {
            setText(AdbEndpoint.DEFAULT_ADB_PORT.toString())
        }
        panel.addView(tcpHostInput)
        panel.addView(tcpPortInput)
        panel.addView(button(R.string.connect_tcp) { connectTcp() })
    }

    private fun buildWirelessCodePanel(parent: LinearLayout) {
        val panel = panel(parent, Mode.WIRELESS_CODE)
        panel.addView(description(R.string.pairing_description))
        panel.addView(heading(R.string.pairing_details))
        pairingHostInput = field(R.string.pairing_host_hint, false)
        pairingPortInput = field(R.string.pairing_port_hint, true)
        pairingCodeInput = field(R.string.pairing_code_hint, true)
        panel.addView(pairingHostInput)
        panel.addView(pairingPortInput)
        panel.addView(pairingCodeInput)
        panel.addView(button(R.string.pair_wireless) { pairWireless() })
    }

    private fun buildQrPanel(parent: LinearLayout) {
        val panel = panel(parent, Mode.WIRELESS_QR)
        panel.addView(description(R.string.qr_description))
        panel.addView(button(R.string.pair_qr) { pairWithQrCode() })
    }

    private fun buildUsbPanel(parent: LinearLayout) {
        val panel = panel(parent, Mode.USB)
        panel.addView(description(R.string.usb_description))
        panel.addView(button(R.string.connect_usb) { selectUsb() })
    }

    private fun panel(parent: LinearLayout, mode: Mode) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        panels[mode] = this
        parent.addView(this)
    }

    private fun showMode(mode: Mode) {
        selectedMode = mode
        panels.forEach { (key, panel) -> panel.visibility = if (key == mode) View.VISIBLE else View.GONE }
    }

    private fun heading(title: Int) = TextView(this).apply {
        setText(title)
        textSize = 18f
        setPadding(0, 16.dp, 0, 4.dp)
    }

    private fun description(text: Int) = TextView(this).apply {
        setText(text)
        textSize = 14f
        setPadding(0, 8.dp, 0, 8.dp)
    }

    private fun field(hint: Int, numeric: Boolean) = EditText(this).apply {
        setHint(hint)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine()
    }

    private fun button(label: Int, track: Boolean = true, action: () -> Unit) = Button(this).apply {
        setText(label)
        setOnClickListener { action() }
        if (track && label != R.string.clear_log) actionButtons.add(this)
    }

    private fun connectTcp() {
        val endpoint = endpoint(tcpHostInput, tcpPortInput) ?: return
        launchAction {
            showStatus(R.string.status_connecting, endpoint.serial)
            outputView.text = ""
            connected = false
            clearBrowser()
            withContext(Dispatchers.IO) {
                client?.close()
                client = DefaultAdbClient.factory(applicationContext).create()
                client!!.connect(endpoint)
            }
            connected = true
            showStatus(R.string.status_connected, endpoint.serial)
        }
    }

    private fun endpoint(hostInput: EditText, portInput: EditText): AdbEndpoint? {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) {
            showStatus(R.string.status_invalid_endpoint)
            return null
        }
        return AdbEndpoint(host, port)
    }

    private fun pairWireless() {
        val host = pairingHostInput.text.toString().trim()
        val port = pairingPortInput.text.toString().toIntOrNull()
        val code = pairingCodeInput.text.toString().trim()
        if (host.isEmpty() || port == null || port !in 1..65535 || !code.matches(Regex("[0-9]{6}"))) {
            showStatus(R.string.status_invalid_pairing)
            return
        }
        launchAction {
            val connections = Channel<QrPairingDiscovery.ResolvedService>(Channel.UNLIMITED)
            val discovery = QrPairingDiscovery(
                this@MainActivity,
                serviceName = null,
                onPairing = { },
                onConnection = { connections.trySend(it) },
                onError = { errorCode ->
                    if (errorCode != null) appendLog(getString(R.string.log_discovery_code, errorCode))
                },
            )
            try {
                discovery.start()
                showStatus(R.string.status_pairing, "$host:$port")
                val guid = withContext(Dispatchers.IO) {
                    val pairingClient = DefaultAdbClient.factory(applicationContext).create()
                    try { pairingClient.pairWireless(AdbEndpoint(host, port), code) }
                    finally { pairingClient.close() }
                }
                pairingCodeInput.text.clear()
                showStatus(R.string.status_finding_connection, guid)
                val connection = withTimeoutOrNull(30_000) {
                    while (true) {
                        val candidate = connections.receive()
                        if (candidate.name.contains(guid, ignoreCase = true)) {
                            return@withTimeoutOrNull candidate
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
                if (connection == null) {
                    showStatus(R.string.status_connection_not_found, guid)
                } else {
                    // An explicitly entered Tailscale address selects that route. mDNS still
                    // supplies the short-lived connection port, but must not replace the host.
                    val useTailscale = host.isTailscaleAddress()
                    val endpoint = AdbEndpoint(if (useTailscale) host else connection.host, connection.port)
                    connected = false
                    clearBrowser()
                    withContext(Dispatchers.IO) {
                        client?.close()
                        client = DefaultAdbClient.factory(applicationContext).create()
                        client!!.connectWireless(endpoint)
                    }
                    connected = true
                    showStatus(R.string.status_paired_connected, endpoint.serial)
                }
            } finally {
                discovery.stop()
                connections.close()
            }
        }
    }

    private fun pairWithQrCode() {
        if (busy || qrDialog != null) return
        val qr = QrPairing()
        val image = ImageView(this).apply {
            setImageBitmap(qr.bitmap())
            adjustViewBounds = true
            setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            contentDescription = getString(R.string.qr_image_description)
        }
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(description(R.string.qr_description).apply {
                setPadding(24.dp, 16.dp, 24.dp, 8.dp)
            })
            addView(image)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.qr_title)
            .setView(dialogContent)
            .setNegativeButton(R.string.cancel) { _, _ -> showStatus(R.string.status_qr_cancelled) }
            .create()
        qrDialog = dialog
        dialog.setOnCancelListener { showStatus(R.string.status_qr_cancelled) }
        dialog.setOnDismissListener { stopQrPairing() }
        dialog.show()

        val connections = Channel<QrPairingDiscovery.ResolvedService>(Channel.UNLIMITED)
        var pairingStarted = false
        val discovery = QrPairingDiscovery(
            this, qr.serviceName,
            onPairing = { service ->
                if (!pairingStarted) {
                    pairingStarted = true
                    qrJob = launchAction {
                        try {
                            showStatus(R.string.status_qr_scanned, "${service.host}:${service.port}")
                            val guid = withContext(Dispatchers.IO) {
                                val pairingClient = DefaultAdbClient.factory(applicationContext).create()
                                try { pairingClient.pairWireless(AdbEndpoint(service.host, service.port), qr.password) }
                                finally { pairingClient.close() }
                            }
                            showStatus(R.string.status_finding_connection, guid)
                            val connection = withTimeoutOrNull(30_000) {
                                while (true) {
                                    val candidate = connections.receive()
                                    if (candidate.name.contains(guid, ignoreCase = true) || candidate.host == service.host) {
                                        return@withTimeoutOrNull candidate
                                    }
                                }
                                @Suppress("UNREACHABLE_CODE")
                                null
                            }
                            if (connection == null) {
                                showStatus(R.string.status_connection_not_found, guid)
                            } else {
                                val useTailscale = service.host.isTailscaleAddress()
                                val endpoint = AdbEndpoint(
                                    if (useTailscale) service.host else connection.host,
                                    connection.port,
                                )
                                connected = false
                                clearBrowser()
                                withContext(Dispatchers.IO) {
                                    client?.close()
                                    client = DefaultAdbClient.factory(applicationContext).create()
                                    client!!.connectWireless(endpoint)
                                }
                                connected = true
                                showStatus(R.string.status_paired_connected, endpoint.serial)
                            }
                        } finally {
                            qrJob = null
                            dialog.dismiss()
                        }
                    }
                }
            },
            onConnection = { connections.trySend(it) },
            onError = { code ->
                showStatus(R.string.status_discovery_failed)
                if (code != null) appendLog(getString(R.string.log_discovery_code, code))
                dialog.dismiss()
            },
        )
        qrDiscovery = discovery
        try {
            discovery.start()
            if (qrDialog === dialog) showStatus(R.string.status_qr_waiting)
        } catch (error: Exception) {
            showStatus(R.string.status_discovery_failed)
            appendLog(getString(R.string.log_error_detail, error.message ?: error.javaClass.simpleName))
            dialog.dismiss()
        }
    }

    private fun stopQrPairing() {
        qrJob?.cancel()
        qrJob = null
        qrDiscovery?.stop()
        qrDiscovery = null
        qrDialog = null
    }

    private fun selectUsb() {
        val devices = UsbAdbTransport.discover(usbManager)
        if (devices.isEmpty()) {
            showStatus(R.string.status_usb_missing)
            return
        }
        val device = devices.first()
        if (usbManager.hasPermission(device)) {
            connectUsb(device)
        } else {
            val intent = PendingIntent.getBroadcast(
                this, 0, Intent(usbPermissionAction).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            usbManager.requestPermission(device, intent)
        }
    }

    private fun connectUsb(device: UsbDevice) = launchAction {
        showStatus(R.string.status_usb_connecting, device.deviceName)
        connected = false
        clearBrowser()
        withContext(Dispatchers.IO) {
            client?.close()
            client = DefaultAdbClient.factory(applicationContext).create()
            client!!.connectUsb(device)
        }
        connected = true
        showStatus(R.string.status_usb_connected)
    }

    private fun readDeviceModel() = launchAction {
        val result = withContext(Dispatchers.IO) {
            checkNotNull(client).shell("getprop ro.product.model").text()
        }
        outputView.text = result.ifBlank { getString(R.string.no_output) }
        showStatus(R.string.status_command_complete)
    }

    private fun showApplications() = launchAction {
        browserPanel.visibility = View.VISIBLE
        navigationPanel.visibility = View.GONE
        parentButton.visibility = View.GONE
        listPanel.removeAllViews()
        listPanel.addView(description(R.string.loading_apps))
        val apps = DeviceManager(this@MainActivity, checkNotNull(client)).applications()
        listPanel.removeAllViews()
        listPanel.addView(heading(R.string.show_apps))
        listPanel.addView(TextView(this@MainActivity).apply { text = getString(R.string.app_count, apps.size) })
        apps.forEach { app ->
            listPanel.addView(TextView(this@MainActivity).apply {
                text = getString(if (app.system) R.string.app_system_row else R.string.app_user_row, app.packageName)
                textSize = 15f
                setPadding(8.dp, 7.dp, 8.dp, 7.dp)
                setTextIsSelectable(true)
            })
        }
        showStatus(R.string.status_apps_loaded, apps.size)
    }

    private fun openBrowser() {
        browserPanel.visibility = View.VISIBLE
        navigationPanel.visibility = View.VISIBLE
        parentButton.visibility = View.VISIBLE
        loadFiles(currentPath)
    }

    private fun loadFiles(path: String): Unit {
        launchAction {
            val normalized = DeviceManager.normalize(path.trim())
            listPanel.removeAllViews()
            listPanel.addView(description(R.string.loading_files))
            val entries = DeviceManager(this@MainActivity, checkNotNull(client)).files(normalized)
            currentPath = normalized
            pathInput.setText(currentPath)
            listPanel.removeAllViews()
            listPanel.addView(heading(R.string.remote_files))
            if (entries.isEmpty()) listPanel.addView(description(R.string.empty_folder))
            entries.forEach { entry ->
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                val type = when (entry.type) {
                    "directory" -> getString(R.string.file_type_directory)
                    "link" -> getString(R.string.file_type_link)
                    "file" -> getString(R.string.file_type_file)
                    else -> getString(R.string.file_type_other)
                }
                val title = if (entry.type == "file" || entry.directory) {
                    button(if (entry.directory) R.string.open_folder else R.string.download, track = false) {
                        if (entry.directory) loadFiles(entry.path) else chooseDownload(entry)
                    }
                } else TextView(this@MainActivity).apply {
                    textSize = 16f
                    setPadding(8.dp, 12.dp, 8.dp, 8.dp)
                }
                title.text = "$type  ${entry.name}"
                row.addView(title)
                row.addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.file_details, type, if (entry.directory) "—" else formatBytes(entry.size), formatTime(entry.modifiedSeconds))
                    textSize = 13f
                    setPadding(8.dp, 0, 8.dp, 4.dp)
                })
                if (entry.directory) row.addView(button(R.string.download_folder, track = false) { chooseDownload(entry) })
                listPanel.addView(row)
            }
            showStatus(R.string.status_files_loaded, entries.size, currentPath)
        }
    }

    private fun chooseDownload(entry: RemoteFile) {
        if (busy) return
        if (entry.type != "file" && entry.type != "directory") return
        pendingDownload = entry
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }, REQUEST_DOWNLOAD_FOLDER)
    }

    @Deprecated("Uses the platform document tree picker without an additional Activity dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DOWNLOAD_FOLDER) return
        val entry = pendingDownload
        pendingDownload = null
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK || entry == null) return
        launchAction {
            showStatus(R.string.status_downloading, entry.name)
            DeviceManager(this@MainActivity, checkNotNull(client)).download(entry, uri) { file ->
                runOnUiThread { statusView.text = getString(R.string.status_label, getString(R.string.status_downloading, file)) }
            }
            showStatus(R.string.status_download_complete, entry.name)
        }
    }

    private fun disconnect() = launchAction {
        withContext(Dispatchers.IO) { client?.disconnect() }
        connected = false
        clearBrowser()
        showStatus(R.string.status_disconnected)
    }

    private fun clearBrowser() {
        browserPanel.visibility = View.GONE
        listPanel.removeAllViews()
        pendingDownload = null
    }

    private fun launchAction(action: suspend () -> Unit): Job? {
        if (busy) return null
        busy = true
        updateButtons()
        return scope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "ADB operation failed", error)
                showStatus(R.string.status_operation_failed)
                appendLog(getString(R.string.log_error_detail, error.describe()))
            } finally {
                busy = false
                updateButtons()
            }
        }
    }

    private fun showStatus(message: Int, vararg args: Any) {
        val text = getString(message, *args)
        statusView.text = getString(R.string.status_label, text)
        appendLog(text)
    }

    private fun appendLog(message: String) {
        Log.i(TAG, message)
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM, resources.configuration.locales[0])
            .format(Date())
        logLines.addLast(getString(R.string.log_entry, time, message))
        if (logLines.size > 100) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
    }

    private fun updateButtons() {
        actionButtons.forEach { it.isEnabled = !busy }
        shellButton.isEnabled = connected && !busy
        appsButton.isEnabled = connected && !busy
        filesButton.isEnabled = connected && !busy
        disconnectButton.isEnabled = connected && !busy
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_MODE, selectedMode.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        qrDialog?.setOnDismissListener(null)
        qrDialog?.dismiss()
        stopQrPairing()
        unregisterReceiver(usbReceiver)
        scope.cancel()
        client?.close()
        super.onDestroy()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "AdbDemo"
        const val STATE_MODE = "connection_mode"
        const val REQUEST_DOWNLOAD_FOLDER = 41
    }
}

private fun Throwable.describe(): String = generateSequence(this) { current ->
    current.cause?.takeUnless { it === current }
}.map { it.message ?: it.javaClass.simpleName }.distinct().joinToString(": ")

internal fun String.isTailscaleAddress(): Boolean {
    val normalized = trim().removePrefix("[").removeSuffix("]").lowercase()
    if (normalized.startsWith("fd7a:115c:a1e0:")) return true
    val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
    return octets.size == 4 && octets.all { it in 0..255 } &&
        octets[0] == 100 && octets[1] in 64..127
}
