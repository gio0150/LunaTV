package app.orchard.tv.lite

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Bundle

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 30, 34])
@Suppress("DEPRECATION")
class MigrationIdentityTest {
    private val c = RuntimeEnvironment.getApplication()
    private fun cert(independent: Boolean) = Signature(javaClass.getResourceAsStream(if (independent) "/halo-independent-cert.der" else "/halo-release-cert.der")!!.readBytes())
    private fun info(pkg: String, independent: Boolean, code: Int = BuildConfig.VERSION_CODE + 1, edition: String = BuildConfig.HALO_EDITION) = PackageInfo().apply {
        packageName = pkg; versionName = "3.1"; versionCode = code; signatures = arrayOf(cert(independent))
        applicationInfo = ApplicationInfo().apply { packageName = pkg; if (android.os.Build.VERSION.SDK_INT >= 24) minSdkVersion = 14; metaData = Bundle().apply { putString("app.halotv.update.variant", edition) } }
    }
    @Before fun setup() { shadowOf(c.packageManager).installPackage(info(c.packageName, BuildConfig.HALO_IS_INDEPENDENT, BuildConfig.VERSION_CODE)) }
    @Test fun ownUpdateRequiresCurrentIdentityAndHigherCode() {
        assertNull(MigrationIdentity.error(c, info(c.packageName, BuildConfig.HALO_IS_INDEPENDENT)))
        assertEquals("not_newer", MigrationIdentity.error(c, info(c.packageName, BuildConfig.HALO_IS_INDEPENDENT, BuildConfig.VERSION_CODE)))
    }
    @Test fun migrationRequiresExactPairedPackageAndIndependentKey() {
        val target = info(BuildConfig.HALO_TARGET_PACKAGE, true, maxOf(BuildConfig.HALO_TARGET_MIN_CODE, BuildConfig.VERSION_CODE) + 1)
        assertNull(MigrationIdentity.error(c, target))
        assertEquals("wrong_signature", MigrationIdentity.error(c, target.apply { signatures = arrayOf(cert(false).takeIf { BuildConfig.HALO_IS_BRIDGE } ?: Signature(byteArrayOf(3))) }))
    }
    @Test fun unrelatedAppIsNeverAcceptedEvenWithReleaseCertificate() {
        assertEquals("wrong_app", MigrationIdentity.error(c, info("some.other.app", BuildConfig.HALO_IS_INDEPENDENT)))
    }
    @Test fun independentCannotInstallLegacyPackage() {
        val old = info(BuildConfig.HALO_LEGACY_PACKAGE, false)
        if (BuildConfig.HALO_IS_INDEPENDENT) assertEquals("wrong_app", MigrationIdentity.error(c, old))
        else assertNull(MigrationIdentity.error(c, old))
    }
    @Test fun wrongEditionCannotCrossMigrate() {
        assertEquals("wrong_variant", MigrationIdentity.error(c, info(BuildConfig.HALO_TARGET_PACKAGE, true, maxOf(BuildConfig.HALO_TARGET_MIN_CODE, BuildConfig.VERSION_CODE) + 1, "not-this-edition")))
    }
    @Test fun targetInstalledWithOtherKeyCannotBeOverwritten() {
        if (!BuildConfig.HALO_IS_BRIDGE) return
        shadowOf(c.packageManager).installPackage(info(BuildConfig.HALO_TARGET_PACKAGE, false))
        assertEquals("installed_signature_mismatch", MigrationIdentity.error(c, info(BuildConfig.HALO_TARGET_PACKAGE, true, maxOf(BuildConfig.HALO_TARGET_MIN_CODE, BuildConfig.VERSION_CODE) + 1)))
    }
    @Test fun migrationPendingCannotOpenUntrustedApp() {
        if (!BuildConfig.HALO_IS_BRIDGE) return
        shadowOf(c.packageManager).installPackage(info(BuildConfig.HALO_TARGET_PACKAGE, false))
        MigrationTransfer.noteInstall(c, BuildConfig.HALO_TARGET_MIN_CODE.toLong())
        assertFalse(MigrationTransfer.openIndependent(c))
        assertFalse(MigrationTransfer.launchPending(c))
    }
}
