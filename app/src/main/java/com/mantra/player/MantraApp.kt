package com.mantra.player

import android.app.Application
import com.mantra.player.ads.AppOpenAds
import com.mantra.player.billing.BillingManager

class MantraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleHelper.applyStoredLocale(this)
        BillingManager.start(this)
        AppOpenAds.start(this)
        NightstandLauncher.install(this)
    }
}
