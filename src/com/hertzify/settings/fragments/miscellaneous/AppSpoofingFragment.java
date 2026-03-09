/*
 * Copyright (C) 2026 HertzifyOS
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hertzify.settings.fragments.miscellaneous;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class AppSpoofingFragment extends SettingsPreferenceFragment {

    private static final String TAG = "AppSpoofingFragment";

    private static final String PREF_APP_SPOOF_ENABLED = "pref_app_spoof_enabled";
    private static final String PREF_SHOW_UNINSTALLED  = "pref_show_uninstalled";
    private static final String PREF_CATEGORY_APPS     = "category_apps";

    private static final Set<String> ALLOWED_KEYS = new HashSet<>(Arrays.asList(
            "BRAND", "DEVICE", "MANUFACTURER", "MODEL", "FINGERPRINT", "PRODUCT"
    ));

    private ActivityResultLauncher<Intent> mImportLauncher;
    private ActivityResultLauncher<Intent> mExportLauncher;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.app_spoofing, rootKey);
        setHasOptionsMenu(true);
        wireToggles();
        buildList();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mImportLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null)
                        importJson(result.getData().getData());
                });
        mExportLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null)
                        exportJson(result.getData().getData());
                });
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.add(0, 1, 0, R.string.app_spoof_add_app)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, 2, 0, R.string.app_spoof_manage_presets);
        menu.add(0, 3, 0, R.string.app_spoof_import_json);
        menu.add(0, 4, 0, R.string.app_spoof_export_json);
        menu.add(0, 5, 0, R.string.app_spoof_clear_all);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1: showAddEditDialog(null);   return true;
            case 2: showManagePresetsDialog(); return true;
            case 3: openFilePicker();          return true;
            case 4: openFileSaver();           return true;
            case 5: confirmClearAll();         return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.HERTZIFY;
    }


    private void wireToggles() {
        SwitchPreferenceCompat enabledToggle = findPreference(PREF_APP_SPOOF_ENABLED);
        if (enabledToggle != null) {
            enabledToggle.setChecked(Settings.Secure.getInt(
                    requireContext().getContentResolver(),
                    Settings.Secure.APP_SPOOF_ENABLED, 1) == 1);
            enabledToggle.setOnPreferenceChangeListener((pref, newVal) -> {
                Settings.Secure.putInt(requireContext().getContentResolver(),
                        Settings.Secure.APP_SPOOF_ENABLED, (Boolean) newVal ? 1 : 0);
                return true;
            });
        }

        SwitchPreferenceCompat showToggle = findPreference(PREF_SHOW_UNINSTALLED);
        if (showToggle != null) {
            showToggle.setChecked(Settings.Secure.getInt(
                    requireContext().getContentResolver(),
                    Settings.Secure.APP_SPOOF_SHOW_UNINSTALLED, 0) == 1);
            showToggle.setOnPreferenceChangeListener((pref, newVal) -> {
                Settings.Secure.putInt(requireContext().getContentResolver(),
                        Settings.Secure.APP_SPOOF_SHOW_UNINSTALLED, (Boolean) newVal ? 1 : 0);
                buildList();
                return true;
            });
        }
    }

    private void buildList() {
        PreferenceCategory category = findPreference(PREF_CATEGORY_APPS);
        if (category == null) return;
        category.removeAll();

        boolean showUninstalled = Settings.Secure.getInt(
                requireContext().getContentResolver(),
                Settings.Secure.APP_SPOOF_SHOW_UNINSTALLED, 0) == 1;

        HashMap<String, HashMap<String, String>> entries = readEntries();
        PackageManager pm = requireContext().getPackageManager();
        List<String> pkgs = new ArrayList<>(entries.keySet());
        pkgs.sort((a, b) -> {
            boolean aInstalled = isInstalled(pm, a);
            boolean bInstalled = isInstalled(pm, b);
            if (aInstalled != bInstalled) return aInstalled ? -1 : 1;
            return a.compareToIgnoreCase(b);
        });

        boolean anyAdded = false;
        for (String pkg : pkgs) {
            boolean installed = isInstalled(pm, pkg);
            if (!installed && !showUninstalled) continue;

            HashMap<String, String> props = entries.get(pkg);
            Preference pref = new Preference(requireContext());
            pref.setKey("entry_" + pkg);

            if (installed) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    pref.setTitle(pm.getApplicationLabel(ai));
                    pref.setIcon(pm.getApplicationIcon(ai));
                } catch (PackageManager.NameNotFoundException e) {
                    pref.setTitle(pkg);
                }
            } else {
                pref.setTitle(pkg);
                pref.setIcon(android.R.drawable.sym_def_app_icon);
            }

            pref.setSummary(buildSummary(props, !installed));
            pref.setOnPreferenceClickListener(p -> {
                showEntryOptionsDialog(pkg);
                return true;
            });
            category.addPreference(pref);
            anyAdded = true;
        }

        if (!anyAdded) {
            Preference empty = new Preference(requireContext());
            empty.setKey("entry_empty");
            empty.setTitle(entries.isEmpty()
                    ? R.string.app_spoof_empty
                    : R.string.app_spoof_all_hidden);
            empty.setSelectable(false);
            category.addPreference(empty);
        }
    }

    private JSONObject readRawJson() {
        try {
            String json = Settings.Secure.getString(
                    requireContext().getContentResolver(), Settings.Secure.APP_SPOOF_DATA);
            if (!TextUtils.isEmpty(json)) return new JSONObject(json);
        } catch (JSONException e) {
            Log.e(TAG, "readRawJson failed", e);
        }
        return new JSONObject();
    }

    private HashMap<String, HashMap<String, String>> readEntries() {
        HashMap<String, HashMap<String, String>> map = new HashMap<>();
        try {
            String json = Settings.Secure.getString(
                    requireContext().getContentResolver(), Settings.Secure.APP_SPOOF_DATA);
            if (!TextUtils.isEmpty(json)) parseJson(json, map);
        } catch (Exception e) {
            Log.e(TAG, "readEntries failed", e);
        }
        return map;
    }

    private void parseJson(String json,
            HashMap<String, HashMap<String, String>> out) throws JSONException {
        JSONObject root = new JSONObject(json);
        for (Iterator<String> pkgIt = root.keys(); pkgIt.hasNext(); ) {
            String pkg = pkgIt.next();
            JSONObject propsObj = root.getJSONObject(pkg);
            HashMap<String, String> props = new HashMap<>();
            for (Iterator<String> keyIt = propsObj.keys(); keyIt.hasNext(); ) {
                String key = keyIt.next();
                if (!ALLOWED_KEYS.contains(key)) continue;
                String value = propsObj.optString(key, "").trim();
                if (!value.isEmpty()) props.put(key, value);
            }
            if (props.containsKey("MODEL") && props.containsKey("MANUFACTURER")) {
                out.put(pkg, props);
            } else {
                Log.w(TAG, "Skipping " + pkg + ": missing MODEL or MANUFACTURER");
            }
        }
    }

    private void saveEntry(String pkg, Map<String, String> props) {
        try {
            JSONObject root = readRawJson();
            JSONObject propsObj = new JSONObject();
            for (Map.Entry<String, String> p : props.entrySet())
                propsObj.put(p.getKey(), p.getValue());
            root.put(pkg, propsObj);
            Settings.Secure.putString(requireContext().getContentResolver(),
                    Settings.Secure.APP_SPOOF_DATA, root.toString());
        } catch (Exception e) {
            Log.e(TAG, "saveEntry failed", e);
        }
    }

    private void deleteEntry(String pkg) {
        try {
            JSONObject root = readRawJson();
            root.remove(pkg);
            Settings.Secure.putString(requireContext().getContentResolver(),
                    Settings.Secure.APP_SPOOF_DATA, root.toString());
        } catch (Exception e) {
            Log.e(TAG, "deleteEntry failed", e);
        }
    }

    private void showEntryOptionsDialog(String pkg) {
        new AlertDialog.Builder(requireContext())
                .setTitle(pkg)
                .setItems(new CharSequence[]{
                        getString(R.string.app_spoof_edit_app),
                        getString(R.string.app_spoof_save_as_preset),
                        getString(R.string.app_spoof_delete_app)
                }, (d, which) -> {
                    switch (which) {
                        case 0: showAddEditDialog(pkg);      break;
                        case 1: showSaveAsPresetDialog(pkg); break;
                        case 2: confirmDeleteEntry(pkg);     break;
                    }
                })
                .show();
    }

    private void confirmDeleteEntry(String pkg) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_spoof_confirm_delete_title)
                .setMessage(getString(R.string.app_spoof_confirm_delete_msg, pkg))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    deleteEntry(pkg);
                    buildList();
                    toast(R.string.app_spoof_deleted);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAddEditDialog(String editPkg) {
        Context ctx = requireContext();
        View view = LayoutInflater.from(ctx)
                .inflate(R.layout.dialog_app_spoof_edit, null);

        AutoCompleteTextView etPkg = view.findViewById(R.id.et_package_name);
        EditText etManufacturer    = view.findViewById(R.id.et_manufacturer);
        EditText etModel           = view.findViewById(R.id.et_model);
        EditText etBrand           = view.findViewById(R.id.et_brand);
        EditText etDevice          = view.findViewById(R.id.et_device);
        EditText etFingerprint     = view.findViewById(R.id.et_fingerprint);
        EditText etProduct         = view.findViewById(R.id.et_product);

        etPkg.setAdapter(new ArrayAdapter<>(ctx,
                android.R.layout.simple_dropdown_item_1line, getInstalledPackageNames()));

        if (editPkg != null) {
            etPkg.setText(editPkg);
            etPkg.setEnabled(false);
            HashMap<String, String> existing = readEntries().get(editPkg);
            if (existing != null) {
                setText(etManufacturer, existing.get("MANUFACTURER"));
                setText(etModel,        existing.get("MODEL"));
                setText(etBrand,        existing.get("BRAND"));
                setText(etDevice,       existing.get("DEVICE"));
                setText(etFingerprint,  existing.get("FINGERPRINT"));
                setText(etProduct,      existing.get("PRODUCT"));
            }
        }

        view.findViewById(R.id.btn_preset).setOnClickListener(v ->
                showPickPresetDialog(etManufacturer, etModel, etBrand,
                        etDevice, etFingerprint, etProduct));

        new AlertDialog.Builder(ctx)
                .setTitle(editPkg == null
                        ? R.string.app_spoof_add_app
                        : R.string.app_spoof_edit_app)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String pkg = etPkg.getText().toString().trim();
                    if (TextUtils.isEmpty(pkg)) {
                        toast(R.string.app_spoof_err_empty_pkg); return;
                    }
                    String mfr   = etManufacturer.getText().toString().trim();
                    String model = etModel.getText().toString().trim();
                    if (TextUtils.isEmpty(mfr)) {
                        toast(R.string.app_spoof_err_required_manufacturer); return;
                    }
                    if (TextUtils.isEmpty(model)) {
                        toast(R.string.app_spoof_err_required_model); return;
                    }
                    HashMap<String, String> props = collectProps(
                            mfr, model, etBrand, etDevice, etFingerprint, etProduct);
                    saveEntry(pkg, props);
                    buildList();
                    toast(R.string.app_spoof_saved);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPickPresetDialog(EditText etMfr, EditText etModel,
            EditText etBrand, EditText etDevice,
            EditText etFp, EditText etProduct) {
        List<AppSpoofPresetManager.Preset> presets =
                AppSpoofPresetManager.loadAll(requireContext().getContentResolver());
        if (presets.isEmpty()) {
            toast(R.string.app_spoof_no_presets); return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_spoof_preset_pick)
                .setItems(buildPresetItems(presets), (d, which) -> {
                    Map<String, String> props = presets.get(which).props;
                    setText(etMfr,     props.get("MANUFACTURER"));
                    setText(etModel,   props.get("MODEL"));
                    setText(etBrand,   props.get("BRAND"));
                    setText(etDevice,  props.get("DEVICE"));
                    setText(etFp,      props.get("FINGERPRINT"));
                    setText(etProduct, props.get("PRODUCT"));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSaveAsPresetDialog(String pkg) {
        HashMap<String, String> props = readEntries().get(pkg);
        if (props == null) return;

        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_app_spoof_preset_name, null);
        EditText etName = view.findViewById(R.id.et_preset_name);
        String defaultName = props.get("MODEL");
        if (!TextUtils.isEmpty(defaultName)) etName.setText(defaultName);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_spoof_save_as_preset)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) {
                        toast(R.string.app_spoof_err_preset_name_empty); return;
                    }
                    AppSpoofPresetManager.save(
                            requireContext().getContentResolver(),
                            new AppSpoofPresetManager.Preset(name, props));
                    toast(R.string.app_spoof_preset_saved);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showManagePresetsDialog() {
        List<AppSpoofPresetManager.Preset> presets =
                AppSpoofPresetManager.loadAll(requireContext().getContentResolver());

        if (presets.isEmpty()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.app_spoof_manage_presets)
                    .setMessage(R.string.app_spoof_no_presets_item)
                    .setPositiveButton(R.string.app_spoof_add_preset, (d, w) ->
                            showPresetEditDialog(null))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_spoof_manage_presets)
                .setItems(buildPresetItems(presets),
                        (d, which) -> showPresetActionsDialog(presets.get(which)))
                .setPositiveButton(R.string.app_spoof_add_preset, (d, w) ->
                        showPresetEditDialog(null))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private CharSequence[] buildPresetItems(List<AppSpoofPresetManager.Preset> presets) {
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.textColorSecondary, tv, true);
        int secondaryColor = tv.data;

        CharSequence[] items = new CharSequence[presets.size()];
        for (int i = 0; i < presets.size(); i++) {
            AppSpoofPresetManager.Preset p = presets.get(i);
            String summary = p.getSummary();
            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(p.name);
            if (!TextUtils.isEmpty(summary)) {
                sb.append("\n");
                int start = sb.length();
                sb.append(summary);
                sb.setSpan(new RelativeSizeSpan(0.8f), start, sb.length(), 0);
                sb.setSpan(new ForegroundColorSpan(secondaryColor), start, sb.length(), 0);
            }
            items[i] = sb;
        }
        return items;
    }

    private void showPresetEditDialog(AppSpoofPresetManager.Preset preset) {
        Context ctx = requireContext();
        View view = LayoutInflater.from(ctx)
                .inflate(R.layout.dialog_app_spoof_edit, null);

        view.findViewById(R.id.layout_package).setVisibility(View.GONE);
        view.findViewById(R.id.btn_preset).setVisibility(View.GONE);
        View nameRow = view.findViewById(R.id.layout_preset_name);
        if (nameRow != null) nameRow.setVisibility(View.VISIBLE);

        EditText etName         = view.findViewById(R.id.et_preset_name_inline);
        EditText etManufacturer = view.findViewById(R.id.et_manufacturer);
        EditText etModel        = view.findViewById(R.id.et_model);
        EditText etBrand        = view.findViewById(R.id.et_brand);
        EditText etDevice       = view.findViewById(R.id.et_device);
        EditText etFingerprint  = view.findViewById(R.id.et_fingerprint);
        EditText etProduct      = view.findViewById(R.id.et_product);

        if (preset != null) {
            if (etName != null) etName.setText(preset.name);
            setText(etManufacturer, preset.props.get("MANUFACTURER"));
            setText(etModel,        preset.props.get("MODEL"));
            setText(etBrand,        preset.props.get("BRAND"));
            setText(etDevice,       preset.props.get("DEVICE"));
            setText(etFingerprint,  preset.props.get("FINGERPRINT"));
            setText(etProduct,      preset.props.get("PRODUCT"));
        }

        int titleRes = preset == null
                ? R.string.app_spoof_add_preset
                : R.string.app_spoof_edit_preset;

        new AlertDialog.Builder(ctx)
                .setTitle(titleRes)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String newName = etName != null
                            ? etName.getText().toString().trim()
                            : (preset != null ? preset.name : "");
                    String mfr   = etManufacturer.getText().toString().trim();
                    String model = etModel.getText().toString().trim();
                    if (TextUtils.isEmpty(newName)) {
                        toast(R.string.app_spoof_err_preset_name_empty); return;
                    }
                    if (TextUtils.isEmpty(mfr)) {
                        toast(R.string.app_spoof_err_required_manufacturer); return;
                    }
                    if (TextUtils.isEmpty(model)) {
                        toast(R.string.app_spoof_err_required_model); return;
                    }
                    HashMap<String, String> props = collectProps(
                            mfr, model, etBrand, etDevice, etFingerprint, etProduct);
                    AppSpoofPresetManager.Preset updated =
                            new AppSpoofPresetManager.Preset(newName, props);
                    if (preset == null) {
                        AppSpoofPresetManager.save(ctx.getContentResolver(), updated);
                    } else {
                        AppSpoofPresetManager.rename(
                                ctx.getContentResolver(), preset.name, updated);
                    }
                    toast(R.string.app_spoof_preset_saved);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPresetActionsDialog(AppSpoofPresetManager.Preset preset) {
        new AlertDialog.Builder(requireContext())
                .setTitle(preset.name)
                .setItems(new CharSequence[]{
                        getString(R.string.app_spoof_edit_preset),
                        getString(R.string.app_spoof_delete_preset)
                }, (d, which) -> {
                    if (which == 0) showPresetEditDialog(preset);
                    else confirmDeletePreset(preset.name);
                })
                .show();
    }

    private void confirmDeletePreset(String name) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_spoof_confirm_delete_title)
                .setMessage(getString(R.string.app_spoof_confirm_delete_preset_msg, name))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    AppSpoofPresetManager.delete(
                            requireContext().getContentResolver(), name);
                    toast(R.string.app_spoof_deleted);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_spoof_clear_all)
                .setMessage(R.string.app_spoof_confirm_clear_all_msg)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Settings.Secure.putString(requireContext().getContentResolver(),
                            Settings.Secure.APP_SPOOF_DATA, null);
                    buildList();
                    toast(R.string.app_spoof_cleared);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        mImportLauncher.launch(intent);
    }

    private void importJson(Uri uri) {
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
            if (is == null) return;
            String json = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));

            HashMap<String, HashMap<String, String>> imported = new HashMap<>();
            parseJson(json, imported);

            if (imported.isEmpty()) {
                toast(R.string.app_spoof_import_empty); return;
            }

            JSONObject root = readRawJson();
            for (Map.Entry<String, HashMap<String, String>> e : imported.entrySet()) {
                JSONObject propsObj = new JSONObject();
                for (Map.Entry<String, String> p : e.getValue().entrySet())
                    propsObj.put(p.getKey(), p.getValue());
                root.put(e.getKey(), propsObj);
            }
            Settings.Secure.putString(requireContext().getContentResolver(),
                    Settings.Secure.APP_SPOOF_DATA, root.toString());
            buildList();
            toast(getString(R.string.app_spoof_import_success, imported.size()));
        } catch (Exception e) {
            Log.e(TAG, "Import failed", e);
            toast(R.string.app_spoof_import_fail);
        }
    }

    private void openFileSaver() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "app_spoof_config.json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        mExportLauncher.launch(intent);
    }

    private void exportJson(Uri uri) {
        try (OutputStream os = requireContext().getContentResolver().openOutputStream(uri)) {
            if (os == null) return;
            JSONObject root = readRawJson();
            os.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            toast(R.string.app_spoof_export_success);
        } catch (Exception e) {
            Log.e(TAG, "Export failed", e);
            toast(R.string.app_spoof_export_fail);
        }
    }

    private boolean isInstalled(PackageManager pm, String pkg) {
        try { pm.getApplicationInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private String buildSummary(Map<String, String> props, boolean notInstalled) {
        List<String> parts = new ArrayList<>();
        String mfr   = props.get("MANUFACTURER");
        String model = props.get("MODEL");
        if (!TextUtils.isEmpty(mfr))   parts.add(mfr);
        if (!TextUtils.isEmpty(model)) parts.add(model);
        if (notInstalled)              parts.add(getString(R.string.app_spoof_not_installed));
        return TextUtils.join(" · ", parts);
    }

    private static HashMap<String, String> collectProps(
            String mfr, String model,
            EditText etBrand, EditText etDevice,
            EditText etFingerprint, EditText etProduct) {
        HashMap<String, String> props = new HashMap<>();
        props.put("MANUFACTURER", mfr);
        props.put("MODEL",        model);
        putIfNotEmpty(props, "BRAND",       etBrand.getText().toString().trim());
        putIfNotEmpty(props, "DEVICE",      etDevice.getText().toString().trim());
        putIfNotEmpty(props, "FINGERPRINT", etFingerprint.getText().toString().trim());
        putIfNotEmpty(props, "PRODUCT",     etProduct.getText().toString().trim());
        return props;
    }

    private List<String> getInstalledPackageNames() {
        List<String> list = new ArrayList<>();
        for (ApplicationInfo ai :
                requireContext().getPackageManager().getInstalledApplications(0))
            list.add(ai.packageName);
        list.sort(String::compareToIgnoreCase);
        return list;
    }

    private static void setText(EditText et, String value) {
        if (et != null) et.setText(value != null ? value : "");
    }

    private static void putIfNotEmpty(Map<String, String> map, String key, String value) {
        if (!TextUtils.isEmpty(value)) map.put(key, value);
    }

    private void toast(int resId) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}