package com.dd.v2ray.utils

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

object V2RayCoreUtils {
    private const val TAG = "V2RayCoreUtils"
    var activeController: Any? = null
    private var coreThread: Thread? = null

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

    private fun sanitizeConfig(rawJson: String): String {
        return try {
            val root = JSONObject(rawJson)
            // Pastikan log level tidak membuat crash
            if (!root.has("log")) {
                val logObj = JSONObject().apply {
                    put("loglevel", "warning")
                }
                root.put("log", logObj)
            }
            root.toString()
        } catch (_: Exception) {
            rawJson
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
                    logCallback?.invoke("[EXEC] Init Core Env...")
                    if (initEnv.parameterTypes.size == 1) {
                        initEnv.invoke(null, assetDir)
                    } else {
                        initEnv.invoke(null)
                    }
                } catch (e: Exception) {
                    logCallback?.invoke("[WARN] initCoreEnv: ${e.message}")
                }
            }

            // Inisialisasi CoreController
            val newControllerMethod = coreClazz.methods.firstOrNull { it.name.equals("newCoreController", true) }
            if (newControllerMethod != null) {
                logCallback?.invoke("[EXEC] Membuat CoreController...")
                val controller = try {
                    if (newControllerMethod.parameterTypes.isNotEmpty()) {
                        newControllerMethod.invoke(null, supportSetInstance)
                    } else {
                        newControllerMethod.invoke(null)
                    }
                } catch (e: Exception) {
                    newControllerMethod.invoke(null, null)
                }

                activeController = controller

                if (controller != null) {
                    val ctrlClass = controller.javaClass
                    val startLoopMethod = ctrlClass.methods.firstOrNull { it.name.equals("startLoop", true) }

                    if (startLoopMethod != null) {
                        val validConfig = sanitizeConfig(configJson)
                        logCallback?.invoke("[EXEC] Menjalankan startLoop...")

                        coreThread = thread(start = true, name = "V2RayCoreThread", isDaemon = true) {
                            try {
                                startLoopMethod.invoke(controller, validConfig)
                            } catch (t: Throwable) {
                                Log.e(TAG, "startLoop error: ${t.message}", t)
                            }
                        }

                        // Beri jeda 500ms untuk memastikan core stabil dan tidak exit mendadak
                        Thread.sleep(500)
                        logCallback?.invoke("[CORE SUCCESS] Engine Controller stabil berjalan.")
                        return true
                    }
                }
            }

            logCallback?.invoke("[WARN] Controller gagal dimulai.")
            return false
        } catch (e: Throwable) {
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
                    it.name.equals("stopLoop", true) || it.name.lowercase().contains("stop") 
                }
                stopMethod?.invoke(controller)
                activeController = null
            }

            try {
                coreThread?.interrupt()
            } catch (_: Exception) {}
            coreThread = null

            logCallback?.invoke("[CORE] Engine dihentikan.")
        } catch (e: Exception) {
            Log.w(TAG, "stopCore warning: ${e.message}")
        }
    }
}
