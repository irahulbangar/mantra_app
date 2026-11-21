package com.mantra.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import com.mantra.player.billing.BillingManager
import com.mantra.player.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val nightPrefs by lazy { NightstandPreferences(this) }
    private val premiumPrefs by lazy { PremiumPreferences(this) }

    private val premiumListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PremiumPreferences.KEY_ADS_REMOVED) {
            runOnUiThread { refreshRemoveAdsUi() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        binding.nightstandEnabled.isChecked = nightPrefs.enabled
        binding.nightModeEnabled.isChecked = nightPrefs.nightMode

        binding.nightstandEnabled.setOnCheckedChangeListener { _, checked ->
            nightPrefs.enabled = checked
        }
        binding.nightModeEnabled.setOnCheckedChangeListener { _, checked ->
            nightPrefs.nightMode = checked
        }

        bindLanguageChips()
        bindSleepChips()
        bindRemoveAds()
        binding.startNightstandButton.setOnClickListener {
            NightstandLauncher.open(this)
        }
    }

    override fun onStart() {
        super.onStart()
        premiumPrefs.registerListener(premiumListener)
        BillingManager.restore { refreshRemoveAdsUi() }
        refreshRemoveAdsUi()
        loadProductPrice()
    }

    override fun onStop() {
        premiumPrefs.unregisterListener(premiumListener)
        super.onStop()
    }

    private fun bindRemoveAds() {
        binding.removeAdsButton.setOnClickListener {
            if (premiumPrefs.adsRemoved) return@setOnClickListener
            binding.removeAdsButton.isEnabled = false
            BillingManager.launchPurchase(this) { result ->
                binding.removeAdsButton.isEnabled = !premiumPrefs.adsRemoved
                when (result) {
                    BillingManager.PurchaseResult.SUCCESS -> {
                        Toast.makeText(this, R.string.billing_purchase_success, Toast.LENGTH_SHORT).show()
                        refreshRemoveAdsUi()
                    }
                    BillingManager.PurchaseResult.CANCELED ->
                        Toast.makeText(this, R.string.billing_purchase_canceled, Toast.LENGTH_SHORT).show()
                    BillingManager.PurchaseResult.ALREADY_OWNED -> {
                        Toast.makeText(this, R.string.billing_already_owned, Toast.LENGTH_SHORT).show()
                        refreshRemoveAdsUi()
                    }
                    BillingManager.PurchaseResult.ERROR ->
                        Toast.makeText(this, R.string.billing_purchase_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.restorePurchasesButton.setOnClickListener {
            binding.restorePurchasesButton.isEnabled = false
            val before = premiumPrefs.adsRemoved
            BillingManager.restore { owned ->
                binding.restorePurchasesButton.isEnabled = true
                refreshRemoveAdsUi()
                val message = when {
                    owned || premiumPrefs.adsRemoved -> R.string.billing_restore_success
                    before -> R.string.billing_restore_success
                    else -> R.string.billing_restore_none
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadProductPrice() {
        BillingManager.queryProductDetails {
            refreshRemoveAdsUi()
        }
    }

    private fun refreshRemoveAdsUi() {
        if (premiumPrefs.adsRemoved) {
            binding.removeAdsButton.text = getString(R.string.settings_ads_removed)
            binding.removeAdsButton.isEnabled = false
            binding.restorePurchasesButton.isVisible = true
            return
        }
        binding.removeAdsButton.isEnabled = true
        val price = BillingManager.formattedPrice()
        binding.removeAdsButton.text = if (price.isNullOrBlank()) {
            getString(R.string.settings_remove_ads)
        } else {
            getString(R.string.settings_remove_ads_price, price)
        }
    }

    private fun bindLanguageChips() {
        binding.languageChips.removeAllViews()
        val selected = LocaleHelper.languageTag(this)
        listOf(
            LocaleHelper.SYSTEM,
            LocaleHelper.ENGLISH,
            LocaleHelper.HINDI
        ).forEach { tag ->
            val chip = Chip(this).apply {
                id = android.view.View.generateViewId()
                text = LocaleHelper.displayName(this@SettingsActivity, tag)
                isCheckable = true
                isChecked = tag == selected
                textSize = 16f
                setTextColor(
                    ContextCompat.getColorStateList(this@SettingsActivity, R.color.chip_sleep_text)
                )
                chipBackgroundColor =
                    ContextCompat.getColorStateList(this@SettingsActivity, R.color.chip_sleep_bg)
                setOnClickListener {
                    if (LocaleHelper.languageTag(this@SettingsActivity) == tag) return@setOnClickListener
                    LocaleHelper.setLanguageTag(this@SettingsActivity, tag)
                    syncLanguageSelection(tag)
                }
            }
            chip.tag = tag
            binding.languageChips.addView(chip)
        }
    }

    private fun syncLanguageSelection(selectedTag: String) {
        for (i in 0 until binding.languageChips.childCount) {
            val chip = binding.languageChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = (chip.tag as? String) == selectedTag
        }
    }

    private fun bindSleepChips() {
        binding.sleepTimerChips.removeAllViews()
        val selected = nightPrefs.sleepMinutes
        NightstandPreferences.SLEEP_OPTIONS_MINUTES.forEach { minutes ->
            val chip = Chip(this).apply {
                id = android.view.View.generateViewId()
                text = if (minutes == 0) {
                    getString(R.string.sleep_timer_off)
                } else {
                    getString(R.string.sleep_timer_minutes, minutes)
                }
                isCheckable = true
                isChecked = minutes == selected
                textSize = 16f
                setTextColor(
                    ContextCompat.getColorStateList(this@SettingsActivity, R.color.chip_sleep_text)
                )
                chipBackgroundColor =
                    ContextCompat.getColorStateList(this@SettingsActivity, R.color.chip_sleep_bg)
                setOnClickListener {
                    nightPrefs.sleepMinutes = minutes
                    syncChipSelection(minutes)
                }
            }
            chip.tag = minutes
            binding.sleepTimerChips.addView(chip)
        }
    }

    private fun syncChipSelection(selectedMinutes: Int) {
        for (i in 0 until binding.sleepTimerChips.childCount) {
            val chip = binding.sleepTimerChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = (chip.tag as? Int) == selectedMinutes
        }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}
