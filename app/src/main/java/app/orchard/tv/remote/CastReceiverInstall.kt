package app.orchard.tv.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import app.orchard.tv.system.CompatibleHttp
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * The cast receiver stays a separate package by design. This downloads the pinning release and
 * hands it to the system installer, so the owner never has to find an APK by hand.
 */
object CastReceiverInstall {
    const val VERSION = "1.2.15"
    const val URL =
        "https://github.com/weekdayjast/MiracastReceiver/releases/download/v1.2.15/miracast-receiver-v1.2.15-armeabi-v7a.apk"
    private const val SHA256 = "5d447f48c4f0d5636728391a7be527c82b198e8111948a46ee58014d71e43bc5"
    private val PACKAGES = setOf("top.jast.miracastreceiver", "com.weekd.miracastreceiver")

    /** Cached, checksum-verified APK; downloads only when the cache is missing or wrong. */
    fun download(context: Context, onProgress: (Int) -> Unit = {}): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "miracast-receiver-$VERSION.apk")
        if (file.isFile && sha256(file) == SHA256) {
            onProgress(100)
            return file
        }
        val tmp = File(dir, file.name + ".part")
        val c = CompatibleHttp.open(URL)
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "LunaTV/$VERSION")
            check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
            val total = c.contentLength
            var read = 0L
            var last = -1
            c.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val pct = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else 0
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        require(sha256(tmp) == SHA256) { "receiver checksum mismatch" }
        require(tmp.length() > 0) { "empty receiver" }
        if (!tmp.renameTo(file)) { tmp.delete(); error("could not keep receiver") }
        onProgress(100)
        return file
    }

    /** Opens the system installer for a downloaded, package-checked APK. */
    fun install(context: Context, file: File): Boolean {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
        require(info != null && info.packageName in PACKAGES) { "unexpected receiver package" }
        val uri: Uri = if (android.os.Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } else Uri.fromFile(file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun sha256(file: File): String? = runCatching {
        MessageDigest.getInstance("SHA-256").apply {
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    update(buf, 0, n)
                }
            }
        }.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
