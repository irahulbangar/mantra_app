package com.mantra.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.mantra.player.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val nightPrefs by lazy { NightstandPreferences(this) }

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
        binding.startNightstandButton.setOnClickListener {
            NightstandLauncher.open(this)
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
