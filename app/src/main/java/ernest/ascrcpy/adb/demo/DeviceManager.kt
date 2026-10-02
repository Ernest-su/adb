package ernest.ascrcpy.adb.demo

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import ernest.ascrcpy.adb.AdbClient
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceInfo(val model: String, val manufacturer: String, val android: String, val sdk: String, val abi: String, val serial: String)
data class AppInfo(val name: String, val packageName: String, val versionName: String, val versionCode: String, val system: Boolean)
data class RemoteFile(val name: String, val path: String, val directory: Boolean, val size: Long, val modifiedSeconds: Long)

class DeviceManager(private val context: Context, private val client: AdbClient) {
    suspend fun deviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        val values = props("ro.product.model", "ro.product.manufacturer", "ro.build.version.release", "ro.build.version.sdk", "ro.product.cpu.abilist", "ro.serialno")
        DeviceInfo(values[0], values[1], values[2], values[3], values[4], values[5])
    }

    suspend fun applications(): List<AppInfo> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Loading application list")
        val packages = shell("pm list packages -f").lineSequence().mapNotNull { line ->
            val raw = line.removePrefix("package:")
            val split = raw.lastIndexOf('=')
            if (split <= 0) null else raw.substring(split + 1).trim() to raw.substring(0, split)
        }.toList()
        packages.map { (pkg, apk) ->
            val dump = shell("dumpsys package ${quote(pkg)}")
            val versionName = Regex("(?m)^\\s*versionName=([^\\s]+)").find(dump)?.groupValues?.get(1) ?: "—"
            val versionCode = Regex("(?m)^\\s*versionCode=(\\d+)").find(dump)?.groupValues?.get(1) ?: "—"
            val label = Regex("(?m)nonLocalizedLabel=([^\\r\\n]+)").find(dump)?.groupValues?.get(1)?.trim()
                ?.takeUnless { it == "null" }
            AppInfo(label ?: pkg.substringAfterLast('.'), pkg, versionName, versionCode, apk.startsWith("/system/") || apk.startsWith("/product/") || apk.startsWith("/vendor/"))
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun files(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val normalized = normalize(path)
        Log.i(TAG, "Listing remote path=$normalized")
        val script = "for f in ${quote(if (normalized == "/") "/" else normalized + "/")}* ${quote(if (normalized == "/") "/" else normalized + "/")}.[!.]*; do [ -e \"\$f\" ] || continue; " +
            "if [ -d \"\$f\" ]; then t=d; else t=f; fi; s=\$(stat -c %s \"\$f\" 2>/dev/null || echo 0); m=\$(stat -c %Y \"\$f\" 2>/dev/null || echo 0); " +
            "n=\${f##*/}; printf '%s\\t%s\\t%s\\t%s\\n' \"\$t\" \"\$s\" \"\$m\" \"\$n\"; done"
        shell(script).lineSequence().mapNotNull { line ->
            val fields = line.split('\t', limit = 4)
            if (fields.size != 4) null else RemoteFile(fields[3], child(normalized, fields[3]), fields[0] == "d", fields[1].toLongOrNull() ?: 0, fields[2].toLongOrNull() ?: 0)
        }.sortedWith(compareByDescending<RemoteFile> { it.directory }.thenBy { it.name.lowercase() }).toList()
    }

    suspend fun download(entry: RemoteFile, treeUri: Uri, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)) { "Unable to access selected folder" }
        if (entry.directory) pullDirectory(entry.path, root, entry.name, progress) else pullFile(entry.path, root, entry.name, progress)
    }

    private suspend fun pullDirectory(remotePath: String, parent: DocumentFile, name: String, progress: (String) -> Unit) {
        val directory = parent.findFile(name)?.takeIf { it.isDirectory } ?: requireNotNull(parent.createDirectory(name)) { "Unable to create $name" }
        files(remotePath).forEach { child ->
            if (child.directory) pullDirectory(child.path, directory, child.name, progress)
            else pullFile(child.path, directory, child.name, progress)
        }
    }

    private suspend fun pullFile(remotePath: String, parent: DocumentFile, name: String, progress: (String) -> Unit) {
        progress(remotePath)
        val file = parent.findFile(name)?.also { it.delete() }?.let { parent.createFile("application/octet-stream", name) }
            ?: parent.createFile("application/octet-stream", name)
        requireNotNull(file) { "Unable to create $name" }
        context.contentResolver.openOutputStream(file.uri, "w").use { output ->
            client.pull(remotePath, requireNotNull(output) { "Unable to open $name" })
        }
    }

    private suspend fun props(vararg names: String): List<String> = names.map { shell("getprop ${quote(it)}").trim().ifBlank { "—" } }
    private suspend fun shell(command: String): String = client.shell(command).text()

    companion object {
        private const val TAG = "AdbDeviceManager"
        fun normalize(path: String): String = (if (path.startsWith('/')) path else "/$path").replace(Regex("/+"), "/").removeSuffix("/").ifEmpty { "/" }
        fun parent(path: String): String = normalize(path).substringBeforeLast('/', "").ifEmpty { "/" }
        fun child(parent: String, name: String): String = if (parent == "/") "/$name" else "$parent/$name"
        fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
    }
}

fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var unit = -1
    do { size /= 1024; unit++ } while (size >= 1024 && unit < units.lastIndex)
    return "%.1f %s".format(size, units[unit])
}

fun formatTime(seconds: Long): String = if (seconds <= 0) "—" else Instant.ofEpochSecond(seconds).toString().replace('T', ' ').removeSuffix("Z")
