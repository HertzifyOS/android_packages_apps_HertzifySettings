/*
 * Copyright (C) 2025 AxionOS Project
 * Copyright (C) 2026 HertzifyOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hertzify.settings.fragments.miscellaneous;

import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PifRepository {

    private static final String TAG        = "PifRepository";
    private static final String GOOGLE_URL = "https://developer.android.com";

    public abstract static class PifResult {
        private PifResult() {}

        public static final class Success extends PifResult {
            public final String     model;
            public final JSONObject pifData;

            public Success(String model, JSONObject pifData) {
                this.model   = model;
                this.pifData = pifData;
            }
        }

        public static final class Error extends PifResult {
            public final String    message;
            public final Exception exception;

            public Error(String message) {
                this(message, null);
            }

            public Error(String message, Exception e) {
                this.message   = message != null ? message : "Unknown error";
                this.exception = e;
            }
        }
    }

    public PifResult fetchBetaPif() {
        try {
            Log.d(TAG, "Fetching Pixel Beta metadata from Google Developer…");

            String versionsHtml = fetchUrl(GOOGLE_URL + "/about/versions");
            List<Integer> versions = extractVersionNumbers(versionsHtml);

            if (versions.isEmpty()) {
                return new PifResult.Error("Could not find any Android version pages");
            }
            Log.d(TAG, "Found versions: " + versions);

            for (int version : versions) {
                String versionPage = GOOGLE_URL + "/about/versions/" + version;
                Log.d(TAG, "Checking: " + versionPage);
                try {
                    String versionHtml = fetchUrl(versionPage);
                    List<String> qprPaths = extractQprPaths(versionHtml, version);

                    if (qprPaths.isEmpty()) {
                        Log.d(TAG, "No QPR pages for version " + version);
                        continue;
                    }

                    for (String qprPath : qprPaths) {
                        String otaPage = GOOGLE_URL + qprPath;
                        Log.d(TAG, "Trying OTA page: " + otaPage);
                        try {
                            String otaHtml = fetchUrl(otaPage);
                            List<String[]> otaUrls = extractOtaUrls(otaHtml);

                            if (otaUrls.isEmpty()) {
                                Log.d(TAG, "No beta OTA URLs on this page");
                                continue;
                            }

                            List<String[]> devices = matchDevicesToOta(otaHtml, otaUrls);
                            if (devices.isEmpty()) {
                                Log.d(TAG, "Could not match devices to OTA URLs");
                                continue;
                            }

                            String[] chosen  = devices.get(new Random().nextInt(devices.size()));
                            String   model   = chosen[0];
                            String   product = chosen[1];
                            String   otaUrl  = chosen[2];
                            String   device  = product.replace("_beta", "");

                            Log.d(TAG, "Selected: " + model + " (" + product + ")");

                            String partial      = fetchPartialUrl(otaUrl, 4096);
                            String fingerprint  = extractRegex(partial, "post-build=(.*)");
                            String securityPatch = extractRegex(partial, "security-patch-level=(.*)");

                            if (fingerprint == null || securityPatch == null) {
                                Log.d(TAG, "Could not extract fingerprint/patch, trying next…");
                                continue;
                            }

                            fingerprint   = fingerprint.trim();
                            securityPatch = securityPatch.trim();

                            Log.d(TAG, "Fingerprint: "   + fingerprint);
                            Log.d(TAG, "SecurityPatch: " + securityPatch);

                            JSONObject pifJson = new JSONObject();
                            pifJson.put("MANUFACTURER",          "Google");
                            pifJson.put("MODEL",                 model);
                            pifJson.put("PRODUCT",               product);
                            pifJson.put("DEVICE",                device);
                            pifJson.put("FINGERPRINT",           fingerprint);
                            pifJson.put("SECURITY_PATCH",        securityPatch);
                            pifJson.put("DEVICE_INITIAL_SDK_INT","32");

                            return new PifResult.Success(model, pifJson);

                        } catch (Exception e) {
                            Log.d(TAG, "QPR page failed: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Version page failed (" + version + "): " + e.getMessage());
                }
            }

            return new PifResult.Error("No valid beta OTA pages found");

        } catch (Exception e) {
            Log.e(TAG, "fetchBetaPif error", e);
            return new PifResult.Error("Failed to fetch from Google: " + e.getMessage(), e);
        }
    }

    public PifResult fetchFromUrl(String urlString) {
        try {
            String json = fetchUrl(urlString);
            JSONObject obj = new JSONObject(json);
            return new PifResult.Success(obj.optString("MODEL", "Unknown"), obj);
        } catch (Exception e) {
            Log.e(TAG, "fetchFromUrl error", e);
            return new PifResult.Error("Failed to download: " + e.getMessage(), e);
        }
    }

    public PifResult parseFromString(String jsonString) {
        try {
            JSONObject obj = new JSONObject(jsonString);
            return new PifResult.Success(obj.optString("MODEL", "Unknown"), obj);
        } catch (Exception e) {
            Log.e(TAG, "parseFromString error", e);
            return new PifResult.Error("Failed to parse JSON: " + e.getMessage(), e);
        }
    }

    private List<Integer> extractVersionNumbers(String html) {
        List<Integer> versions = new ArrayList<>();
        Matcher m = Pattern.compile(
                "https://developer\\.android\\.com/about/versions/(\\d+)")
                .matcher(html);
        while (m.find()) {
            int v = Integer.parseInt(m.group(1));
            if (!versions.contains(v)) versions.add(v);
        }
        versions.sort((a, b) -> b - a); // descending
        return versions;
    }

    private List<String> extractQprPaths(String html, int version) {
        List<int[]>  nums  = new ArrayList<>();
        List<String> paths = new ArrayList<>();

        Matcher m = Pattern.compile(
                "href=\"(/about/versions/" + version + "/qpr(\\d+)/download-ota)\"")
                .matcher(html);
        while (m.find()) {
            nums.add(new int[]{Integer.parseInt(m.group(2))});
            paths.add(m.group(1));
        }

        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) indices.add(i);
        indices.sort((a, b) -> nums.get(b)[0] - nums.get(a)[0]);

        List<String> sorted = new ArrayList<>();
        for (int idx : indices) sorted.add(paths.get(idx));
        return sorted;
    }

    private List<String[]> extractOtaUrls(String html) {
        List<String[]> result = new ArrayList<>();
        Matcher m = Pattern.compile(
                "href=\"(https://dl\\.google\\.com/[^\"]*ota/([^/\"]+_beta)[^\"]*?)\"")
                .matcher(html);
        while (m.find()) {
            result.add(new String[]{m.group(1), m.group(2)});
        }
        return result;
    }

    private List<String[]> matchDevicesToOta(String html, List<String[]> otaUrls) {
        List<String[]> devices  = new ArrayList<>();
        Pattern        tdPat    = Pattern.compile("<td[^>]*>([^<]+)</td>");

        for (String[] entry : otaUrls) {
            String otaUrl = entry[0];
            String product = entry[1];
            int urlIndex = html.indexOf(otaUrl);
            if (urlIndex < 0) continue;

            String  before = html.substring(0, urlIndex);
            Matcher tdm    = tdPat.matcher(before);
            String  lastTd = null;
            while (tdm.find()) lastTd = tdm.group(1).trim();

            if (lastTd != null && !lastTd.isEmpty()) {
                devices.add(new String[]{lastTd, product, otaUrl});
                Log.d(TAG, "Matched: " + lastTd + " → " + product);
            }
        }
        return devices;
    }

    private String fetchUrl(String urlString) throws Exception {
        URLConnection conn = new URL(urlString).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36");
        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String fetchPartialUrl(String urlString, int maxBytes) throws Exception {
        URLConnection conn = new URL(urlString).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);

        byte[]        buf       = new byte[512];
        StringBuilder sb        = new StringBuilder();
        int           totalRead = 0;

        try (InputStream is = conn.getInputStream()) {
            while (totalRead < maxBytes) {
                int read = is.read(buf);
                if (read < 0) break;
                sb.append(new String(buf, 0, read, StandardCharsets.ISO_8859_1));
                totalRead += read;
            }
        }
        return sb.toString();
    }

    private String extractRegex(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }
}