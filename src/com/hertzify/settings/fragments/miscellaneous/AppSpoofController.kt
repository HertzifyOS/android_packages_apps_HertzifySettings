package com.hertzify.settings.fragments.miscellaneous

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.nio.file.Files

class AppSpoofController(private val context: Context) {
    companion object {
        private const val TAG = "AppSpoofController"
        private const val CONFIG_DIR = "/data/adb/appprops"
        private const val CONFIG_FILE = "appprops.json"
        private const val PROFILES_FILE = "profiles.json"
    }

    private val dir = File(CONFIG_DIR)

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    fun isEnabled(): Boolean {
        val file = File(dir, CONFIG_FILE)
        if (!file.exists()) return false
        return try {
            val json = JSONObject(String(Files.readAllBytes(file.toPath())))
            json.optBoolean("enabled", false)
        } catch (e: Exception) { false }
    }

    fun setEnabled(enabled: Boolean) {
        try {
            val json = loadRawConfig()
            json.put("enabled", enabled)
            saveRawConfig(json)
        } catch (e: Exception) { Log.e(TAG, "Failed to set enabled", e) }
    }

    fun getAppCount(): Int {
        val file = File(dir, CONFIG_FILE)
        if (!file.exists()) return 0
        return try {
            val json = JSONObject(String(Files.readAllBytes(file.toPath())))
            json.optJSONObject("apps")?.length() ?: 0
        } catch (e: Exception) { 0 }
    }

    fun loadAppConfigs(): List<AppConfig> {
        val configs = mutableListOf<AppConfig>()
        try {
            val json = loadRawConfig()
            val appsJson = json.optJSONObject("apps") ?: return configs
            val keys = appsJson.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val propsJson = appsJson.getJSONObject(pkg)
                val props = mutableMapOf<String, String>()
                val propKeys = propsJson.keys()
                while (propKeys.hasNext()) {
                    val k = propKeys.next()
                    props[k] = propsJson.getString(k)
                }
                configs.add(AppConfig(pkg, "", "", props))
            }
        } catch (e: Exception) { Log.e(TAG, "Load configs error", e) }
        return configs
    }

    fun saveAppConfigs(enabled: Boolean, configs: List<AppConfig>) {
        try {
            val appsObj = JSONObject()
            configs.forEach { appsObj.put(it.packageName, JSONObject(it.props)) }
            val json = JSONObject().apply { 
                put("enabled", enabled)
                put("apps", appsObj) 
            }
            saveRawConfig(json)
        } catch (e: Exception) { Log.e(TAG, "Save configs error", e) }
    }

    fun loadProfiles(): List<DeviceProfile> {
        val profiles = mutableListOf<DeviceProfile>()
        val file = File(dir, PROFILES_FILE)
        if (!file.exists()) return emptyList()
        try {
            val json = JSONObject(String(Files.readAllBytes(file.toPath())))
            val keys = json.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val propsJson = json.getJSONObject(name)
                val props = mutableMapOf<String, String>()
                val propKeys = propsJson.keys()
                while (propKeys.hasNext()) {
                    val k = propKeys.next()
                    props[k] = propsJson.getString(k)
                }
                profiles.add(DeviceProfile(name, props))
            }
        } catch (e: Exception) { Log.e(TAG, "Load profiles error", e) }
        return profiles
    }

    fun saveProfiles(profiles: List<DeviceProfile>) {
        try {
            val json = JSONObject()
            profiles.forEach { json.put(it.name, JSONObject(it.props)) }
            val f = File(dir, PROFILES_FILE)
            FileWriter(f).use { it.write(json.toString(2)) }
            f.setReadable(true, false)
        } catch (e: Exception) { Log.e(TAG, "Save profiles error", e) }
    }

    fun exportToJson(): String {
        return try {
            val raw = loadRawConfig()
            val export = JSONObject()
            export.put("apps", raw.optJSONObject("apps") ?: JSONObject())
            export.toString(2)
        } catch (e: Exception) {
            Log.e(TAG, "Export error", e)
            "{}"
        }
    }

    fun importFromJson(jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)
            if (!json.has("apps")) {
                Log.e(TAG, "Invalid JSON structure")
                return false
            }
            val currentEnabled = isEnabled()
            val incomingApps = json.getJSONObject("apps")
            val existing = loadRawConfig().optJSONObject("apps") ?: JSONObject()
            val merged = JSONObject()
            existing.keys().forEach { merged.put(it, existing.getJSONObject(it)) }
            incomingApps.keys().forEach { merged.put(it, incomingApps.getJSONObject(it)) }
            saveRawConfig(JSONObject().apply {
                put("enabled", currentEnabled)
                put("apps", merged)
            })
            true
        } catch (e: Exception) {
            Log.e(TAG, "Import error", e)
            false
        }
    }

    fun clearAllApps() {
        try {
            val enabled = isEnabled()
            val json = JSONObject().apply {
                put("enabled", enabled)
                put("apps", JSONObject())
            }
            saveRawConfig(json)
        } catch (e: Exception) {
            Log.e(TAG, "Clear all error", e)
        }
    }

    private fun loadRawConfig(): JSONObject {
        val file = File(dir, CONFIG_FILE)
        if (!file.exists()) return JSONObject()
        return try { JSONObject(String(Files.readAllBytes(file.toPath()))) } catch (e: Exception) { JSONObject() }
    }

    private fun saveRawConfig(json: JSONObject) {
        val f = File(dir, CONFIG_FILE)
        FileWriter(f).use { it.write(json.toString(2)) }
        f.setReadable(true, false)
    }
}