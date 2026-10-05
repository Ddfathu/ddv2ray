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
                // 1. Daftarkan socket protect ala v2rayNG
                registerSocketProtector()

                // 2. Setup TUN Interface
                val builder = Builder()
                builder.setSession("DDV2Ray")
                builder.setMtu(1500)
                builder.addAddress("172.19.0.1", 30)
                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("8.8.8.8")
                builder.addRoute("0.0.0.0", 0)

                val myPkg = applicationContext.packageName
                try {
                    builder.addDisallowedApplication(myPkg)
                    log("[2/4] Package $myPkg di-bypass dari rute TUN.")
                } catch (e: Exception) {
                    log("[WARN] Bypass package: ${e.message}")
                }

                vpnInterface = builder.establish()
                val pfd = vpnInterface

                if (pfd != null) {
                    val fd = pfd.fd
                    log("[3/4] TUN Aktif (FD: $fd). Menjalankan core...")

                    val success = V2RayCoreUtils.startCoreWithTun(
                        context = applicationContext,
                        configJson = configJson,
                        tunFd = fd,
                        logCallback = { coreMsg -> log(coreMsg) }
                    )

                    if (success) {
                        isRunning = true
                        log("[4/4] CONNECTED! Core Xray/V2Ray aktif.")
                    } else {
                        log("[ERROR] Engine gagal distart!")
                        stopVpn()
                    }
                } else {
                    log("[ERROR] builder.establish() bernilai null!")
                    stopVpn()
                }

            } catch (e: Exception) {
                log("[FATAL] Inisialisasi VPN error: ${e.message}")
                stopVpn()
            }
        }
    }

    private fun registerSocketProtector() {
        try {
            val candidates = listOf(
                "libv2ray.Libv2ray" to "libv2ray.V2RayVPNServiceSupportsSet",
                "libXray.LibXray" to "libXray.DialerController"
            )

            for ((coreClassName, interfaceName) in candidates) {
                try {
                    val coreClazz = Class.forName(coreClassName)
                    val ifaceClazz = Class.forName(interfaceName)

                    val proxyInstance = Proxy.newProxyInstance(
                        ifaceClazz.classLoader,
                        arrayOf(ifaceClazz)
                    ) { _, method, args ->
                        val name = method.name.lowercase()
                        if (name.contains("protect") || name.contains("dial")) {
                            val fd = (args?.get(0) as? Number)?.toInt() ?: 0
                            if (fd > 0) this@V2RayVpnService.protect(fd) else true
                        } else {
                            null
                        }
                    }

                    val regMethod = coreClazz.methods.firstOrNull { 
                        it.name.contains("register", ignoreCase = true) && 
                        it.parameterTypes.isNotEmpty() && 
                        it.parameterTypes[0].isAssignableFrom(ifaceClazz)
                    }

                    if (regMethod != null) {
                        regMethod.invoke(null, proxyInstance)
                        log("[CORE] Socket Protect Controller terdaftar.")
                        break
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            log("[WARN] Socket protector skipped: ${e.message}")
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
        log("[SELESAI] VPN dimatikan.")
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
        val launchIntent = packageManager.getLaunchIntentForPackage(applicationContext.packageName)
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
