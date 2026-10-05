package com.dd.v2ray.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dd.v2ray.utils.V2RayCoreUtils

class V2RayVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val CHANNEL_ID = "DDV2Ray_VPN_Channel"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val configJson = intent?.getStringExtra("CONFIG_JSON") ?: ""
        Log.d("V2RayVpnService", "Starting VPN Service with full routing...")

        createNotificationChannel()
        startForeground(1, createNotification())

        if (setupVpn()) {
            val pfd = vpnInterface
            if (pfd != null && configJson.isNotEmpty()) {
                val fd = pfd.detachFd()
                V2RayCoreUtils.startCoreWithTun(this, configJson, fd)
            }
        }

        return START_STICKY
    }

    private fun setupVpn(): Boolean {
        return try {
            val builder = Builder()
            
            builder.addAddress("10.0.0.2", 24)
            builder.addRoute("0.0.0.0", 0)
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("8.8.8.8")
            
            builder.setSession("DDV2Ray")
            builder.setMtu(1500)

            vpnInterface = builder.establish()
            Log.d("V2RayVpnService", "VPN Interface established successfully")
            true
        } catch (e: Exception) {
            Log.e("V2RayVpnService", "Error starting VPN interface: ${e.message}")
            false
        }
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
            )
            val manager = getSystemService(Context::NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DDV2Ray Connected")
            .setContentText("Internet diproteksi & disalurkan via V2Ray Core")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        V2RayCoreUtils.stopCore()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e("V2RayVpnService", "Error closing VPN Interface: ${e.message}")
        }
        vpnInterface = null
        stopForeground(true)
        Log.d("V2RayVpnService", "V2Ray VPN Service Stopped")
    }
}
