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

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class SpoofFragment extends SettingsPreferenceFragment {

    private static final String TAG = "SpoofFragment";

    private static final String KEY_PIF_FETCH   = "pif_fetch";
    private static final String KEY_PIF_IMPORT_FILE  = "pif_import_file";
    private static final String KEY_PIF_SHOW_PROPS   = "pif_show_props";
    private static final String KEY_PIF_SPOOF_PHOTOS = "pif_spoof_photos";

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private PifManager mPifManager;
    private PifRepository mPifRepository;

    private Preference mFetchPif;
    private Preference mImportPif;
    private Preference mShowProps;
    private SwitchPreferenceCompat mSpoofPhotos;

    private final ActivityResultLauncher<Intent> mPifFileLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) importPifFile(uri);
                        }
                    });

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.spoof_settings, rootKey);

        mPifManager    = new PifManager(requireContext());
        mPifRepository = new PifRepository();

        bindPreferences();
        refreshSummaries();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshSummaries();
    }

    private void bindPreferences() {
        mFetchPif   = requirePreference(KEY_PIF_FETCH);
        mImportPif   = requirePreference(KEY_PIF_IMPORT_FILE);
        mShowProps   = requirePreference(KEY_PIF_SHOW_PROPS);
        mSpoofPhotos = (SwitchPreferenceCompat) requirePreference(KEY_PIF_SPOOF_PHOTOS);
        
        mFetchPif.setOnPreferenceClickListener(p -> { fetchPif();      return true; });
        mImportPif.setOnPreferenceClickListener(p -> { openFilePicker();    return true; });
        mShowProps.setOnPreferenceClickListener(p -> { showCurrentProps();  return true; });

        mSpoofPhotos.setChecked(mPifManager.isSpoofPhotosEnabled());
        mSpoofPhotos.setOnPreferenceChangeListener((p, v) -> {
            mPifManager.setSpoofPhotos((Boolean) v);
            return true;
        });
    }

    private void refreshSummaries() {
        String model      = mPifManager.getCurrentModel();
        String config     = mPifManager.getActiveConfigName();
        boolean hasConfig = !model.isEmpty();

        mShowProps.setEnabled(hasConfig);
        mShowProps.setSummary(hasConfig
                ? config + " · " + model
                : getString(R.string.pif_no_props));
    }

    private void fetchPif() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.pif_fetch_select_source)
                .setItems(new String[]{
                        getString(R.string.pif_source_google),
                        getString(R.string.pif_source_hertzify)
                }, (d, which) -> doFetch(which == 0
                        ? PifRepository.Source.GOOGLE
                        : PifRepository.Source.HERTZIFY))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void doFetch(PifRepository.Source source) {
        Toast.makeText(requireContext(), R.string.pif_fetching, Toast.LENGTH_SHORT).show();
        mFetchPif.setEnabled(false);

        new Thread(() -> {
            PifRepository.PifResult result = mPifRepository.fetchPif(source);
            mHandler.post(() -> {
                mFetchPif.setEnabled(true);
                if (result instanceof PifRepository.PifResult.Success) {
                    PifRepository.PifResult.Success success =
                            (PifRepository.PifResult.Success) result;
                    mPifManager.applyPif(success.pifData);
                    refreshSummaries();
                    Toast.makeText(requireContext(),
                            getString(R.string.pif_fetch_success, success.model),
                            Toast.LENGTH_LONG).show();
                } else {
                    PifRepository.PifResult.Error err =
                            (PifRepository.PifResult.Error) result;
                    Toast.makeText(requireContext(),
                            getString(R.string.pif_fetch_failed, err.message),
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        mPifFileLauncher.launch(intent);
    }

    private void importPifFile(Uri uri) {
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
            if (is == null) throw new Exception("Null input stream");
            String jsonString = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            PifRepository.PifResult result = mPifRepository.parseFromString(jsonString);
            if (result instanceof PifRepository.PifResult.Success) {
                mPifManager.applyPif(((PifRepository.PifResult.Success) result).pifData);
                refreshSummaries();
                Toast.makeText(requireContext(),
                        R.string.pif_import_success, Toast.LENGTH_SHORT).show();
            } else {
                PifRepository.PifResult.Error err = (PifRepository.PifResult.Error) result;
                Toast.makeText(requireContext(),
                        getString(R.string.pif_import_failed, err.message),
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "importPifFile error", e);
            Toast.makeText(requireContext(),
                    getString(R.string.pif_import_error, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showCurrentProps() {
        Map<String, String> props = mPifManager.getCurrentProperties();
        if (props.isEmpty()) {
            Toast.makeText(requireContext(), R.string.pif_no_props, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject json = new JSONObject(props);
            String text = json.toString(4).replace("\\/", "/");
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pif_current_props_title)
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showCurrentProps error", e);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.HERTZIFY;
    }

    private Preference requirePreference(String key) {
        Preference p = findPreference(key);
        if (p == null) throw new IllegalStateException("Preference not found: " + key);
        return p;
    }
}