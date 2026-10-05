package com.dd.v2ray.utils

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method

object V2RayCoreUtils {
    private const val TAG = "V2RayCoreUtils"

    /**
     * Memastikan file geoip.dat dan geosite.dat disalin ke storage internal app.
     */
    fun copyAssetsIfNeeded(context: Context, logCallback: ((String) -> Unit)? = null) {
        val files = listOf("geoip.dat", "geosite.dat")
        for (fileName in files) {
            val destFile = File(context.filesDir, fileName)
            if (!destFile.exists() || destFile.length() == 0L) {
                try {
                    context.assets.open(fileName).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    logCallback?.invoke("[ASSET] Berhasil salin $fileName")
                } catch (e: Exception) {
                    logCallback?.invoke("[ASSET ERROR] Gagal salin $fileName: ${e.message}")
                }
            } else {
                logCallback?.invoke("[ASSET] Aset $fileName siap.")
            }
        }
    }

    /**
     * Parsing share link sederhana ala v2rayNG jika URL belum berwujud JSON.
     */
    fun parseUrlToJson(shareUrl: String): String {
        val trimmed = shareUrl.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }
        // Fallback panggil method parser bawaan lib jika ada
        return try {
            val libClazz = findCoreClass()
            val method = libClazz.methods.firstOrNull { 
                it.name.contains("convert", ignoreCase = true) || it.name.contains("parse", ignoreCase = true) 
            }
            if (method != null) {
                method.invoke(null, trimmed) as? String ?: "{}"
            } else {
                "{}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal parse URL: ${e.message}")
            "{}"
        }
    }

    /**
     * Menyiapkan Inbound Dokodemo-door (Port 0 / TUN) & Socks lokal port 10808 ala v2rayNG.
     */
    fun prepareConfigForTun(rawConfigJson: String): String {
        return try {
            val root = JSONObject(rawConfigJson)
            val inbounds = root.optJSONArray("inbounds") ?: JSONArray()

            var hasDokodemo = false
            var hasSocks = false

            for (i in 0 until inbounds.length()) {
                val item = inbounds.optJSONObject(i)
                val protocol = item?.optString("protocol", "")
                if (protocol == "dokodemo-door") hasDokodemo = true
                if (protocol == "socks") hasSocks = true
            }

            if (!hasDokodemo) {
                val dokoInbound = JSONObject().apply {
                    put("tag", "tun-in")
                    put("port", 0)
                    put("listen", "127.0.0.1")
                    put("protocol", "dokodemo-door")
                    put("settings", JSONObject().apply {
                        put("network", "tcp,udp")
                        put("followRedirect", true)
                    })
                    put("sniffing", JSONObject().apply {
                        put("enabled", true)
                        put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                    })
                }
                inbounds.put(dokoInbound)
            }

            if (!hasSocks) {
                val socksInbound = JSONObject().apply {
                    put("tag", "socks-in")
                    put("port", 10808)
                    put("listen", "127.0.0.1")
                    put("protocol", "socks")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                    put("sniffing", JSONObject().apply {
                        put("enabled", true)
                        put("destOverride", JSONArray().put("http").put("tls"))
                    })
                }
                inbounds.put(socksInbound)
            }

            root.put("inbounds", inbounds)
            root.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Injeksi inbound gagal: ${e.message}")
            rawConfigJson
        }
    }

    /**
     * Mencari class core (libv2ray atau libXray).
     */
    private fun findCoreClass(): Class<*> {
        val candidates = listOf(
            "libv2ray.Libv2ray",
            "libXray.LibXray",
            "com.v2ray.ang.Libv2ray"
        )
        for (name in candidates) {
            try {
                return Class.forName(name)
            } catch (_: ClassNotFoundException) {}
        }
        throw ClassNotFoundException("Tidak ditemukan class Libv2ray maupun LibXray")
    }

    /**
     * Menjalankan Core engine langsung tanpa invoke JSON envelope yang bikin pusing.
     */
    fun startCoreWithTun(
        context: Context,
        configJson: String,
        tunFd: Int,
        logCallback: ((String) -> Unit)? = null
    ): Boolean {
        return try {
            copyAssetsIfNeeded(context, logCallback)
            val assetDir = context.filesDir.absolutePath

            // 1. Set environment direktori aset DAT
            try {
                System.setProperty("v2ray.location.asset", assetDir)
                System.setProperty("xray.location.asset", assetDir)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Os.setenv("V2RAY_LOCATION_ASSET", assetDir, true)
                    Os.setenv("XRAY_LOCATION_ASSET", assetDir, true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Setenv warning: ${e.message}")
            }

            val coreClazz = findCoreClass()
            logCallback?.invoke("[CORE] Memakai core engine: ${coreClazz.name}")

            // Inisialisasi Environment Core ala v2rayNG (initV2Env / initEnv)
            val initMethod = coreClazz.methods.firstOrNull { 
                it.name.equals("initV2Env", ignoreCase = true) || it.name.equals("initEnv", ignoreCase = true) 
            }
            if (initMethod != null) {
                logCallback?.invoke("[CORE] Menjalankan ${initMethod.name}(assets)...")
                initMethod.invoke(null, assetDir)
            }

            val finalConfig = prepareConfigForTun(configJson)

            // Cari method start langsung: startV2Ray, startXray, atau run
            val startMethod: Method? = coreClazz.methods.firstOrNull { m ->
                val name = m.name.lowercase()
                (name.contains("start") || name.contains("run")) &&
                        !name.contains("request") &&
                        m.parameterTypes.isNotEmpty() &&
                        m.parameterTypes[0] == String::class.java
            }

            if (startMethod != null) {
                logCallback?.invoke("[CORE] Menjalankan via method ${startMethod.name}...")
                val res = startMethod.invoke(null, finalConfig)
                logCallback?.invoke("[CORE] Output: $res")
                true
            } else {
                // Jika library Anda mewajibkan parameter invoke datar:
                logCallback?.invoke("[CORE] Menjalankan via direct invoke(config)...")
                val invokeMethod = coreClazz.getMethod("invoke", String::class.java)
                val directReq = JSONObject().apply {
                    put("name", "RunXray")
                    put("xrayJson", finalConfig)
                    put("datDir", assetDir)
                }
                val rawRes = invokeMethod.invoke(null, directReq.toString()) as? String ?: ""
                logCallback?.invoke("[CORE RES] $rawRes")
                !rawRes.contains("\"success\":false")
            }
        } catch (e: Exception) {
            logCallback?.invoke("[FATAL] Gagal start core: ${e.message}")
            Log.e(TAG, "startCore error", e)
            false
        }
    }

    /**
     * Menghentikan Core engine.
     */
    fun stopCore(logCallback: ((String) -> Unit)? = null) {
        try {
            val coreClazz = findCoreClass()
            val stopMethod = coreClazz.methods.firstOrNull { 
                val n = it.name.lowercase()
                n.contains("stop") || n.contains("close") 
            }
            if (stopMethod != null) {
                stopMethod.invoke(null)
                logCallback?.invoke("[CORE] Engine dihentikan via ${stopMethod.name}.")
            } else {
                logCallback?.invoke("[CORE] Core dilepas.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopCore warning: ${e.message}")
        }
    }
}
