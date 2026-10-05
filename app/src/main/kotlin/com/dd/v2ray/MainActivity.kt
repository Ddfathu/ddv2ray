package com.dd.v2ray

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.dd.v2ray.service.V2RayVpnService
import com.dd.v2ray.utils.V2RayCoreUtils
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvConfigSummary: TextView
    private lateinit var etConfigUrl: EditText
    private lateinit var btnPaste: Button
    private lateinit var btnScan: Button
    private lateinit var btnToggleVpn: Button

    private var isVpnRunning = false
    private var currentConfigJson: String = ""

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
            startVpnService()
        } else {
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

        // 1. Ambil teks dari clipboard
        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val pasted = clipData.getItemAt(0).text?.toString()?.trim() ?: ""
                if (pasted.isNotEmpty()) {
                    etConfigUrl.setText(pasted)
                    processAndLoadConfig(pasted)
                } else {
                    Toast.makeText(this, "Clipboard kosong", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard kosong", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. Scan QR Code
        btnScan.setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Arahkan kamera ke QR Code V2Ray")
                setBeepEnabled(true)
            }
            barcodeLauncher.launch(options)
        }

        // 3. Tombol Connect / Disconnect
        btnToggleVpn.setOnClickListener {
            if (isVpnRunning) {
                stopVpnService()
            } else {
                val inputUrl = etConfigUrl.text.toString().trim()
                if (inputUrl.isNotEmpty() && currentConfigJson.isEmpty()) {
                    processAndLoadConfig(inputUrl)
                }

                if (currentConfigJson.isEmpty()) {
                    Toast.makeText(this, "Pilih atau paste config terlebih dahulu!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                prepareAndStartVpn()
            }
        }
    }

    private fun processAndLoadConfig(rawUrl: String) {
        try {
            val json = V2RayCoreUtils.parseUrlToJson(rawUrl)
            if (json.isNotEmpty() && !json.contains("\"outbounds\":[]")) {
                currentConfigJson = json
                val protocol = rawUrl.substringBefore("://")
                tvConfigSummary.text = "Config siap: $protocol"
                tvConfigSummary.setTextColor(Color.parseColor("#10B981"))
                Toast.makeText(this, "Konfigurasi valid!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Format URL tidak didukung!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal memproses URL: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, V2RayVpnService::class.java).apply {
            action = V2RayVpnService.ACTION_START
            putExtra(V2RayVpnService.EXTRA_CONFIG, currentConfigJson)
        }
        startService(intent)

        isVpnRunning = true
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

        isVpnRunning = false
        tvStatus.text = "DISCONNECTED"
        tvStatus.setTextColor(Color.parseColor("#EF4444"))

        btnToggleVpn.text = "CONNECT VPN"
        btnToggleVpn.setBackgroundColor(Color.parseColor("#2563EB"))
    }
}
