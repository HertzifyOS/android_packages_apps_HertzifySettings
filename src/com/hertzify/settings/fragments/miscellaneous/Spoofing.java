package com.hertzify.settings.fragments.miscellaneous;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.util.hertzify.SystemRestartUtils;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import com.hertzify.settings.preferences.PifDataPreference;
import com.hertzify.settings.preferences.SecureSettingSwitchPreference;

import org.json.JSONObject;

import java.util.List;

@SearchIndexable
public class Spoofing extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    public static final String TAG = "Spoofing";

    private static final String KEY_PI_ENABLE       = "pi_enable_spoof";
    private static final String KEY_PI_PIXEL        = "pi_pixel_spoof";
    private static final String KEY_PI_PHOTOS       = "pi_photos_spoof";
    private static final String KEY_PIF_INFO        = "pif_current_info";
    private static final String PIF_DATA_KEY        = "pif_data_setting";

    private static final String GOOGLE_PHOTOS_PKG   = "com.google.android.apps.photos";
    private static final String[] GMS_PACKAGES      = {
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.nbu.paisa.user",
            "com.google.android.apps.walletnfcrel",
            "com.android.vending"
    };

    private ActivityResultLauncher<Intent> mPifFilePickerLauncher;

    private Preference mPifInfoPreference;
    private PifDataPreference mPifDataPreference;

    private SecureSettingSwitchPreference mPiEnableSpoof;
    private SecureSettingSwitchPreference mPiPixelSpoof;
    private SecureSettingSwitchPreference mPiPhotosSpoof;

    private Handler mHandler;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler();
        addPreferencesFromResource(R.xml.spoofing);

        mPiEnableSpoof = (SecureSettingSwitchPreference) findPreference(KEY_PI_ENABLE);
        mPiPixelSpoof  = (SecureSettingSwitchPreference) findPreference(KEY_PI_PIXEL);
        mPiPhotosSpoof = (SecureSettingSwitchPreference) findPreference(KEY_PI_PHOTOS);

        if (mPiEnableSpoof != null) mPiEnableSpoof.setOnPreferenceChangeListener(this);
        if (mPiPixelSpoof  != null) mPiPixelSpoof.setOnPreferenceChangeListener(this);
        if (mPiPhotosSpoof != null) mPiPhotosSpoof.setOnPreferenceChangeListener(this);

        // PIF info preference
        mPifInfoPreference = findPreference(KEY_PIF_INFO);
        if (mPifInfoPreference != null) {
            mPifInfoPreference.setOnPreferenceClickListener(pref -> {
                showPifDialog();
                return true;
            });
        }

        // PIF file picker
        mPifFilePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK
                        && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    Preference pref = findPreference(PIF_DATA_KEY);
                    if (pref instanceof PifDataPreference) {
                        ((PifDataPreference) pref).handleFileSelected(uri);
                        killGMSPackages();
                    }
                }
            }
        );
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // PIF
        mPifDataPreference = findPreference(PIF_DATA_KEY);
        if (mPifDataPreference != null) {
            mPifDataPreference.setFilePickerLauncher(mPifFilePickerLauncher);
            mPifDataPreference.setOnPreferenceChangeListener((pref, newValue) -> {
                killGMSPackages();
                return true;
            });
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mPiEnableSpoof) {
            killGMSPackages();
            return true;
        }
        if (preference == mPiPixelSpoof) {
            SystemRestartUtils.showSystemRestartDialog(getContext());
            return true;
        }
        if (preference == mPiPhotosSpoof) {
            killPackage(GOOGLE_PHOTOS_PKG);
            return true;
        }
        return false;
    }

    private void killPackage(String pkg) {
        try {
            ActivityManager am = (ActivityManager) getContext()
                    .getSystemService(Context.ACTIVITY_SERVICE);
            am.getClass()
              .getMethod("forceStopPackage", String.class)
              .invoke(am, pkg);
            Log.i(TAG, pkg + " killed");
        } catch (Exception e) {
            Log.e(TAG, "Failed to kill " + pkg, e);
        }
    }

    private void killGMSPackages() {
        for (String pkg : GMS_PACKAGES) {
            killPackage(pkg);
        }
    }

    private void showPifDialog() {
        Context ctx = getContext();

        String user = Settings.Secure.getString(
                ctx.getContentResolver(),
                Settings.Secure.PIF_DATA);

        String fetched = Settings.Secure.getString(
                ctx.getContentResolver(),
                Settings.Secure.FETCHED_PIF);

        String json = !TextUtils.isEmpty(user) ? user : fetched;
        String source = !TextUtils.isEmpty(user) ? "User" : "Fetched";

        if (TextUtils.isEmpty(json)) {
            new AlertDialog.Builder(ctx)
                    .setTitle("Play Integrity Profile")
                    .setMessage("No PIF configured")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        try {
            JSONObject obj = new JSONObject(json);

            String message =
                    "Model: " + obj.optString("MODEL") + "\n" +
                    "Brand: " + obj.optString("BRAND") + "\n" +
                    "Device: " + obj.optString("DEVICE") + "\n" +
                    "Security patch: " + obj.optString("SECURITY_PATCH") + "\n" +
                    "Fingerprint:\n" + obj.optString("FINGERPRINT") + "\n" +
                    "Source: " + source;

            new AlertDialog.Builder(ctx)
                    .setTitle("Play Integrity Profile")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();

        } catch (Exception e) {
            Log.e(TAG, "Failed to parse PIF", e);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.HERTZIFY;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.spoofing) {
                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    return super.getNonIndexableKeys(context);
                }
            };
}