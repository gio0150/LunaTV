package app.orchard.tv.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.os.SystemClock
import android.view.KeyEvent
import app.orchard.tv.MainActivity
import app.orchard.tv.guard.HomeGuardService
import app.orchard.tv.settings.Restricted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phone remote commands exposed by the local phone page. Home keys use the foreground Activity
 * first; an explicitly connected loopback ADB session provides OneTV-style cross-app keys.
 */
object RemoteController {
    private const val MAX_TEXT = 2000

    data class Status(
        val guard: Boolean,
        val imeActive: Boolean,
        val imeSelected: Boolean,
        val secureSettings: Boolean,
        val adbEnabled: Boolean,
        val adbReady: Boolean,
        val adbPort: Int,
        val adbMessage: String,
    )

    fun status(context: Context): Status {
        val imeComponent = imeComponent(context)
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()
        val secure = context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val adb = SystemRemote.get(context)
        return Status(
            guard = HomeGuardService.isRunning,
            imeActive = RemoteInputService.isActive,
            imeSelected = current == imeComponent.flattenToShortString(),
            secureSettings = secure,
            adbEnabled = adb.enabled(),
            adbReady = adb.ready,
            adbPort = adb.port(),
            adbMessage = adb.message,
        )
    }

    fun command(context: Context, cmd: String, value: String?): Pair<Boolean, String> {
        if (Restricted.active) return false to "locked"
        return when (cmd) {
            "up", "down", "left", "right", "ok", "back", "home", "menu",
            "volumeUp", "volumeDown", "volumeMute", "power" -> handleAction(context, cmd)
            "inputText" -> sendText(context, value.orEmpty())
            "adbConnect" -> {
                val adb = SystemRemote.get(context)
                val connected = adb.connect(value?.toIntOrNull() ?: 5555)
                connected to if (connected) "ok" else adb.message
            }
            "adbDisconnect" -> {
                SystemRemote.get(context).disconnect()
                true to "ok"
            }
            else -> false to "unknown"
        }
    }

    private fun handleAction(context: Context, cmd: String): Pair<Boolean, String> {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (cmd) {
            "volumeUp" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                return true to "ok"
            }
            "volumeDown" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                return true to "ok"
            }
            "volumeMute" -> {
                val direction = if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    AudioManager.ADJUST_UNMUTE
                } else {
                    AudioManager.ADJUST_MUTE
                }
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                return true to "ok"
            }
            "power" -> {
                if (HomeGuardService.showPowerMenu()) return true to "ok"
                return systemAction(context, cmd, "guard_unavailable")
            }
            "home" -> {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val started = runCatching { context.startActivity(intent) }.isSuccess
                return if (started) true to "ok" else systemAction(context, cmd, "unavailable")
            }
            "back" -> {
                if (MainActivity.remoteInstance()?.let { it.dispatchBack() } == true) return true to "ok"
                if (HomeGuardService.performBack()) return true to "ok"
                return systemAction(context, cmd, "unavailable")
            }
        }

        val key = when (cmd) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
            "menu" -> KeyEvent.KEYCODE_MENU
            else -> return false to "unknown"
        }
        val activity = MainActivity.remoteInstance()
        if (activity?.dispatchRemoteKey(key) == true) return true to "ok"
        return systemAction(context, cmd, if (activity == null) "not_in_front" else "not_handled")
    }

    private fun systemAction(context: Context, cmd: String, fallback: String): Pair<Boolean, String> {
        val adb = SystemRemote.get(context)
        if (!adb.enabled()) return false to fallback
        if (!adb.ready && !adb.restore()) return false to "adb_disconnected"
        val sent = adb.command(cmd)
        return sent to if (sent) "ok" else "adb_failed"
    }

    private fun sendText(context: Context, raw: String): Pair<Boolean, String> {
        val text = raw.trim()
        if (!RemoteText.isValid(text)) return false to "invalid_text"

        val ime = imeComponent(context)
        val imeName = ime.flattenToShortString()
        if (RemoteInputService.isActive) {
            return if (RemoteInputService.commit(text)) true to "ok" else false to "commit_failed"
        }

        // The guard's accessibility binding can write into another app's focused editor without
        // the development-only secure-settings grant, which a normal APK reinstall drops.
        if (HomeGuardService.commitText(text)) return true to "ok"

        val secure = context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!secure) return false to if (HomeGuardService.isRunning) "guard_failed" else "guard_off"

        val resolver = context.contentResolver
        val previousDefault = runCatching {
            Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull().orEmpty()
        val previousEnabled = runCatching {
            Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS)
        }.getOrNull().orEmpty()
        val previousSubtype = runCatching {
            Settings.Secure.getInt(resolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE)
        }.getOrNull()

        val enabled = previousEnabled.split(':').filter { it.isNotBlank() }.toMutableList()
        val addedIme = imeName !in enabled
        var switched = false
        try {
            if (addedIme) {
                enabled += imeName
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_INPUT_METHODS, enabled.joinToString(":"))
            }
            Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, imeName)
            switched = true

            val ready = runBlocking(Dispatchers.Main) {
                withTimeoutOrNull(3500L) {
                    var nextShow = 0L
                    while (!RemoteInputService.isActive) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now >= nextShow) {
                            RemoteInputService.requestShow()
                            nextShow = now + 400L
                        }
                        kotlinx.coroutines.delay(50)
                    }
                    true
                }
            } ?: false
            if (!ready) return false to "ime_not_focused"
            return if (RemoteInputService.commit(text)) true to "ok" else false to "commit_failed"
        } catch (_: SecurityException) {
            return false to "secure_settings_denied"
        } finally {
            runCatching {
                if (switched) {
                    if (previousDefault.isNotBlank()) {
                        Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, previousDefault)
                    }
                    if (addedIme && previousEnabled.isNotBlank()) {
                        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_INPUT_METHODS, previousEnabled)
                    }
                    previousSubtype?.let {
                        Settings.Secure.putInt(resolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, it)
                    }
                }
            }
        }
    }

    private fun imeComponent(context: Context) =
        ComponentName(context, RemoteInputService::class.java)
}
