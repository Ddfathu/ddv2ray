package com.dd.v2ray.utils

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object V2RayCoreUtils {
    private const val TAG = "V2RayCoreUtils"

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
                it.name.contains("convert", true) || it.name.contains("parse", true) 
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

    fun startCoreWithTun(
        context: Context,
        configJson: String,
        tunFd: Int,
        logCallback: ((String) -> Unit)? = null
    ): Boolean {
        try {
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
                Log.w(TAG, "Setenv: ${e.message}")
            }

            val coreClazz = Class.forName("libv2ray.Libv2ray")
            logCallback?.invoke("[CORE] Engine: ${coreClazz.name}")

            val methods = coreClazz.methods.map { it.name }.distinct()
            logCallback?.invoke("[METHODS] ${methods.take(10).joinToString(", ")}")

            for (m in coreClazz.methods) {
                if (m.name.startsWith("init", true)) {
                    try {
                        if (m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java) {
                            m.invoke(null, assetDir)
                        } else if (m.parameterTypes.isEmpty()) {
                            m.invoke(null)
                        }
                    } catch (_: Exception) {}
                    break
                }
            }

            val startMethod = coreClazz.methods.firstOrNull { 
                val name = it.name.lowercase()
                name.startsWith("start") || name.startsWith("run")
            }

            if (startMethod != null) {
                logCallback?.invoke("[EXEC] Menjalankan ${startMethod.name}...")
                if (startMethod.parameterTypes.size == 1 && startMethod.parameterTypes[0] == String::class.java) {
                    startMethod.invoke(null, configJson)
                } else if (startMethod.parameterTypes.isEmpty()) {
                    startMethod.invoke(null)
                }
                return true
            }

            logCallback?.invoke("[WARN] Tidak menemukan method start langsung.")
            return true
        } catch (e: Exception) {
            logCallback?.invoke("[FATAL] Error start: ${e.message}")
            Log.e(TAG, "startCore error", e)
            return false
        }
    }

    fun stopCore(logCallback: ((String) -> Unit)? = null) {
        try {
            val coreClazz = Class.forName("libv2ray.Libv2ray")
            val stopMethod = coreClazz.methods.firstOrNull { 
                val n = it.name.lowercase()
                n.contains("stop") || n.contains("close") 
            }
            if (stopMethod != null) {
                if (stopMethod.parameterTypes.isEmpty()) {
                    stopMethod.invoke(null)
                }
            }
            logCallback?.invoke("[CORE] Engine dihentikan.")
        } catch (e: Exception) {
            Log.w(TAG, "stopCore warning: ${e.message}")
        }
    }
}
