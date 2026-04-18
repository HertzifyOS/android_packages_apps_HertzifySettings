/*
 * Copyright (C) 2026 HertzifyOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.hertzify.settings.fragments.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.SystemProperties;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import com.hertzify.settings.preferences.SystemSettingListPreference;
import com.hertzify.settings.utils.SystemUtils;

import java.util.List;

@SearchIndexable
public class UserInterface extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "UserInterface";
    private static final String KEY_EMOJI_STYLE = "emoji_style";
    private static final String PROP_EMOJI_STYLE = "persist.sys.emoji_style";
    private static final String DEFAULT_EMOJI_STYLE = "android";

    private SystemSettingListPreference mEmojiStylePref;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.hertzify_settings_user_interface);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        final PreferenceScreen prefScreen = getPreferenceScreen();
        final Resources resources = context.getResources();

        mEmojiStylePref = findPreference(KEY_EMOJI_STYLE);
        if (mEmojiStylePref != null) {
            mEmojiStylePref.setValue(SystemProperties.get(PROP_EMOJI_STYLE, DEFAULT_EMOJI_STYLE));
            mEmojiStylePref.setOnPreferenceChangeListener(this);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        if (preference == mEmojiStylePref) {
            SystemProperties.set(PROP_EMOJI_STYLE, (String) newValue);
            SystemUtils.showSystemRestartDialog(context);
            return true;
        }
        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.HERTZIFY ;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
        new BaseSearchIndexProvider(R.xml.hertzify_settings_user_interface) {

            @Override
            public List<String> getNonIndexableKeys(Context context) {
                List<String> keys = super.getNonIndexableKeys(context);
                final Resources resources = context.getResources();
                return keys;
            }
        };
}
