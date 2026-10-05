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

            // Inisialisasi Controller
            val newControllerMethod = coreClazz.methods.firstOrNull { it.name.equals("newCoreController", true) }
            if (newControllerMethod != null) {
                logCallback?.invoke("[EXEC] Menyiapkan CoreController...")
                val controller = try {
                    if (newControllerMethod.parameterTypes.isNotEmpty()) {
                        newControllerMethod.invoke(null, supportSetInstance)
                    } else {
                        newControllerMethod.invoke(null)
                    }
                } catch (e: Exception) {
                    logCallback?.invoke("[WARN] newCoreController callback fallback...")
                    newControllerMethod.invoke(null, null)
                }

                activeController = controller

                if (controller != null) {
                    val ctrlClass = controller.javaClass
                    val methods = ctrlClass.methods
                    val targetMethod = methods.firstOrNull { m ->
                        val n = m.name.lowercase()
                        n.contains("start") || n.contains("run") || n.contains("loop")
                    }

                    if (targetMethod != null) {
                        logCallback?.invoke("[EXEC] Menjalankan ${targetMethod.name} di background thread...")
                        
                        coreThread = thread(start = true, name = "V2RayCoreThread") {
                            try {
                                val params = targetMethod.parameterTypes
                                when (params.size) {
                                    1 -> {
                                        if (params[0] == String::class.java) {
                                            targetMethod.invoke(controller, configJson)
                                        } else if (params[0] == Int::class.javaPrimitiveType || params[0] == Long::class.javaPrimitiveType) {
                                            targetMethod.invoke(controller, tunFd)
                                        } else {
                                            targetMethod.invoke(controller, null)
                                        }
                                    }
                                    2 -> targetMethod.invoke(controller, configJson, tunFd)
                                    else -> targetMethod.invoke(controller)
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "Core thread exception: ${e.message}", e)
                            }
                        }

                        logCallback?.invoke("[CORE SUCCESS] Engine berjalan di background thread!")
                        return true
                    }
                }
            }

            logCallback?.invoke("[WARN] Tidak menemukan method start/loop yang cocok.")
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
                    val n = it.name.lowercase()
                    n.contains("stop") || n.contains("close") 
                }
                stopMethod?.invoke(controller)
                activeController = null
            }

            try {
                coreThread?.interrupt()
            } catch (_: Exception) {}
            coreThread = null

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
