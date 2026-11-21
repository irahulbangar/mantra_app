package com.mantra.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.mantra.player.ads.AdSlotController
import com.mantra.player.ads.AdsManager
import com.mantra.player.ads.AppOpenAds
import com.mantra.player.ads.InterstitialAds
import com.mantra.player.databinding.ActivityNightstandBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NightstandActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNightstandBinding
    private lateinit var adSlot: AdSlotController
    private val nightPrefs by lazy { NightstandPreferences(this) }
    private val playbackPrefs by lazy { MantraPreferences(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var sleepEndsAtMs: Long = 0L
    private var autoDimmed = false
    private var timeFormat = SimpleDateFormat("h:mm", Locale.getDefault())
    private var dateFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            updateSleepRemaining()
            handler.postDelayed(this, 1_000L)
        }
    }

    private val sleepEndRunnable = Runnable {
        MantraControls.stop(this)
        finishNightstand()
    }

    private val autoDimRunnable = Runnable {
        autoDimmed = true
        setScreenBrightness(if (nightPrefs.nightMode) DIM_BRIGHTNESS_NIGHT else DIM_BRIGHTNESS)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_DISCONNECTED -> finishNightstand()
                Intent.ACTION_BATTERY_CHANGED -> updateChargingStatus(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNightstandBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        refreshFormats()
        applyAppearance()
        bindMantraVisual()
        scheduleAutoDim()

        binding.nightstandRoot.setOnClickListener { wakeBrightness() }
        binding.exitButton.setOnClickListener {
            wakeBrightness()
            finishNightstand()
        }

        AdsManager.gatherConsent(this)
        AdsManager.whenReady {
            InterstitialAds.preload(this)
            AppOpenAds.preload()
        }
        adSlot = AdSlotController(
            activity = this,
            container = binding.adContainer,
            placeholder = binding.adPlaceholder,
            nativeLayoutRes = R.layout.ad_native_landscape,
            mediaAspectRatio = NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_LANDSCAPE
        ) { visible ->
            binding.adSection.isVisible = visible
        }
        val mantra = Mantra.byId(playbackPrefs.mantraId) ?: Mantra.ALL.first()
        adSlot.setMantra(mantra.id)
        adSlot.start()

        val sleepMinutes = nightPrefs.sleepMinutes
        if (sleepMinutes > 0) {
            sleepEndsAtMs = System.currentTimeMillis() + sleepMinutes * 60_000L
            binding.sleepRemaining.isVisible = true
            handler.postDelayed(sleepEndRunnable, sleepMinutes * 60_000L)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) {
            updateChargingStatus(sticky)
        }
        handler.post(clockTick)
    }

    override fun onResume() {
        super.onResume()
        adSlot.onResume()
    }

    override fun onPause() {
        adSlot.onPause()
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(clockTick)
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(sleepEndRunnable)
        handler.removeCallbacks(autoDimRunnable)
        adSlot.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishNightstand()
    }

    private fun finishNightstand() {
        NightstandLauncher.markDismissed()
        finish()
    }

    private fun bindMantraVisual() {
        val mantra = Mantra.byId(playbackPrefs.mantraId) ?: Mantra.ALL.first()
        binding.godImage.setImageResource(mantra.imageRes)
        binding.adPlaceholderImage.setImageResource(mantra.imageRes)
        binding.mantraLabel.text = mantra.title
    }

    private fun applyAppearance() {
        if (!nightPrefs.nightMode) {
            setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            binding.clockText.setTextColor(getColor(R.color.cream))
            binding.dateText.setTextColor(getColor(R.color.cream))
            binding.mantraLabel.setTextColor(getColor(R.color.cream))
            binding.sleepRemaining.setTextColor(getColor(R.color.cream))
            binding.chargingStatus.setTextColor(getColor(R.color.brand))
            binding.exitButton.setTextColor(getColor(R.color.cream))
            binding.godImage.colorFilter = null
            binding.imageScrim.setBackgroundColor(0x00000000)
            return
        }

        setScreenBrightness(START_BRIGHTNESS_NIGHT)

        val nightRed = 0xFFFF6B5A.toInt()
        binding.clockText.setTextColor(nightRed)
        binding.dateText.setTextColor(0xCCFF6B5A.toInt())
        binding.mantraLabel.setTextColor(0xCCFF6B5A.toInt())
        binding.sleepRemaining.setTextColor(0x99FF6B5A.toInt())
        binding.chargingStatus.setTextColor(nightRed)
        binding.exitButton.setTextColor(nightRed)
        binding.imageScrim.setBackgroundColor(0x33100000)

        val matrix = ColorMatrix(
            floatArrayOf(
                0.9f, 0f, 0f, 0f, 20f,
                0f, 0.25f, 0f, 0f, 0f,
                0f, 0f, 0.2f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        binding.godImage.colorFilter = ColorMatrixColorFilter(matrix)
    }

    private fun scheduleAutoDim() {
        handler.removeCallbacks(autoDimRunnable)
        autoDimmed = false
        handler.postDelayed(autoDimRunnable, AUTO_DIM_DELAY_MS)
    }

    private fun wakeBrightness() {
        autoDimmed = false
        if (nightPrefs.nightMode) {
            setScreenBrightness(START_BRIGHTNESS_NIGHT)
        } else {
            setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
        scheduleAutoDim()
    }

    private fun setScreenBrightness(value: Float) {
        val attrs = window.attributes
        attrs.screenBrightness = value
        window.attributes = attrs
    }

    private fun updateChargingStatus(battery: Intent) {
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val percent = if (level >= 0 && scale > 0) {
            (level * 100) / scale
        } else {
            -1
        }
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            NightstandLauncher.isCharging(this)

        binding.chargingStatus.text = when {
            percent < 0 && charging -> getString(R.string.nightstand_charging)
            percent < 0 -> getString(R.string.nightstand_on_battery)
            status == BatteryManager.BATTERY_STATUS_FULL ->
                getString(R.string.nightstand_charged, percent)
            charging -> getString(R.string.nightstand_charging_percent, percent)
            else -> getString(R.string.nightstand_battery_percent, percent)
        }
    }

    private fun refreshFormats() {
        val locale = Locale.getDefault()
        timeFormat = SimpleDateFormat("h:mm", locale)
        dateFormat = SimpleDateFormat("EEEE, MMM d", locale)
    }

    private fun updateClock() {
        val now = Date()
        binding.clockText.text = timeFormat.format(now)
        binding.dateText.text = dateFormat.format(now)
    }

    private fun updateSleepRemaining() {
        if (sleepEndsAtMs <= 0L) {
            binding.sleepRemaining.isVisible = false
            return
        }
        val remainingMs = (sleepEndsAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val totalSeconds = (remainingMs / 1000L).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        binding.sleepRemaining.isVisible = true
        binding.sleepRemaining.text = getString(R.string.nightstand_sleep_remaining, minutes, seconds)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val AUTO_DIM_DELAY_MS = 30_000L
        private const val DIM_BRIGHTNESS = 0.12f
        private const val DIM_BRIGHTNESS_NIGHT = 0.05f
        private const val START_BRIGHTNESS_NIGHT = 0.18f
    }
}
