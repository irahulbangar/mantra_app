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

/**
 * Shows an app-open ad when the user returns from the background.
 * Preloads while backgrounded so an ad is usually ready on return.
 */
object AppOpenAds : Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {
    private const val TAG = "AppOpenAds"
    private const val AD_TIMEOUT_MS = 4 * 60 * 60 * 1000L
    /** Short enough that return-to-app ads usually show, without every quick switch. */
    private const val MIN_INTERVAL_MS = 2 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var currentActivity: Activity? = null
    private var appOpenAd: AppOpenAd? = null
    private var loadTimeMs = 0L
    private var lastShownAtMs = 0L
    private var loading = false
    private var showing = false
    private var started = false
    private var movedToBackground = false

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

    override fun onStop(owner: LifecycleOwner) {
        movedToBackground = true
        // Load while the user is away so return has an ad ready.
        preload(force = true)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!movedToBackground) {
            preload()
            return
        }
        movedToBackground = false
        // Post so Activity.onStart/onResume have set currentActivity.
        main.post {
            val activity = currentActivity
            if (activity == null || activity.isFinishing) {
                preload(force = true)
                return@post
            }
            showIfAvailable(activity)
        }
    }

    fun preload(force: Boolean = false) {
        val app = application ?: return
        val unitId = BuildConfig.ADMOB_APP_OPEN_ID
        if (unitId.isBlank()) return
        if (!force && (loading || isAdAvailable())) return
        if (force && loading) return
        AdsManager.whenReady {
            if (!AdsManager.canRequestAds(app)) return@whenReady
            if (loading || isAdAvailable()) return@whenReady
            loading = true
            AppOpenAd.load(
                app,
                unitId,
                AdRequest.Builder().build(),
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        loading = false
                        appOpenAd = ad
                        loadTimeMs = System.currentTimeMillis()
                        Log.i(TAG, "app-open loaded")
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loading = false
                        appOpenAd = null
                        Log.w(TAG, "load failed: ${error.code} ${error.message}")
                        // Retry later so return visits can still get an ad.
                        main.postDelayed({ preload(force = true) }, 30_000L)
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
            System.currentTimeMillis() - lastShownAtMs < MIN_INTERVAL_MS
        ) {
            preload()
            return
        }
        if (!isAdAvailable()) {
            preload(force = true)
            return
        }

        val ad = appOpenAd ?: return
        showing = true
        appOpenAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                showing = false
                lastShownAtMs = System.currentTimeMillis()
                preload(force = true)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                showing = false
                Log.w(TAG, "show failed: ${error.message}")
                preload(force = true)
            }

            override fun onAdShowedFullScreenContent() {
                lastShownAtMs = System.currentTimeMillis()
            }
        }
        ad.show(activity)
    }

    private fun isAdAvailable(): Boolean {
        if (appOpenAd == null) return false
        val fresh = System.currentTimeMillis() - loadTimeMs < AD_TIMEOUT_MS
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
