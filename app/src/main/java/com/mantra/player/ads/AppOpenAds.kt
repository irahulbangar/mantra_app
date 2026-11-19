package com.mantra.player.ads

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.mantra.player.BuildConfig
import java.util.Date

/**
 * Shows an app-open ad when the user returns to the app from the background.
 * Skips cold-start noise, consent, and recent interstitial/app-open shows.
 */
object AppOpenAds : Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {
    private const val TAG = "AppOpenAds"
    private const val AD_TIMEOUT_MS = 4 * 60 * 60 * 1000L
    /** Soft cap: return-to-app ads stay rare so listening is not interrupted often. */
    private const val MIN_INTERVAL_MS = 8 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var currentActivity: Activity? = null
    private var appOpenAd: AppOpenAd? = null
    private var loadTimeMs = 0L
    private var lastShownAtMs = 0L
    private var loading = false
    private var showing = false
    private var started = false

    fun start(app: Application) {
        if (started) return
        started = true
        application = app
        app.registerActivityLifecycleCallbacks(this)
        main.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
    }

    fun markFullscreenShown() {
        lastShownAtMs = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        val activity = currentActivity ?: return
        showIfAvailable(activity)
    }

    fun preload() {
        val app = application ?: return
        val unitId = BuildConfig.ADMOB_APP_OPEN_ID
        if (unitId.isBlank() || loading || isAdAvailable()) return
        AdsManager.whenReady {
            if (!AdsManager.canRequestAds(app) || loading || isAdAvailable()) return@whenReady
            loading = true
            AppOpenAd.load(
                app,
                unitId,
                AdRequest.Builder().build(),
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        loading = false
                        appOpenAd = ad
                        loadTimeMs = Date().time
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loading = false
                        Log.w(TAG, "load failed: ${error.code} ${error.message}")
                    }
                }
            )
        }
    }

    private fun showIfAvailable(activity: Activity) {
        if (
            showing ||
            InterstitialAds.isShowing ||
            activity.isFinishing ||
            !isAdAvailable() ||
            System.currentTimeMillis() - lastShownAtMs < MIN_INTERVAL_MS
        ) {
            preload()
            return
        }

        val ad = appOpenAd ?: return
        showing = true
        appOpenAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                showing = false
                lastShownAtMs = System.currentTimeMillis()
                preload()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                showing = false
                Log.w(TAG, "show failed: ${error.message}")
                preload()
            }

            override fun onAdShowedFullScreenContent() {
                lastShownAtMs = System.currentTimeMillis()
            }
        }
        ad.show(activity)
    }

    private fun isAdAvailable(): Boolean {
        if (appOpenAd == null) return false
        val fresh = Date().time - loadTimeMs < AD_TIMEOUT_MS
        if (!fresh) {
            appOpenAd = null
            return false
        }
        return true
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) {
        if (!showing) currentActivity = activity
    }
    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
    }
}
