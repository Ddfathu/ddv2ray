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

    fun copyAssetsIfNeeded(context: Context, logCallback: ((String) -> Unit)? = null) {
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
                    logCallback?.invoke("[ASSET] Berhasil salin $name")
                } catch (e: Exception) {
                    logCallback?.invoke("[WARN] Aset $name dilewati: ${e.message}")
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
                else -> ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal parsing config: ${e.message}")
            ""
        }
    }

    private fun parseVless(url: String): String {
        val uri = URI(url)
        val uuid = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port != -1) uri.port else 443

        val query = parseQueryParams(uri.rawQuery)
        val rawPath = query["path"] ?: ""
        val network = query["type"] ?: if (rawPath.isNotEmpty()) "ws" else "tcp"

        var path = try { URLDecoder.decode(rawPath, "UTF-8") } catch (_: Exception) { rawPath }
        if (path.isNotEmpty() && !path.startsWith("/")) path = "/$path"

        val security = query["security"] ?: if (port == 443) "tls" else "none"
        val hostHeader = query["host"] ?: ""
        val sni = query["sni"]?.takeIf { it.isNotEmpty() } ?: host

        return generateStandardXrayJson("vless", host, port, uuid, network, path, security, sni, hostHeader)
    }

    private fun parseVmess(url: String): String {
        val b64Data = url.substringAfter("://")
        val decoded = String(Base64.decode(b64Data, Base64.DEFAULT or Base64.URL_SAFE))
        val json = JSONObject(decoded)

        val host = json.optString("add", "")
        val port = json.optInt("port", 443)
        val uuid = json.optString("id", "")
        var path = json.optString("path", "")
        if (path.isNotEmpty() && !path.startsWith("/")) path = "/$path"

        val network = json.optString("net", "tcp").ifEmpty { if (path.isNotEmpty()) "ws" else "tcp" }
        val security = json.optString("tls", "none")
        val hostHeader = json.optString("host", "")
        val sni = json.optString("sni", "").takeIf { it.isNotEmpty() } ?: host

        return generateStandardXrayJson("vmess", host, port, uuid, network, path, security, sni, hostHeader)
    }

    private fun parseTrojan(url: String): String {
        val uri = URI(url)
        val password = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port != -1) uri.port else 443

        val query = parseQueryParams(uri.rawQuery)
        val rawPath = query["path"] ?: ""
        val network = query["type"] ?: if (rawPath.isNotEmpty()) "ws" else "tcp"

        var path = try { URLDecoder.decode(rawPath, "UTF-8") } catch (_: Exception) { rawPath }
        if (path.isNotEmpty() && !path.startsWith("/")) path = "/$path"

        val security = query["security"] ?: "tls"
        val hostHeader = query["host"] ?: ""
        val sni = query["sni"]?.takeIf { it.isNotEmpty() } ?: host

        return generateStandardXrayJson("trojan", host, port, password, network, path, security, sni, hostHeader)
    }

    private fun generateStandardXrayJson(
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
            })
        })

        // 3. Inbounds: Socks lokal
        val inbounds = JSONArray().apply {
            put(JSONObject().apply {
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
        }
        root.put("inbounds", inbounds)

        // 4. Outbound Proxy
        val outboundProxy = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", protocol)

            val userObj = JSONObject().apply {
                if (protocol == "trojan") {
                    put("password", authId)
                } else {
                    put("id", authId)
                    if (protocol == "vless") put("encryption", "none")
                    if (protocol == "vmess") {
                        put("alterId", 0)
                        put("security", "auto")
                    }
                }
            }

            val serverObj = JSONObject().apply {
                put("address", host)
                put("port", port)
                if (protocol == "trojan") {
                    put("password", authId)
                } else {
                    put("users", JSONArray().apply { put(userObj) })
                }
            }

            if (protocol == "trojan") {
                put("settings", JSONObject().apply {
                    put("servers", JSONArray().apply { put(serverObj) })
                })
            } else {
                put("settings", JSONObject().apply {
                    put("vnext", JSONArray().apply { put(serverObj) })
                })
            }

            // Stream Transport Settings
            put("streamSettings", JSONObject().apply {
                put("network", network)

                if (security.equals("tls", ignoreCase = true)) {
                    put("security", "tls")
                    put("tlsSettings", JSONObject().apply {
                        put("serverName", sni)
                        put("allowInsecure", true)
                    })
                }

                if (network.equals("ws", ignoreCase = true)) {
                    put("wsSettings", JSONObject().apply {
                        put("path", if (path.isNotEmpty()) path else "/")
                        put("headers", JSONObject().apply {
                            put("Host", if (hostHeader.isNotEmpty()) hostHeader else sni)
                        })
                    })
                }
            })
        }

        // Outbound Freedom (Direct)
        val outboundDirect = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
        }

        root.put("outbounds", JSONArray().apply {
            put(outboundProxy)
            put(outboundDirect)
        })

        // 5. Routing
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("network", "tcp,udp")
                })
            })
        })

        return root.toString(2)
    }

    private fun parseQueryParams(query: String?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isNullOrEmpty()) return map
        query.split("&").forEach { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) map[parts[0]] = parts[1]
        }
        return map
    }

    fun startCoreWithTun(context: Context, configJson: String, tunFd: Int, logCallback: ((String) -> Unit)? = null): Boolean {
        return try {
            copyAssetsIfNeeded(context, logCallback)

            logCallback?.invoke("[CORE] Menghubungkan ke class libXray.LibXray...")
            val libClazz = Class.forName("libXray.LibXray")

            // Ambil LibXrayAPIVersion resmi dari static field di binary
            val apiVersion = try {
                libClazz.getField("LibXrayAPIVersion").getLong(null)
            } catch (e: Exception) {
                logCallback?.invoke("[WARN] Gagal baca LibXrayAPIVersion: ${e.message}")
                1L
            }
            logCallback?.invoke("[CORE] Menggunakan LibXrayAPIVersion: $apiVersion")

            val invokeMethod = libClazz.getMethod("invoke", String::class.java)

            // Struct Go RunXrayRequest: { "xrayJson": string }
            val runPayload = JSONObject().apply {
                put("xrayJson", configJson)
            }

            // Struct Go LibXrayInvokeRequest: { "apiVersion": int64, "name": string, "data": object }
            val invokeRequest = JSONObject().apply {
                put("apiVersion", apiVersion)
                put("name", "RunXray")
                put("data", runPayload)
            }

            logCallback?.invoke("[CORE] Memulai proses RunXray via invoke...")
            val rawResponse = invokeMethod.invoke(null, invokeRequest.toString()) as? String ?: ""
            logCallback?.invoke("[CORE RES] $rawResponse")

            val responseObj = JSONObject(rawResponse)
            val success = responseObj.optBoolean("success", false)

            if (success) {
                logCallback?.invoke("[CORE] Xray Engine AKTIF dan berjalan lancar!")
                true
            } else {
                val errorMsg = responseObj.optString("error", "Gagal menjalankan Xray")
                logCallback?.invoke("[CORE ERROR] $errorMsg")
                false
            }
        } catch (e: ClassNotFoundException) {
            logCallback?.invoke("[FATAL] libXray.LibXray tidak ditemukan: ${e.message}")
            false
        } catch (e: Exception) {
            logCallback?.invoke("[FATAL] Error startCore: ${e.message}")
            false
        }
    }

    fun stopCore() {
        try {
            val libClazz = Class.forName("libXray.LibXray")
            val apiVersion = try {
                libClazz.getField("LibXrayAPIVersion").getLong(null)
            } catch (_: Exception) {
                1L
            }

            val invokeMethod = libClazz.getMethod("invoke", String::class.java)
            val stopRequest = JSONObject().apply {
                put("apiVersion", apiVersion)
                put("name", "StopXray")
                put("data", JSONObject())
            }
            invokeMethod.invoke(null, stopRequest.toString())
            Log.d(TAG, "Xray engine dihentikan.")
        } catch (_: Exception) {}
    }
}
