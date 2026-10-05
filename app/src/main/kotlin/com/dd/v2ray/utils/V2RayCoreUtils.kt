package com.dd.v2ray.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import android.system.Os
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
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

        if (trimmed.startsWith("vless://", ignoreCase = true)) {
            try {
                val uri = Uri.parse(trimmed)
                val uuid = uri.userInfo ?: ""
                val host = uri.host ?: ""
                val port = if (uri.port > 0) uri.port else 443

                val queryMap = mutableMapOf<String, String>()
                val query = uri.query
                if (!query.isNullOrEmpty()) {
                    for (param in query.split("&")) {
                        val pair = param.split("=", limit = 2)
                        if (pair.isNotEmpty()) {
                            val key = pair[0]
                            val value = if (pair.size > 1) URLDecoder.decode(pair[1], "UTF-8") else ""
                            queryMap[key] = value
                        }
                    }
                }

                val type = queryMap["type"] ?: "ws"
                val security = queryMap["security"] ?: "tls"
                val sni = queryMap["sni"] ?: queryMap["host"] ?: host
                val path = queryMap["path"] ?: "/"
                val encryption = queryMap["encryption"] ?: "none"

                val root = JSONObject()
                root.put("log", JSONObject().apply {
                    put("loglevel", "warning")
                })

                val inbounds = JSONArray()
                inbounds.put(JSONObject().apply {
                    put("tag", "socks")
                    put("port", 10808)
                    put("listen", "127.0.0.1")
                    put("protocol", "socks")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                })
                inbounds.put(JSONObject().apply {
                    put("tag", "tun-in")
                    put("port", 0)
                    put("listen", "127.0.0.1")
                    put("protocol", "dokodemo-door")
                    put("settings", JSONObject().apply {
                        put("network", "tcp,udp")
                        put("followRedirect", true)
                    })
                })
                root.put("inbounds", inbounds)

                val outbounds = JSONArray()
                val vlessOutbound = JSONObject().apply {
                    put("tag", "proxy")
                    put("protocol", "vless")
                    put("settings", JSONObject().apply {
                        val vnext = JSONArray()
                        vnext.put(JSONObject().apply {
                            put("address", host)
                            put("port", port)
                            val users = JSONArray().put(JSONObject().apply {
                                put("id", uuid)
                                put("encryption", encryption)
                            })
                            put("users", users)
                        })
                        put("vnext", vnext)
                    })

                    val streamSettings = JSONObject().apply {
                        put("network", type)
                        put("security", security)
                        if (security == "tls") {
                            put("tlsSettings", JSONObject().apply {
                                put("serverName", sni)
                                put("allowInsecure", true)
                            })
                        }
                        if (type == "ws") {
                            put("wsSettings", JSONObject().apply {
                                put("path", path)
                                val headers = JSONObject()
                                if (queryMap.containsKey("host")) {
                                    headers.put("Host", queryMap["host"])
                                } else {
                                    headers.put("Host", sni)
                                }
                                put("headers", headers)
                            })
                        }
                    }
                    put("streamSettings", streamSettings)
                }
                outbounds.put(vlessOutbound)

                outbounds.put(JSONObject().apply {
                    put("tag", "direct")
                    put("protocol", "freedom")
                })
                root.put("outbounds", outbounds)

                return root.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Gagal konversi VLESS URL: ${e.message}")
            }
        }
        return "{}"
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
            val cacheDir = context.cacheDir.absolutePath

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

            // Inisialisasi Environment dengan 2 parameter (assetDir, cacheDir)
            val initEnv = coreClazz.methods.firstOrNull { it.name.equals("initCoreEnv", true) }
            if (initEnv != null) {
                try {
                    val pCount = initEnv.parameterTypes.size
                    logCallback?.invoke("[EXEC] Memanggil initCoreEnv (params: $pCount)...")
                    when (pCount) {
                        2 -> initEnv.invoke(null, assetDir, cacheDir)
                        1 -> initEnv.invoke(null, assetDir)
                        else -> initEnv.invoke(null)
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
                } catch (_: Exception) {
                    newControllerMethod.invoke(null, null)
                }

                activeController = controller

                if (controller != null) {
                    val ctrlClass = controller.javaClass
                    val startLoopMethod = ctrlClass.methods.firstOrNull { it.name.equals("startLoop", true) }

                    if (startLoopMethod != null) {
                        logCallback?.invoke("[EXEC] Menjalankan startLoop...")
                        coreThread = thread(start = true, name = "V2RayCoreThread", isDaemon = true) {
                            try {
                                startLoopMethod.invoke(controller, configJson)
                            } catch (t: Throwable) {
                                Log.e(TAG, "startLoop error: ${t.message}", t)
                            }
                        }

                        Thread.sleep(600)
                        logCallback?.invoke("[CORE SUCCESS] Engine Controller berjalan aktif!")
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
