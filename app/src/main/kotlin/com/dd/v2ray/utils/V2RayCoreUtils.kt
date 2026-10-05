package com.dd.v2ray.utils

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLDecoder

object V2RayCoreUtils {

    private const val TAG = "V2RayCoreUtils"

    fun copyAssetsIfNeeded(context: Context) {
        val assetNames = listOf("geoip.dat", "geosite.dat")
        for (name in assetNames) {
            val file = File(context.filesDir, name)
            if (!file.exists()) {
                try {
                    context.assets.open(name).use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Copied asset: $name")
                } catch (e: Exception) {
                    Log.e(TAG, "Asset copy warning: ${e.message}")
                }
            }
        }
    }

    fun parseUrlToJson(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return try {
            when {
                trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed)
                trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed)
                trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed)
                else -> createDummyJson()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse V2Ray URL: ${e.message}", e)
            createDummyJson()
        }
    }

    private fun parseVless(url: String): String {
        val uri = URI(url)
        val uuid = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port != -1) uri.port else 443

        val queryParams = parseQueryParams(uri.rawQuery)
        val network = queryParams["type"] ?: "tcp"
        val path = URLDecoder.decode(queryParams["path"] ?: "", "UTF-8")
        val security = queryParams["security"] ?: if (port == 443) "tls" else "none"
        val hostHeader = queryParams["host"] ?: ""
        val sni = queryParams["sni"]?.takeIf { it.isNotEmpty() }
            ?: hostHeader.takeIf { it.isNotEmpty() }
            ?: host

        return buildFullConfigJson("vless", host, port, uuid, network, path, security, sni, hostHeader)
    }

    private fun parseVmess(url: String): String {
        val b64Data = url.substringAfter("://")
        val decodedJsonStr = String(Base64.decode(b64Data, Base64.DEFAULT or Base64.URL_SAFE))
        val vmessJson = JSONObject(decodedJsonStr)

        val host = vmessJson.optString("add", "")
        val port = vmessJson.optInt("port", 443)
        val uuid = vmessJson.optString("id", "")
        val network = vmessJson.optString("net", "tcp").ifEmpty { "tcp" }
        val path = vmessJson.optString("path", "")
        val security = vmessJson.optString("tls", "none").ifEmpty { "none" }
        val hostHeader = vmessJson.optString("host", "")
        val sni = vmessJson.optString("sni", "").takeIf { it.isNotEmpty() }
            ?: hostHeader.takeIf { it.isNotEmpty() }
            ?: host

        return buildFullConfigJson("vmess", host, port, uuid, network, path, security, sni, hostHeader)
    }

    private fun parseTrojan(url: String): String {
        val uri = URI(url)
        val password = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port != -1) uri.port else 443

        val queryParams = parseQueryParams(uri.rawQuery)
        val network = queryParams["type"] ?: "tcp"
        val path = URLDecoder.decode(queryParams["path"] ?: "", "UTF-8")
        val security = queryParams["security"] ?: "tls"
        val hostHeader = queryParams["host"] ?: ""
        val sni = queryParams["sni"]?.takeIf { it.isNotEmpty() }
            ?: hostHeader.takeIf { it.isNotEmpty() }
            ?: host

        return buildFullConfigJson("trojan", host, port, password, network, path, security, sni, hostHeader)
    }

    private fun buildFullConfigJson(
        protocol: String,
        host: String,
        port: Int,
        authId: String,
        network: String,
        path: String,
        security: String,
        sni: String,
        hostHeader: String
    ): String {
        val root = JSONObject()

        // 1. Log
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        // 2. DNS
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("1.1.1.1")
                put("8.8.8.8")
                put("localhost")
            })
        })

        // 3. Inbound SOCKS lokal (fallback bridge)
        val inboundsArray = JSONArray()
        inboundsArray.put(JSONObject().apply {
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
                put("destOverride", JSONArray().apply {
                    put("http")
                    put("tls")
                })
            })
        })
        root.put("inbounds", inboundsArray)

        // 4. Outbound Proxy (VLESS / VMESS / TROJAN)
        val outboundProxy = JSONObject().apply {
            put("protocol", protocol)
            put("tag", "proxy")

            val userSetting = JSONObject().apply {
                if (protocol == "trojan") {
                    put("password", authId)
                } else {
                    put("id", authId)
                    if (protocol == "vless") put("encryption", "none")
                }
            }

            val serverSetting = JSONObject().apply {
                put("address", host)
                put("port", port)
                put("users", JSONArray().apply { put(userSetting) })
            }

            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply { put(serverSetting) })
                if (protocol == "trojan") {
                    put("servers", JSONArray().apply { put(serverSetting) })
                }
            })

            // Stream Settings (Transport & TLS)
            val streamSettings = JSONObject().apply {
                put("network", network)

                if (security.equals("tls", ignoreCase = true)) {
                    put("security", "tls")
                    put("tlsSettings", JSONObject().apply {
                        if (sni.isNotEmpty()) put("serverName", sni)
                        put("allowInsecure", true)
                    })
                }

                if (network.equals("ws", ignoreCase = true)) {
                    put("wsSettings", JSONObject().apply {
                        if (path.isNotEmpty()) put("path", path)
                        put("headers", JSONObject().apply {
                            if (hostHeader.isNotEmpty()) {
                                put("Host", hostHeader)
                            } else if (sni.isNotEmpty()) {
                                put("Host", sni)
                            }
                        })
                    })
                }

                if (network.equals("grpc", ignoreCase = true)) {
                    put("grpcSettings", JSONObject().apply {
                        if (path.isNotEmpty()) put("serviceName", path)
                        put("multiMode", true)
                    })
                }
            }
            put("streamSettings", streamSettings)
        }

        // Outbound Freedom (Direct)
        val outboundDirect = JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
            put("settings", JSONObject().apply {
                put("domainStrategy", "UseIP")
            })
        }

        val outboundsArray = JSONArray().apply {
            put(outboundProxy)
            put(outboundDirect)
        }
        root.put("outbounds", outboundsArray)

        // 5. Routing Rules (Prioritaskan proxy untuk semua request TUN)
        val routing = JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("network", "tcp,udp")
                })
            })
        }
        root.put("routing", routing)

        return root.toString(2)
    }

    private fun parseQueryParams(query: String?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isNullOrEmpty()) return map
        query.split("&").forEach { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                map[parts[0]] = parts[1]
            }
        }
        return map
    }

    private fun createDummyJson(): String {
        return "{\"inbounds\":[],\"outbounds\":[]}"
    }

    fun startCoreWithTun(context: Context, configJson: String, tunFd: Int): Boolean {
        return try {
            copyAssetsIfNeeded(context)
            val assetPath = context.filesDir.absolutePath

            Log.d(TAG, "Assets Path: $assetPath | TUN FD: $tunFd")

            try {
                val libClazz = Class.forName("libv2ray.Libv2ray")

                // Inisialisasi Environment Asset (geoip.dat, geosite.dat)
                try {
                    val initMethod = libClazz.getMethod("initV2Env", String::class.java)
                    initMethod.invoke(null, assetPath)
                } catch (e: Exception) {
                    Log.w(TAG, "initV2Env skipped: ${e.message}")
                }

                // Jalankan V2Ray Core dengan TUN FD
                try {
                    val startMethod = libClazz.getMethod("startV2Ray", String::class.java, Int::class.javaPrimitiveType)
                    startMethod.invoke(null, configJson, tunFd)
                    Log.d(TAG, "Core Tun V2Ray BERHASIL DIJALANKAN!")
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Gagal memanggil startV2Ray JNI: ${e.message}", e)
                    return false
                }

            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "libv2ray.Libv2ray class tidak ditemukan di libs!")
                return false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error startCoreWithTun: ${e.message}", e)
            false
        }
    }

    fun stopCore(): Boolean {
        return try {
            try {
                val libClazz = Class.forName("libv2ray.Libv2ray")
                val stopMethod = libClazz.getMethod("stopV2Ray")
                stopMethod.invoke(null)
            } catch (e: Exception) {
                Log.w(TAG, "stopV2Ray fallback: ${e.message}")
            }
            Log.d(TAG, "V2Ray Core Dihentikan.")
            true
        } catch (e: Exception) {
            false
        }
    }
}
