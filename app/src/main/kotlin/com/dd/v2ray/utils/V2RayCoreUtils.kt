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
                    logCallback?.invoke("[WARN] Aset $name tidak ada di assets folder.")
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
            Log.e(TAG, "Gagal parsing: ${e.message}")
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
        
        // Bug SNI Quipper: SNI mengarah ke host server bug (agar lolos kuota edukasi), Host header ke backend
        val sni = query["sni"]?.takeIf { it.isNotEmpty() } ?: host

        return generateStandardV2RayJson("vless", host, port, uuid, network, path, security, sni, hostHeader)
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

        return generateStandardV2RayJson("vmess", host, port, uuid, network, path, security, sni, hostHeader)
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

        return generateStandardV2RayJson("trojan", host, port, password, network, path, security, sni, hostHeader)
    }

    // Format Konfigurasi Standar V2RayNG (Resmi)
    private fun generateStandardV2RayJson(
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

        // 3. Inbound Dokodemo-door untuk paket TUN
        val inbounds = JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "proxy")
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
                put("id", authId)
                if (protocol == "vless") put("encryption", "none")
                if (protocol == "vmess") {
                    put("alterId", 0)
                    put("security", "auto")
                }
            }

            if (protocol == "trojan") {
                put("settings", JSONObject().apply {
                    put("servers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", host)
                            put("port", port)
                            put("password", authId)
                        })
                    })
                })
            } else {
                put("settings", JSONObject().apply {
                    put("vnext", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", host)
                            put("port", port)
                            put("users", JSONArray().apply { put(userObj) })
                        })
                    })
                })
            }

            // Stream Settings (Transport)
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

        // Outbound Direct
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
            val assetPath = context.filesDir.absolutePath

            logCallback?.invoke("[CORE] Mencari kelas native libv2ray.Libv2ray...")
            val libClazz = Class.forName("libv2ray.Libv2ray")

            try {
                val initMethod = libClazz.getMethod("initV2Env", String::class.java)
                initMethod.invoke(null, assetPath)
                logCallback?.invoke("[CORE] Inisialisasi env aset berhasil.")
            } catch (e: Exception) {
                logCallback?.invoke("[CORE] initV2Env: ${e.message}")
            }

            logCallback?.invoke("[CORE] Menjalankan startV2Ray dengan TUN FD: $tunFd...")
            val startMethod = libClazz.getMethod("startV2Ray", String::class.java, Int::class.javaPrimitiveType)
            startMethod.invoke(null, configJson, tunFd)

            logCallback?.invoke("[CORE] Mesin V2Ray Core berhasil berputar.")
            true
        } catch (e: ClassNotFoundException) {
            logCallback?.invoke("[FATAL] libv2ray.aar tidak ditemukan di libs!")
            false
        } catch (e: Exception) {
            logCallback?.invoke("[FATAL] Gagal menyalakan core: ${e.message}")
            false
        }
    }

    fun stopCore() {
        try {
            val libClazz = Class.forName("libv2ray.Libv2ray")
            val stopMethod = libClazz.getMethod("stopV2Ray")
            stopMethod.invoke(null)
        } catch (_: Exception) {}
    }
}
