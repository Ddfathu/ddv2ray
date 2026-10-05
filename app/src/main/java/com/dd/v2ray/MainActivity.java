package com.dd.v2ray;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_VPN_CODE = 1001;
    private EditText etConfig;
    private Button btnConnect;
    private String parsedConfigJson = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etConfig = findViewById(R.id.etConfig);
        btnConnect = findViewById(R.id.btnConnect);

        updateUiState();

        btnConnect.setOnClickListener(v -> {
            if (!DDVpnService.isRunning) {
                String rawConfig = etConfig.getText().toString().trim();
                if (rawConfig.isEmpty()) {
                    Toast.makeText(this, "Isi config/link V2Ray (vless:// / vmess://) dulu bos!", Toast.LENGTH_SHORT).show();
                    return;
                }

                try {
                    parsedConfigJson = V2RayConfigParser.parseToV2RayJson(rawConfig);
                } catch (Exception e) {
                    Toast.makeText(this, "Error Parse Link: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }

                // Minta Izin sistem Android buat aktifin VPN
                Intent vpnIntent = VpnService.prepare(this);
                if (vpnIntent != null) {
                    startActivityForResult(vpnIntent, REQUEST_VPN_CODE);
                } else {
                    onActivityResult(REQUEST_VPN_CODE, RESULT_OK, null);
                }
            } else {
                // Matikan VPN Service
                Intent stopIntent = new Intent(this, DDVpnService.class);
                stopIntent.setAction("ACTION_STOP");
                startService(stopIntent);

                btnConnect.setText("Connect");
                Toast.makeText(this, "VPN Disconnected", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN_CODE && resultCode == RESULT_OK) {
            // Jalankan VPN Service dengan membawa Config JSON
            Intent startIntent = new Intent(this, DDVpnService.class);
            startIntent.putExtra("CONFIG_JSON", parsedConfigJson);
            startService(startIntent);

            btnConnect.setText("Disconnect");
            Toast.makeText(this, "VPN Service Berhasil Dijalankan!", Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQUEST_VPN_CODE) {
            Toast.makeText(this, "Izin VPN ditolak!", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateUiState() {
        if (DDVpnService.isRunning) {
            btnConnect.setText("Disconnect");
        } else {
            btnConnect.setText("Connect");
        }
    }
}
