package com.mantra.player

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.lang.ref.WeakReference
import java.util.Calendar

object NightstandLauncher {
    private const val AUTO_ENTER_DELAY_MS = 10_000L
    private const val NIGHT_START_HOUR = 21 // 9 PM
    private const val NIGHT_END_HOUR = 6 // 6 AM

    @Volatile
    private var dismissedUntilUnplug = false

    @Volatile
    private var appInForeground = false

    private var foregroundActivity: WeakReference<Activity>? = null
    private var powerReceiverRegistered = false
    private var installed = false

    private val handler = Handler(Looper.getMainLooper())
    private var pendingAutoEnter: Runnable? = null

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    dismissedUntilUnplug = false
                    maybeAutoEnterFromForeground()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    dismissedUntilUnplug = false
                    cancelPendingAutoEnter()
                }
            }
        }
    }

    fun install(app: Application) {
        if (installed) return
        installed = true

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                foregroundActivity = WeakReference(activity)
                if (!isCharging(activity)) {
                    dismissedUntilUnplug = false
                    cancelPendingAutoEnter()
                }
                if (activity is MainActivity || activity is PlayerActivity) {
                    tryAutoEnter(activity)
                }
            }

            override fun onActivityPaused(activity: Activity) {
                val current = foregroundActivity?.get()
                if (current === activity) {
                    foregroundActivity = null
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                appInForeground = true
                registerPowerReceiver(app)
                maybeAutoEnterFromForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                appInForeground = false
                cancelPendingAutoEnter()
                unregisterPowerReceiver(app)
            }
        })
    }

    fun isCharging(context: Context): Boolean {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        if (battery.isCharging) return true
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun isNightHours(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
    }

    fun shouldAutoEnter(context: Context): Boolean {
        if (!isCharging(context)) {
            dismissedUntilUnplug = false
            return false
        }
        if (!isNightHours()) return false
        val prefs = NightstandPreferences(context)
        return prefs.enabled && !dismissedUntilUnplug
    }

    fun tryAutoEnter(activity: Activity) {
        if (activity is NightstandActivity || activity is SettingsActivity) return
        if (!shouldAutoEnter(activity)) {
            cancelPendingAutoEnter()
            return
        }
        scheduleAutoEnter()
    }

    fun open(context: Context) {
        cancelPendingAutoEnter()
        val intent = Intent(context, NightstandActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun markDismissed() {
        dismissedUntilUnplug = true
        cancelPendingAutoEnter()
    }

    private fun scheduleAutoEnter() {
        if (pendingAutoEnter != null) return
        val runnable = Runnable {
            pendingAutoEnter = null
            if (!appInForeground) return@Runnable
            val activity = foregroundActivity?.get() ?: return@Runnable
            if (activity is NightstandActivity || activity is SettingsActivity) return@Runnable
            if (activity !is MainActivity && activity !is PlayerActivity) return@Runnable
            if (!shouldAutoEnter(activity)) return@Runnable
            open(activity)
        }
        pendingAutoEnter = runnable
        handler.postDelayed(runnable, AUTO_ENTER_DELAY_MS)
    }

    private fun cancelPendingAutoEnter() {
        pendingAutoEnter?.let { handler.removeCallbacks(it) }
        pendingAutoEnter = null
    }

    private fun maybeAutoEnterFromForeground() {
        if (!appInForeground) return
        val activity = foregroundActivity?.get() ?: return
        if (activity is MainActivity || activity is PlayerActivity) {
            tryAutoEnter(activity)
        }
    }

    private fun registerPowerReceiver(app: Application) {
        if (powerReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(powerReceiver, filter)
        }
        powerReceiverRegistered = true
    }

    private fun unregisterPowerReceiver(app: Application) {
        if (!powerReceiverRegistered) return
        try {
            app.unregisterReceiver(powerReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
        powerReceiverRegistered = false
    }
}
