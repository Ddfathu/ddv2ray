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
    var v2rayPointInstance: Any? = null

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

    fun parseUrlToJson(shareUrl: String): String {
        val trimmed = shareUrl.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }
        return try {
            val coreClazz = Class.forName("libv2ray.Libv2ray")
            val method = coreClazz.methods.firstOrNull { 
                it.name.contains("convert", ignoreCase = true) || it.name.contains("parse", ignoreCase = true) 
            }
            if (method != null && method.parameterTypes.isNotEmpty() && method.parameterTypes[0] == String::class.java) {
                method.invoke(null, trimmed) as? String ?: "{}"
            } else {
                "{}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal parse URL: ${e.message}")
            "{}"
        }
    }

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

    fun startCoreWithTun(
        context: Context,
        configJson: String,
        tunFd: Int,
        supportSetInstance: Any?,
        logCallback: ((String) -> Unit)? = null
    ): Boolean {
        return try {
            copyAssetsIfNeeded(context, logCallback)
            val assetDir = context.filesDir.absolutePath

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

            val coreClazz = Class.forName("libv2ray.Libv2ray")
            logCallback?.invoke("[CORE] Class: ${coreClazz.name}")

            val allMethods = coreClazz.methods.map { it.name }.distinct()
            val relevantMethods = allMethods.filter {
                it.contains("start", true) || it.contains("init", true) || 
                it.contains("run", true) || it.contains("point", true)
            }
            logCallback?.invoke("[METHODS] ${relevantMethods.joinToString(", ")}")

            // Inisialisasi Environment Libv2ray
            coreClazz.methods.firstOrNull { it.name.startsWith("init", true) }?.let { initM ->
                try {
                    logCallback?.invoke("[CORE] Menjalankan ${initM.name}...")
                    if (initM.parameterTypes.size == 1 && initM.parameterTypes[0] == String::class.java) {
                        initM.invoke(null, assetDir)
                    } else if (initM.parameterTypes.isEmpty()) {
                        initM.invoke(null)
                    }
                } catch (e: Exception) {
                    logCallback?.invoke("[WARN] Init: ${e.message}")
                }
            }

            val finalConfig = prepareConfigForTun(configJson)

            // Jalur 1: Pola V2RayPoint khas v2rayNG
            val newPointMethod = coreClazz.methods.firstOrNull { it.name.equals("newV2RayPoint", ignoreCase = true) }
            if (newPointMethod != null && supportSetInstance != null) {
                logCallback?.invoke("[EXEC] Membuat V2RayPoint...")
                val point = if (newPointMethod.parameterTypes.size == 2) {
                    newPointMethod.invoke(null, supportSetInstance, false)
                } else {
                    newPointMethod.invoke(null, supportSetInstance)
                }
                v2rayPointInstance = point

                if (point != null) {
                    // Masukkan konfigurasi JSON
                    val setConfigMethod = point.javaClass.methods.firstOrNull { 
                        it.name.contains("config", ignoreCase = true) && 
                        it.parameterTypes.isNotEmpty() && 
                        it.parameterTypes[0] == String::class.java 
                    }

                    if (setConfigMethod != null) {
                        setConfigMethod.invoke(point, finalConfig)
                    } else {
                        val field = point.javaClass.declaredFields.firstOrNull { 
                            it.name.contains("config", ignoreCase = true) 
                        }
                        field?.isAccessible = true
                        field?.set(point, finalConfig)
                    }

                    // Jalankan Core (runLoop / start)
                    val runMethod = point.javaClass.methods.firstOrNull { 
                        it.name.equals("runLoop", ignoreCase = true) || it.name.startsWith("start", ignoreCase = true) 
                    }

                    if (runMethod != null) {
                        logCallback?.invoke("[EXEC] Menjalankan ${runMethod.name} pada V2RayPoint...")
                        Thread {
                            try {
                                if (runMethod.parameterTypes.isEmpty()) {
                                    runMethod.invoke(point)
                                } else if (runMethod.parameterTypes.size == 1 && runMethod.parameterTypes[0] == Boolean::class.javaPrimitiveType) {
                                    runMethod.invoke(point, false)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "RunLoop exit: ${e.message}")
                            }
                        }.start()
                        logCallback?.invoke("[CORE] Engine V2RayPoint BERJALAN!")
                        return true
                    }
                }
            }

            // Jalur 2: Direct Start Method jika tersedia (startV2Ray)
            val targetStart: Method? = coreClazz.methods.firstOrNull { m ->
                val n = m.name.lowercase()
                (n.startsWith("start") || n.startsWith("run")) && !n.contains("request")
            }

            if (targetStart != null) {
                logCallback?.invoke("[EXEC] Menjalankan ${targetStart.name}...")
                val params = targetStart.parameterTypes
                val result = when (params.size) {
                    0 -> targetStart.invoke(null)
                    1 -> {
                        if (params[0] == String::class.java) targetStart.invoke(null, finalConfig)
                        else targetStart.invoke(null, null)
                    }
                    else -> null
                }
                logCallback?.invoke("[CORE SUCCESS] Hasil: $result")
                true
            } else {
                logCallback?.invoke("[ERROR] Tidak menemukan launcher start yang cocok!")
                false
            }
        } catch (e: Exception) {
            logCallback?.invoke("[FATAL] Error start: ${e.message}")
            Log.e(TAG, "startCore error", e)
            false
        }
    }

    fun stopCore(logCallback: ((String) -> Unit)? = null) {
        try {
            val point = v2rayPointInstance
            if (point != null) {
                val stopMethod = point.javaClass.methods.firstOrNull { 
                    val n = it.name.lowercase()
                    n.contains("stop") || n.contains("close") 
                }
                stopMethod?.invoke(point)
                v2rayPointInstance = null
                logCallback?.invoke("[CORE] V2RayPoint dihentikan.")
                return
            }

            val coreClazz = Class.forName("libv2ray.Libv2ray")
            val stopMethod = coreClazz.methods.firstOrNull { 
                val n = it.name.lowercase()
                n.contains("stop") || n.contains("close") 
            }
            stopMethod?.invoke(null)
            logCallback?.invoke("[CORE] Engine dihentikan.")
        } catch (e: Exception) {
            Log.w(TAG, "stopCore warning: ${e.message}")
        }
    }
}
