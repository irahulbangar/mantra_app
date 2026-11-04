package com.mantra.player

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mantra.player.ads.AdsManager
import com.mantra.player.ads.RemoteAdConfigRepository
import com.mantra.player.databinding.ActivityMainBinding
import com.mantra.player.databinding.ItemMantraBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { MantraPreferences(this) }
    private val cards = linkedMapOf<String, ItemMantraBinding>()
    private var pendingMantraId: String? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { render() }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingMantraId?.let { openMantra(it) }
        pendingMantraId = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Mantra.ALL.forEach { mantra ->
            val item = ItemMantraBinding.inflate(layoutInflater, binding.mantraList, true)
            item.title.text = mantra.title
            item.subtitle.text = mantra.subtitle
            item.image.setImageResource(mantra.imageRes)
            item.root.setOnClickListener { onMantraClicked(mantra.id) }
            cards[mantra.id] = item
        }
        binding.nowPlayingBar.setOnClickListener {
            prefs.mantraId?.let { PlayerActivity.open(this, it) }
        }
        binding.nowPlayingStop.setOnClickListener {
            MantraControls.stop(this)
            render()
        }
        AdsManager.gatherConsent(this)
        RemoteAdConfigRepository.refresh(this)
        render()
    }

    override fun onStart() {
        super.onStart()
        prefs.registerListener(prefsListener)
        render()
    }

    override fun onStop() {
        prefs.unregisterListener(prefsListener)
        super.onStop()
    }

    private fun onMantraClicked(mantraId: String) {
        if (needsNotificationPermission()) {
            pendingMantraId = mantraId
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        openMantra(mantraId)
    }

    private fun openMantra(mantraId: String) {
        if (!MantraControls.play(this, mantraId)) {
            Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_SHORT).show()
        }
        render()
        PlayerActivity.open(this, mantraId)
    }

    private fun render() {
        val selected = if (prefs.armed) prefs.mantraId else null
        val mantra = Mantra.byId(selected)
        binding.status.text = if (mantra == null) {
            getString(R.string.status_idle)
        } else {
            getString(R.string.status_armed, mantra.title)
        }
        binding.nowPlayingBar.visibility = if (mantra != null) View.VISIBLE else View.GONE
        if (mantra != null) {
            binding.nowPlayingTitle.text = mantra.title
            binding.nowPlayingImage.setImageResource(mantra.imageRes)
        }
        cards.forEach { (id, item) ->
            val isSelected = id == selected
            item.root.strokeWidth = if (isSelected) dp(2) else dp(1)
            item.root.strokeColor = ContextCompat.getColor(
                this,
                if (isSelected) R.color.brand else R.color.stroke_idle
            )
            item.root.setCardBackgroundColor(
                ContextCompat.getColor(this, if (isSelected) R.color.card_selected else R.color.card)
            )
            item.badge.visibility = if (isSelected) View.VISIBLE else View.GONE
            item.image.strokeWidth = (if (isSelected) 3f else 2f) * resources.displayMetrics.density
        }
    }

    private fun needsNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
