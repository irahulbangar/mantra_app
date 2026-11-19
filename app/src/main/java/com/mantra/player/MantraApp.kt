package com.mantra.player

import android.app.Application
import com.mantra.player.ads.AppOpenAds

class MantraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppOpenAds.start(this)
    }
}
