package app.orchard.tv.migration

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.UserManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.orchard.tv.BuildConfig
import app.orchard.tv.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Opt-in local transfer. No LAN endpoint, network token, system grant or signing secret is exported. */
object MigrationTransfer {
    private const val PREF = "halo_identity_migration"
    private val preferenceNames = setOf("settings", "restricted", "wallpaper", "layout", "weather", "tiles", "local_saver_selection", "online_wallpaper_rotation", "aerial_set")
    private val directories = setOf("photos", "icons", "screensaver-videos")
    private val files = setOf("online-bing-current.jpg", "online-bing-current.credit.json", "bing-current.jpg")
    private const val MAX_BYTES = 8L * 1024 * 1024 * 1024
    private const val RESERVE = 32L * 1024 * 1024
    @Volatile var onHandoff: (() -> Unit)? = null
    /** A handed-off bridge must also stay out of the way before the owner unlocks the TV. */
    fun handedOff(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 24) return c.getSharedPreferences(PREF, 0).getBoolean("handoff", false)
        val directBoot = c.createDeviceProtectedStorageContext().getSharedPreferences(PREF, 0)
        if (directBoot.contains("handoff")) return directBoot.getBoolean("handoff", false)
        // 3.1/3.1.1 stored this only in credential storage. Read and migrate that flag after unlock;
        // never open those preferences from a direct-boot service while the user is still locked.
        if (c.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return false
        return c.getSharedPreferences(PREF, 0).getBoolean("handoff", false).also { handedOff ->
            if (handedOff) directBoot.edit().putBoolean("handoff", true).commit()
        }
    }
    fun available(c: Context): Boolean {
        if (!MigrationIdentity.independent) return false
        val info = MigrationIdentity.installed(c, MigrationIdentity.legacy) ?: return false
        val provider = c.packageManager.resolveContentProvider("${MigrationIdentity.legacy}.halomigrate", 0) ?: return false
        return provider.packageName == info.packageName && MigrationIdentity.signers(info) == setOf(MigrationIdentity.OLD_CERT) && info.applicationInfo?.metaData?.getString("app.halotv.update.variant") == MigrationIdentity.edition
    }
    fun redirect(activity: Activity): Boolean {
        if (!MigrationIdentity.independent || activity.getSharedPreferences(PREF, 0).getBoolean("setup", false) || !available(activity)) return false
        activity.startActivity(Intent(activity, MigrationActivity::class.java)); activity.finish(); return true
    }
    fun targetInstalled(c: Context): Boolean {
        if (!MigrationIdentity.bridge) return false
        val info = MigrationIdentity.installed(c, MigrationIdentity.target) ?: return false
        return MigrationIdentity.signers(info) == setOf(BuildConfig.HALO_NEW_CERT) &&
            info.applicationInfo?.metaData?.getString("app.halotv.update.variant") == MigrationIdentity.edition
    }
    fun noteInstall(c: Context, code: Long) {
        c.getSharedPreferences(PREF, 0).edit().putLong("pendingCode", code).putLong("pendingAt", System.currentTimeMillis()).commit()
    }
    fun openIndependent(c: Context): Boolean {
        if (!targetInstalled(c)) return false
        val home = if (MigrationIdentity.edition == "lite") "app.orchard.tv.lite.HomeActivity" else "app.orchard.tv.MainActivity"
        val started = runCatching { c.startActivity(Intent().setClassName(MigrationIdentity.target, home).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        if (started) c.getSharedPreferences(PREF, 0).edit().remove("pendingCode").remove("pendingAt").apply()
        return started
    }
    fun launchPending(c: Context): Boolean {
        if (!MigrationIdentity.bridge) return false
        val p = c.getSharedPreferences(PREF, 0)
        val needed = p.getLong("pendingCode", 0L)
        if (needed <= 0L) return false
        if (System.currentTimeMillis() - p.getLong("pendingAt", 0) !in 0..(15 * 60_000L)) {
            p.edit().remove("pendingCode").remove("pendingAt").apply(); return false
        }
        val installed = MigrationIdentity.installed(c, MigrationIdentity.target) ?: return false
        return MigrationIdentity.code(installed) >= needed && openIndependent(c)
    }
    private fun handoff(c: Context) {
        // The new edition stays usable even when a TV blocks cross-app provider calls.
        runCatching { c.contentResolver.call(Uri.parse("content://${MigrationIdentity.legacy}.halomigrate"), "handoff", null, null) }
    }
    fun fresh(c: Context) { complete(c); if (available(c)) handoff(c) }
    fun complete(c: Context) { check(c.getSharedPreferences(PREF, 0).edit().putBoolean("setup", true).commit()) }
    private fun permitted(path: String): Boolean = path in files || directories.any { path.startsWith("$it/") && path.length > it.length + 1 }
    private fun exportPrefs(c: Context, filtered: Boolean = true): JSONObject = JSONObject().apply {
        for (name in preferenceNames) {
            val values = JSONObject()
            for ((k, value) in c.getSharedPreferences(name, 0).all) {
                // Device grants, launch recovery and remote access never transfer.
                if (filtered && (k.startsWith("guard_") || k in setOf("keep_adb", "setup_offered"))) continue
                val type = when (value) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; is Set<*> -> "set"; else -> continue }
                values.put(k, JSONObject().put("type", type).put("value", if (value is Set<*>) JSONArray(value.toList()) else value))
            }
            put(name, values)
        }
    }
    fun write(c: Context, out: java.io.OutputStream) {
        val roots = directories.map { File(c.filesDir, it) }
        val source = roots.flatMap { dir -> if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.toList() else emptyList() } + files.map { File(c.filesDir, it) }.filter { it.isFile }
        val safe = source.filter { f -> f.canonicalPath.startsWith(c.filesDir.canonicalPath + "/") && permitted(f.relativeTo(c.filesDir).invariantSeparatorsPath) }
        val size = safe.sumOf { it.length() }; require(size <= MAX_BYTES)
        ZipOutputStream(out).use { z ->
            z.setLevel(0)
            val meta = JSONObject().put("schema", 1).put("edition", MigrationIdentity.edition).put("bytes", size).put("preferences", exportPrefs(c))
            z.putNextEntry(ZipEntry("manifest.json")); z.write(meta.toString().toByteArray(Charsets.UTF_8)); z.closeEntry()
            for (f in safe) { z.putNextEntry(ZipEntry("files/" + f.relativeTo(c.filesDir).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z, 64 * 1024) }; z.closeEntry() }
        }
    }
    fun read(c: Context) {
        check(available(c)); rollback(c); val staging = File(c.cacheDir, "migration-staging"); staging.deleteRecursively(); check(staging.mkdirs())
        val stream = c.contentResolver.openInputStream(Uri.parse("content://${MigrationIdentity.legacy}.halomigrate/export")) ?: error("旧版未提供迁移数据")
        try {
            var count = 0L; var entries = 0; val seen = HashSet<String>(); var meta: JSONObject? = null
            ZipInputStream(stream).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    require(++entries <= 10000 && seen.add(e.name))
                    if (entries == 1) {
                        require(e.name == "manifest.json"); val out = java.io.ByteArrayOutputStream(); val b = ByteArray(4096)
                        while (true) { val n = z.read(b); if (n < 0) break; require(out.size() + n <= 1024 * 1024); out.write(b, 0, n) }
                        meta = JSONObject(String(out.toByteArray(), Charsets.UTF_8))
                        require(meta!!.getInt("schema") == 1 && meta!!.getString("edition") == MigrationIdentity.edition)
                        val needed = meta!!.getLong("bytes"); require(needed in 0..MAX_BYTES); check(c.filesDir.usableSpace >= needed + RESERVE) { "存储空间不足，请先释放空间后重试" }
                    } else {
                        require(e.name.startsWith("files/")); val relative = e.name.removePrefix("files/")
                        require(permitted(relative) && relative.split('/').none { it == ".." || it == "." } && '\\' !in relative)
                        val f = File(staging, relative); require(f.canonicalPath.startsWith(staging.canonicalPath + "/")); check(f.parentFile!!.isDirectory || f.parentFile!!.mkdirs())
                        f.outputStream().use { out -> val b = ByteArray(64 * 1024); while (true) { val n = z.read(b); if (n < 0) break; count += n; require(count <= MAX_BYTES && count <= meta!!.getLong("bytes")); check(c.filesDir.usableSpace > RESERVE); out.write(b, 0, n) } }
                    }
                    z.closeEntry()
                }
            }
            val m = meta ?: error("迁移数据不完整"); require(count == m.getLong("bytes"))
            val prefs = m.getJSONObject("preferences")
            // Build/validate editors before touching persistent state or material files.
            val editors = preferenceNames.associateWith { name ->
                val edit = c.getSharedPreferences(name, 0).edit()
                val values = prefs.optJSONObject(name)
                if (values != null) {
                    val keys = values.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        require(!k.startsWith("guard_") && k !in setOf("keep_adb", "setup_offered"))
                        applyValue(edit, k, values.getJSONObject(k))
                    }
                }
                if (name == "restricted") edit.putBoolean("suspended", true)
                edit
            }
            val imported = staging.walkTopDown().filter { it.isFile }.toList()
            val destinations = imported.map { File(c.filesDir, it.relativeTo(staging).path) }
            require(destinations.none { it.exists() }) { "独立版已有素材，未覆盖；可选择全新开始" }
            // Durable rollback plan protects retries after an interrupted copy/commit.
            val journal = File(c.filesDir, ".migration-journal.json")
            val backup = exportPrefs(c, filtered = false)
            journal.writeText(JSONObject().put("preferences", backup).put("files", JSONArray(destinations.map { it.relativeTo(c.filesDir).invariantSeparatorsPath })).toString())
            try {
                imported.zip(destinations).forEach { (f, dest) -> check(dest.parentFile!!.isDirectory || dest.parentFile!!.mkdirs()); check(f.renameTo(dest)) }
                editors.values.forEach { check(it.commit()) }
                complete(c)
                journal.delete()
            } catch (e: Exception) {
                rollback(c); throw e
            }
            handoff(c)
        } finally { staging.deleteRecursively() }
    }
    private fun applyValue(edit: android.content.SharedPreferences.Editor, key: String, v: JSONObject) {
        when (v.getString("type")) {
            "b" -> edit.putBoolean(key, v.getBoolean("value")); "i" -> edit.putInt(key, v.getInt("value"))
            "l" -> edit.putLong(key, v.getLong("value")); "f" -> edit.putFloat(key, v.getDouble("value").toFloat())
            "s" -> edit.putString(key, v.getString("value"))
            "set" -> { val a = v.getJSONArray("value"); edit.putStringSet(key, (0 until a.length()).map { a.getString(it) }.toSet()) }
            else -> error("不支持的设置类型")
        }
    }
    private fun rollback(c: Context) {
        val journal = File(c.filesDir, ".migration-journal.json")
        if (!journal.isFile) return
        val saved = JSONObject(journal.readText())
        val paths = saved.getJSONArray("files")
        for (i in 0 until paths.length()) {
            val path = paths.getString(i)
            if (permitted(path) && path.split('/').none { it == ".." || it == "." }) File(c.filesDir, path).delete()
        }
        val prefs = saved.getJSONObject("preferences")
        for (name in preferenceNames) {
            val values = prefs.getJSONObject(name); val edit = c.getSharedPreferences(name, 0).edit().clear()
            val keys = values.keys(); while (keys.hasNext()) { val k = keys.next(); applyValue(edit, k, values.getJSONObject(k)) }
            check(edit.commit())
        }
        check(journal.delete())
    }
    fun markHandoff(c: Context) {
        if (Build.VERSION.SDK_INT >= 24) {
            check(c.createDeviceProtectedStorageContext().getSharedPreferences(PREF, 0).edit().putBoolean("handoff", true).commit())
        }
        check(c.getSharedPreferences(PREF, 0).edit().putBoolean("handoff", true).commit())
        android.os.Handler(android.os.Looper.getMainLooper()).post { onHandoff?.invoke() }
        // Remove only this old app's own service entries; never disable other accessibility services.
        runCatching {
            val resolver = c.contentResolver; val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val retained = Settings.Secure.getString(resolver, key).orEmpty().split(':').filter { it.isNotBlank() && !it.startsWith(c.packageName + "/") }
            Settings.Secure.putString(resolver, key, retained.joinToString(":"))
        }
    }
    fun restore(c: Context) {
        c.getSharedPreferences(PREF, 0).edit().remove("handoff").apply()
        if (Build.VERSION.SDK_INT >= 24) {
            c.createDeviceProtectedStorageContext().getSharedPreferences(PREF, 0).edit().remove("handoff").apply()
        }
    }
}

/** Export requires the independent release certificate and exact paired package, not a shared PIN. */
class MigrationProvider : ContentProvider() {
    override fun onCreate() = true
    private fun authorize() {
        check(MigrationIdentity.bridge)
        val c = context ?: error("context")
        val pkgs = c.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (pkgs.none { it == MigrationIdentity.target && MigrationIdentity.signers(MigrationIdentity.installed(c, it)) == setOf(BuildConfig.HALO_NEW_CERT) }) throw SecurityException("Untrusted migration caller")
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        authorize(); require(mode == "r" && uri.path == "/export")
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({ runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { MigrationTransfer.write(context!!, it) } } }, "Halo-Migrate-Export").start()
        return pipe[0]
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        authorize(); require(method == "handoff"); MigrationTransfer.markHandoff(context!!); return Bundle().apply { putBoolean("ok", true) }
    }
    override fun getType(uri: Uri): String { authorize(); return "application/zip" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? { authorize(); return null }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw SecurityException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw SecurityException()
}

/** Shown before Home initializes its stores, so imported data is read normally on first launch. */
class MigrationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = 5894
        val scale = resources.displayMetrics.widthPixels / 960f
        fun dp(n: Int) = (n * scale).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(80), dp(30), dp(80), dp(30)); setBackgroundColor(Color.rgb(15, 33, 56)) }
        setContentView(root)
        fun text(value: String, size: Int, color: Int = Color.WHITE) = TextView(this).apply { text = value; textSize = size * scale / resources.displayMetrics.scaledDensity; setTextColor(color); setPadding(0, dp(8), 0, dp(8)); root.addView(this) }
        text(getString(R.string.halo_migration_welcome), 32).setTypeface(null, Typeface.BOLD)
        val status = text(getString(R.string.halo_migration_import_description), 16, Color.rgb(190, 211, 237))
        val buttons = ArrayList<Button>()
        fun button(label: Int, action: () -> Unit): Button = Button(this).apply {
            text = getString(label); textSize = 18 * scale / resources.displayMetrics.scaledDensity; isAllCaps = false
            setOnClickListener { action() }; root.addView(this, LinearLayout.LayoutParams(dp(600), dp(60)).apply { topMargin = dp(12) }); buttons.add(this)
        }
        button(R.string.halo_migration_import) {
            buttons.forEach { it.isEnabled = false }; status.text = getString(R.string.halo_migration_importing)
            Thread({
                val result = runCatching { MigrationTransfer.read(applicationContext) }
                runOnUiThread { if (isFinishing) return@runOnUiThread; result.onSuccess { status.text = getString(R.string.halo_migration_done); openHome() }.onFailure { e -> status.text = getString(R.string.halo_migration_failed, e.message.orEmpty()); buttons.forEach { it.isEnabled = true } } }
            }, "Halo-Migrate-Import").start()
        }.requestFocus()
        button(R.string.halo_migration_fresh) { MigrationTransfer.fresh(this); openHome() }
    }
    private fun openHome() {
        val home = if (MigrationIdentity.edition == "lite") "app.orchard.tv.lite.HomeActivity" else "app.orchard.tv.MainActivity"
        startActivity(Intent().setClassName(packageName, home).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish()
    }
}
