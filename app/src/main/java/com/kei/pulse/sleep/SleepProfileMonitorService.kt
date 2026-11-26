package com.kei.pulse.sleep

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.kei.pulse.AppContainer
import com.kei.pulse.MainActivity
import com.kei.pulse.R
import com.kei.pulse.i18n.AppLanguage
import com.kei.pulse.i18n.PulseStrings
import com.kei.pulse.i18n.resolvePulseStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SleepProfileMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transitionMutex = Mutex()
    private val container by lazy { AppContainer(this) }
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> applySleepProfile()
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> restorePreSleepState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            val settings = container.settingsStorage.settings.first()
            if (!settings.sleepProfileEnabled) {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun applySleepProfile() {
        serviceScope.launch {
            transitionMutex.withLock {
                val settings = container.settingsStorage.settings.first()
                val profileId = settings.sleepProfileId
                if (!settings.sleepProfileEnabled || profileId == null) return@withLock
                container.repository.applySleepProfile(profileId)
            }
        }
    }

    private fun restorePreSleepState() {
        serviceScope.launch {
            transitionMutex.withLock {
                val settings = container.settingsStorage.settings.first()
                if (!settings.sleepProfileEnabled) return@withLock
                container.repository.restorePreSleepState()
            }
        }
    }

    private fun currentStrings(): PulseStrings {
        val lang = runCatching {
            kotlinx.coroutines.runBlocking { container.settingsStorage.settings.first().appLanguage }
        }.getOrDefault(AppLanguage.ZH_TW)
        return resolvePulseStrings(lang)
    }

    private fun createNotificationChannel() {
        val strings = currentStrings()
        val channel = NotificationChannel(
            CHANNEL_ID,
            strings.notifySleepChannelName,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            description = strings.notifySleepChannelDesc
        }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val strings = currentStrings()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_underclock)
            .setContentTitle(strings.notifySleepTitle)
            .setContentText(strings.notifySleepContent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sleep_profile_monitoring"
        private const val NOTIFICATION_ID = 31

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SleepProfileMonitorService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SleepProfileMonitorService::class.java))
        }
    }
}
