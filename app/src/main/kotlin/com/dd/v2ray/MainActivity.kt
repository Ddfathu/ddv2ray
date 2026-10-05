package com.dd.v2ray

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
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
    private lateinit var btnToggleVpn: Button
    private var isVpnRunning = false
    private var currentConfigJson: String = ""

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val rawUrl = result.contents
            currentConfigJson = V2RayCoreUtils.parseUrlToJson(rawUrl)
            Toast.makeText(this, "Config Berhasil Di-parse!", Toast.LENGTH_SHORT).show()
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "Izin VPN Ditolak!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnToggleVpn = findViewById(R.id.btnToggleVpn)

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setPrompt("Scan QR Code Config V2Ray")
            options.setBeepEnabled(true)
            barcodeLauncher.launch(options)
        }

        btnToggleVpn.setOnClickListener {
            if (isVpnRunning) {
                stopVpnService()
            } else {
                prepareAndStartVpn()
            }
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
            putExtra("CONFIG_JSON", currentConfigJson)
        }
        startService(intent)
        isVpnRunning = true
        tvStatus.text = "Status: CONNECTED"
        btnToggleVpn.text = "Disconnect VPN"
    }

    private fun stopVpnService() {
        val intent = Intent(this, V2RayVpnService::class.java)
        stopService(intent)
        isVpnRunning = false
        tvStatus.text = "Status: DISCONNECTED"
        btnToggleVpn.text = "Connect VPN"
    }
}
