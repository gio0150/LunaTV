package app.orchard.tv.remote

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Casting switch for the TV. The installed Miracast Receiver is preferred because it is the
 * receiver that actually advertises to phones; the firmware's own Wi-Fi Display setting is the
 * fallback on TVs without it. Only fixed, whitelisted shell commands reach adbd.
 */
object CastControl {
    private const val OLD_RECEIVER = "com.weekd.miracastreceiver"
    private const val NEW_RECEIVER = "top.jast.miracastreceiver"
    private val RECEIVERS = listOf(NEW_RECEIVER, OLD_RECEIVER)
    private const val PREFS = "luna_cast_control"
    private const val KEY_ON = "last_enabled"

    data class Snapshot(
        val receiverPackage: String?,
        val receiverVersion: String?,
        val serviceRunning: Boolean?,
        val connected: Boolean?,
        val systemWifiDisplay: Boolean?,
        val adbReady: Boolean,
        val address: String?,
        val detail: String? = null,
    ) {
        val installed: Boolean get() = receiverPackage != null
    }

    data class SwitchResult(val ok: Boolean, val enabled: Boolean, val error: String? = null)

    fun installedReceiver(context: Context): String? =
        RECEIVERS.firstOrNull { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }

    fun snapshot(context: Context): Snapshot {
        val pkg = installedReceiver(context)
        val version = pkg?.let { name ->
            runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(name, 0).versionName
            }.getOrNull()
        }
        val wifiDisplay = runCatching {
            Settings.Global.getInt(context.contentResolver, "wifi_display_on") == 1
        }.getOrNull()
        val remote = SystemRemote.get(context)
        if (!remote.ready && remote.enabled()) runCatching { remote.restore() }
        val adbReady = remote.ready
        var running: Boolean? = null
        var connected: Boolean? = null
        var detail: String? = null
        if (pkg != null && adbReady) {
            remote.castPid(pkg).onSuccess { out ->
                running = out.lineSequence().map { it.trim() }.any { it.isNotEmpty() && it.all(Char::isDigit) }
            }.onFailure { detail = it.message }
            remote.castProjection(pkg).onSuccess { out ->
                connected = out.contains(pkg)
            }
        }
        return Snapshot(pkg, version, running, connected, wifiDisplay, adbReady, lanAddress(), detail)
    }

    fun setEnabled(context: Context, enabled: Boolean): SwitchResult {
        val pkg = installedReceiver(context)
        if (pkg == null) return systemWifiDisplay(context, enabled)
        val remote = ensureRemote(context)
            ?: return SwitchResult(false, false, "no_adb")
        return if (enabled) {
            val result = remote.startCast(pkg)
            val pid = result.getOrNull()?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
            if (result.isSuccess && !pid.isNullOrEmpty()) {
                remember(context, true)
                SwitchResult(true, true)
            } else SwitchResult(false, false, result.exceptionOrNull()?.message ?: "start_failed")
        } else {
            val result = remote.stopCast(pkg)
            if (result.isSuccess) {
                remember(context, false)
                SwitchResult(true, false)
            } else SwitchResult(false, true, result.exceptionOrNull()?.message ?: "stop_failed")
        }
    }

    /**
     * The cast switch owns the ADB connection instead of depending on the phone remote page.
     * Reuse a saved session when possible; otherwise make the first loopback connection and let
     * adbd show its normal RSA authorization prompt on the TV.
     */
    private fun ensureRemote(context: Context): SystemRemote? {
        val remote = SystemRemote.get(context)
        if (remote.ready) return remote
        if (remote.enabled() && runCatching { remote.restore() }.getOrDefault(false)) return remote
        return if (runCatching { remote.connect(remote.port()) }.getOrDefault(false)) remote else null
    }

    fun lastDesired(context: Context): Boolean? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .let { if (it.contains(KEY_ON)) it.getBoolean(KEY_ON, false) else null }

    private fun remember(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }

    private fun systemWifiDisplay(context: Context, enabled: Boolean): SwitchResult = try {
        Settings.Global.putInt(
            context.contentResolver,
            "wifi_display_on",
            if (enabled) 1 else 0,
        )
        remember(context, enabled)
        SwitchResult(true, enabled)
    } catch (e: Exception) {
        SwitchResult(false, !enabled, e.message ?: "secure_settings_denied")
    }

    private fun lanAddress(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
        interfaces.toList()
            .filter { it.isUp && !it.isLoopback }
            .filterNot { it.name.startsWith("p2p") || it.name.startsWith("dummy") || it.name.startsWith("rmnet") }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()
}
