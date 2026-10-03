package ernest.ascrcpy.adb.demo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import ernest.ascrcpy.adb.transport.UsbAdbTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private data object ConnectRoute
    private data object DeviceRoute
    private val backStack = mutableStateListOf<Any>(ConnectRoute)
    private enum class Mode(val title: Int) {
        TCP(R.string.method_tcp), WIRELESS_CODE(R.string.method_wireless_code),
        WIRELESS_QR(R.string.method_wireless_qr), USB(R.string.method_usb),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val history by lazy { ConnectionHistory(this) }
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val usbPermissionAction by lazy { "$packageName.USB_PERMISSION" }
    private var selectedMode by mutableStateOf(Mode.TCP)
    private var tcpHost by mutableStateOf("")
    private var tcpPort by mutableStateOf(AdbEndpoint.DEFAULT_ADB_PORT.toString())
    private var pairingHost by mutableStateOf("")
    private var pairingPort by mutableStateOf("")
    private var pairingCode by mutableStateOf("")
    private var status by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var historyVersion by mutableIntStateOf(0)
    private var qrSession by mutableStateOf<QrPairing?>(null)
    private var qrDiscovery: QrPairingDiscovery? = null
    private var qrJob: Job? = null
    private var pendingUsbHistory: ConnectionRecord? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != usbPermissionAction) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                connectUsb(device, pendingUsbHistory?.kind ?: Mode.USB.name)
            } else showStatus(R.string.status_usb_denied)
            pendingUsbHistory = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedMode = savedInstanceState?.getString("mode")?.let { name ->
            Mode.entries.firstOrNull { it.name == name }
        } ?: Mode.TCP
        if (savedInstanceState?.getBoolean("device_route") == true && DeviceSession.client != null) {
            backStack.add(DeviceRoute)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, IntentFilter(usbPermissionAction), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, IntentFilter(usbPermissionAction))
        }
        status = getString(R.string.status_disconnected)
        setContent {
            MaterialTheme {
                NavDisplay(backStack = backStack, onBack = ::leaveDevice,
                    entryProvider = { key ->
                        when (key) {
                            ConnectRoute -> NavEntry(key) { ConnectionScreen() }
                            DeviceRoute -> NavEntry(key) { DeviceScreen(onDisconnect = ::leaveDevice) }
                            else -> error("Unknown route: $key")
                        }
                    })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        historyVersion++
    }

    @Composable
    private fun ConnectionScreen() {
        val records = remember(historyVersion, selectedMode) { history.list(selectedMode.name) }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.connection_method), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { mode ->
                    if (mode == selectedMode) Button(onClick = { selectedMode = mode }, enabled = !busy) {
                        Text(stringResource(mode.title))
                    } else OutlinedButton(onClick = { selectedMode = mode }, enabled = !busy) {
                        Text(stringResource(mode.title))
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (selectedMode) {
                        Mode.TCP -> {
                            Text(stringResource(R.string.tcp_description))
                            OutlinedTextField(tcpHost, { tcpHost = it }, label = { Text(stringResource(R.string.host_hint)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(tcpPort, { tcpPort = it }, label = { Text(stringResource(R.string.port_hint)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Button(onClick = ::connectTcp, enabled = !busy) { Text(stringResource(R.string.connect_tcp)) }
                        }
                        Mode.WIRELESS_CODE -> {
                            Text(stringResource(R.string.pairing_description))
                            OutlinedTextField(pairingHost, { pairingHost = it },
                                label = { Text(stringResource(R.string.pairing_host_hint)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(pairingPort, { pairingPort = it },
                                label = { Text(stringResource(R.string.pairing_port_hint)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(pairingCode, { pairingCode = it },
                                label = { Text(stringResource(R.string.pairing_code_hint)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                visualTransformation = PasswordVisualTransformation())
                            Button(onClick = ::pairWireless, enabled = !busy) { Text(stringResource(R.string.pair_wireless)) }
                        }
                        Mode.WIRELESS_QR -> {
                            Text(stringResource(R.string.qr_description))
                            Button(onClick = ::pairWithQrCode, enabled = !busy) { Text(stringResource(R.string.pair_qr)) }
                        }
                        Mode.USB -> {
                            Text(stringResource(R.string.usb_description))
                            Button(onClick = ::selectUsb, enabled = !busy) { Text(stringResource(R.string.connect_usb)) }
                        }
                    }
                }
            }
            Text(stringResource(R.string.connection_history), style = MaterialTheme.typography.titleLarge)
            if (records.isEmpty()) Text(stringResource(R.string.no_connection_history),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            records.forEach { record ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(if (record.kind == Mode.USB.name) record.usbName else record.host,
                                style = MaterialTheme.typography.titleMedium)
                            Text(if (record.kind == Mode.USB.name)
                                "USB ${record.vendorId}:${record.productId}" else "${record.host}:${record.port}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { connectHistory(record) }, enabled = !busy) {
                            Text(stringResource(R.string.connect_saved))
                        }
                        TextButton(onClick = { history.remove(record); historyVersion++ }, enabled = !busy) {
                            Text(stringResource(R.string.remove_history))
                        }
                    }
                }
            }
        }
        qrSession?.let { qr ->
            val qrImage = remember(qr) { qr.bitmap().asImageBitmap() }
            AlertDialog(onDismissRequest = ::stopQrPairing,
                title = { Text(stringResource(R.string.qr_title)) },
                text = { Column { Text(stringResource(R.string.qr_description)); Spacer(Modifier.height(12.dp));
                    Image(qrImage, contentDescription = stringResource(R.string.qr_image_description),
                        modifier = Modifier.fillMaxWidth()) } },
                confirmButton = {}, dismissButton = {
                    TextButton(onClick = ::stopQrPairing) { Text(stringResource(R.string.cancel)) }
                })
        }
    }

    private fun connectTcp() {
        val port = tcpPort.toIntOrNull()
        if (tcpHost.isBlank() || port == null || port !in 1..65535) {
            showStatus(R.string.status_invalid_endpoint); return
        }
        val endpoint = AdbEndpoint(tcpHost.trim(), port)
        launchAction {
            showStatus(R.string.status_connecting, endpoint.serial)
            connectAndOpen(ConnectionRecord(Mode.TCP.name, endpoint.host, endpoint.port)) { connect(endpoint) }
        }
    }

    private fun pairWireless() {
        val host = pairingHost.trim()
        val port = pairingPort.toIntOrNull()
        val code = pairingCode.trim()
        if (host.isEmpty() || port == null || port !in 1..65535 || !code.matches(Regex("[0-9]{6}"))) {
            showStatus(R.string.status_invalid_pairing); return
        }
        launchAction {
            showStatus(R.string.status_pairing, "$host:$port")
            val guid = withContext(Dispatchers.IO) {
                DefaultAdbClient.factory(applicationContext).create().let { pairingClient ->
                    try { pairingClient.pairWireless(AdbEndpoint(host, port), code) }
                    finally { pairingClient.close() }
                }
            }
            pairingCode = ""
            showStatus(R.string.status_finding_connection, guid)
            val service = discoverConnection(guid, 30_000)
            if (service == null) showStatus(R.string.status_connection_not_found, guid)
            else {
                val endpoint = AdbEndpoint(if (host.isTailscaleAddress()) host else service.host, service.port)
                connectAndOpen(ConnectionRecord(Mode.WIRELESS_CODE.name, endpoint.host, endpoint.port, guid)) {
                    connectWireless(endpoint)
                }
            }
        }
    }

    private fun pairWithQrCode() {
        if (busy || qrSession != null) return
        val qr = QrPairing()
        qrSession = qr
        val connections = Channel<QrPairingDiscovery.ResolvedService>(Channel.UNLIMITED)
        var started = false
        val discovery = QrPairingDiscovery(this, qr.serviceName,
            onPairing = { service ->
                if (!started) {
                    started = true
                    qrJob = launchAction {
                        try {
                            showStatus(R.string.status_qr_scanned, "${service.host}:${service.port}")
                            val guid = withContext(Dispatchers.IO) {
                                DefaultAdbClient.factory(applicationContext).create().let { pairingClient ->
                                    try { pairingClient.pairWireless(AdbEndpoint(service.host, service.port), qr.password) }
                                    finally { pairingClient.close() }
                                }
                            }
                            showStatus(R.string.status_finding_connection, guid)
                            val connection = withTimeoutOrNull(30_000) {
                                while (true) {
                                    val candidate = connections.receive()
                                    if (candidate.name.contains(guid, ignoreCase = true) || candidate.host == service.host) {
                                        return@withTimeoutOrNull candidate
                                    }
                                }
                                @Suppress("UNREACHABLE_CODE") null
                            }
                            if (connection == null) showStatus(R.string.status_connection_not_found, guid)
                            else {
                                val endpoint = AdbEndpoint(connection.host, connection.port)
                                connectAndOpen(ConnectionRecord(Mode.WIRELESS_QR.name, endpoint.host, endpoint.port, guid)) {
                                    connectWireless(endpoint)
                                }
                            }
                        } finally {
                            qrDiscovery?.stop(); qrDiscovery = null
                            qrSession = null; qrJob = null; connections.close()
                        }
                    }
                }
            }, onConnection = { connections.trySend(it) },
            onError = { code ->
                showStatus(R.string.status_discovery_failed)
                if (code != null) Log.e(TAG, "NSD error $code")
                stopQrPairing()
            })
        qrDiscovery = discovery
        try { discovery.start(); showStatus(R.string.status_qr_waiting) }
        catch (error: Exception) { stopQrPairing(); report(error) }
    }

    private fun stopQrPairing() {
        qrJob?.cancel(); qrJob = null
        qrDiscovery?.stop(); qrDiscovery = null
        qrSession = null
    }

    private fun connectHistory(record: ConnectionRecord) {
        when (record.kind) {
            Mode.TCP.name -> launchAction {
                val endpoint = AdbEndpoint(record.host, record.port)
                showStatus(R.string.status_connecting, endpoint.serial)
                connectAndOpen(record) { connect(endpoint) }
            }
            Mode.WIRELESS_CODE.name, Mode.WIRELESS_QR.name -> launchAction {
                showStatus(R.string.status_finding_connection, record.guid)
                val service = discoverConnection(record.guid, 10_000)
                val endpoint = if (service != null) AdbEndpoint(
                    if (record.host.isTailscaleAddress()) record.host else service.host, service.port)
                else AdbEndpoint(record.host, record.port)
                connectAndOpen(record.copy(host = endpoint.host, port = endpoint.port)) { connectWireless(endpoint) }
            }
            Mode.USB.name -> selectUsb(record)
        }
    }

    private suspend fun discoverConnection(guid: String, timeout: Long): QrPairingDiscovery.ResolvedService? {
        val connections = Channel<QrPairingDiscovery.ResolvedService>(Channel.UNLIMITED)
        val discovery = QrPairingDiscovery(this, null, {}, { connections.trySend(it) },
            { code -> if (code != null) Log.e(TAG, "NSD error $code") })
        return try {
            discovery.start()
            withTimeoutOrNull(timeout) {
                while (true) {
                    val service = connections.receive()
                    if (service.name.contains(guid, ignoreCase = true)) return@withTimeoutOrNull service
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        } finally { discovery.stop(); connections.close() }
    }

    private fun selectUsb(record: ConnectionRecord? = null) {
        val devices = UsbAdbTransport.discover(usbManager)
        val device = if (record == null) devices.firstOrNull() else
            devices.firstOrNull { it.deviceName == record.usbName } ?:
                devices.firstOrNull { it.vendorId == record.vendorId && it.productId == record.productId }
        if (device == null) { showStatus(R.string.status_usb_missing); return }
        pendingUsbHistory = record
        if (usbManager.hasPermission(device)) connectUsb(device, record?.kind ?: Mode.USB.name)
        else {
            val intent = PendingIntent.getBroadcast(this, 0,
                Intent(usbPermissionAction).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            usbManager.requestPermission(device, intent)
        }
    }

    private fun connectUsb(device: UsbDevice, kind: String) = launchAction {
        showStatus(R.string.status_usb_connecting, device.deviceName)
        connectAndOpen(ConnectionRecord(kind, device.deviceName, vendorId = device.vendorId,
            productId = device.productId, usbName = device.deviceName)) { connectUsb(device) }
    }

    private suspend fun connectAndOpen(record: ConnectionRecord, connect: suspend AdbClient.() -> Unit) {
        val active = DefaultAdbClient.factory(applicationContext).create()
        try {
            withContext(Dispatchers.IO) { active.connect() }
            history.save(record)
            historyVersion++
            DeviceSession.set(active, if (record.kind == Mode.USB.name) record.usbName else "${record.host}:${record.port}")
            if (backStack.lastOrNull() != DeviceRoute) backStack.add(DeviceRoute)
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { active.close() }.onFailure { Log.w(TAG, "Unable to close failed ADB connection", it) }
            }
            throw error
        }
    }

    private fun launchAction(action: suspend () -> Unit): Job? {
        if (busy) return null
        busy = true
        return scope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { report(error) }
            finally { busy = false }
        }
    }

    private fun report(error: Throwable) {
        Log.e(TAG, "ADB operation failed", error)
        status = getString(R.string.status_operation_failed) + " " + (error.message ?: error.javaClass.simpleName)
    }

    private fun showStatus(id: Int, vararg args: Any) { status = getString(id, *args); Log.i(TAG, status) }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("mode", selectedMode.name)
        outState.putBoolean("device_route", backStack.lastOrNull() == DeviceRoute)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        stopQrPairing()
        unregisterReceiver(usbReceiver)
        scope.cancel()
        if (isFinishing) DeviceSession.clear()
        super.onDestroy()
    }

    private fun leaveDevice() {
        DeviceSession.clear()
        if (backStack.lastOrNull() == DeviceRoute) backStack.removeAt(backStack.lastIndex)
        showStatus(R.string.status_disconnected)
    }

    private companion object { const val TAG = "AdbDemo" }
}

internal fun String.isTailscaleAddress(): Boolean {
    val normalized = trim().removePrefix("[").removeSuffix("]").lowercase()
    if (normalized.startsWith("fd7a:115c:a1e0:")) return true
    val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
    return octets.size == 4 && octets.all { it in 0..255 } &&
        octets[0] == 100 && octets[1] in 64..127
}
