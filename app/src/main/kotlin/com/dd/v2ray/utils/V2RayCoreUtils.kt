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
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
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

                // Inbounds: SOCKS & Dokodemo/TUN
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
                    put("sniffing", JSONObject().apply {
                        put("enabled", true)
                        put("destOverride", JSONArray().put("http").put("tls"))
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
                    put("sniffing", JSONObject().apply {
                        put("enabled", true)
                        put("destOverride", JSONArray().put("http").put("tls"))
                    })
                })
                root.put("inbounds", inbounds)

                // Outbounds
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
                outbounds.put(JSONObject().apply {
                    put("tag", "block")
                    put("protocol", "blackhole")
                })
                root.put("outbounds", outbounds)

                // Routing
                val routing = JSONObject()
                routing.put("domainStrategy", "AsIs")
                val rules = JSONArray()
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().put("tun-in").put("socks"))
                    put("outboundTag", "proxy")
                })
                routing.put("rules", rules)
                root.put("routing", routing)

                return root.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Gagal konversi VLESS: ${e.message}")
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
                    Os.setenv("xray.tun.fd", tunFd.toString(), true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Setenv: ${e.message}")
            }

            val coreClazz = Class.forName("libv2ray.Libv2ray")

            // Inisialisasi Environment Core
            val initEnv = coreClazz.methods.firstOrNull { it.name.equals("initCoreEnv", true) }
            if (initEnv != null) {
                try {
                    val pCount = initEnv.parameterTypes.size
                    logCallback?.invoke("[EXEC] Inisialisasi Environment Core...")
                    when (pCount) {
                        2 -> initEnv.invoke(null, assetDir, cacheDir)
                        1 -> initEnv.invoke(null, assetDir)
                        else -> initEnv.invoke(null)
                    }
                } catch (_: Exception) {}
            }

            // Inisialisasi Controller
            val newControllerMethod = coreClazz.methods.firstOrNull { it.name.equals("newCoreController", true) }
            if (newControllerMethod != null) {
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
                        val pTypes = startLoopMethod.parameterTypes
                        
                        coreThread = thread(start = true, name = "V2RayCoreThread", isDaemon = true) {
                            try {
                                if (pTypes.size == 1) {
                                    startLoopMethod.invoke(controller, configJson)
                                } else if (pTypes.size == 2) {
                                    startLoopMethod.invoke(controller, configJson, tunFd)
                                }
                            } catch (t: Throwable) {
                                logCallback?.invoke("[CORE CRASH] ${t.message}")
                            }
                        }

                        // Tunggu engine running 1 detik lalu lakukan PING TEST LIVE
                        thread(start = true) {
                            Thread.sleep(1200)
                            runPingAndDiagnose(controller, logCallback)
                        }

                        logCallback?.invoke("[CORE SUCCESS] Engine Aktif!")
                        return true
                    }
                }
            }
            return false
        } catch (e: Throwable) {
            logCallback?.invoke("[FATAL] Error start: ${e.message}")
            return false
        }
    }

    // DIAGNOSIS & LIVE PING TEST
    private fun runPingAndDiagnose(controller: Any?, logCallback: ((String) -> Unit)?) {
        try {
            logCallback?.invoke("[PING] Menguji respon core...")
            
            // 1. Coba panggil measureDelay bawaan libv2ray
            if (controller != null) {
                val measureMethod = controller.javaClass.methods.firstOrNull { it.name.equals("measureDelay", true) }
                if (measureMethod != null) {
                    try {
                        val delay = measureMethod.invoke(controller, "https://www.google.com/generate_204")
                        logCallback?.invoke("[CORE PING] Google delay: ${delay}ms")
                    } catch (e: Exception) {
                        logCallback?.invoke("[CORE PING] measureDelay error: ${e.message}")
                    }
                }
            }

            // 2. Cek apakah SOCKS local port 10808 terbuka
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress("127.0.0.1", 10808), 1000)
                sock.close()
                logCallback?.invoke("[DIAGNOSA] SOCKS 10808 AKTIF & Siap melayani traffic!")
            } catch (e: Exception) {
                logCallback?.invoke("[DIAGNOSA] SOCKS port 10808 belum merespon (${e.message})")
            }

            // 3. Tes HTTP request langsung
            val start = System.currentTimeMillis()
            val url = URL("https://connectivitycheck.gstatic.com/generate_204")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            val cost = System.currentTimeMillis() - start
            if (code == 204 || code == 200) {
                logCallback?.invoke("[INTERNET SUKSES] HTTP Ping OK ($cost ms)!")
            } else {
                logCallback?.invoke("[HTTP TEST] Respon status: $code ($cost ms)")
            }
        } catch (e: Exception) {
            logCallback?.invoke("[INTERNET NYANGKUT] ${e.javaClass.simpleName}: ${e.message}")
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
            try { coreThread?.interrupt() } catch (_: Exception) {}
            coreThread = null
            logCallback?.invoke("[CORE] Engine dihentikan.")
        } catch (_: Exception) {}
    }
}
