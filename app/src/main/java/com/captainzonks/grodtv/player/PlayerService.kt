package com.captainzonks.grodtv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.captainzonks.grodtv.AppContainer
import com.captainzonks.grodtv.MainActivity
import com.captainzonks.grodtv.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PlayerService : MediaSessionService() {

    private lateinit var container: AppContainer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        container = appContainer
        startForegroundCompat()
        val player = container.playerController.player
        session = MediaSession.Builder(this, player).build()

        // Auto-advance: when current track ends, pop queue head, resolve, play.
        container.playerController.setOnEnded(object : OnEndedCallback {
            override fun onEnded() {
                scope.launch { advance() }
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when the user leaves the launcher. Audio-only podcast use case.
    }

    override fun onDestroy() {
        container.playerController.setOnEnded(null)
        session?.run { release() }
        session = null
        super.onDestroy()
    }

    private suspend fun advance() {
        val head = container.queueRepository.popHead() ?: run {
            container.queueRepository.clearNowPlaying()
            return
        }
        val client = container.pipedClient.value
        val quality = container.settings.value.defaultQuality
        client.resolve(head.videoId, quality)
            .onSuccess { video ->
                container.playerController.load(video)
                container.queueRepository.setNowPlaying(video.id, video.title)
            }
            .onFailure {
                // Resolve failed — skip and try next.
                advance()
            }
    }

    private fun startForegroundCompat() {
        val channelId = "grod_tv_player"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(channelId, "grod_tv Player", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("grod_tv player")
            .setContentText("Ready")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val NOTIF_ID = 1002

        fun start(context: android.content.Context) {
            val intent = Intent(context, PlayerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
