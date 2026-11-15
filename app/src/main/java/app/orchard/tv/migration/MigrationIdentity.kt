package app.orchard.tv.migration

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import app.orchard.tv.BuildConfig
import java.security.MessageDigest

/** Package and certificate are an inseparable identity; names and version labels are not trust. */
object MigrationIdentity {
    const val OLD_CERT = "3ba29454f5977999ccf76bd129b1087d3eea25c2f0a8370441d4fab1ccaa9198"
    val bridge get() = BuildConfig.HALO_IS_BRIDGE
    val independent get() = BuildConfig.HALO_IS_INDEPENDENT
    val target get() = BuildConfig.HALO_TARGET_PACKAGE
    val legacy get() = BuildConfig.HALO_LEGACY_PACKAGE
    val edition get() = BuildConfig.HALO_EDITION
    val label get() = if (independent) "独立版" else "过渡版"
    @Suppress("DEPRECATION")
    fun signers(info: PackageInfo?): Set<String> {
        val modern = if (Build.VERSION.SDK_INT >= 28) runCatching { info?.signingInfo?.apkContentsSigners }.getOrNull() else null
        return (modern?.takeIf { it.isNotEmpty() } ?: info?.signatures)?.map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        }?.toSet().orEmpty()
    }
    @Suppress("DEPRECATION")
    fun installed(context: Context, pkg: String): PackageInfo? = runCatching {
        val flags = PackageManager.GET_SIGNATURES or PackageManager.GET_META_DATA or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        val preferred = context.packageManager.getPackageInfo(pkg, flags)
        if (signers(preferred).isNotEmpty() || Build.VERSION.SDK_INT < 28) preferred
        else context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES or PackageManager.GET_META_DATA)
    }.getOrNull()
    @Suppress("DEPRECATION")
    fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    fun supports(pkg: String, current: String): Boolean = pkg == current || (bridge && pkg == target)
    fun crossPackage(pkg: String, current: String) = bridge && pkg == target && pkg != current

    fun error(context: Context, next: PackageInfo): String? {
        val pkg = next.packageName
        if (!supports(pkg, context.packageName)) return "wrong_app"
        val incoming = signers(next)
        val current = signers(installed(context, context.packageName))
        if (incoming.isEmpty() || current.isEmpty()) return "signature_unavailable"
        if (current != setOf(BuildConfig.HALO_CERT)) return "installed_signature_mismatch"
        val migration = crossPackage(pkg, context.packageName)
        val expected = if (migration) BuildConfig.HALO_NEW_CERT else BuildConfig.HALO_CERT
        if (incoming != setOf(expected)) return "wrong_signature"
        val variant = next.applicationInfo?.metaData?.getString("app.halotv.update.variant")
        if (variant != edition) return "wrong_variant"
        if (Build.VERSION.SDK_INT >= 24 && (next.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT) return "unsupported"
        val installedTarget = if (migration) installed(context, target) else null
        if (installedTarget != null && signers(installedTarget) != incoming) return "installed_signature_mismatch"
        val floor = if (migration) installedTarget?.let(::code) ?: (BuildConfig.HALO_TARGET_MIN_CODE.toLong() - 1L) else BuildConfig.VERSION_CODE.toLong()
        if (code(next) <= floor) return "not_newer"
        return null
    }
}
