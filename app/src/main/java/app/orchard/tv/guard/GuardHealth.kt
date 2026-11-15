package app.orchard.tv.guard

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** An on-demand snapshot, not a new background poller or a permission grant. */
internal data class GuardHealth(
    val running: Boolean,
    val enabled: Boolean?,
    val canRestore: Boolean,
    val revivalAllowed: Boolean,
    val configured: Boolean?,
    val secureSettingsGranted: Boolean,
    /** null means the platform does not let this app inspect the restricted-settings app-op. */
    val restrictedSettingsBlocked: Boolean?,
    val otherGuardPackages: List<String>,
) {
    companion object {
        fun read(context: Context): GuardHealth {
            val me = ComponentName(context, HomeGuardService::class.java)
            val services = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                    .split(':').mapNotNull(ComponentName::unflattenFromString)
            }.getOrNull()
            val configured = services?.contains(me)
            // The legacy package may be Aerial or an older Halo. Warn, never disable it for users.
            val otherGuards = services.orEmpty().filter {
                it.packageName != context.packageName && it.className == HomeGuardService::class.java.name
            }.map { it.packageName }.distinct()
            val enabled = runCatching {
                val global = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
                configured?.let { global == 1 && it }
            }.getOrNull()
            val secure = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            val restricted = restrictedSettingsBlocked(context)
            return GuardHealth(HomeGuardService.isRunning, enabled,
                secure && restricted != true,
                runCatching { NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }.getOrDefault(false),
                configured, secure, restricted, otherGuards)
        }

        private fun restrictedSettingsBlocked(context: Context): Boolean? {
            if (Build.VERSION.SDK_INT < 33) return false
            // This op is not public on every firmware. Read only our UID, never grant it, and
            // keep unknown distinct from allowed. A retained setting is still not a bound service.
            return runCatching {
                val manager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                    ?: return@runCatching null
                @Suppress("DEPRECATION")
                val mode = manager.checkOpNoThrow("android:access_restricted_settings", Process.myUid(), context.packageName)
                when (mode) {
                    AppOpsManager.MODE_ALLOWED -> false
                    AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> true
                    else -> null
                }
            }.getOrNull()
        }
    }
}
