package com.hertzify.settings.fragments.miscellaneous

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AppSpoofController(private val context: Context) {

    companion object {
        private const val TAG            = "AppSpoofController"
        private const val CONFIG_DIR     = "/data/system/appprops"
        private const val CONFIG_FILE    = "appprops.json"
        private const val PROFILES_FILE  = "profiles.json"
    }

    private val dir         = File(CONFIG_DIR).also { it.mkdirs() }
    private val configFile  = File(dir, CONFIG_FILE)
    private val profileFile = File(dir, PROFILES_FILE)

    fun readConfig(): Pair<Boolean, List<AppConfig>> {
        if (!configFile.exists()) return false to emptyList()
        return try {
            val json    = JSONObject(configFile.readText())
            val enabled = json.optBoolean("enabled", false)
            val appsObj = json.optJSONObject("apps") ?: return enabled to emptyList()
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

    fun writeConfig(enabled: Boolean, apps: List<AppConfig>) {
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
            writeAtomic(configFile, json.toString(2))
        } catch (e: Exception) { Log.e(TAG, "writeConfig error", e) }
    }

    fun readProfiles(): List<DeviceProfile> {
        if (!profileFile.exists()) {
            writeProfiles(AppSpoofConstants.DEFAULT_PROFILES)
            return AppSpoofConstants.DEFAULT_PROFILES
        }
        return try {
            val arr = JSONArray(profileFile.readText())
            (0 until arr.length()).map { i ->
                val obj   = arr.getJSONObject(i)
                val name  = obj.getString("name")
                val props = obj.getJSONObject("props")
                DeviceProfile(name, props.keys().asSequence().associateWith { props.getString(it) })
            }
        } catch (e: Exception) {
            Log.e(TAG, "readProfiles error", e)
            AppSpoofConstants.DEFAULT_PROFILES
        }
    }

    fun writeProfiles(profiles: List<DeviceProfile>) {
        try {
            val arr = JSONArray().apply {
                profiles.forEach { p ->
                    put(JSONObject().apply {
                        put("name", p.name)
                        put("props", JSONObject().apply { p.props.forEach { (k, v) -> put(k, v) } })
                    })
                }
            }
            writeAtomic(profileFile, arr.toString(2))
        } catch (e: Exception) { Log.e(TAG, "writeProfiles error", e) }
    }

    fun exportToJson(uri: Uri, apps: List<AppConfig>, profiles: List<DeviceProfile>): Boolean {
        return try {
            val root = JSONObject().apply {
                val appsObj = JSONObject()
                apps.forEach { app ->
                    appsObj.put(app.packageName, JSONObject().apply {
                        app.props.forEach { (k, v) -> put(k, v) }
                    })
                }
                put("apps", appsObj)

                val profilesArr = JSONArray()
                profiles.forEach { p ->
                    profilesArr.put(JSONObject().apply {
                        put("name", p.name)
                        put("props", JSONObject().apply { p.props.forEach { (k, v) -> put(k, v) } })
                    })
                }
                put("profiles", profilesArr)
            }
            context.contentResolver.openOutputStream(uri)?.use { it.write(root.toString(2).toByteArray()) }
            true
        } catch (e: Exception) { false }
    }

    fun importFromJson(uri: Uri): Boolean {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return false
            val json = JSONObject(content)

            val currentData = readConfig()
            val mergedConfigs = currentData.second.toMutableList()
            json.optJSONObject("apps")?.let { obj ->
                obj.keys().forEach { pkg ->
                    val propsObj = obj.getJSONObject(pkg)
                    val props = propsObj.keys().asSequence().associateWith { propsObj.getString(it) }
                    val idx = mergedConfigs.indexOfFirst { it.packageName == pkg }
                    val newCfg = AppConfig(pkg, pkg, props)
                    if (idx != -1) mergedConfigs[idx] = newCfg else mergedConfigs.add(newCfg)
                }
            }

            val mergedProfiles = readProfiles().toMutableList()
            json.optJSONArray("profiles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val name = obj.getString("name")
                    val props = obj.getJSONObject("props").let { p -> 
                        p.keys().asSequence().associateWith { p.getString(it) } 
                    }
                    if (mergedProfiles.none { it.name == name }) mergedProfiles.add(DeviceProfile(name, props))
                }
            }

            writeConfig(currentData.first, mergedConfigs)
            writeProfiles(mergedProfiles)
            true
        } catch (e: Exception) { false }
    }

    private fun writeAtomic(dest: File, content: String) {
        val tmp = File(dest.parent, "${dest.name}.tmp")
        try {
            tmp.writeText(content)
            tmp.setReadable(true, false)
            if (!tmp.renameTo(dest)) {
                dest.writeText(content)
                dest.setReadable(true, false)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            Log.e(TAG, "writeAtomic failed: ${dest.name}", e)
            throw e
        }
    }
}