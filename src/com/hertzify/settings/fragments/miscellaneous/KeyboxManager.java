/*
 * Copyright (C) 2025 AxionOS Project
 * Copyright (C) 2026 HertzifyOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hertzify.settings.fragments.miscellaneous;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;

public class KeyboxManager {

    private static final String TAG = "KeyboxManager";
    private static final String TRICKY_DIR    = "/data/adb/tricky_store";
    private static final String KEYBOX_FILE   = "keybox.xml";
    private static final String TARGET_FILE   = "target.txt";
    private static final String VENDING_PKG   = "com.android.vending";

    private final Context mContext;

    public KeyboxManager(Context context) {
        mContext = context.getApplicationContext();
        ensureDir();
    }

    public boolean keyboxExists() {
        File f = new File(TRICKY_DIR, KEYBOX_FILE);
        return f.exists() && f.canRead();
    }

    public void importKeybox(Uri uri) throws Exception {
        copyUriToFile(uri, new File(TRICKY_DIR, KEYBOX_FILE));
        killVending();
        Log.i(TAG, "Keybox imported");
    }

    public void deleteKeybox() {
        File f = new File(TRICKY_DIR, KEYBOX_FILE);
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "Failed to delete keybox");
        } else {
            Log.i(TAG, "Keybox deleted");
        }
    }

    public int getTargetAppCount() {
        File f = new File(TRICKY_DIR, TARGET_FILE);
        if (!f.exists()) return 0;
        int count = 0;
        try (BufferedReader br = new BufferedReader(
                new java.io.FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) count++;
            }
        } catch (Exception e) {
            Log.e(TAG, "getTargetAppCount error", e);
        }
        return count;
    }

    public void importTargetFile(Uri uri) throws Exception {
        copyUriToFile(uri, new File(TRICKY_DIR, TARGET_FILE));
        Log.i(TAG, "Target file imported");
    }

    public void saveTargetLines(java.util.List<String> lines) {
        File f = new File(TRICKY_DIR, TARGET_FILE);
        try (FileWriter fw = new FileWriter(f)) {
            for (String line : lines) {
                fw.write(line);
                fw.write("\n");
            }
            f.setReadable(true, false);
            Log.i(TAG, "Target file saved (" + lines.size() + " entries)");
        } catch (Exception e) {
            Log.e(TAG, "saveTargetLines error", e);
        }
    }

    public java.util.List<String> readTargetLines() {
        java.util.List<String> result = new java.util.ArrayList<>();
        File f = new File(TRICKY_DIR, TARGET_FILE);
        if (!f.exists()) return result;
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) result.add(line);
            }
        } catch (Exception e) {
            Log.e(TAG, "readTargetLines error", e);
        }
        return result;
    }

    private void ensureDir() {
        File dir = new File(TRICKY_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private void copyUriToFile(Uri uri, File dest) throws Exception {
        try (InputStream is = mContext.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new Exception("Cannot open input stream for URI");
            byte[] bytes = is.readAllBytes();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                fos.write(bytes);
            }
            dest.setReadable(true, false);
        }
    }

    private void killVending() {
        try {
            ActivityManager am = (ActivityManager)
                    mContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.forceStopPackage(VENDING_PKG);
        } catch (Exception e) {
            Log.w(TAG, "killVending failed", e);
        }
    }
}