package com.dd.v2ray

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.dd.v2ray.service.V2RayVpnService
import com.dd.v2ray.utils.V2RayCoreUtils
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvConfigSummary: TextView
    private lateinit var etConfigUrl: EditText
    private lateinit var btnPaste: Button
    private lateinit var btnScan: Button
    private lateinit var btnToggleVpn: Button
    private lateinit var tvDebugLog: TextView
    private lateinit var scrollDebug: ScrollView
    private lateinit var btnClearLog: Button

    private var currentConfigJson: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val rawUrl = result.contents.trim()
            etConfigUrl.setText(rawUrl)
            processAndLoadConfig(rawUrl)
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            appendLog("[PERM] Izin diberikan oleh pengguna.")
            executeStartService()
        } else {
            appendLog("[ERROR] Izin VPN ditolak!")
            Toast.makeText(this, "Izin VPN ditolak!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvConfigSummary = findViewById(R.id.tvConfigSummary)
        etConfigUrl = findViewById(R.id.etConfigUrl)
        btnPaste = findViewById(R.id.btnPaste)
        btnScan = findViewById(R.id.btnScan)
        btnToggleVpn = findViewById(R.id.btnToggleVpn)
        tvDebugLog = findViewById(R.id.tvDebugLog)
        scrollDebug = findViewById(R.id.scrollDebug)
        btnClearLog = findViewById(R.id.btnClearLog)

        // Hubungkan log callback langsung tanpa broadcast
        V2RayVpnService.onLogReceived = { msg ->
            mainHandler.post {
                appendLog(msg)
            }
        }

        btnClearLog.setOnClickListener {
            tvDebugLog.text = ""
        }

        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val pasted = clipData.getItemAt(0).text?.toString()?.trim() ?: ""
                if (pasted.isNotEmpty()) {
                    etConfigUrl.setText(pasted)
                    processAndLoadConfig(pasted)
                }
            }
        }

        btnScan.setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan QR Code V2Ray")
                setBeepEnabled(true)
            }
            barcodeLauncher.launch(options)
        }

        btnToggleVpn.setOnClickListener {
            if (V2RayVpnService.isRunning) {
                stopVpnService()
            } else {
                val inputUrl = etConfigUrl.text.toString().trim()
                if (inputUrl.isNotEmpty() && currentConfigJson.isEmpty()) {
                    processAndLoadConfig(inputUrl)
                }

                if (currentConfigJson.isEmpty()) {
                    appendLog("[ERROR] Masukkan URL valid terlebih dahulu!")
                    Toast.makeText(this, "Config kosong!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                prepareAndStartVpn()
            }
        }
    }

    private fun appendLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvDebugLog.append("[$time] $text\n")
        scrollDebug.post {
            scrollDebug.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun processAndLoadConfig(rawUrl: String) {
        appendLog("[PARSE] Mengonversi URL ke JSON format V2RayNG...")
        val json = V2RayCoreUtils.parseUrlToJson(rawUrl)
        if (json.isNotEmpty()) {
            currentConfigJson = json
            val proto = rawUrl.substringBefore("://")
            tvConfigSummary.text = "Config siap: $proto (Converted)"
            tvConfigSummary.setTextColor(Color.parseColor("#10B981"))
            appendLog("[PARSE] Berhasil! Konfigurasi siap dikirim ke TUN.")
        } else {
            appendLog("[ERROR] URL tidak dikenali atau format salah!")
            Toast.makeText(this, "URL salah!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            appendLog("[VPN] Meminta izin sistem VpnService...")
            vpnPermissionLauncher.launch(intent)
        } else {
            executeStartService()
        }
    }

    private fun executeStartService() {
        val intent = Intent(this, V2RayVpnService::class.java).apply {
            action = V2RayVpnService.ACTION_START
            putExtra(V2RayVpnService.EXTRA_CONFIG, currentConfigJson)
        }
        startService(intent)

        tvStatus.text = "CONNECTED"
        tvStatus.setTextColor(Color.parseColor("#10B981"))
        btnToggleVpn.text = "DISCONNECT VPN"
        btnToggleVpn.setBackgroundColor(Color.parseColor("#DC2626"))
    }

    private fun stopVpnService() {
        val intent = Intent(this, V2RayVpnService::class.java).apply {
            action = V2RayVpnService.ACTION_STOP
        }
        startService(intent)

        tvStatus.text = "DISCONNECTED"
        tvStatus.setTextColor(Color.parseColor("#EF4444"))
        btnToggleVpn.text = "CONNECT VPN"
        btnToggleVpn.setBackgroundColor(Color.parseColor("#2563EB"))
    }
}
