package com.mockloc

import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.provider.Settings

/**
 * One read-back from inside the hooked process, logged once per process at attach time.
 *
 * This exists because LSPosed refuses to put a module in its own scope, so the app's status page
 * can never show what the hooks do to other apps. Reading the same APIs a target app would read is
 * the only way to answer "did the substitution bite?" without a third-party app to watch.
 *
 * Read-only on purpose: registering location updates inside someone else's process would be a side
 * effect this module has no business causing.
 *
 * To make the result meaningful, point the config somewhere other than the system test provider -
 * if the log shows the config coordinate while `dumpsys location` shows the provider's, the hook
 * changed the value; if both show the same thing the test proves nothing.
 */
object SelfTest {

    private val probeProviders = listOf(
        LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused",
    )

    fun run(pkg: String, note: (String) -> Unit) {
        val app = runCatching {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
        }.getOrNull() as? Application ?: return note("$pkg 自检跳过：拿不到 Application")

        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return note("$pkg 自检跳过：拿不到 LocationManager")

        val fixes = probeProviders.joinToString(" ") { name ->
            val loc = runCatching { lm.getLastKnownLocation(name) }.getOrNull()
            "$name=" + (loc?.let { "${fmt(it.latitude)},${fmt(it.longitude)} mock=${mock(it)}" } ?: "null")
        }
        val providers = runCatching { lm.getProviders(false) }.getOrNull()
        val enabled = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()
        val setting = runCatching {
            Settings.Secure.getString(app.contentResolver, "mock_location")
        }.getOrNull()

        note("$pkg 自检(进程内读回) $fixes | providers=$providers gpsEnabled=$enabled mockSetting=$setting")
    }

    private fun fmt(value: Double) = "%.4f".format(value)

    @Suppress("DEPRECATION")
    private fun mock(location: android.location.Location): Boolean =
        location.isFromMockProvider
}
