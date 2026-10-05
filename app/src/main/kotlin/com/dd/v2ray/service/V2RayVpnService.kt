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

        var onLogReceived: ((String) -> Unit)? = null
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLogReceived?.invoke(msg)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            log("[VPN] Menghentikan VPN...")
            stopVpn()
            return START_NOT_STICKY
        }

        val configJson = intent?.getStringExtra(EXTRA_CONFIG) ?: ""
        if (configJson.isEmpty()) {
            log("[ERROR] Konfigurasi kosong!")
            stopSelf()
            return START_NOT_STICKY
        }

        startVpn(configJson)
        return START_STICKY
    }

    private fun startVpn(configJson: String) {
        log("[1/4] Membangun Virtual TUN Interface...")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        try {
            // 1. Daftarkan socket protect ke libXray agar tidak terjadi loop koneksi
            try {
                registerXrayDialer()
                log("[CORE] Dialer controller terdaftar (protect socket aktif).")
            } catch (e: Exception) {
                log("[WARN] Register dialer note: ${e.message}")
            }

            val builder = Builder()
            builder.setSession("DDV2Ray")
            builder.setMtu(1500)
            builder.addAddress("172.19.0.1", 30)

            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("8.8.8.8")
            builder.addRoute("0.0.0.0", 0)

            try {
                builder.addDisallowedApplication(packageName)
                log("[2/4] Aplikasi sendiri di-bypass dari TUN.")
            } catch (e: Exception) {
                log("[WARN] Bypass package: ${e.message}")
            }

            vpnInterface = builder.establish()
            val pfd = vpnInterface

            if (pfd != null) {
                val fd = pfd.fd
                log("[3/4] TUN Aktif dengan FD: $fd. Menjalankan libXray...")

                val success = V2RayCoreUtils.startCoreWithTun(this, configJson, fd) { coreMsg ->
                    log(coreMsg)
                }

                if (success) {
                    isRunning = true
                    log("[4/4] CONNECTED! Core Xray aktif.")
                } else {
                    log("[ERROR] Native Core gagal dijalankan!")
                    stopVpn()
                }
            } else {
                log("[ERROR] Interface TUN NULL!")
                stopVpn()
            }

        } catch (e: Exception) {
            log("[FATAL] Gagal membuat VPN: ${e.message}")
            stopVpn()
        }
    }

    private fun registerXrayDialer() {
        try {
            val libClazz = Class.forName("libXray.LibXray")
            val dialerInterface = Class.forName("libXray.DialerController")
            
            // Buat dynamic proxy untuk interface DialerController
            val proxyInstance = java.lang.reflect.Proxy.newProxyInstance(
                dialerInterface.classLoader,
                arrayOf(dialerInterface)
            ) { _, method, args ->
                if (method.name == "protect" || method.name == "dial" || method.name == "protectFD") {
                    val fd = (args?.get(0) as? Number)?.toLong() ?: 0L
                    this@V2RayVpnService.protect(fd.toInt())
                } else {
                    null
                }
            }

            val regMethod = libClazz.getMethod("registerDialerController", dialerInterface)
            regMethod.invoke(null, proxyInstance)
        } catch (e: Exception) {
            Log.w(TAG, "registerXrayDialer skipped: ${e.message}")
        }
    }

    private fun stopVpn() {
        isRunning = false
        V2RayCoreUtils.stopCore()

        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
        log("[SELESAI] VPN mati. Jaringan kembali normal.")
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
            ).apply { setShowBadge(false) }
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

        val stopIntent = Intent(this, V2RayVpnService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setContentTitle("DDV2Ray Aktif")
            .setContentText("Terhubung ke server V2Ray")
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
        stopVpn()
        super.onRevoke()
    }
}
