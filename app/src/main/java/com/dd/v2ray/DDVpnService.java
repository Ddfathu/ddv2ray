package com.dd.v2ray;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

public class DDVpnService extends VpnService {

    private static final String TAG = "DDVpnService";
    private ParcelFileDescriptor vpnInterface = null;
    public static boolean isRunning = false;
    public static String activeConfigJson = "";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "";

        if ("ACTION_STOP".equals(action)) {
            stopVpn();
            return START_NOT_STICKY;
        }

        if (intent != null && intent.hasExtra("CONFIG_JSON")) {
            activeConfigJson = intent.getStringExtra("CONFIG_JSON");
        }

        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        try {
            // 1. Buat Virtual Network Interface Android (TUN Device)
            Builder builder = new Builder();
            builder.setSession("DDV2Ray")
                   .addAddress("26.26.26.1", 24)
                   .addRoute("0.0.0.0", 0)
                   .addDnsServer("8.8.8.8")
                   .addDnsServer("1.1.1.1")
                   .setMtu(1500);

            vpnInterface = builder.establish();
            isRunning = true;
            Log.d(TAG, "TUN Interface berhasil dibuat!");

            // 2. Start V2Ray Core Engine (Proses background)
            if (activeConfigJson != null && !activeConfigJson.isEmpty()) {
                startV2RayCore(activeConfigJson);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error pas nempatin VPN interface", e);
            stopVpn();
        }
    }

    private void startV2RayCore(String configJson) {
        new Thread(() -> {
            try {
                Log.d(TAG, "Memulai V2Ray Core...");
                // Di sini V2Ray Core jalan membawa SOCKS Local Port (10808)
                // Dan Tun2Socks bakal ngarahin trafik dari TUN Interface (vpnInterface) ke 127.0.0.1:10808
            } catch (Exception e) {
                Log.e(TAG, "V2Ray Core gagal start: ", e);
            }
        }).start();
    }

    private void stopVpn() {
        try {
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping VPN", e);
        }
        isRunning = false;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
