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

        val rawPath = queryParams["path"] ?: ""
        val network = queryParams["type"] ?: if (rawPath.isNotEmpty()) "ws" else "tcp"

        var path = try {
            URLDecoder.decode(rawPath, "UTF-8")
        } catch (e: Exception) {
            rawPath
        }
        if (path.isNotEmpty() && !path.startsWith("/")) {
            path = "/$path"
        }

        val security = queryParams["security"] ?: if (port == 443) "tls" else "none"
        val hostHeader = queryParams["host"] ?: ""

        // Prioritas SNI: SNI parameter -> Bug Host server asli -> Host Header
        val sni = queryParams["sni"]?.takeIf { it.isNotEmpty() }
            ?: host.takeIf { it.isNotEmpty() }
            ?: hostHeader

        Log.d(TAG, "Parsed VLESS -> Host: $host:$port | Net: $network | SNI: $sni | HostHeader: $hostHeader | Path: $path")

        return buildFullConfigJson("vless", host, port, uuid, network, path, security, sni, hostHeader)
    }

    private fun parseVmess(url: String): String {
        val b64Data = url.substringAfter("://")
        val decodedJsonStr = String(Base64.decode(b64Data, Base64.DEFAULT or Base64.URL_SAFE))
        val vmessJson = JSONObject(decodedJsonStr)

        val host = vmessJson.optString("add", "")
        val port = vmessJson.optInt("port", 443)
        val uuid = vmessJson.optString("id", "")
        var rawPath = vmessJson.optString("path", "")
        if (rawPath.isNotEmpty() && !rawPath.startsWith("/")) {
            rawPath = "/$rawPath"
        }

        val network = vmessJson.optString("net", "tcp").ifEmpty {
            if (rawPath.isNotEmpty()) "ws" else "tcp"
        }
        val security = vmessJson.optString("tls", "none").ifEmpty { "none" }
        val hostHeader = vmessJson.optString("host", "")
        val sni = vmessJson.optString("sni", "").takeIf { it.isNotEmpty() }
            ?: host.takeIf { it.isNotEmpty() }
            ?: hostHeader

        return buildFullConfigJson("vmess", host, port, uuid, network, rawPath, security, sni, hostHeader)
    }

    private fun parseTrojan(url: String): String {
        val uri = URI(url)
        val password = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port != -1) uri.port else 443

        val queryParams = parseQueryParams(uri.rawQuery)
        val rawPath = queryParams["path"] ?: ""
        val network = queryParams["type"] ?: if (rawPath.isNotEmpty()) "ws" else "tcp"

        var path = try {
            URLDecoder.decode(rawPath, "UTF-8")
        } catch (e: Exception) {
            rawPath
        }
        if (path.isNotEmpty() && !path.startsWith("/")) {
            path = "/$path"
        }

        val security = queryParams["security"] ?: "tls"
        val hostHeader = queryParams["host"] ?: ""
        val sni = queryParams["sni"]?.takeIf { it.isNotEmpty() }
            ?: host.takeIf { it.isNotEmpty() }
            ?: hostHeader

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

        // DNS Server Internal Core
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("1.1.1.1")
                put("8.8.8.8")
                put("localhost")
            })
        })

        val inboundsArray = JSONArray()

        // 1. Inbound TUN Capture (Dokodemo-door)
        inboundsArray.put(JSONObject().apply {
            put("tag", "tun-in")
            put("port", 10807)
            put("listen", "127.0.0.1")
            put("protocol", "dokodemo-door")
            put("settings", JSONObject().apply {
                put("network", "tcp,udp")
                put("followRedirect", true)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().apply {
                    put("http")
                    put("tls")
                })
            })
        })

        // 2. Inbound SOCKS
        inboundsArray.put(JSONObject().apply {
            put("tag", "socks-in")
            put("port", 10808)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
        })
        root.put("inbounds", inboundsArray)

        // Outbound Proxy
        val outboundProxy = JSONObject().apply {
            put("protocol", protocol)
            put("tag", "proxy")

            val userSetting = JSONObject().apply {
                if (protocol == "trojan") {
                    put("password", authId)
                } else if (protocol == "vless") {
                    put("id", authId)
                    put("encryption", "none")
                    put("level", 0)
                } else if (protocol == "vmess") {
                    put("id", authId)
                    put("alterId", 0)
                    put("security", "auto")
                    put("level", 0)
                }
            }

            val serverSetting = JSONObject().apply {
                put("address", host)
                put("port", port)
                put("users", JSONArray().apply { put(userSetting) })
            }

            if (protocol == "trojan") {
                put("settings", JSONObject().apply {
                    put("servers", JSONArray().apply { put(serverSetting) })
                })
            } else {
                put("settings", JSONObject().apply {
                    put("vnext", JSONArray().apply { put(serverSetting) })
                })
            }

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
                        put("path", if (path.isNotEmpty()) path else "/")
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

        // Outbound Direct
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

        // Routing: Force all TUN traffic to proxy outbound
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

    fun startCoreWithTun(context: Context, configJson: String, tunFd: Int, logCallback: ((String) -> Unit)? = null): Boolean {
        return try {
            copyAssetsIfNeeded(context)
            val assetPath = context.filesDir.absolutePath

            logCallback?.invoke("Assets dir: $assetPath")
            logCallback?.invoke("Mengoper FD TUN: $tunFd")

            try {
                val libClazz = Class.forName("libv2ray.Libv2ray")

                try {
                    val initMethod = libClazz.getMethod("initV2Env", String::class.java)
                    initMethod.invoke(null, assetPath)
                    logCallback?.invoke("initV2Env OK")
                } catch (e: Exception) {
                    logCallback?.invoke("initV2Env skipped: ${e.message}")
                }

                try {
                    val startMethod = libClazz.getMethod("startV2Ray", String::class.java, Int::class.javaPrimitiveType)
                    startMethod.invoke(null, configJson, tunFd)
                    logCallback?.invoke("startV2Ray berhasil dimulai.")
                    return true
                } catch (e: Exception) {
                    logCallback?.invoke("Gagal panggil startV2Ray JNI: ${e.message}")
                    return false
                }

            } catch (e: ClassNotFoundException) {
                logCallback?.invoke("Class libv2ray.Libv2ray tidak ditemukan di .aar")
                return false
            }

        } catch (e: Exception) {
            logCallback?.invoke("startCoreWithTun Exception: ${e.message}")
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
