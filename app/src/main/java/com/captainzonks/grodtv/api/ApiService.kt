package com.captainzonks.grodtv.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.captainzonks.grodtv.appContainer
import java.nio.charset.StandardCharsets

class ApiService : LifecycleService() {

    private var server: ApiServer? = null
    private var nsd: NsdManager? = null
    private var nsdReg: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()

        val c = appContainer
        val port = API_PORT
        val pin = c.settings.value.apiPin

        server = ApiServer(c, port = port, pin = pin).also { it.start() }
        registerMdns(port = port, pinSet = pin.isNotEmpty())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterMdns()
        server?.stop()
        server = null
        super.onDestroy()
    }

    // --- foreground + notification ---

    private fun startForegroundCompat() {
        val channelId = "grod_tv_api"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(channelId, "grod_tv API", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("grod_tv")
            .setContentText("API + discovery running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // --- mDNS ---

    private fun registerMdns(port: Int, pinSet: Boolean) {
        // Hold multicast lock so mDNS works on Wi-Fi-only TVs.
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("grod_tv_mdns").apply {
            setReferenceCounted(false)
            acquire()
        }

        val info = NsdServiceInfo().apply {
            serviceName = "grod_tv"
            serviceType = "_grod._tcp."
            this.port = port
            // TXT records — phone uses `device` to differentiate TV from Rust daemon.
            setAttribute("version", "0.0.1")
            setAttribute("pin", if (pinSet) "1" else "0")
            setAttribute("device", "grod-tv")
        }
        nsdReg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(p0: NsdServiceInfo?) {}
            override fun onServiceUnregistered(p0: NsdServiceInfo?) {}
            override fun onRegistrationFailed(p0: NsdServiceInfo?, p1: Int) {}
            override fun onUnregistrationFailed(p0: NsdServiceInfo?, p1: Int) {}
        }
        nsd = (getSystemService(Context.NSD_SERVICE) as NsdManager).also {
            it.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdReg!!)
        }
    }

    private fun unregisterMdns() {
        try { nsdReg?.let { nsd?.unregisterService(it) } } catch (_: Throwable) {}
        nsd = null
        nsdReg = null
        try { multicastLock?.release() } catch (_: Throwable) {}
        multicastLock = null
    }

    companion object {
        const val API_PORT = 7878
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ApiService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ApiService::class.java))
        }
    }
}

// Helper bytes (TXT setAttribute already encodes string-to-bytes; not used directly).
@Suppress("unused")
private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)
