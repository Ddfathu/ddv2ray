package com.dd.v2ray.utils

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
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

            // Inisialisasi Controller
            val newControllerMethod = coreClazz.methods.firstOrNull { it.name.equals("newCoreController", true) }
            if (newControllerMethod != null) {
                logCallback?.invoke("[EXEC] Membuat newCoreController...")
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
                    
                    // Set callback handler jika ada method setCallbackHandler
                    ctrlClass.methods.firstOrNull { it.name.equals("setCallbackHandler", true) }?.let { setCb ->
                        try {
                            setCb.invoke(controller, supportSetInstance)
                        } catch (_: Exception) {}
                    }

                    // Cari method startLoop
                    val startLoopMethod = ctrlClass.methods.firstOrNull { it.name.equals("startLoop", true) }
                    if (startLoopMethod != null) {
                        val pTypes = startLoopMethod.parameterTypes
                        val paramNames = pTypes.map { it.simpleName }.joinToString(", ")
                        logCallback?.invoke("[EXEC] startLoop params: ($paramNames)")

                        coreThread = thread(start = true, name = "V2RayControllerLoop") {
                            try {
                                when (pTypes.size) {
                                    0 -> startLoopMethod.invoke(controller)
                                    1 -> {
                                        if (pTypes[0] == String::class.java) {
                                            startLoopMethod.invoke(controller, configJson)
                                        } else if (pTypes[0] == Int::class.javaPrimitiveType || pTypes[0] == Long::class.javaPrimitiveType) {
                                            startLoopMethod.invoke(controller, tunFd)
                                        } else {
                                            startLoopMethod.invoke(controller, null)
                                        }
                                    }
                                    2 -> {
                                        if (pTypes[0] == String::class.java && (pTypes[1] == Int::class.javaPrimitiveType || pTypes[1] == Long::class.javaPrimitiveType)) {
                                            startLoopMethod.invoke(controller, configJson, tunFd)
                                        } else {
                                            startLoopMethod.invoke(controller, tunFd, configJson)
                                        }
                                    }
                                    else -> logCallback?.invoke("[WARN] startLoop butuh ${pTypes.size} parameter yang tidak dikenal.")
                                }
                            } catch (t: Throwable) {
                                Log.e(TAG, "startLoop crash: ${t.message}", t)
                            }
                        }

                        logCallback?.invoke("[CORE SUCCESS] Engine Controller aktif di background thread!")
                        return true
                    }
                }
            }

            logCallback?.invoke("[WARN] Controller tidak ditemukan.")
            return true
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
