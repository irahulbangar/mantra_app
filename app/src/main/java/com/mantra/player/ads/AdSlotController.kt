package com.mantra.player.ads

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.mantra.player.BuildConfig
import com.mantra.player.R

/**
 * Fills the player screen's ad slot with an AdMob native ad (video starts muted) or one of the
 * remotely scheduled house ads, rotating them while the screen is visible.
 */
class AdSlotController(
    private val activity: Activity,
    private val container: FrameLayout,
    private val placeholder: View,
    private val onSlotVisibilityChanged: (Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val scheduler = HouseAdScheduler(activity)
    private var config = RemoteAdConfig.DEFAULT
    private var nativeAd: NativeAd? = null
    private var housePlayer: ExoPlayer? = null
    private var showingHouse = false
    private var currentHouse: HouseAd? = null
    private var mantraId: String? = null
    private var failedAttempts = 0
    private var slotVersion = 0
    private var nextAdvanceAtMs = 0L
    private var resumed = false
    private var released = false

    private val advanceRunnable = Runnable { advance() }

    private val configPoll = Runnable {
        fetchConfig()
        schedulePoll()
    }

    private val refreshSeconds: Int
        get() = config.refreshSeconds.coerceAtLeast(MIN_REFRESH_SECONDS)

    /** Mantra currently on screen; house ads can be targeted to specific mantras. */
    fun setMantra(id: String) {
        if (id == mantraId) return
        val previous = mantraId
        mantraId = id
        if (previous == null || released || config.mode != RemoteAdConfig.Mode.HOUSE) return
        val showing = currentHouse
        val targeted = config.houseAds.any { it.mantras != null || it.excludeMantras.isNotEmpty() }
        // Re-pick when the visible ad doesn't belong to this mantra, or a targeted ad might now apply.
        if ((showing != null && !scheduler.matchesMantra(showing, id)) || (showing == null && targeted)) {
            advance()
        }
    }

    fun start() {
        config = RemoteAdConfigRepository.current(activity)
        advance()
        fetchConfig()
    }

    fun onResume() {
        resumed = true
        housePlayer?.play()
        if (nextAdvanceAtMs != 0L) {
            postAdvance((nextAdvanceAtMs - System.currentTimeMillis()).coerceAtLeast(0L))
        }
        schedulePoll()
    }

    fun onPause() {
        resumed = false
        handler.removeCallbacks(advanceRunnable)
        handler.removeCallbacks(configPoll)
        housePlayer?.pause()
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        clear()
    }

    private fun fetchConfig() {
        RemoteAdConfigRepository.refresh(activity) { latest ->
            if (!released && latest != config) {
                config = latest
                advance()
            }
        }
    }

    /** Decides what the slot shows next. Any in-flight AdMob request becomes stale. */
    private fun advance() {
        if (released) return
        slotVersion++
        handler.removeCallbacks(advanceRunnable)
        nextAdvanceAtMs = 0L
        when (config.mode) {
            RemoteAdConfig.Mode.OFF -> {
                clear()
                onSlotVisibilityChanged(false)
            }
            RemoteAdConfig.Mode.ADMOB -> loadAdmob(houseCheckSeconds = null)
            RemoteAdConfig.Mode.HOUSE -> {
                val now = System.currentTimeMillis()
                val ad = scheduler.pick(config.houseAds, mantraId, now)
                if (ad != null) {
                    showHouse(ad)
                    currentHouse = ad
                    scheduler.recordPlay(ad, now)
                    scheduleAdvance(ad.durationSeconds)
                } else {
                    val wait = scheduler.secondsUntilNextCheck(config.houseAds, mantraId, now)
                    if (config.fallbackToAdmob) {
                        loadAdmob(houseCheckSeconds = wait)
                    } else {
                        onSlotVisibilityChanged(true)
                        showPlaceholder()
                        wait?.let(::scheduleAdvance)
                    }
                }
            }
        }
    }

    /** [houseCheckSeconds] brings the slot back to house ads as soon as one may be eligible again. */
    private fun loadAdmob(houseCheckSeconds: Int?) {
        if (released) return
        onSlotVisibilityChanged(true)
        if (showingHouse) showPlaceholder()
        val version = slotVersion
        // A config change or house rotation while this request is in flight makes its result stale.
        fun stale() = released || version != slotVersion
        fun nextCheck(seconds: Int) = minOf(seconds, houseCheckSeconds ?: seconds)

        AdsManager.whenReady {
            if (stale()) return@whenReady
            if (!AdsManager.canRequestAds(activity)) {
                if (nativeAd == null) showPlaceholder()
                scheduleAdvance(nextCheck(refreshSeconds))
                return@whenReady
            }
            val loader = AdLoader.Builder(activity, BuildConfig.ADMOB_NATIVE_ID)
                .forNativeAd { ad ->
                    if (stale() || activity.isDestroyed) {
                        ad.destroy()
                    } else {
                        failedAttempts = 0
                        showNative(ad)
                        scheduleAdvance(nextCheck(refreshSeconds))
                    }
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w(TAG, "native ad failed: ${error.code} ${error.message}")
                        if (stale()) return
                        if (nativeAd == null) {
                            showPlaceholder()
                            // Empty slot: retry sooner, 10s, 20s, 40s, then the refresh interval.
                            val retry = (RETRY_BASE_SECONDS shl failedAttempts.coerceAtMost(2))
                                .coerceAtMost(refreshSeconds)
                            failedAttempts++
                            scheduleAdvance(nextCheck(retry))
                        } else {
                            scheduleAdvance(nextCheck(refreshSeconds))
                        }
                    }
                })
                .withNativeAdOptions(
                    NativeAdOptions.Builder()
                        .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
                        .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_ANY)
                        .build()
                )
                .build()
            loader.loadAd(AdRequest.Builder().build())
        }
    }

    private fun showNative(ad: NativeAd) {
        clear()
        nativeAd = ad
        val view = activity.layoutInflater.inflate(R.layout.ad_native, container, false) as NativeAdView
        val headline = view.findViewById<TextView>(R.id.ad_headline)
        val body = view.findViewById<TextView>(R.id.ad_body)
        val cta = view.findViewById<TextView>(R.id.ad_cta)
        val icon = view.findViewById<ImageView>(R.id.ad_icon)

        headline.text = ad.headline
        view.headlineView = headline
        view.mediaView = view.findViewById<MediaView>(R.id.ad_media)

        body.text = ad.body
        body.isVisible = !ad.body.isNullOrBlank()
        view.bodyView = body

        cta.text = ad.callToAction
        cta.isVisible = !ad.callToAction.isNullOrBlank()
        view.callToActionView = cta

        val iconDrawable = ad.icon?.drawable
        icon.setImageDrawable(iconDrawable)
        icon.isVisible = iconDrawable != null
        view.iconView = icon

        view.setNativeAd(ad)
        container.addView(view)
        placeholder.isVisible = false
    }

    private fun showHouse(house: HouseAd) {
        clear()
        showingHouse = true
        onSlotVisibilityChanged(true)
        val view = activity.layoutInflater.inflate(R.layout.ad_house, container, false)
        val image = view.findViewById<ImageView>(R.id.house_image)
        val video = view.findViewById<PlayerView>(R.id.house_video)
        val mute = view.findViewById<ImageButton>(R.id.house_mute)
        val headline = view.findViewById<TextView>(R.id.house_headline)
        val cta = view.findViewById<TextView>(R.id.house_cta)

        headline.text = house.headline
        headline.isVisible = !house.headline.isNullOrBlank()
        cta.text = house.cta ?: activity.getString(R.string.ad_learn_more)
        cta.isVisible = house.clickUrl != null
        house.clickUrl?.let { url ->
            val open = View.OnClickListener { openUrl(url) }
            view.setOnClickListener(open)
            cta.setOnClickListener(open)
        }

        val version = slotVersion
        val onMediaFailed = {
            if (!released && version == slotVersion && config.fallbackToAdmob) {
                loadAdmob(houseCheckSeconds = house.durationSeconds)
            }
        }

        if (house.isVideo) {
            val exo = ExoPlayer.Builder(activity).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    false
                )
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(MediaItem.fromUri(house.mediaUrl))
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(TAG, "house video failed: ${error.message}")
                        onMediaFailed()
                    }
                })
                prepare()
                playWhenReady = resumed
            }
            housePlayer = exo
            video.player = exo
            video.isVisible = true
            mute.isVisible = true
            mute.setOnClickListener {
                val unmute = exo.volume == 0f
                exo.volume = if (unmute) 1f else 0f
                mute.setImageResource(if (unmute) R.drawable.ic_volume_up else R.drawable.ic_volume_off)
            }
        } else {
            RemoteImageLoader.load(house.mediaUrl) { bitmap ->
                if (released || version != slotVersion) return@load
                if (bitmap != null) image.setImageBitmap(bitmap) else onMediaFailed()
            }
        }

        container.addView(view)
        placeholder.isVisible = false
    }

    private fun showPlaceholder() {
        clear()
        placeholder.isVisible = true
    }

    private fun clear() {
        container.removeAllViews()
        nativeAd?.destroy()
        nativeAd = null
        housePlayer?.release()
        housePlayer = null
        showingHouse = false
        currentHouse = null
    }

    private fun scheduleAdvance(seconds: Int) {
        nextAdvanceAtMs = System.currentTimeMillis() + seconds * 1000L
        if (resumed) postAdvance(seconds * 1000L)
    }

    private fun postAdvance(delayMs: Long) {
        handler.removeCallbacks(advanceRunnable)
        if (!released) handler.postDelayed(advanceRunnable, delayMs)
    }

    private fun schedulePoll() {
        handler.removeCallbacks(configPoll)
        if (resumed && !released) handler.postDelayed(configPoll, refreshSeconds * 1000L)
    }

    private fun openUrl(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // No browser available.
        }
    }

    companion object {
        private const val TAG = "AdSlot"
        private const val MIN_REFRESH_SECONDS = 60
        private const val RETRY_BASE_SECONDS = 10
    }
}
