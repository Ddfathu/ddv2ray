package com.dd.v2ray.utils

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object V2RayCoreUtils {
    private const val TAG = "V2RayCoreUtils"
    var activeController: Any? = null

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
        supportSetInstance: Any? = null,
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

            // Inisialisasi Environment
            val initEnv = coreClazz.methods.firstOrNull { it.name.equals("initCoreEnv", true) }
            if (initEnv != null) {
                try {
                    logCallback?.invoke("[EXEC] Memanggil initCoreEnv...")
                    if (initEnv.parameterTypes.size == 1 && initEnv.parameterTypes[0] == String::class.java) {
                        initEnv.invoke(null, assetDir)
                    } else if (initEnv.parameterTypes.isEmpty()) {
                        initEnv.invoke(null)
                    }
                } catch (e: Exception) {
                    logCallback?.invoke("[WARN] initCoreEnv: ${e.message}")
                }
            }

            // Jalankan via newCoreController
            val newControllerMethod = coreClazz.methods.firstOrNull { it.name.equals("newCoreController", true) }
            if (newControllerMethod != null) {
                logCallback?.invoke("[EXEC] Membuat newCoreController...")
                val controller = newControllerMethod.invoke(null)
                activeController = controller

                if (controller != null) {
                    val ctrlMethods = controller.javaClass.methods.map { it.name }.distinct()
                    logCallback?.invoke("[CTRL METHODS] ${ctrlMethods.take(10).joinToString(", ")}")

                    // Cari method start di dalam controller
                    val ctrlStart = controller.javaClass.methods.firstOrNull { 
                        val n = it.name.lowercase()
                        n.startsWith("start") || n.startsWith("run") || n.contains("start")
                    }

                    if (ctrlStart != null) {
                        logCallback?.invoke("[EXEC] Menjalankan ${ctrlStart.name} pada CoreController...")
                        if (ctrlStart.parameterTypes.size == 1 && ctrlStart.parameterTypes[0] == String::class.java) {
                            ctrlStart.invoke(controller, configJson)
                        } else if (ctrlStart.parameterTypes.isEmpty()) {
                            ctrlStart.invoke(controller)
                        }
                        logCallback?.invoke("[CORE SUCCESS] Engine Controller AKTIF!")
                        return true
                    }
                }
            }

            logCallback?.invoke("[WARN] CoreController tidak memiliki start method!")
            return true
        } catch (e: Exception) {
            logCallback?.invoke("[FATAL] Error start: ${e.message}")
            Log.e(TAG, "startCore error", e)
            return false
        }
    }

    fun stopCore(logCallback: ((String) -> Unit)? = null) {
        try {
            val controller = activeController
            if (controller != null) {
                val stopMethod = controller.javaClass.methods.firstOrNull { 
                    val n = it.name.lowercase()
                    n.contains("stop") || n.contains("close") 
                }
                stopMethod?.invoke(controller)
                activeController = null
                logCallback?.invoke("[CORE] CoreController dihentikan.")
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
