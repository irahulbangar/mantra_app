package com.mantra.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer

/**
 * Plays the selected mantra continuously on loop in the background.
 * Playback stops only when the user chooses another mantra or taps Stop.
 */
class MantraPlaybackService : Service() {
    private val prefs by lazy { MantraPreferences(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var currentId: String? = null
    private var armed = false
    private var foregroundStarted = false

    private val progressSaver = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, POSITION_SAVE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SELECT -> select(intent.getStringExtra(EXTRA_ID))
            ACTION_PREVIOUS -> select(Mantra.previousOf(currentId ?: prefs.mantraId).id)
            ACTION_NEXT -> select(Mantra.nextOf(currentId ?: prefs.mantraId).id)
            ACTION_TOGGLE -> togglePlayback()
            ACTION_STOP -> shutdown()
            else -> restore()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (armed) {
            startPlayback()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        savePosition()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun select(id: String?) {
        val mantra = Mantra.byId(id)
        if (mantra == null) {
            shutdown()
            return
        }

        // Same mantra already playing: do not break it.
        if (armed && currentId == mantra.id && player?.isPlaying == true) {
            showNotification()
            Log.i(TAG, "keep playing ${mantra.id}")
            return
        }

        val switching = currentId != null && currentId != mantra.id
        if (switching || prefs.mantraId != mantra.id) {
            prefs.positionMs = 0L
        }
        prefs.mantraId = mantra.id
        prefs.armed = true
        armed = true
        prepare(mantra, forceRestart = switching)
        startPlayback()
        Log.i(TAG, "selected ${mantra.id}")
    }

    private fun restore() {
        if (!prefs.armed) {
            stopSelf()
            return
        }
        val mantra = Mantra.byId(prefs.mantraId)
        if (mantra == null) {
            shutdown()
            return
        }
        armed = true
        prepare(mantra, forceRestart = false)
        startPlayback()
        Log.i(TAG, "restored ${mantra.id}")
    }

    private fun prepare(mantra: Mantra, forceRestart: Boolean) {
        val exo = ensurePlayer()
        if (!forceRestart && currentId == mantra.id && exo.mediaItemCount > 0) {
            return
        }
        val uri = RawResourceDataSource.buildRawResourceUri(mantra.rawRes)
        // Duplicate the same track so loop handoff stays continuous.
        val item = MediaItem.fromUri(uri)
        exo.setMediaItems(listOf(item, item))
        exo.prepare()
        exo.seekTo(prefs.positionMs.coerceAtLeast(0L))
        currentId = mantra.id
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        return ExoPlayer.Builder(this).build().also { exo ->
            exo.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            exo.setHandleAudioBecomingNoisy(true)
            exo.setWakeMode(C.WAKE_MODE_LOCAL)
            exo.repeatMode = Player.REPEAT_MODE_ALL
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    showNotification()
                    if (!isPlaying) {
                        savePosition()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && armed) {
                        exo.seekTo(0, 0L)
                        exo.play()
                    }
                }
            })
            player = exo
        }
    }

    private fun startPlayback() {
        val exo = player ?: return
        if (exo.playbackState == Player.STATE_IDLE) {
            exo.prepare()
        }
        exo.playWhenReady = true
        exo.play()
        showNotification()
        handler.removeCallbacks(progressSaver)
        handler.post(progressSaver)
        Log.i(TAG, "play $currentId at ${exo.currentPosition}")
    }

    private fun togglePlayback() {
        if (!armed) {
            restore()
            return
        }
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
            savePosition()
            handler.removeCallbacks(progressSaver)
        } else {
            startPlayback()
            return
        }
        showNotification()
    }

    private fun shutdown() {
        armed = false
        currentId = null
        prefs.clear()
        handler.removeCallbacks(progressSaver)
        player?.pause()
        player?.clearMediaItems()
        Log.i(TAG, "stop")
        foregroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun savePosition() {
        val exo = player ?: return
        if (!armed) {
            return
        }
        // Always store offset inside the current clip, not the second loop copy.
        val position = exo.currentPosition.coerceAtLeast(0L)
        prefs.positionMs = position
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun showNotification() {
        if (!armed) {
            return
        }
        val notification = buildNotification()
        if (!foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } else {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val mantra = Mantra.byId(currentId ?: prefs.mantraId)
        val playing = player?.isPlaying == true
        val openPlayer = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_MANTRA_ID, mantra?.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val previous = PendingIntent.getService(
            this,
            2,
            Intent(this, MantraPlaybackService::class.java).setAction(ACTION_PREVIOUS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val next = PendingIntent.getService(
            this,
            3,
            Intent(this, MantraPlaybackService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this,
            1,
            Intent(this, MantraPlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val art = mantra?.let { loadArtSquare(it.imageRes, 512) }
        val subtitle = buildString {
            append(mantra?.subtitle ?: getString(R.string.app_name))
            append(" · ")
            append(
                if (playing) getString(R.string.notification_playing)
                else getString(R.string.notification_paused)
            )
        }
        val playPauseIcon = if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play
        val playPauseLabel = getString(if (playing) R.string.pause else R.string.resume)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(mantra?.title ?: getString(R.string.app_name))
            .setContentText(subtitle)
            .setSubText(getString(R.string.now_playing_label))
            .setLargeIcon(art)
            .setContentIntent(openPlayer)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(ContextCompat.getColor(this, R.color.brand))
            .setColorized(true)
            .addAction(R.drawable.ic_media_previous, getString(R.string.previous), previous)
            .addAction(playPauseIcon, playPauseLabel, toggle)
            .addAction(R.drawable.ic_media_next, getString(R.string.next), next)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    /** Center-crops mantra art into a sharp square for the notification large icon (right side). */
    private fun loadArtSquare(imageRes: Int, size: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(resources, imageRes, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val minSide = minOf(bounds.outWidth, bounds.outHeight)
            while (minSide / sample > size * 2) sample *= 2
            val src = BitmapFactory.decodeResource(
                resources,
                imageRes,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val crop = minOf(src.width, src.height)
            val left = (src.width - crop) / 2
            val top = (src.height - crop) / 2
            val square = Bitmap.createBitmap(src, left, top, crop, crop)
            if (square !== src) src.recycle()
            if (square.width == size && square.height == size) return square
            val scaled = Bitmap.createScaledBitmap(square, size, size, true)
            if (scaled !== square) square.recycle()
            scaled
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "MantraPlayback"
        private const val CHANNEL_ID = "mantra_now_playing"
        private const val NOTIFICATION_ID = 42
        private const val POSITION_SAVE_MS = 5_000L

        const val ACTION_SELECT = "com.mantra.player.SELECT"
        const val ACTION_PREVIOUS = "com.mantra.player.PREVIOUS"
        const val ACTION_NEXT = "com.mantra.player.NEXT"
        const val ACTION_TOGGLE = "com.mantra.player.TOGGLE"
        const val ACTION_STOP = "com.mantra.player.STOP"
        const val EXTRA_ID = "mantra_id"

        fun select(context: Context, mantraId: String) {
            val intent = Intent(context, MantraPlaybackService::class.java).apply {
                action = ACTION_SELECT
                putExtra(EXTRA_ID, mantraId)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MantraPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
