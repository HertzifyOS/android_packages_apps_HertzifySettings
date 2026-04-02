
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
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settings.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TargetAppsFragment extends Fragment {

    private static final String TAG = "TargetAppsFragment";

    public enum TargetMode {
        AUTO(""),
        LEAF_HACK("?"),
        CERT_GEN("!");

        public final String symbol;
        TargetMode(String s) { this.symbol = s; }
    }

    private KeyboxManager   mKeyboxManager;
    private AppAdapter      mAdapter;
    private List<AppEntry>  mAllApps    = new ArrayList<>();
    private List<AppEntry>  mFiltered   = new ArrayList<>();
    private boolean         mShowSystem = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        mKeyboxManager = new KeyboxManager(requireContext());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_target_apps, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView rv = view.findViewById(R.id.rv_apps);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        mAdapter = new AppAdapter();
        rv.setAdapter(mAdapter);

        loadApps();
    }

    @Override
    public void onPause() {
        super.onPause();
        saveTargetFile();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.menu_target_apps, menu);
        MenuItem item = menu.findItem(R.id.action_show_system);
        if (item != null) item.setChecked(mShowSystem);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_show_system) {
            mShowSystem = !mShowSystem;
            item.setChecked(mShowSystem);
            filterApps(null);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = requireContext().getPackageManager();
            Map<String, TargetMode> currentTargets = loadCurrentTargets();

            List<AppEntry> apps = new ArrayList<>();
            List<ApplicationInfo> installed =
                    pm.getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo ai : installed) {
                boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                TargetMode mode = currentTargets.getOrDefault(ai.packageName, TargetMode.AUTO);
                boolean inTarget = currentTargets.containsKey(ai.packageName);

                AppEntry entry = new AppEntry();
                entry.packageName = ai.packageName;
                entry.label       = pm.getApplicationLabel(ai).toString();
                entry.icon        = ai.loadIcon(pm);
                entry.isSystem    = isSystem;
                entry.inTarget    = inTarget;
                entry.mode        = mode;
                apps.add(entry);
            }

            apps.sort((a, b) -> {
                if (a.inTarget != b.inTarget) return a.inTarget ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            });

            mAllApps = apps;

            requireActivity().runOnUiThread(() -> filterApps(null));
        }).start();
    }

    private void filterApps(@Nullable String query) {
        List<AppEntry> result = new ArrayList<>();
        String q = (query == null) ? "" : query.trim().toLowerCase();
        for (AppEntry e : mAllApps) {
            if (!mShowSystem && e.isSystem && !e.inTarget) continue;
            if (!q.isEmpty()
                    && !e.label.toLowerCase().contains(q)
                    && !e.packageName.toLowerCase().contains(q)) continue;
            result.add(e);
        }
        mFiltered = result;
        mAdapter.notifyDataSetChanged();
    }

    private Map<String, TargetMode> loadCurrentTargets() {
        Map<String, TargetMode> map = new HashMap<>();
        for (String line : mKeyboxManager.readTargetLines()) {
            if (line.endsWith("?")) {
                map.put(line.substring(0, line.length() - 1), TargetMode.LEAF_HACK);
            } else if (line.endsWith("!")) {
                map.put(line.substring(0, line.length() - 1), TargetMode.CERT_GEN);
            } else {
                map.put(line, TargetMode.AUTO);
            }
        }
        return map;
    }

    private void saveTargetFile() {
        List<String> lines = new ArrayList<>();
        for (AppEntry e : mAllApps) {
            if (e.inTarget) lines.add(e.packageName + e.mode.symbol);
        }
        mKeyboxManager.saveTargetLines(lines);
    }

    private static class AppEntry {
        String   packageName;
        String   label;
        Drawable icon;
        boolean  isSystem;
        boolean  inTarget;
        TargetMode mode;
    }

    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_target_app, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            AppEntry e = mFiltered.get(pos);

            h.ivIcon.setImageDrawable(e.icon);
            h.tvLabel.setText(e.label);
            h.tvPackage.setText(e.packageName);

            h.cbEnabled.setOnCheckedChangeListener(null);
            h.rgMode.setOnCheckedChangeListener(null);

            h.cbEnabled.setChecked(e.inTarget);
            h.rgMode.setVisibility(e.inTarget ? View.VISIBLE : View.GONE);
            if (e.inTarget) {
                switch (e.mode) {
                    case LEAF_HACK: h.rgMode.check(R.id.rb_leaf_hack); break;
                    case CERT_GEN:  h.rgMode.check(R.id.rb_cert_gen);  break;
                    default:        h.rgMode.check(R.id.rb_auto);       break;
                }
            }

            h.cbEnabled.setOnCheckedChangeListener((btn, checked) -> {
                e.inTarget = checked;
                h.rgMode.setVisibility(checked ? View.VISIBLE : View.GONE);
                saveTargetFile();
                h.itemView.post(() -> {
                    int p = h.getAdapterPosition();
                    if (p != RecyclerView.NO_POSITION) notifyItemChanged(p);
                });
            });

            h.itemView.setOnClickListener(v -> h.cbEnabled.toggle());

            h.rgMode.setOnCheckedChangeListener((group, checkedId) -> {
                if      (checkedId == R.id.rb_leaf_hack) e.mode = TargetMode.LEAF_HACK;
                else if (checkedId == R.id.rb_cert_gen)  e.mode = TargetMode.CERT_GEN;
                else                                      e.mode = TargetMode.AUTO;
                saveTargetFile();
            });
        }

        @Override
        public int getItemCount() { return mFiltered.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView  tvLabel, tvPackage;
            CheckBox  cbEnabled;
            RadioGroup rgMode;

            VH(View v) {
                super(v);
                ivIcon   = v.findViewById(R.id.iv_app_icon);
                tvLabel  = v.findViewById(R.id.tv_app_label);
                tvPackage = v.findViewById(R.id.tv_app_package);
                cbEnabled = v.findViewById(R.id.cb_app_enabled);
                rgMode    = v.findViewById(R.id.rg_mode);
            }
        }
    }
}