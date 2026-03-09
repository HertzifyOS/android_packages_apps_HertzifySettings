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

import android.content.ContentResolver;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AppSpoofPresetManager {

    private static final String TAG = "AppSpoofPresetManager";

    private static final String[] PROP_KEYS = {
            "BRAND", "DEVICE", "MANUFACTURER", "MODEL", "FINGERPRINT", "PRODUCT"
    };

    public static final class Preset {
        public final String name;
        public final Map<String, String> props;

        public Preset(String name, Map<String, String> props) {
            this.name  = name;
            this.props = Collections.unmodifiableMap(new HashMap<>(props));
        }

        public String getSummary() {
            String mfr   = props.get("MANUFACTURER");
            String model = props.get("MODEL");
            if (!TextUtils.isEmpty(mfr) && !TextUtils.isEmpty(model))
                return mfr + " · " + model;
            if (!TextUtils.isEmpty(model)) return model;
            if (!TextUtils.isEmpty(mfr))  return mfr;
            return "";
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("name", name);
            for (String k : PROP_KEYS) {
                String v = props.get(k);
                o.put(k, v != null ? v : "");
            }
            return o;
        }

        static Preset fromJson(JSONObject o) throws JSONException {
            String name = o.optString("name", "").trim();
            if (TextUtils.isEmpty(name)) return null;
            Map<String, String> props = new HashMap<>();
            for (String k : PROP_KEYS) {
                String v = o.optString(k, "").trim();
                if (!v.isEmpty()) props.put(k, v);
            }
            if (!props.containsKey("MODEL") || !props.containsKey("MANUFACTURER"))
                return null;
            return new Preset(name, props);
        }
    }

    public static List<Preset> loadAll(ContentResolver cr) {
        List<Preset> list = new ArrayList<>();
        try {
            String json = Settings.Secure.getString(cr, Settings.Secure.APP_SPOOF_PRESETS);
            if (TextUtils.isEmpty(json)) return list;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                Preset p = Preset.fromJson(arr.getJSONObject(i));
                if (p != null) list.add(p);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to load presets", e);
        }
        return list;
    }

    public static void save(ContentResolver cr, Preset preset) {
        List<Preset> list = loadAll(cr);
        int idx = indexOfName(list, preset.name);
        if (idx >= 0) list.set(idx, preset);
        else list.add(0, preset);
        persist(cr, list);
    }

    public static void rename(ContentResolver cr, String oldName, Preset updated) {
        List<Preset> list = loadAll(cr);
        int idx = indexOfName(list, oldName);
        if (idx < 0) return;
        list.set(idx, updated);
        persist(cr, list);
    }

    public static void delete(ContentResolver cr, String name) {
        List<Preset> list = loadAll(cr);
        list.removeIf(p -> p.name.equalsIgnoreCase(name));
        persist(cr, list);
    }

    private static int indexOfName(List<Preset> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private static void persist(ContentResolver cr, List<Preset> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Preset p : list) arr.put(p.toJson());
            Settings.Secure.putString(cr, Settings.Secure.APP_SPOOF_PRESETS, arr.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to persist presets", e);
        }
    }
}