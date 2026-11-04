package com.mantra.player.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the Google consent flow (required for EEA/UK users) once per process,
 * then initializes the Mobile Ads SDK and releases anyone waiting to load ads.
 */
object AdsManager {
    /** Hashed IDs printed by the Ads SDK in logcat ("setTestDeviceIds(...)"); add more phones here. */
    private val TEST_DEVICE_IDS = listOf(
        "28B9D800D4E085EEE9E19CB8147310B4", // Rahul's phone, debug build
        "DB6EED67E74F493D02FA2A64C4BCDF95"  // Rahul's phone, release build (ID differs per signing key)
    )
    private val main = Handler(Looper.getMainLooper())
    private val consentStarted = AtomicBoolean(false)
    private val sdkStarted = AtomicBoolean(false)
    private var resolved = false
    private val waiting = mutableListOf<() -> Unit>()

    fun gatherConsent(activity: Activity) {
        if (!consentStarted.compareAndSet(false, true)) return
        val info = UserMessagingPlatform.getConsentInformation(activity)
        info.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    onResolved(activity)
                }
            },
            { onResolved(activity) }
        )
        if (info.canRequestAds()) {
            onResolved(activity)
        }
    }

    fun canRequestAds(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).canRequestAds()

    /** Runs [callback] on the main thread once consent has been resolved. */
    fun whenReady(callback: () -> Unit) {
        main.post {
            if (resolved) callback() else waiting += callback
        }
    }

    private fun onResolved(context: Context) {
        val app = context.applicationContext
        main.post {
            if (sdkStarted.compareAndSet(false, true)) {
                // Applied in release too: clicking real ads on your own phones can get the AdMob account banned.
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder().setTestDeviceIds(TEST_DEVICE_IDS).build()
                )
                MobileAds.initialize(app) {}
            }
            resolved = true
            val callbacks = waiting.toList()
            waiting.clear()
            callbacks.forEach { it() }
        }
    }
}
