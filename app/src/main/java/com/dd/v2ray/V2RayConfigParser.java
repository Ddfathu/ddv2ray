package com.dd.v2ray;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class V2RayConfigParser {

    public static String parseToV2RayJson(String rawUrl) throws Exception {
        if (rawUrl.startsWith("vless://")) {
            return parseVless(rawUrl);
        } else if (rawUrl.startsWith("vmess://")) {
            return parseVmess(rawUrl);
        } else {
            throw new IllegalArgumentException("Format tidak didukung! Gunakan link vless:// atau vmess://");
        }
    }

    private static String parseVless(String urlStr) throws Exception {
        URI uri = new URI(urlStr);
        String uuid = uri.getUserInfo();
        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        String query = uri.getQuery();

        String type = "tcp";
        String security = "none";
        String path = "";
        String sni = host;

        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2) {
                    String key = pair[0];
                    String value = URLDecoder.decode(pair[1], "UTF-8");
                    if ("type".equalsIgnoreCase(key)) type = value;
                    if ("security".equalsIgnoreCase(key)) security = value;
                    if ("path".equalsIgnoreCase(key)) path = value;
                    if ("sni".equalsIgnoreCase(key)) sni = value;
                }
            }
        }

        return generateJsonConfig(host, port, uuid, "vless", security, type, path, sni);
    }

    private static String parseVmess(String urlStr) throws Exception {
        String base64Data = urlStr.replace("vmess://", "");
        byte[] decodedBytes = Base64.decode(base64Data, Base64.DEFAULT);
        String jsonStr = new String(decodedBytes, StandardCharsets.UTF_8);

        JSONObject vmessJson = new JSONObject(jsonStr);
        String host = vmessJson.optString("add");
        int port = vmessJson.optInt("port", 443);
        String uuid = vmessJson.optString("id");
        String security = vmessJson.optString("tls", "none");
        String type = vmessJson.optString("net", "tcp");
        String path = vmessJson.optString("path", "");
        String sni = vmessJson.optString("sni", host);

        return generateJsonConfig(host, port, uuid, "vmess", security, type, path, sni);
    }

    private static String generateJsonConfig(String address, int port, String id, String protocol, String security, String network, String path, String sni) throws Exception {
        JSONObject config = new JSONObject();

        // Inbound (SOCKS local)
        JSONObject inbound = new JSONObject();
        inbound.put("port", 10808);
        inbound.put("listen", "127.0.0.1");
        inbound.put("protocol", "socks");

        JSONArray inbounds = new JSONArray();
        inbounds.put(inbound);
        config.put("inbounds", inbounds);

        // Outbound
        JSONObject outbound = new JSONObject();
        outbound.put("protocol", protocol);

        JSONObject user = new JSONObject();
        user.put("id", id);
        user.put("encryption", "none");

        JSONArray users = new JSONArray();
        users.put(user);

        JSONObject server = new JSONObject();
        server.put("address", address);
        server.put("port", port);
        server.put("users", users);

        JSONArray vnext = new JSONArray();
        vnext.put(server);

        JSONObject settings = new JSONObject();
        settings.put("vnext", vnext);
        outbound.put("settings", settings);

        // Stream Settings (TLS/WS/TCP)
        JSONObject streamSettings = new JSONObject();
        streamSettings.put("network", network);
        streamSettings.put("security", security);

        if ("tls".equalsIgnoreCase(security)) {
            JSONObject tlsSettings = new JSONObject();
            tlsSettings.put("serverName", sni);
            streamSettings.put("tlsSettings", tlsSettings);
        }

        if ("ws".equalsIgnoreCase(network)) {
            JSONObject wsSettings = new JSONObject();
            wsSettings.put("path", path);
            streamSettings.put("wsSettings", wsSettings);
        }

        outbound.put("streamSettings", streamSettings);

        JSONArray outbounds = new JSONArray();
        outbounds.put(outbound);
        config.put("outbounds", outbounds);

        return config.toString(2);
    }
}
