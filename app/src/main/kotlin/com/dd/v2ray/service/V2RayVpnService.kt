package com.dd.v2ray.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dd.v2ray.MainActivity
import com.dd.v2ray.utils.V2RayCoreUtils

class V2RayVpnService : VpnService() {

    companion object {
        private const val TAG = "V2RayVpnService"
        private const val CHANNEL_ID = "DDV2Ray_VPN_Channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.dd.v2ray.START"
        const val ACTION_STOP = "com.dd.v2ray.STOP"
        const val ACTION_DEBUG_LOG = "com.dd.v2ray.DEBUG_LOG"
        const val EXTRA_CONFIG = "CONFIG_JSON"
        const val EXTRA_LOG_MSG = "DEBUG_MSG"

        var isRunning: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    private fun sendDebug(msg: String) {
        Log.d(TAG, msg)
        val intent = Intent(ACTION_DEBUG_LOG).apply {
            putExtra(EXTRA_LOG_MSG, msg)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            sendDebug("[VPN] Menghentikan VPN Service...")
            stopVpn()
            return START_NOT_STICKY
        }

        val configJson = intent?.getStringExtra(EXTRA_CONFIG) ?: ""
        if (configJson.isEmpty()) {
            sendDebug("[ERROR] Config JSON kosong!")
            stopSelf()
            return START_NOT_STICKY
        }

        startVpn(configJson)
        return START_STICKY
    }

    private fun startVpn(configJson: String) {
        sendDebug("[VPN] Menyiapkan Antarmuka TUN...")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        try {
            val builder = Builder()

            // Alokasi virtual gateway lokal
            builder.addAddress("26.26.26.1", 24)

            // Rute default traffic global
            builder.addRoute("0.0.0.0", 0)

            // DNS Resolvers
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("8.8.8.8")

            // Rute eksplisit DNS agar tidak blackhole
            try {
                builder.addRoute("1.1.1.1", 32)
                builder.addRoute("8.8.8.8", 32)
            } catch (e: Exception) {
                sendDebug("[WARN] Route DNS route: ${e.message}")
            }

            // Hindari infinite loop aplikasi sendiri
            try {
                builder.addDisallowedApplication(packageName)
                sendDebug("[VPN] Exclude package berhasil: $packageName")
            } catch (e: Exception) {
                sendDebug("[WARN] Gagal bypass package: ${e.message}")
            }

            builder.setSession("DDV2Ray")
            builder.setMtu(1500)
            
            // Menggunakan blocking read untuk kompatibilitas tun2socks Linux/Go
            builder.setBlocking(true)

            vpnInterface = builder.establish()
            val pfd = vpnInterface

            if (pfd != null) {
                val fd = pfd.detachFd()
                sendDebug("[VPN] TUN Terhubung (FD: $fd). Memulai Core V2Ray...")

                val success = V2RayCoreUtils.startCoreWithTun(this, configJson, fd) { coreLog ->
                    sendDebug("[CORE] $coreLog")
                }

                if (success) {
                    isRunning = true
                    sendDebug("[SUCCESS] Terowongan VPN & Core aktif!")
                } else {
                    sendDebug("[ERROR] Inisialisasi Core gagal.")
                    stopVpn()
                }
            } else {
                sendDebug("[ERROR] Interface TUN null (Izin ditolak/bentrok).")
                stopVpn()
            }

        } catch (e: Exception) {
            sendDebug("[FATAL] VPN Setup Error: ${e.message}")
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning = false
        V2RayCoreUtils.stopCore()

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            sendDebug("[WARN] Gagal menutup interface: ${e.message}")
        }
        vpnInterface = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
        sendDebug("[VPN] Service berhasil dihentikan.")
    }

    override fun protect(socket: Int): Boolean {
        return super.protect(socket)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DDV2Ray Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, V2RayVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setContentTitle("DDV2Ray Terhubung")
            .setContentText("Internet dialirkan lewat V2Ray")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Putuskan", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        sendDebug("[VPN] Hak akses dicabut pengguna.")
        stopVpn()
        super.onRevoke()
    }
}
