package com.example.security

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Security and privacy guarantees, checked on the merged manifest and resources the app ships with.
 * These protect promises made to users and in the store listing: nothing leaves the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION")
    private fun packageInfo(flags: Int) = context.packageManager.getPackageInfo(context.packageName, flags)

    @Test fun appCannotAccessTheInternet() {
        val requested = packageInfo(PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        val network = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_NETWORK_STATE"
        )
        assertTrue(
            "The welcome notice promises no data is sent; found ${requested intersect network}",
            (requested intersect network).isEmpty()
        )
    }

    @Test fun onlyExpectedPermissionsAreRequested() {
        val requested = packageInfo(PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        val allowed = setOf(
            "android.permission.CAMERA",               // fingertip pulse
            "android.permission.ACTIVITY_RECOGNITION", // step counter
            // Added by AndroidX for its own internal broadcasts, not a user-facing permission.
            "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
        val unexpected = requested - allowed
        assertTrue(
            "New permission(s) $unexpected: update the privacy policy, Data Safety form and this test",
            unexpected.isEmpty()
        )
    }

    @Test fun onlyTheLauncherScreenIsOpenToOtherApps() {
        val info = packageInfo(
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        )
        val components = info.activities.orEmpty().toList() + info.services.orEmpty() +
            info.receivers.orEmpty() + info.providers.orEmpty()
        // Exported without a protecting permission = any app on the phone can start it.
        val openToAll = components.filter { it.exported && it.permission() == null }.map { it.name }.toSet()
        // Developer builds also contain Compose preview/test screens (debugImplementation only);
        // store builds must expose nothing but the launcher screen.
        val debugTooling = setOf("androidx.compose.ui.tooling.PreviewActivity", "androidx.activity.ComponentActivity")
        val allowed = setOf("com.example.MainActivity") + if (BuildConfig.DEBUG) debugTooling else emptySet()
        assertTrue("launcher screen must stay reachable", "com.example.MainActivity" in openToAll)
        assertEquals("components open to any app", emptySet<String>(), openToAll - allowed)
    }

    @Test fun measurementsAreExcludedFromCloudBackup() {
        // Android 12+ rules
        val cloudExcludes = readRules(R.xml.data_extraction_rules, section = "cloud-backup")
        // Android 11 and older
        val legacy = readRules(R.xml.backup_rules, section = null)
        for (healthData in listOf("exclude:database:.", "exclude:sharedpref:step_counter.xml")) {
            assertTrue("cloud backup must contain $healthData: $cloudExcludes", healthData in cloudExcludes)
            assertTrue("legacy backup must contain $healthData: $legacy", healthData in legacy)
        }
    }

    /** "include:domain:path" / "exclude:domain:path" entries, optionally only inside one section element. */
    private fun readRules(xmlRes: Int, section: String?): List<String> {
        val parser = context.resources.getXml(xmlRes)
        val rules = mutableListOf<String>()
        var inSection = section == null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    section -> inSection = true
                    "include", "exclude" -> if (inSection) {
                        rules += "${parser.name}:${parser.getAttributeValue(null, "domain")}:" +
                            parser.getAttributeValue(null, "path")
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == section) inSection = false
            }
        }
        return rules
    }

    private fun android.content.pm.ComponentInfo.permission(): String? = when (this) {
        is android.content.pm.ActivityInfo -> permission
        is android.content.pm.ServiceInfo -> permission
        is android.content.pm.ProviderInfo -> readPermission ?: writePermission
        else -> null
    }
}
