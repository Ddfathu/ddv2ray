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
        const val EXTRA_CONFIG = "CONFIG_JSON"

        var isRunning: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        val configJson = intent?.getStringExtra(EXTRA_CONFIG) ?: ""
        if (configJson.isEmpty()) {
            Log.e(TAG, "Config JSON kosong, membatalkan service...")
            stopSelf()
            return START_NOT_STICKY
        }

        startVpn(configJson)
        return START_STICKY
    }

    private fun startVpn(configJson: String) {
        Log.d(TAG, "Memulai inisialisasi VPN TUN...")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        try {
            val builder = Builder()

            // 1. Alokasi IP virtual antarmuka TUN (lokal)
            builder.addAddress("26.26.26.1", 24)

            // 2. Full Internet Route (Seluruh traffic dialirkan ke TUN)
            builder.addRoute("0.0.0.0", 0)

            // Dukungan IPv6 (opsional namun penting untuk mencegah DNS/Traffic leak)
            try {
                builder.addAddress("fdfe:dcba:9876::1", 64)
                builder.addRoute("::", 0)
            } catch (e: Exception) {
                Log.w(TAG, "IPv6 route tidak didukung di perangkat ini: ${e.message}")
            }

            // 3. DNS Resolver default
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("8.8.8.8")

            // 4. KUNCI FULL INTERNET: Hindari Loopback aplikasi sendiri
            // Aplikasi ini sendiri tidak boleh di-route ke VPN agar core libv2ray bisa connect ke VPS
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Gagal mengecualikan package aplikasi dari VPN: ${e.message}")
            }

            builder.setSession("DDV2Ray")
            builder.setMtu(1500)
            builder.setBlocking(false)

            // Bangun antarmuka TUN
            vpnInterface = builder.establish()
            val pfd = vpnInterface

            if (pfd != null) {
                val fd = pfd.detachFd()
                Log.d(TAG, "TUN Interface berhasil dibangun. FD: $fd")

                // Jalankan Core V2Ray dengan file descriptor TUN yang diperoleh
                val success = V2RayCoreUtils.startCoreWithTun(this, configJson, fd)
                if (success) {
                    isRunning = true
                    Log.d(TAG, "V2Ray VPN aktif dan siap internetan!")
                } else {
                    Log.e(TAG, "Gagal menjalankan V2Ray core.")
                    stopVpn()
                }
            } else {
                Log.e(TAG, "builder.establish() mengembalikan null. Izin VPN mungkin ditolak.")
                stopVpn()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error saat membangun antarmuka VPN: ${e.message}", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning = false
        V2RayCoreUtils.stopCore()

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menutup vpnInterface: ${e.message}")
        }
        vpnInterface = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
        Log.d(TAG, "Layanan VPN dihentikan total.")
    }

    override fun protect(socket: Int): Boolean {
        // Digunakan oleh core C/Go untuk mem-bypass socket langsung ke ISP tanpa masuk TUN
        return super.protect(socket)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DDV2Ray Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status notifikasi koneksi VPN DDV2Ray"
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
            .setContentText("Semua traffic internet diarahkan melalui VPN")
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
        // Terpanggil otomatis jika user mencabut izin VPN lewat pengaturan sistem Android
        stopVpn()
        super.onRevoke()
    }
}
