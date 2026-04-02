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
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

    private static final String CONFIG_DIR    = "/data/adb/appprops";
    private static final String CONFIG_FILE   = "appprops.json";
    private static final String PROFILES_FILE = "profiles.json";

    private static final String KEY_ENABLED         = "app_spoof_enabled";
    private static final String KEY_ADD_APP         = "app_spoof_add_app";
    private static final String KEY_MANAGE_PROFILES = "app_spoof_manage_profiles";
    private static final String KEY_APP_LIST_CAT    = "app_spoof_app_list_category";

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
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);

        EditText etSearch = new EditText(requireContext());
        etSearch.setHint(getString(R.string.search));
        etSearch.setSingleLine(true);
        etSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams lpSearch = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        int dp12 = dp(12);
        lpSearch.setMargins(dp12, dp12, dp12, dp(4));
        etSearch.setLayoutParams(lpSearch);
        root.addView(etSearch);

        RecyclerView rv = new RecyclerView(requireContext());
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(400)));
        root.addView(rv);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_spoof_select_app)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        new Thread(() -> {
            PackageManager pm = requireContext().getPackageManager();
            List<ApplicationInfo> installed =
                    pm.getInstalledApplications(PackageManager.GET_META_DATA);

            List<AppPickerEntry> entries = new ArrayList<>();
            for (ApplicationInfo ai : installed) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                boolean alreadyAdded = false;
                for (AppConfig c : mConfigs) {
                    if (c.packageName.equals(ai.packageName)) { alreadyAdded = true; break; }
                }
                if (alreadyAdded) continue;
                AppPickerEntry e = new AppPickerEntry();
                e.packageName = ai.packageName;
                e.label       = pm.getApplicationLabel(ai).toString();
                e.ai          = ai;
                e.pm          = pm;
                entries.add(e);
            }
            entries.sort((a, b) -> a.label.compareToIgnoreCase(b.label));

            requireActivity().runOnUiThread(() -> {
                AppPickerAdapter adapter = new AppPickerAdapter(entries, entry -> {
                    dialog.dismiss();
                    showProfilePickerDialog(entry.packageName, entry.label, null);
                });
                rv.setAdapter(adapter);

                etSearch.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                    @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                    @Override public void afterTextChanged(Editable s) {
                        adapter.filter(s.toString());
                    }
                });
            });
        }).start();

        dialog.show();
    }

    private void showProfilePickerDialog(String pkg, String label, AppConfig replacing) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);

        RecyclerView rv = new RecyclerView(requireContext());
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        root.addView(rv);

        TextView btnAdd = new TextView(requireContext());
        btnAdd.setText(getString(R.string.app_spoof_profile_add));
        btnAdd.setTextColor(requireContext().getColor(android.R.color.holo_blue_light));
        int dp12 = dp(12); int dp10 = dp(10);
        btnAdd.setPadding(dp12, dp10, dp12, dp10);
        root.addView(btnAdd);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(label)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        Runnable refreshList = () -> {
            ProfilePickerAdapter adapter = new ProfilePickerAdapter(
                    new ArrayList<>(mProfiles), profile -> {
                        PackageManager pm = requireContext().getPackageManager();
                        String appLabel = label;
                        try {
                            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                            appLabel = pm.getApplicationLabel(ai).toString();
                        } catch (Exception ignored) {}

                        if (replacing != null) mConfigs.remove(replacing);
                        mConfigs.add(new AppConfig(pkg, appLabel, profile.name,
                                new LinkedHashMap<>(profile.props)));
                        saveConfig();
                        refreshAppPreferences();
                        Toast.makeText(requireContext(),
                                getString(R.string.app_spoof_app_added, appLabel),
                                Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    });
            rv.setAdapter(adapter);
        };

        refreshList.run();

        btnAdd.setOnClickListener(v -> {
            showAddProfileDialog(null, () -> {
                refreshList.run();
            });
        });

        dialog.show();
    }

    private void showEditAppDialog(AppConfig config) {
        new AlertDialog.Builder(requireContext())
                .setTitle(config.appName)
                .setItems(new String[]{
                        getString(R.string.app_spoof_change_profile),
                        getString(R.string.remove)
                }, (d, which) -> {
                    if (which == 0) {
                        showProfilePickerDialog(config.packageName, config.appName, config);
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
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);

        RecyclerView rv = new RecyclerView(requireContext());
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        root.addView(rv);

        TextView btnAdd = new TextView(requireContext());
        btnAdd.setText(getString(R.string.app_spoof_profile_add));
        btnAdd.setTextColor(requireContext().getColor(android.R.color.holo_blue_light));
        int dp12 = dp(12); int dp10 = dp(10);
        btnAdd.setPadding(dp12, dp10, dp12, dp10);
        root.addView(btnAdd);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_spoof_manage_profiles_title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        Runnable refreshList = () -> {
            ManageProfilesAdapter adapter = new ManageProfilesAdapter(
                    new ArrayList<>(mProfiles),
                    profile -> new AlertDialog.Builder(requireContext())
                            .setTitle(profile.name)
                            .setItems(new String[]{
                                    getString(R.string.app_spoof_profile_edit),
                                    getString(R.string.app_spoof_profile_delete)
                            }, (d2, which) -> {
                                if (which == 0) {
                                    showAddProfileDialog(profile, () -> {
                                        dialog.dismiss();
                                        showManageProfilesDialog();
                                    });
                                } else {
                                    new AlertDialog.Builder(requireContext())
                                            .setTitle(R.string.app_spoof_profile_delete_confirm_title)
                                            .setMessage(getString(
                                                    R.string.app_spoof_profile_delete_confirm_msg,
                                                    profile.name))
                                            .setPositiveButton(R.string.delete, (d3, w3) -> {
                                                mProfiles.remove(profile);
                                                saveProfiles();
                                                dialog.dismiss();
                                                showManageProfilesDialog();
                                            })
                                            .setNegativeButton(android.R.string.cancel, null)
                                            .show();
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
            );
            rv.setAdapter(adapter);
        };

        refreshList.run();
        btnAdd.setOnClickListener(v -> showAddProfileDialog(null, () -> {
            dialog.dismiss();
            showManageProfilesDialog();
        }));

        dialog.show();
    }

    private void showAddProfileDialog(DeviceProfile editing, Runnable onSaved) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int dp8 = dp(8); int dp24 = dp(24);
        root.setPadding(dp24, dp8, dp24, dp8);
        scroll.addView(root);

        TextView tvNameLabel = new TextView(requireContext());
        tvNameLabel.setText(getString(R.string.app_spoof_profile_name_hint));
        tvNameLabel.setTextSize(12);
        tvNameLabel.setAlpha(0.6f);
        tvNameLabel.setPadding(0, dp(12), 0, 0);
        root.addView(tvNameLabel);

        EditText etName = new EditText(requireContext());
        etName.setHint(getString(R.string.app_spoof_profile_name_hint));
        etName.setInputType(InputType.TYPE_CLASS_TEXT);
        if (editing != null) etName.setText(editing.name);
        root.addView(etName);

        TextView tvPropsLabel = new TextView(requireContext());
        tvPropsLabel.setText("Properties");
        tvPropsLabel.setTextSize(12);
        tvPropsLabel.setAlpha(0.6f);
        tvPropsLabel.setPadding(0, dp(16), 0, 0);
        root.addView(tvPropsLabel);

        List<EditText[]> propRows = new ArrayList<>();
        LinearLayout propsContainer = new LinearLayout(requireContext());
        propsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(propsContainer);

        if (editing != null && !editing.props.isEmpty()) {
            for (Map.Entry<String, String> e : editing.props.entrySet())
                addPropRow(propsContainer, propRows, e.getKey(), e.getValue());
        } else {
            addPropRow(propsContainer, propRows, "MODEL", "");
            addPropRow(propsContainer, propRows, "MANUFACTURER", "");
        }

        TextView btnAddProp = new TextView(requireContext());
        btnAddProp.setText(getString(R.string.app_spoof_add_prop));
        btnAddProp.setTextColor(requireContext().getColor(android.R.color.holo_blue_light));
        btnAddProp.setPadding(0, dp8, 0, dp8);
        btnAddProp.setOnClickListener(v -> addPropRow(propsContainer, propRows, "", ""));
        root.addView(btnAddProp);

        new AlertDialog.Builder(requireContext())
                .setTitle(editing == null
                        ? getString(R.string.app_spoof_profile_add)
                        : getString(R.string.app_spoof_profile_edit))
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(requireContext(),
                                R.string.app_spoof_profile_name_empty,
                                Toast.LENGTH_SHORT).show();
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
                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static class AppPickerAdapter
            extends RecyclerView.Adapter<AppPickerAdapter.VH> {

        interface OnPick { void onPick(AppPickerEntry entry); }

        private final List<AppPickerEntry> mAll;
        private List<AppPickerEntry>       mShown;
        private final OnPick               mListener;

        AppPickerAdapter(List<AppPickerEntry> all, OnPick listener) {
            mAll    = all;
            mShown  = new ArrayList<>(all);
            mListener = listener;
        }

        void filter(String q) {
            String lower = q.trim().toLowerCase();
            mShown = new ArrayList<>();
            for (AppPickerEntry e : mAll) {
                if (lower.isEmpty()
                        || e.label.toLowerCase().contains(lower)
                        || e.packageName.toLowerCase().contains(lower)) {
                    mShown.add(e);
                }
            }
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            int dp12 = Math.round(12 * parent.getContext()
                    .getResources().getDisplayMetrics().density);
            int dp8  = Math.round(8  * parent.getContext()
                    .getResources().getDisplayMetrics().density);
            row.setPadding(dp12, dp8, dp12, dp8);

            ImageView icon = new ImageView(parent.getContext());
            int dp36 = Math.round(36 * parent.getContext()
                    .getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams lpIcon =
                    new LinearLayout.LayoutParams(dp36, dp36);
            lpIcon.setMarginEnd(dp12);
            icon.setLayoutParams(lpIcon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(icon);

            LinearLayout text = new LinearLayout(parent.getContext());
            text.setOrientation(LinearLayout.VERTICAL);
            text.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvLabel = new TextView(parent.getContext());
            tvLabel.setTextSize(15);
            text.addView(tvLabel);

            TextView tvPkg = new TextView(parent.getContext());
            tvPkg.setTextSize(11);
            tvPkg.setAlpha(0.6f);
            text.addView(tvPkg);

            row.addView(text);
            return new VH(row, icon, tvLabel, tvPkg);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            AppPickerEntry e = mShown.get(pos);
            h.icon.setImageDrawable(e.getIcon());
            h.tvLabel.setText(e.label);
            h.tvPkg.setText(e.packageName);
            h.itemView.setOnClickListener(v -> mListener.onPick(e));
        }

        @Override public int getItemCount() { return mShown.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView icon; TextView tvLabel, tvPkg;
            VH(View v, ImageView i, TextView l, TextView p) {
                super(v); icon = i; tvLabel = l; tvPkg = p;
            }
        }
    }

    private static class ProfilePickerAdapter
            extends RecyclerView.Adapter<ProfilePickerAdapter.VH> {

        interface OnPick { void onPick(DeviceProfile p); }

        private final List<DeviceProfile> mProfiles;
        private final OnPick              mListener;

        ProfilePickerAdapter(List<DeviceProfile> profiles, OnPick listener) {
            mProfiles = profiles; mListener = listener;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            int dp12 = Math.round(12 * parent.getContext()
                    .getResources().getDisplayMetrics().density);
            int dp14 = Math.round(14 * parent.getContext()
                    .getResources().getDisplayMetrics().density);
            tv.setPadding(dp12, dp14, dp12, dp14);
            tv.setTextSize(15);
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            DeviceProfile p = mProfiles.get(pos);
            h.tv.setText(p.name);
            h.tv.setOnClickListener(v -> mListener.onPick(p));
        }

        @Override public int getItemCount() { return mProfiles.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tv;
            VH(TextView v) { super(v); tv = v; }
        }
    }

    private static class ManageProfilesAdapter
            extends RecyclerView.Adapter<ManageProfilesAdapter.VH> {

        interface OnProfile { void on(DeviceProfile p); }

        private final List<DeviceProfile> mProfiles;
        private final OnProfile           mOnTap;

        ManageProfilesAdapter(List<DeviceProfile> profiles, OnProfile onTap) {
            mProfiles = profiles;
            mOnTap    = onTap;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            int dp12 = Math.round(12 * parent.getContext()
                    .getResources().getDisplayMetrics().density);
            int dp14 = Math.round(14 * parent.getContext()
                    .getResources().getDisplayMetrics().density);
            tv.setPadding(dp12, dp14, dp12, dp14);
            tv.setTextSize(15);
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            DeviceProfile p = mProfiles.get(pos);
            h.tv.setText(p.name);
            h.tv.setOnClickListener(v -> mOnTap.on(p));
        }

        @Override public int getItemCount() { return mProfiles.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tv;
            VH(TextView v) { super(v); tv = v; }
        }
    }

    private void addPropRow(LinearLayout container, List<EditText[]> rows,
            String key, String value) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));

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

    private static class AppPickerEntry {
        String          packageName;
        String          label;
        ApplicationInfo ai;
        PackageManager  pm;
        Drawable        iconCache;
        Drawable getIcon() {
            if (iconCache == null) iconCache = ai.loadIcon(pm);
            return iconCache;
        }
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
                    } catch (Exception e) { appName = pkg; }
                    mConfigs.add(new AppConfig(pkg, appName, matchProfileName(props), props));
                }
            }
        } catch (Exception e) { Log.e(TAG, "loadConfig error", e); }
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
                for (Map.Entry<String, String> e : c.props.entrySet())
                    props.put(e.getKey(), e.getValue());
                apps.put(c.packageName, props);
            }
            json.put("apps", apps);
            File f = new File(dir, CONFIG_FILE);
            try (FileWriter fw = new FileWriter(f)) { fw.write(json.toString(2)); }
            f.setReadable(true, false);
        } catch (Exception e) { Log.e(TAG, "saveConfig error", e); }
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
                    while (pk.hasNext()) { String k = pk.next(); props.put(k, propsJson.getString(k)); }
                    mProfiles.add(new DeviceProfile(name, props));
                }
            } catch (Exception e) { Log.e(TAG, "loadProfiles error", e); }
        }
        if (mProfiles.isEmpty()) mProfiles.addAll(defaultProfiles());
    }

    private void saveProfiles() {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        try {
            JSONObject json = new JSONObject();
            for (DeviceProfile p : mProfiles) {
                JSONObject props = new JSONObject();
                for (Map.Entry<String, String> e : p.props.entrySet())
                    props.put(e.getKey(), e.getValue());
                json.put(p.name, props);
            }
            File f = new File(dir, PROFILES_FILE);
            try (FileWriter fw = new FileWriter(f)) { fw.write(json.toString(2)); }
            f.setReadable(true, false);
        } catch (Exception e) { Log.e(TAG, "saveProfiles error", e); }
    }

    private String matchProfileName(Map<String, String> props) {
        for (DeviceProfile p : mProfiles) { if (p.props.equals(props)) return p.name; }
        String model = props.get("MODEL");
        String mfr   = props.get("MANUFACTURER");
        if (model != null && mfr != null) return mfr + " " + model;
        if (model != null) return model;
        return getString(R.string.app_spoof_custom_profile);
    }

    public static class AppConfig {
        public final String packageName, appName, profileName;
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