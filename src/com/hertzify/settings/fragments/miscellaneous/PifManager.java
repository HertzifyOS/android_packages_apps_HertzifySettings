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

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PifManager {

    private static final String TAG            = "PifManager";
    private static final String PIF_DIR        = "/data/adb/playintegrityfix";
    private static final String VENDING_PKG    = "com.android.vending";
    private static final String KEY_SPOOF_PHOTOS = "spoofPhotos";
    private static final String PHOTOS_PKG = "com.google.android.apps.photos";

    // Priority order: first found file wins
    private static final List<String> CONFIG_FILES = Arrays.asList(
            "custom.pif.prop",
            "custom.pif.json",
            "pif.prop",
            "pif.json");

    private final Context mContext;

    public PifManager(Context context) {
        mContext = context.getApplicationContext();
        ensureDir();
    }

    public String getActiveConfigName() {
        File f = findActiveFile();
        return f != null ? f.getName() : "";
    }

    public String getCurrentModel() {
        return getCurrentProperties().getOrDefault("MODEL", "");
    }

    public Map<String, String> getCurrentProperties() {
        File active = findActiveFile();
        return active != null ? readConfig(active) : Collections.emptyMap();
    }

    public void applyPif(JSONObject pifData) {
        try {
            File target = new File(PIF_DIR, "custom.pif.json");
            try (FileWriter fw = new FileWriter(target)) {
                fw.write(pifData.toString(2));
            }
            target.setReadable(true, false);
            killPackage(VENDING_PKG)();
            Log.i(TAG, "PIF applied → " + target.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "applyPif failed", e);
        }
    }

    public boolean isSpoofPhotosEnabled() {
        String val = getCurrentProperties().get(KEY_SPOOF_PHOTOS);
        return "true".equalsIgnoreCase(val) || "1".equals(val);
    }

    public void setSpoofPhotos(boolean enabled) {
        File active = findActiveFile();
        if (active == null) {
            Log.w(TAG, "setSpoofPhotos: no active config file found");
            return;
        }
        updateConfigKey(active, KEY_SPOOF_PHOTOS, String.valueOf(enabled));
        killPackage(PHOTOS_PKG);;
    }

    private void ensureDir() {
        File dir = new File(PIF_DIR);
        if (!dir.exists()) dir.mkdirs();
    }

    private File findActiveFile() {
        File dir = new File(PIF_DIR);
        for (String name : CONFIG_FILES) {
            File f = new File(dir, name);
            if (f.exists() && f.canRead()) return f;
        }
        return null;
    }

    private Map<String, String> readConfig(File file) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            String content = readFileToString(file);
            if (file.getName().endsWith(".json")) {
                JSONObject json = new JSONObject(content);
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    result.put(k, json.optString(k, ""));
                }
            } else {
                for (String line : content.split("\n")) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue;
                    int eq = t.indexOf('=');
                    if (eq <= 0) continue;
                    String key   = t.substring(0, eq).trim();
                    String value = t.substring(eq + 1).trim();
                    // Strip inline comments
                    int ci = value.indexOf('#');
                    if (ci >= 0) value = value.substring(0, ci).trim();
                    if (!value.isEmpty()) result.put(key, value);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "readConfig error for " + file.getName(), e);
        }
        return result;
    }

    private void updateConfigKey(File file, String key, String value) {
        try {
            String content = readFileToString(file);
            String updated;

            if (file.getName().endsWith(".json")) {
                JSONObject json = new JSONObject(content);
                json.put(key, value);
                updated = json.toString(2);
            } else {
                StringBuilder sb    = new StringBuilder();
                boolean       found = false;
                for (String line : content.split("\n")) {
                    String t = line.trim();
                    if (!t.startsWith("#") && t.startsWith(key + "=")) {
                        sb.append(key).append("=").append(value).append("\n");
                        found = true;
                    } else {
                        sb.append(line).append("\n");
                    }
                }
                if (!found) sb.append(key).append("=").append(value).append("\n");
                updated = sb.toString();
            }

            try (FileWriter fw = new FileWriter(file)) {
                fw.write(updated);
            }
        } catch (Exception e) {
            Log.e(TAG, "updateConfigKey error", e);
        }
    }

    private String readFileToString(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private void killPackage(String pkg) {
        try {
            ActivityManager am = (ActivityManager)
                    mContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.forceStopPackage(pkg);
        } catch (Exception e) {
            Log.w(TAG, "killPackage failed: " + pkg, e);
        }
    }
}