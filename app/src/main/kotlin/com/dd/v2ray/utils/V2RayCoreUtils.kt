package com.dd.v2ray.utils

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object V2RayCoreUtils {
    private const val TAG = "V2RayCoreUtils"

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
            } else {
                logCallback?.invoke("[ASSET] Aset $fileName siap.")
            }
        }
    }

    fun parseUrlToJson(shareUrl: String): String {
        return try {
            val libClazz = Class.forName("libXray.LibXray")
            val invokeMethod = libClazz.getMethod("invoke", String::class.java)

            val apiVersion = try {
                libClazz.getField("LibXrayAPIVersion").getLong(null)
            } catch (e: Exception) {
                3L
            }

            val payload = JSONObject().apply {
                put("text", shareUrl.trim())
            }

            val rawBytes = payload.toString().toByteArray(Charsets.UTF_8)
            val base64Data = Base64.encodeToString(rawBytes, Base64.NO_WRAP)

            val invokeRequest = JSONObject().apply {
                put("apiVersion", apiVersion)
                put("name", "ConvertShareLinksToXrayJson")
                put("data", base64Data)
            }

            val responseStr = invokeMethod.invoke(null, invokeRequest.toString()) as? String ?: ""
            val resObj = JSONObject(responseStr)

            if (resObj.optBoolean("success", false)) {
                val dataStr = resObj.optString("data", "")
                try {
                    val decodedBytes = Base64.decode(dataStr, Base64.DEFAULT)
                    String(decodedBytes, Charsets.UTF_8)
                } catch (_: Exception) {
                    dataStr
                }
            } else {
                Log.e(TAG, "Gagal konversi share link: ${resObj.optString("error")}")
                "{}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception di parseUrlToJson: ${e.message}")
            "{}"
        }
    }

    fun prepareConfigForTun(rawConfigJson: String): String {
        return try {
            val root = JSONObject(rawConfigJson)
            val inbounds = root.optJSONArray("inbounds") ?: JSONArray()

            var hasDokodemo = false
            var hasSocks = false

            for (i in 0 until inbounds.length()) {
                val item = inbounds.optJSONObject(i)
                val protocol = item?.optString("protocol", "")
                if (protocol == "dokodemo-door") hasDokodemo = true
                if (protocol == "socks") hasSocks = true
            }

            if (!hasDokodemo) {
                val dokoInbound = JSONObject().apply {
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
                        put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                    })
                }
                inbounds.put(dokoInbound)
            }

            if (!hasSocks) {
                val socksInbound = JSONObject().apply {
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
                        put("destOverride", JSONArray().put("http").put("tls"))
                    })
                }
                inbounds.put(socksInbound)
            }

            root.put("inbounds", inbounds)
            root.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Injeksi gagal, memakai config asli: ${e.message}")
            rawConfigJson
        }
    }

    fun startCoreWithTun(
        context: Context,
        configJson: String,
        tunFd: Int,
        logCallback: ((String) -> Unit)? = null
    ): Boolean {
        return try {
            copyAssetsIfNeeded(context, logCallback)
            val assetPath = context.filesDir.absolutePath

            try {
                System.setProperty("xray.location.asset", assetPath)
                System.setProperty("v2ray.location.asset", assetPath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Os.setenv("XRAY_LOCATION_ASSET", assetPath, true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gagal setenv asset path: ${e.message}")
            }

            val preparedConfig = prepareConfigForTun(configJson)

            logCallback?.invoke("[CORE] Menghubungkan ke class libXray.LibXray...")
            val libClazz = Class.forName("libXray.LibXray")

            val apiVersion = try {
                libClazz.getField("LibXrayAPIVersion").getLong(null)
            } catch (e: Exception) {
                3L
            }
            logCallback?.invoke("[CORE] API Version: $apiVersion")

            val invokeMethod = libClazz.getMethod("invoke", String::class.java)

            // Sub-payload RunXrayRequest
            val runPayload = JSONObject().apply {
                put("xrayJson", preparedConfig)
            }

            // Encode payload ke Base64 (Go Data []uint8)
            val rawPayloadBytes = runPayload.toString().toByteArray(Charsets.UTF_8)
            val base64Data = Base64.encodeToString(rawPayloadBytes, Base64.NO_WRAP)

            // Envelope invoke
            val invokeRequest = JSONObject().apply {
                put("apiVersion", apiVersion)
                put("name", "RunXray")
                put("data", base64Data)
            }

            logCallback?.invoke("[CORE] Menjalankan RunXray via invoke...")
            val rawResponse = invokeMethod.invoke(null, invokeRequest.toString()) as? String ?: ""
            logCallback?.invoke("[CORE RES RUN] $rawResponse")
            Log.d(TAG, "[CORE RES RUN] $rawResponse")

            val responseObj = JSONObject(rawResponse)
            val success = responseObj.optBoolean("success", false)

            if (success) {
                logCallback?.invoke("[CORE] Xray Engine AKTIF!")
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

    fun stopCore(logCallback: ((String) -> Unit)? = null) {
        logCallback?.invoke("[CORE] Core dinonaktifkan.")
    }
}
