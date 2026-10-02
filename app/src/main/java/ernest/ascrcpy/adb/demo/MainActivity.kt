package ernest.ascrcpy.adb.demo

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import ernest.ascrcpy.adb.transport.UsbAdbTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var client: AdbClient? = null
    private var connected = false
    private var busy = false

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var pairingPortInput: EditText
    private lateinit var pairingCodeInput: EditText
    private lateinit var connectButton: Button
    private lateinit var pairButton: Button
    private lateinit var qrPairButton: Button
    private lateinit var wirelessButton: Button
    private lateinit var usbButton: Button
    private lateinit var shellButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusView: TextView
    private lateinit var outputView: TextView
    private var qrDialog: AlertDialog? = null
    private var qrDiscovery: QrPairingDiscovery? = null
    private var qrJob: Job? = null
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val usbPermissionAction by lazy { "$packageName.USB_PERMISSION" }
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != usbPermissionAction) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                connectUsb(device)
            } else showStatus("USB permission denied")
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom,
                    )
                }
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

        content.addView(TextView(this).apply {
            text = "Connect with TCP ADB, Android 11+ Wireless debugging, or USB"
            textSize = 18f
        })
        hostInput = EditText(this).apply {
            hint = "Device IP address or host"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        content.addView(hostInput)
        portInput = EditText(this).apply {
            hint = "Port"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(AdbEndpoint.DEFAULT_ADB_PORT.toString())
            setSingleLine()
        }
        content.addView(portInput)

        pairingPortInput = EditText(this).apply {
            hint = "Wireless pairing port (different from connection port)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        content.addView(pairingPortInput)
        pairingCodeInput = EditText(this).apply {
            hint = "Six-digit pairing code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        content.addView(pairingCodeInput)

        connectButton = Button(this).apply { text = "Connect" }
        pairButton = Button(this).apply { text = "Pair wireless" }
        qrPairButton = Button(this).apply { text = "Pair with QR code" }
        wirelessButton = Button(this).apply { text = "Connect wireless" }
        usbButton = Button(this).apply { text = "Connect USB" }
        shellButton = Button(this).apply { text = "Read device model" }
        disconnectButton = Button(this).apply { text = "Disconnect" }
        content.addView(connectButton)
        content.addView(pairButton)
        content.addView(qrPairButton)
        content.addView(wirelessButton)
        content.addView(usbButton)
        content.addView(shellButton)
        content.addView(disconnectButton)

        statusView = TextView(this).apply { textSize = 16f }
        outputView = TextView(this).apply { textSize = 16f }
        content.addView(statusView)
        content.addView(outputView)
        showStatus("Disconnected")
        updateButtons()

        connectButton.setOnClickListener { connect() }
        pairButton.setOnClickListener { pairWireless() }
        qrPairButton.setOnClickListener { pairWithQrCode() }
        wirelessButton.setOnClickListener { connectWireless() }
        usbButton.setOnClickListener { selectUsb() }
        shellButton.setOnClickListener { readDeviceModel() }
        disconnectButton.setOnClickListener { disconnect() }
    }

    private fun connect() {
        connectWith(wireless = false)
    }

    private fun connectWireless() {
        connectWith(wireless = true)
    }

    private fun connectWith(wireless: Boolean) {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) {
            showStatus("Enter a device host and a port from 1 to 65535")
            return
        }
        val endpoint = AdbEndpoint(host, port)
        launchAction {
            showStatus("Connecting to ${endpoint.serial}")
            outputView.text = ""
            withContext(Dispatchers.IO) {
                client?.close()
                client = DefaultAdbClient.factory(applicationContext).create()
                if (wireless) client!!.connectWireless(endpoint) else client!!.connect(endpoint)
            }
            connected = true
            showStatus("Connected to ${endpoint.serial}")
        }
    }

    private fun pairWireless() {
        val host = hostInput.text.toString().trim()
        val port = pairingPortInput.text.toString().toIntOrNull()
        val code = pairingCodeInput.text.toString().trim()
        if (host.isEmpty() || port == null || port !in 1..65535 || !code.matches(Regex("[0-9]{6}"))) {
            showStatus("Enter host, wireless pairing port, and six-digit code")
            return
        }
        launchAction {
            showStatus("Pairing with $host:$port")
            val guid = withContext(Dispatchers.IO) {
                val pairingClient = DefaultAdbClient.factory(applicationContext).create()
                try { pairingClient.pairWireless(AdbEndpoint(host, port), code) }
                finally { pairingClient.close() }
            }
            pairingCodeInput.text.clear()
            showStatus("Paired ($guid). Enter the separate connection port, then Connect wireless.")
        }
    }

    private fun pairWithQrCode() {
        if (busy || qrDialog != null) return
        val qr = QrPairing(this)
        val image = ImageView(this).apply {
            setImageBitmap(qr.bitmap())
            adjustViewBounds = true
            setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            contentDescription = "Wireless debugging pairing QR code"
        }
        val instructions = TextView(this).apply {
            text = "On the other Android device: Developer options → Wireless debugging → Pair device with QR code. Keep both devices on the same Wi-Fi network."
            setPadding(24.dp, 16.dp, 24.dp, 8.dp)
        }
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(instructions)
            addView(image)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Pair wireless debugging")
            .setView(dialogContent)
            .setNegativeButton("Cancel", null)
            .create()
        qrDialog = dialog
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
                            showStatus("QR scanned. Pairing with ${service.host}:${service.port}")
                            val guid = withContext(Dispatchers.IO) {
                                val pairingClient = DefaultAdbClient.factory(applicationContext).create()
                                try { pairingClient.pairWireless(AdbEndpoint(service.host, service.port), qr.password) }
                                finally { pairingClient.close() }
                            }
                            showStatus("Paired ($guid). Finding the wireless connection...")
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
                                hostInput.setText(service.host)
                                showStatus("Paired ($guid). Connection service not found; enter the connection port shown on the device.")
                            } else {
                                val endpoint = AdbEndpoint(connection.host, connection.port)
                                withContext(Dispatchers.IO) {
                                    client?.close()
                                    client = DefaultAdbClient.factory(applicationContext).create()
                                    client!!.connectWireless(endpoint)
                                }
                                connected = true
                                hostInput.setText(connection.host)
                                portInput.setText(connection.port.toString())
                                showStatus("Paired and connected to ${endpoint.serial}")
                            }
                        } finally {
                            qrJob = null
                            dialog.dismiss()
                        }
                    }
                }
            },
            onConnection = { connections.trySend(it) },
            onError = { message ->
                showStatus(message)
                dialog.dismiss()
            },
        )
        qrDiscovery = discovery
        try {
            discovery.start()
            showStatus("Waiting for the other device to scan the QR code")
        } catch (error: Exception) {
            showStatus(error.message ?: "Could not start network discovery")
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
            showStatus("No USB ADB device found. Check USB Host mode and USB debugging.")
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
        showStatus("Connecting USB ${device.deviceName}")
        withContext(Dispatchers.IO) {
            client?.close()
            client = DefaultAdbClient.factory(applicationContext).create()
            client!!.connectUsb(device)
        }
        connected = true
        showStatus("Connected via USB")
    }

    private fun readDeviceModel() = launchAction {
        val result = withContext(Dispatchers.IO) {
            checkNotNull(client).shell("getprop ro.product.model").text()
        }
        outputView.text = result.ifBlank { "No output" }
        showStatus("Command completed")
    }

    private fun disconnect() = launchAction {
        withContext(Dispatchers.IO) { client?.disconnect() }
        connected = false
        showStatus("Disconnected")
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
                connected = false
                showStatus(error.message ?: error.javaClass.simpleName)
            } finally {
                busy = false
                updateButtons()
            }
        }
    }

    private fun showStatus(message: String) {
        statusView.text = "Status: $message"
    }

    private fun updateButtons() {
        connectButton.isEnabled = !busy
        pairButton.isEnabled = !busy
        qrPairButton.isEnabled = !busy
        wirelessButton.isEnabled = !busy
        usbButton.isEnabled = !busy
        shellButton.isEnabled = connected && !busy
        disconnectButton.isEnabled = connected && !busy
    }

    override fun onDestroy() {
        stopQrPairing()
        unregisterReceiver(usbReceiver)
        scope.cancel()
        client?.close()
        super.onDestroy()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
