package com.mantra.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.mantra.player.ads.AdSlotController
import com.mantra.player.ads.AdsManager
import com.mantra.player.databinding.ActivityPlayerBinding

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private val prefs by lazy { MantraPreferences(this) }
    private lateinit var adSlot: AdSlotController
    private var mantra: Mantra = Mantra.ALL.first()
    private var visualPlayer: ExoPlayer? = null
    private var visualVideoRes: Int? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { syncWithPlayback() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mantra = Mantra.byId(intent.getStringExtra(EXTRA_MANTRA_ID))
            ?: Mantra.byId(prefs.mantraId)
            ?: Mantra.ALL.first()

        binding.backButton.setOnClickListener { finish() }
        binding.prevButton.setOnClickListener { switchTo(Mantra.previousOf(mantra.id)) }
        binding.nextButton.setOnClickListener { switchTo(Mantra.nextOf(mantra.id)) }
        binding.playStopButton.setOnClickListener {
            if (MantraControls.isPlaying(this, mantra.id)) {
                MantraControls.stop(this)
            } else {
                switchTo(mantra)
            }
            render()
        }

        AdsManager.gatherConsent(this)
        adSlot = AdSlotController(this, binding.adContainer, binding.adPlaceholder) { visible ->
            binding.adSection.isVisible = visible
        }
        render()
        adSlot.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Mantra.byId(intent.getStringExtra(EXTRA_MANTRA_ID))?.let {
            mantra = it
            render()
        }
    }

    override fun onStart() {
        super.onStart()
        prefs.registerListener(prefsListener)
        syncWithPlayback()
    }

    override fun onResume() {
        super.onResume()
        visualPlayer?.play()
        adSlot.onResume()
    }

    override fun onPause() {
        adSlot.onPause()
        visualPlayer?.pause()
        super.onPause()
    }

    override fun onStop() {
        prefs.unregisterListener(prefsListener)
        super.onStop()
    }

    override fun onDestroy() {
        adSlot.release()
        releaseVisualPlayer()
        super.onDestroy()
    }

    private fun switchTo(target: Mantra) {
        mantra = target
        if (!MantraControls.play(this, target.id)) {
            Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_SHORT).show()
        }
        render()
    }

    /** Follows changes made from the notification (Previous / Next / Stop). */
    private fun syncWithPlayback() {
        if (prefs.armed) {
            Mantra.byId(prefs.mantraId)?.let { mantra = it }
        }
        render()
    }

    private fun render() {
        val playing = MantraControls.isPlaying(this, mantra.id)
        binding.playerTitle.text = mantra.title
        binding.playerSubtitle.text = mantra.subtitle
        binding.playerStatus.setText(
            if (playing) R.string.player_status_playing else R.string.player_status_stopped
        )
        binding.playStopButton.setImageResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play)
        binding.playStopButton.contentDescription =
            getString(if (playing) R.string.stop else R.string.play)
        binding.visualImage.setImageResource(mantra.imageRes)
        binding.adPlaceholderImage.setImageResource(mantra.imageRes)
        showVisualVideo(mantra.videoRes)
        adSlot.setMantra(mantra.id)
    }

    private fun showVisualVideo(videoRes: Int?) {
        if (videoRes == visualVideoRes) return
        visualVideoRes = videoRes
        if (videoRes == null) {
            releaseVisualPlayer()
            binding.visualVideo.isVisible = false
            return
        }
        val exo = visualPlayer ?: ExoPlayer.Builder(this).build().also { player ->
            // Silent visual only: never take audio focus from the mantra.
            player.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false
            )
            player.volume = 0f
            player.repeatMode = Player.REPEAT_MODE_ONE
            visualPlayer = player
            binding.visualVideo.player = player
        }
        exo.setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(videoRes)))
        exo.prepare()
        exo.playWhenReady = true
        binding.visualVideo.isVisible = true
    }

    private fun releaseVisualPlayer() {
        binding.visualVideo.player = null
        visualPlayer?.release()
        visualPlayer = null
    }

    companion object {
        const val EXTRA_MANTRA_ID = "mantra_id"

        fun open(context: Context, mantraId: String) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_MANTRA_ID, mantraId)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }
}
