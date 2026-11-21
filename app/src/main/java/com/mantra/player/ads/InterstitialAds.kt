package com.mantra.player.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.mantra.player.BuildConfig

/**
 * Full-screen interstitial with a hard frequency cap so mantra switching stays usable
 * and AdMob policy risk stays low.
 */
object InterstitialAds {
    private const val TAG = "InterstitialAds"
    /** Soft cap: enough for revenue without spamming mantra switches. */
    private const val MIN_INTERVAL_MS = 5 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private var ad: InterstitialAd? = null
    private var loading = false
    private var lastShownAtMs = 0L
    private var showing = false

    val isShowing: Boolean get() = showing

    fun preload(context: Context) {
        val unitId = BuildConfig.ADMOB_INTERSTITIAL_ID
        if (unitId.isBlank() || loading || ad != null) return
        if (!AdsManager.adsEnabled(context)) return
        AdsManager.whenReady {
            if (!AdsManager.adsEnabled(context) || !AdsManager.canRequestAds(context) || loading || ad != null) {
                return@whenReady
            }
            loading = true
            InterstitialAd.load(
                context.applicationContext,
                unitId,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(interstitialAd: InterstitialAd) {
                        loading = false
                        ad = interstitialAd
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loading = false
                        ad = null
                        Log.w(TAG, "load failed: ${error.code} ${error.message}")
                    }
                }
            )
        }
    }

    /**
     * Shows an interstitial when one is ready and the frequency cap allows it.
     * Always runs [onContinue] afterward (immediately if no ad is shown).
     */
    fun showIfReady(activity: Activity, onContinue: () -> Unit) {
        if (!AdsManager.adsEnabled(activity)) {
            onContinue()
            return
        }
        val interstitial = ad
        val now = System.currentTimeMillis()
        if (
            interstitial == null ||
            activity.isFinishing ||
            showing ||
            now - lastShownAtMs < MIN_INTERVAL_MS
        ) {
            onContinue()
            preload(activity)
            return
        }

        showing = true
        ad = null
        interstitial.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                showing = false
                lastShownAtMs = System.currentTimeMillis()
                AppOpenAds.markFullscreenShown()
                main.post(onContinue)
                preload(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                showing = false
                Log.w(TAG, "show failed: ${error.message}")
                main.post(onContinue)
                preload(activity)
            }

            override fun onAdShowedFullScreenContent() {
                lastShownAtMs = System.currentTimeMillis()
                AppOpenAds.markFullscreenShown()
            }
        }
        interstitial.show(activity)
    }
}
