package com.hertzify.settings.fragments.miscellaneous

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AppSpoofController(private val context: Context) {

    companion object {
        private const val TAG        = "AppSpoofController"
        private const val CONFIG_KEY = "spoof_appprops_config"
        const val PRESETS_KEY        = "app_spoofing_user_presets"
    }

    suspend fun readConfig(): Pair<Boolean, List<AppConfig>> = withContext(Dispatchers.IO) {
        val content = Settings.Secure.getString(context.contentResolver, CONFIG_KEY)
            ?: return@withContext false to emptyList()

        return@withContext try {
            val json    = JSONObject(content)
            val enabled = json.optBoolean("enabled", false)
            val appsObj = json.optJSONObject("apps") ?: return@withContext enabled to emptyList()
            
            val apps = appsObj.keys().asSequence().map { pkg ->
                val propsObj = appsObj.getJSONObject(pkg)
                val props = propsObj.keys().asSequence().associateWith { propsObj.getString(it) }
                AppConfig(pkg, pkg, props)
            }.toList()
            
            enabled to apps
        } catch (e: Exception) {
            Log.e(TAG, "readConfig error", e)
            false to emptyList()
        }
    }

    suspend fun writeConfig(enabled: Boolean, apps: List<AppConfig>) = withContext(Dispatchers.IO) {
        try {
            val appsObj = JSONObject().apply {
                apps.forEach { app ->
                    put(app.packageName, JSONObject().apply {
                        app.props.forEach { (k, v) -> put(k, v) }
                    })
                }
            }
            val json = JSONObject().apply {
                put("enabled", enabled)
                put("apps", appsObj)
            }
            
            Settings.Secure.putString(
                context.contentResolver,
                CONFIG_KEY,
                json.toString(2)
            )
        } catch (e: Exception) {
            Log.e(TAG, "writeConfig error", e)
        }
    }

    fun readProfiles(): List<DeviceProfile> {
        val jsonString = Settings.Secure.getString(context.contentResolver, PRESETS_KEY) ?: return emptyList()
        val profiles = mutableListOf<DeviceProfile>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.getString("name")
                val propsObj = obj.getJSONObject("props")
                val props = propsObj.keys().asSequence().associateWith { propsObj.getString(it) }
                profiles.add(DeviceProfile(name, props.toMutableMap()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom presets", e)
        }
        return profiles
    }

    fun writeProfiles(profiles: List<DeviceProfile>) {
        val jsonArray = JSONArray()
        profiles.forEach { p ->
            val obj = JSONObject()
            obj.put("name", p.name)
            val propsObj = JSONObject()
            p.props.forEach { (k, v) -> propsObj.put(k, v) }
            obj.put("props", propsObj)
            jsonArray.put(obj)
        }
        Settings.Secure.putString(context.contentResolver, PRESETS_KEY, jsonArray.toString())
    }
}