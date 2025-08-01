/*
 * Copyright (C) 2026 HertzifyOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.hertzify.settings.fragments.quicksettings;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import com.hertzify.settings.utils.SystemUtils;

import java.util.List;

@SearchIndexable
public class QuickSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "QuickSettings";

    private static final String KEY_QS_COMPACT_PLAYER  = "qs_compact_media_player_mode";

    private Preference mQsCompactPlayer;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.hertzify_settings_quick_settings);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        final PreferenceScreen prefScreen = getPreferenceScreen();
        final Resources resources = context.getResources();

        mQsCompactPlayer = (Preference) findPreference(KEY_QS_COMPACT_PLAYER);
        mQsCompactPlayer.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();

        if (preference == mQsCompactPlayer) {
            SystemUtils.showSystemUiRestartDialog(context);
            return true;
        }

        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.HERTZIFY;
    }

    /**
     * For search
     */
    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.hertzify_settings_quick_settings) {

                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    List<String> keys = super.getNonIndexableKeys(context);
                    final Resources resources = context.getResources();
                    return keys;
                }
            };
}