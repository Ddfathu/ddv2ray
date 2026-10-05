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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy

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
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLogReceived?.invoke(msg)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            log("[VPN] Menghentikan VPN Service...")
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
        log("[1/4] Membangun antarmuka Virtual TUN...")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            try {
                // 1. Daftarkan socket protection ke library Xray agar traffic outbound tidak loop back
                registerXrayDialer()

                // 2. Konfigurasi virtual TUN interface
                val builder = Builder()
                builder.setSession("DDV2Ray")
                builder.setMtu(1500)
                builder.addAddress("172.19.0.1", 30)
                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("8.8.8.8")
                builder.addRoute("0.0.0.0", 0)

                // Bypass paket aplikasi sendiri
                try {
                    builder.addDisallowedApplication(packageName)
                    log("[2/4] Package $packageName di-bypass dari rute TUN.")
                } catch (e: Exception) {
                    log("[WARN] Bypass package error: ${e.message}")
                }

                vpnInterface = builder.establish()
                val pfd = vpnInterface

                if (pfd != null) {
                    val fd = pfd.fd
                    log("[3/4] TUN Aktif dengan FD: $fd. Menjalankan core Xray...")

                    val success = V2RayCoreUtils.startCoreWithTun(
                        context = this@V2RayVpnService,
                        configJson = configJson,
                        tunFd = fd,
                        logCallback = { coreMsg -> log(coreMsg) }
                    )

                    if (success) {
                        isRunning = true
                        log("[4/4] CONNECTED! Engine Xray aktif.")
                    } else {
                        log("[ERROR] Native Core gagal dijalankan!")
                        stopVpn()
                    }
                } else {
                    log("[ERROR] builder.establish() menghasilkan NULL!")
                    stopVpn()
                }

            } catch (e: Exception) {
                log("[FATAL] Gagal inisialisasi VPN: ${e.message}")
                stopVpn()
            }
        }
    }

    private fun registerXrayDialer() {
        try {
            val libClazz = Class.forName("libXray.LibXray")
            val dialerInterface = Class.forName("libXray.DialerController")

            val proxyInstance = Proxy.newProxyInstance(
                dialerInterface.classLoader,
                arrayOf(dialerInterface)
            ) { _, method, args ->
                val methodName = method.name
                if (methodName.equals("protect", ignoreCase = true) ||
                    methodName.equals("dial", ignoreCase = true) ||
                    methodName.equals("protectFd", ignoreCase = true)
                ) {
                    val fd = (args?.get(0) as? Number)?.toInt() ?: 0
                    if (fd > 0) {
                        this@V2RayVpnService.protect(fd)
                    } else {
                        true
                    }
                } else {
                    null
                }
            }

            val regMethod = libClazz.getMethod("registerDialerController", dialerInterface)
            regMethod.invoke(null, proxyInstance)
            log("[CORE] DialerController terdaftar (Socket Protect Aktif).")
        } catch (e: Exception) {
            log("[WARN] Inisialisasi DialerController dilewati: ${e.message}")
        }
    }

    private fun stopVpn() {
        isRunning = false
        V2RayCoreUtils.stopCore { log(it) }

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
        log("[SELESAI] VPN dimatikan. Jaringan kembali ke mode normal.")
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
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DDV2Ray Aktif")
            .setContentText("Koneksi VPN sedang berjalan")
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
