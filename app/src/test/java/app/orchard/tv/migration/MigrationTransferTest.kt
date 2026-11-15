package app.orchard.tv.migration

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.UserManager
import android.provider.Settings
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 30])
class MigrationTransferTest {
    private val c = RuntimeEnvironment.getApplication()
    @Test fun exportContainsOnlySelectedPrefsAndUserMaterials() {
        c.getSharedPreferences("settings", 0).edit().putBoolean("show_weather", true).putBoolean("keep_adb", true).putBoolean("guard_enabled", true).commit()
        c.getSharedPreferences("restricted", 0).edit().putString("pin_hash", "saved-pin").commit()
        c.getSharedPreferences("phone_page", 0).edit().putString("token", "do-not-export").commit()
        File(c.filesDir, "photos").mkdirs(); File(c.filesDir, "photos/user.jpg").writeBytes(byteArrayOf(1,2,3))
        File(c.filesDir, "private-secret").writeText("never-export")
        val bytes = ByteArrayOutputStream(); MigrationTransfer.write(c, bytes)
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        assertTrue(entries.containsKey("files/photos/user.jpg"))
        assertFalse(entries.containsKey("files/private-secret"))
        val manifest = JSONObject(String(entries.getValue("manifest.json"), Charsets.UTF_8))
        val p = manifest.getJSONObject("preferences")
        assertFalse(p.has("phone_page"))
        assertFalse(p.getJSONObject("settings").has("keep_adb"))
        assertFalse(p.getJSONObject("settings").has("guard_enabled"))
        assertEquals("saved-pin", p.getJSONObject("restricted").getJSONObject("pin_hash").getString("value"))
        assertEquals(3, manifest.getLong("bytes"))
    }
    @Test fun handoffRemovesOnlyOwnAccessibilityEntry() {
        Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, c.packageName+"/Guard:another.app/Reader")
        MigrationTransfer.markHandoff(c)
        assertTrue(MigrationTransfer.handedOff(c))
        assertEquals("another.app/Reader", Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        MigrationTransfer.restore(c)
        assertFalse(MigrationTransfer.handedOff(c))
    }
    @Test fun untrustedMigrationCallerCannotReadDataOrStopGuard() {
        val provider = org.robolectric.Robolectric.buildContentProvider(MigrationProvider::class.java).create().get()
        assertTrue(runCatching { provider.call("handoff", null, null) }.isFailure)
        assertFalse(MigrationTransfer.handedOff(c))
    }
    @Test @Config(sdk = [30]) fun handedOffBridgeRemainsStoppedBeforeUnlock() {
        MigrationTransfer.markHandoff(c)
        shadowOf(c.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        assertTrue(MigrationTransfer.handedOff(lockedCredentials(c)))
    }
    @Test @Config(sdk = [30]) fun legacyHandoffMigratesOnlyAfterUnlock() {
        c.getSharedPreferences("halo_identity_migration", 0).edit().putBoolean("handoff", true).commit()
        val users = shadowOf(c.getSystemService(UserManager::class.java))
        users.setUserUnlocked(false)
        assertFalse(MigrationTransfer.handedOff(lockedCredentials(c)))
        users.setUserUnlocked(true)
        assertTrue(MigrationTransfer.handedOff(c))
        users.setUserUnlocked(false)
        assertTrue(MigrationTransfer.handedOff(lockedCredentials(c)))
    }
    @Test @Config(sdk = [30]) fun restoringLegacyGuardClearsDirectBootHandoffAsWell() {
        MigrationTransfer.markHandoff(c)
        MigrationTransfer.restore(c)
        shadowOf(c.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        assertFalse(MigrationTransfer.handedOff(lockedCredentials(c)))
    }

    // A real credential-protected Context throws here before USER_UNLOCKED. Make an accidental
    // read fail even on Robolectric versions that do not enforce credential storage locking.
    private fun lockedCredentials(base: Context) = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            throw IllegalStateException("Credential storage is locked")
    }
}
