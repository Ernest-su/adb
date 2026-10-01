package ernest.ascrcpy.adb.demo

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var client: AdbClient? = null
    private var connected = false
    private var busy = false

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var connectButton: Button
    private lateinit var shellButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusView: TextView
    private lateinit var outputView: TextView

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

        content.addView(TextView(this).apply {
            text = "Connect to a device with TCP ADB already enabled"
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

        connectButton = Button(this).apply { text = "Connect" }
        shellButton = Button(this).apply { text = "Read device model" }
        disconnectButton = Button(this).apply { text = "Disconnect" }
        content.addView(connectButton)
        content.addView(shellButton)
        content.addView(disconnectButton)

        statusView = TextView(this).apply { textSize = 16f }
        outputView = TextView(this).apply { textSize = 16f }
        content.addView(statusView)
        content.addView(outputView)
        showStatus("Disconnected")
        updateButtons()

        connectButton.setOnClickListener { connect() }
        shellButton.setOnClickListener { readDeviceModel() }
        disconnectButton.setOnClickListener { disconnect() }
    }

    private fun connect() {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) {
            showStatus("Enter a device host and a port from 1 to 65535")
            return
        }
        val endpoint = AdbEndpoint(host, port)
        launchAction {
            showStatus("Connecting to ${endpoint.serial}. Approve the RSA prompt on the device if shown.")
            outputView.text = ""
            withContext(Dispatchers.IO) {
                client?.close()
                client = DefaultAdbClient.factory(applicationContext).create()
                client!!.connect(endpoint)
            }
            connected = true
            showStatus("Connected to ${endpoint.serial}")
        }
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

    private fun launchAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        updateButtons()
        scope.launch {
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
        shellButton.isEnabled = connected && !busy
        disconnectButton.isEnabled = connected && !busy
    }

    override fun onDestroy() {
        scope.cancel()
        client?.close()
        super.onDestroy()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
