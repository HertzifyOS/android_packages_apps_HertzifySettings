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

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settings.R;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AppSpoofFragment extends SettingsBasePreferenceFragment {

    private static final String TAG = "AppSpoofFragment";

    private static final String CONFIG_DIR      = "/data/adb/appprops";
    private static final String CONFIG_FILE     = "appprops.json";
    private static final String PROFILES_FILE   = "profiles.json";

    private static final String KEY_ENABLED          = "app_spoof_enabled";
    private static final String KEY_ADD_APP          = "app_spoof_add_app";
    private static final String KEY_MANAGE_PROFILES  = "app_spoof_manage_profiles";
    private static final String KEY_APP_LIST_CAT     = "app_spoof_app_list_category";

    private static List<DeviceProfile> defaultProfiles() {
        List<DeviceProfile> list = new ArrayList<>();

        list.add(new DeviceProfile("ROG Phone 8 Pro",
                mapOf("MODEL", "ASUS_AI2401_A", "MANUFACTURER", "asus")));
        list.add(new DeviceProfile("Galaxy S24 Ultra",
                mapOf("MODEL", "SM-S928B", "MANUFACTURER", "samsung")));
        list.add(new DeviceProfile("Xiaomi 13 Pro",
                mapOf("MODEL", "2210132C", "MANUFACTURER", "Xiaomi")));
        list.add(new DeviceProfile("OnePlus 9 Pro",
                mapOf("MODEL", "LE2101", "MANUFACTURER", "OnePlus")));
        list.add(new DeviceProfile("Black Shark 4",
                mapOf("MODEL", "2SM-X706B", "MANUFACTURER", "blackshark")));
        list.add(new DeviceProfile("Lenovo Y700",
                mapOf("MODEL", "Lenovo TB-9707F", "MANUFACTURER", "Lenovo")));
        return list;
    }

    private static Map<String, String> mapOf(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private List<AppConfig>     mConfigs  = new ArrayList<>();
    private List<DeviceProfile> mProfiles = new ArrayList<>();
    private boolean             mEnabled  = false;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.app_spoof_settings, rootKey);
        loadProfiles();
        loadConfig();
        bindPreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAppPreferences();
    }

    private void bindPreferences() {
        SwitchPreferenceCompat enabledPref = findPreference(KEY_ENABLED);
        if (enabledPref != null) {
            enabledPref.setChecked(mEnabled);
            enabledPref.setOnPreferenceChangeListener((p, v) -> {
                mEnabled = (Boolean) v;
                saveConfig();
                return true;
            });
        }

        Preference addApp = findPreference(KEY_ADD_APP);
        if (addApp != null) {
            addApp.setOnPreferenceClickListener(p -> { showAddAppDialog(); return true; });
        }

        Preference manageProfiles = findPreference(KEY_MANAGE_PROFILES);
        if (manageProfiles != null) {
            manageProfiles.setOnPreferenceClickListener(p -> {
                showManageProfilesDialog();
                return true;
            });
        }

        refreshAppPreferences();
    }

    private void refreshAppPreferences() {
        PreferenceCategory cat = findPreference(KEY_APP_LIST_CAT);
        if (cat == null) return;
        cat.removeAll();

        PackageManager pm = requireContext().getPackageManager();
        for (AppConfig config : mConfigs) {
            Preference pref = new Preference(requireContext());
            pref.setKey("app_spoof_entry_" + config.packageName);

            try {
                ApplicationInfo ai = pm.getApplicationInfo(config.packageName, 0);
                pref.setTitle(pm.getApplicationLabel(ai));
                pref.setIcon(ai.loadIcon(pm));
            } catch (PackageManager.NameNotFoundException e) {
                pref.setTitle(config.packageName);
            }

            pref.setSummary(config.profileName);
            pref.setOnPreferenceClickListener(p -> { showEditAppDialog(config); return true; });
            cat.addPreference(pref);
        }

        if (mConfigs.isEmpty()) {
            Preference empty = new Preference(requireContext());
            empty.setTitle(R.string.app_spoof_no_apps);
            empty.setEnabled(false);
            cat.addPreference(empty);
        }
    }

    private void showAddAppDialog() {
        new Thread(() -> {
            PackageManager pm = requireContext().getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

            List<ApplicationInfo> filtered = new ArrayList<>();
            for (ApplicationInfo ai : apps) {
                boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (isSystem) continue;
                boolean alreadyAdded = false;
                for (AppConfig c : mConfigs) {
                    if (c.packageName.equals(ai.packageName)) { alreadyAdded = true; break; }
                }
                if (!alreadyAdded) filtered.add(ai);
            }
            filtered.sort((a, b) -> pm.getApplicationLabel(a).toString()
                    .compareToIgnoreCase(pm.getApplicationLabel(b).toString()));

            String[] labels = new String[filtered.size()];
            for (int i = 0; i < filtered.size(); i++) {
                labels[i] = pm.getApplicationLabel(filtered.get(i)).toString();
            }

            requireActivity().runOnUiThread(() ->
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.app_spoof_select_app)
                        .setItems(labels, (d, which) ->
                                showProfilePickerDialog(filtered.get(which), pm, null))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
            );
        }).start();
    }

    private void showProfilePickerDialog(ApplicationInfo ai, PackageManager pm,
            AppConfig replacing) {
        if (mProfiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.app_spoof_no_profiles,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[mProfiles.size()];
        for (int i = 0; i < mProfiles.size(); i++) names[i] = mProfiles.get(i).name;

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_spoof_select_profile)
                .setItems(names, (d, which) -> {
                    DeviceProfile profile = mProfiles.get(which);
                    String appLabel = pm.getApplicationLabel(ai).toString();

                    if (replacing != null) mConfigs.remove(replacing);

                    mConfigs.add(new AppConfig(
                            ai.packageName, appLabel,
                            profile.name,
                            new LinkedHashMap<>(profile.props)));

                    saveConfig();
                    refreshAppPreferences();
                    Toast.makeText(requireContext(),
                            getString(R.string.app_spoof_app_added, appLabel),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showEditAppDialog(AppConfig config) {
        String[] options = {
                getString(R.string.app_spoof_change_profile),
                getString(R.string.remove)
        };

        new AlertDialog.Builder(requireContext())
                .setTitle(config.appName)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        PackageManager pm = requireContext().getPackageManager();
                        try {
                            ApplicationInfo ai = pm.getApplicationInfo(config.packageName, 0);
                            showProfilePickerDialog(ai, pm, config);
                        } catch (Exception ignored) {}
                    } else {
                        mConfigs.remove(config);
                        saveConfig();
                        refreshAppPreferences();
                        Toast.makeText(requireContext(), R.string.app_spoof_removed,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showManageProfilesDialog() {
        String[] options = {
                getString(R.string.app_spoof_profile_add),
                getString(R.string.app_spoof_profile_edit),
                getString(R.string.app_spoof_profile_delete)
        };

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_spoof_manage_profiles_title)
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: showAddProfileDialog(null); break;
                        case 1: showPickProfileToEdit(); break;
                        case 2: showPickProfileToDelete(); break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPickProfileToEdit() {
        if (mProfiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.app_spoof_no_profiles,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = profileNames();
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_spoof_profile_edit)
                .setItems(names, (d, which) -> showAddProfileDialog(mProfiles.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPickProfileToDelete() {
        if (mProfiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.app_spoof_no_profiles,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = profileNames();
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_spoof_profile_delete)
                .setItems(names, (d, which) -> {
                    DeviceProfile profile = mProfiles.get(which);
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.app_spoof_profile_delete_confirm_title)
                            .setMessage(getString(
                                    R.string.app_spoof_profile_delete_confirm_msg, profile.name))
                            .setPositiveButton(R.string.delete, (d2, w2) -> {
                                mProfiles.remove(profile);
                                saveProfiles();
                                Toast.makeText(requireContext(),
                                        R.string.app_spoof_profile_deleted,
                                        Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAddProfileDialog(DeviceProfile editing) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int dp8 = dp(8);
        root.setPadding(dp(24), dp8, dp(24), dp8);

        EditText etName = new EditText(requireContext());
        etName.setHint(getString(R.string.app_spoof_profile_name_hint));
        etName.setInputType(InputType.TYPE_CLASS_TEXT);
        if (editing != null) etName.setText(editing.name);
        root.addView(etName);

        // Prop key/value rows
        List<EditText[]> propRows = new ArrayList<>();
        LinearLayout propsContainer = new LinearLayout(requireContext());
        propsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(propsContainer);

        Runnable addEmptyRow = () -> addPropRow(propsContainer, propRows, "", "");

        if (editing != null && !editing.props.isEmpty()) {
            for (Map.Entry<String, String> e : editing.props.entrySet()) {
                addPropRow(propsContainer, propRows, e.getKey(), e.getValue());
            }
        } else {
            addPropRow(propsContainer, propRows, "MODEL", "");
            addPropRow(propsContainer, propRows, "MANUFACTURER", "");
        }

        TextView btnAddProp = new TextView(requireContext());
        btnAddProp.setText(getString(R.string.app_spoof_add_prop));
        btnAddProp.setTextColor(requireContext().getColor(android.R.color.holo_blue_light));
        btnAddProp.setPadding(0, dp8, 0, dp8);
        btnAddProp.setOnClickListener(v -> addEmptyRow.run());
        root.addView(btnAddProp);

        String title = editing == null
                ? getString(R.string.app_spoof_profile_add)
                : getString(R.string.app_spoof_profile_edit);

        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(root)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(requireContext(),
                                R.string.app_spoof_profile_name_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Map<String, String> props = new LinkedHashMap<>();
                    for (EditText[] row : propRows) {
                        String k = row[0].getText().toString().trim();
                        String v = row[1].getText().toString().trim();
                        if (!k.isEmpty()) props.put(k, v);
                    }

                    if (editing != null) mProfiles.remove(editing);
                    mProfiles.add(new DeviceProfile(name, props));
                    saveProfiles();
                    Toast.makeText(requireContext(),
                            editing == null
                                ? getString(R.string.app_spoof_profile_saved, name)
                                : getString(R.string.app_spoof_profile_updated, name),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addPropRow(LinearLayout container, List<EditText[]> rows,
            String key, String value) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        int dp4 = dp(4);
        row.setPadding(0, dp4, 0, dp4);

        EditText etKey = new EditText(requireContext());
        etKey.setHint("KEY");
        etKey.setText(key);
        etKey.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams lpKey = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpKey.setMarginEnd(dp(8));
        etKey.setLayoutParams(lpKey);

        EditText etVal = new EditText(requireContext());
        etVal.setHint("value");
        etVal.setText(value);
        etVal.setInputType(InputType.TYPE_CLASS_TEXT);
        etVal.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(etKey);
        row.addView(etVal);
        container.addView(row);
        rows.add(new EditText[]{etKey, etVal});
    }

    private int dp(int dp) {
        return Math.round(dp * requireContext().getResources().getDisplayMetrics().density);
    }

    private String[] profileNames() {
        String[] names = new String[mProfiles.size()];
        for (int i = 0; i < mProfiles.size(); i++) names[i] = mProfiles.get(i).name;
        return names;
    }

    private void loadConfig() {
        mConfigs.clear();
        mEnabled = false;
        File f = new File(CONFIG_DIR, CONFIG_FILE);
        if (!f.exists()) return;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(f.toPath()));
            JSONObject json = new JSONObject(content);
            mEnabled = json.optBoolean("enabled", false);
            if (json.has("apps")) {
                JSONObject apps = json.getJSONObject("apps");
                Iterator<String> keys = apps.keys();
                PackageManager pm = requireContext().getPackageManager();
                while (keys.hasNext()) {
                    String pkg = keys.next();
                    JSONObject propsJson = apps.getJSONObject(pkg);
                    Map<String, String> props = new LinkedHashMap<>();
                    Iterator<String> pks = propsJson.keys();
                    while (pks.hasNext()) {
                        String k = pks.next();
                        props.put(k, propsJson.getString(k));
                    }
                    String appName;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        appName = pm.getApplicationLabel(ai).toString();
                    } catch (Exception e) {
                        appName = pkg;
                    }
                    String profileName = matchProfileName(props);
                    mConfigs.add(new AppConfig(pkg, appName, profileName, props));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "loadConfig error", e);
        }
    }

    private void saveConfig() {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        try {
            JSONObject json = new JSONObject();
            json.put("enabled", mEnabled);
            JSONObject apps = new JSONObject();
            for (AppConfig c : mConfigs) {
                JSONObject props = new JSONObject();
                for (Map.Entry<String, String> e : c.props.entrySet()) {
                    props.put(e.getKey(), e.getValue());
                }
                apps.put(c.packageName, props);
            }
            json.put("apps", apps);
            File f = new File(dir, CONFIG_FILE);
            try (FileWriter fw = new FileWriter(f)) { fw.write(json.toString(2)); }
            f.setReadable(true, false);
        } catch (Exception e) {
            Log.e(TAG, "saveConfig error", e);
        }
    }

    private void loadProfiles() {
        mProfiles.clear();
        File f = new File(CONFIG_DIR, PROFILES_FILE);
        if (f.exists()) {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(f.toPath()));
                JSONObject json = new JSONObject(content);
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String name = keys.next();
                    JSONObject propsJson = json.getJSONObject(name);
                    Map<String, String> props = new LinkedHashMap<>();
                    Iterator<String> pk = propsJson.keys();
                    while (pk.hasNext()) {
                        String k = pk.next();
                        props.put(k, propsJson.getString(k));
                    }
                    mProfiles.add(new DeviceProfile(name, props));
                }
            } catch (Exception e) {
                Log.e(TAG, "loadProfiles error", e);
            }
        }

        if (mProfiles.isEmpty()) {
            mProfiles.addAll(defaultProfiles());
        }
    }

    private void saveProfiles() {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        try {
            JSONObject json = new JSONObject();
            for (DeviceProfile p : mProfiles) {
                JSONObject props = new JSONObject();
                for (Map.Entry<String, String> e : p.props.entrySet()) {
                    props.put(e.getKey(), e.getValue());
                }
                json.put(p.name, props);
            }
            File f = new File(dir, PROFILES_FILE);
            try (FileWriter fw = new FileWriter(f)) { fw.write(json.toString(2)); }
            f.setReadable(true, false);
        } catch (Exception e) {
            Log.e(TAG, "saveProfiles error", e);
        }
    }

    private String matchProfileName(Map<String, String> props) {
        for (DeviceProfile p : mProfiles) {
            if (p.props.equals(props)) return p.name;
        }
        String model = props.get("MODEL");
        String mfr   = props.get("MANUFACTURER");
        if (model != null && mfr != null) return mfr + " " + model;
        if (model != null) return model;
        return getString(R.string.app_spoof_custom_profile);
    }

    public static class AppConfig {
        public final String packageName;
        public final String appName;
        public final String profileName;
        public final Map<String, String> props;
        public AppConfig(String pkg, String name, String profile, Map<String, String> p) {
            packageName = pkg; appName = name; profileName = profile; props = p;
        }
    }

    public static class DeviceProfile {
        public final String name;
        public final Map<String, String> props;
        public DeviceProfile(String n, Map<String, String> p) { name = n; props = p; }
    }
}