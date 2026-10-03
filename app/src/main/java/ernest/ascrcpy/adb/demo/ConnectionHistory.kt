package ernest.ascrcpy.adb.demo

import android.content.Context
import android.util.Log
import ernest.ascrcpy.adb.AdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Connection details only. Pairing codes and private keys never enter preferences. */
internal data class ConnectionRecord(
    val kind: String,
    val host: String,
    val port: Int = 0,
    val guid: String = "",
    val vendorId: Int = 0,
    val productId: Int = 0,
    val usbName: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val identity: String get() = when (kind) {
        "TCP" -> "$kind:$host:$port"
        "USB" -> "$kind:$vendorId:$productId:$usbName"
        else -> "$kind:$guid"
    }
}

internal class ConnectionHistory(context: Context) {
    private val preferences = context.getSharedPreferences("connection_history", Context.MODE_PRIVATE)

    fun list(kind: String): List<ConnectionRecord> = all().filter { it.kind == kind }

    fun save(record: ConnectionRecord) {
        val entries = all().filterNot { it.identity == record.identity }.toMutableList()
        entries.add(record.copy(updatedAt = System.currentTimeMillis()))
        write(entries)
    }

    fun remove(record: ConnectionRecord) = write(all().filterNot { it.identity == record.identity })

    private fun all(): List<ConnectionRecord> = runCatching {
        val array = JSONArray(preferences.getString("entries", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val value = array.optJSONObject(index) ?: return@mapNotNull null
            val kind = value.optString("kind")
            val host = value.optString("host")
            if (kind !in setOf("TCP", "WIRELESS_CODE", "WIRELESS_QR", "USB") || host.isBlank()) return@mapNotNull null
            val port = value.optInt("port")
            val guid = value.optString("guid")
            if (kind != "USB" && port !in 1..65535) return@mapNotNull null
            if (kind.startsWith("WIRELESS_") && guid.isBlank()) return@mapNotNull null
            ConnectionRecord(kind, host, port, guid,
                value.optInt("vendorId"), value.optInt("productId"), value.optString("usbName"),
                value.optLong("updatedAt"))
        }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    private fun write(entries: List<ConnectionRecord>) {
        val array = JSONArray()
        entries.forEach { record ->
            array.put(JSONObject().apply {
                put("kind", record.kind)
                put("host", record.host)
                put("port", record.port)
                put("guid", record.guid)
                put("vendorId", record.vendorId)
                put("productId", record.productId)
                put("usbName", record.usbName)
                put("updatedAt", record.updatedAt)
            })
        }
        preferences.edit().putString("entries", array.toString()).apply()
    }
}

/** Owns the live connection while the connection and device pages change. */
internal object DeviceSession {
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var client: AdbClient? = null
        private set
    var label: String = ""
        private set

    fun set(active: AdbClient, name: String) {
        client?.takeIf { it !== active }?.let(::closeInBackground)
        client = active
        label = name
    }

    fun clear() {
        val previous = client
        client = null
        label = ""
        previous?.let(::closeInBackground)
    }

    private fun closeInBackground(active: AdbClient) {
        cleanupScope.launch {
            try { active.close() }
            catch (error: Exception) { Log.w("DeviceSession", "Unable to close ADB connection", error) }
        }
    }
}
