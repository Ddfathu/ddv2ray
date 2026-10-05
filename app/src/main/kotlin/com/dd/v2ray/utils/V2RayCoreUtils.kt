package com.dd.v2ray.utils

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI

object V2RayCoreUtils {

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
                    Log.d("V2RayCoreUtils", "Copied asset: $name")
                } catch (e: Exception) {
                    Log.e("V2RayCoreUtils", "Asset copy warning: ${e.message}")
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
            Log.e("V2RayCoreUtils", "Failed to parse V2Ray URL: ${e.message}")
            createDummyJson()
        }
    }

    private fun parseVless(url: String): String {
        val uri = URI(url)
        val uuid = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port != -1) uri.port else 443

        val queryParams = parseQueryParams(uri.query)
        val network = queryParams["type"] ?: "tcp"
        val path = queryParams["path"] ?: ""
        val security = queryParams["security"] ?: "none"
        val hostHeader = queryParams["host"] ?: ""
        val sni = queryParams["sni"] ?: if (hostHeader.isNotEmpty()) hostHeader else host

        return buildFullConfigJson("vless", host, port, uuid, network, path, security, sni, hostHeader)
    }

    private fun parseVmess(url: String): String {
        val b64Data = url.removePrefix("vmess://").removePrefix("VMESS://")
        val decodedJsonStr = String(Base64.decode(b64Data, Base64.DEFAULT or Base64.URL_SAFE))
        val vmessJson = JSONObject(decodedJsonStr)

        val host = vmessJson.optString("add")
        val port = vmessJson.optInt("port", 443)
        val uuid = vmessJson.optString("id")
        val network = vmessJson.optString("net", "tcp")
        val path = vmessJson.optString("path", "")
        val security = vmessJson.optString("tls", "none")
        val hostHeader = vmessJson.optString("host", "")
        val sni = if (hostHeader.isNotEmpty()) hostHeader else host

        return buildFullConfigJson("vmess", host, port, uuid, network, path, security, sni, hostHeader)
    }

    private fun parseTrojan(url: String): String {
        val uri = URI(url)
        val password = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port != -1) uri.port else 443

        val queryParams = parseQueryParams(uri.query)
        val network = queryParams["type"] ?: "tcp"
        val path = queryParams["path"] ?: ""
        val security = queryParams["security"] ?: "tls"
        val hostHeader = queryParams["host"] ?: ""
        val sni = queryParams["sni"] ?: if (hostHeader.isNotEmpty()) hostHeader else host

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

        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("1.1.1.1")
                put("8.8.8.8")
            })
        })

        val inbound = JSONObject().apply {
            put("port", 10808)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
        }

        val outbound = JSONObject().apply {
            put("protocol", protocol)

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
            })

            val streamSettings = JSONObject().apply {
                put("network", network)

                if (security == "tls") {
                    put("security", "tls")
                    put("tlsSettings", JSONObject().apply {
                        put("serverName", sni)
                        put("allowInsecure", true)
                    })
                }

                if (network == "ws") {
                    put("wsSettings", JSONObject().apply {
                        if (path.isNotEmpty()) put("path", path)
                        put("headers", JSONObject().apply {
                            if (hostHeader.isNotEmpty()) {
                                put("Host", hostHeader)
                            } else {
                                put("Host", sni)
                            }
                        })
                    })
                }

                if (network == "grpc") {
                    put("grpcSettings", JSONObject().apply {
                        put("serviceName", path)
                        put("multiMode", true)
                    })
                }
            }
            put("streamSettings", streamSettings)
            put("tag", "proxy")
        }

        val directOutbound = JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
        }

        val outboundsArray = JSONArray()
        outboundsArray.put(outbound)
        outboundsArray.put(directOutbound)

        root.put("inbounds", JSONArray().apply { put(inbound) })
        root.put("outbounds", outboundsArray)

        return root.toString(2)
    }

    private fun parseQueryParams(query: String?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isNullOrEmpty()) return map
        query.split("&").forEach { param ->
            val parts = param.split("=")
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

            Log.d("V2RayCoreUtils", "Initializing Native Libv2ray Core...")
            Log.d("V2RayCoreUtils", "Assets: $assetPath | TUN FD: $tunFd")

            try {
                val libClazz = Class.forName("libv2ray.Libv2ray")

                try {
                    val initMethod = libClazz.getMethod("initV2Env", String::class.java)
                    initMethod.invoke(null, assetPath)
                } catch (e: Exception) {
                    Log.w("V2RayCoreUtils", "initV2Env skipped/not present: ${e.message}")
                }

                try {
                    val startMethod = libClazz.getMethod("startV2Ray", String::class.java, Int::class.javaPrimitiveType)
                    startMethod.invoke(null, configJson, tunFd)
                    Log.d("V2RayCoreUtils", "V2Ray Core STARTED & CONNECTED!")
                } catch (e: Exception) {
                    Log.e("V2RayCoreUtils", "Failed calling startV2Ray on JNI: ${e.message}")
                }

            } catch (e: ClassNotFoundException) {
                Log.e("V2RayCoreUtils", "libv2ray.Libv2ray class missing from app/libs/libv2ray.aar")
            }

            true
        } catch (e: Exception) {
            Log.e("V2RayCoreUtils", "Error starting Core Tun: ${e.message}")
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
                Log.w("V2RayCoreUtils", "stopV2Ray fallback: ${e.message}")
            }
            Log.d("V2RayCoreUtils", "Stopped V2Ray Core")
            true
        } catch (e: Exception) {
            false
        }
    }
}
