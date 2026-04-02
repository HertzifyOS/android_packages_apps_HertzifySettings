package com.hertzify.settings.fragments.miscellaneous

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap

class PifManager(private val context: Context) {

    private val TAG = "PifManager"
    private val pifDir = File("/data/adb/playintegrityfix")
    private val configFiles = listOf("custom.pif.prop", "custom.pif.json", "pif.prop", "pif.json")
    private val photosSpoofFile = "spoof_photos.prop"

    init {
        if (!pifDir.exists()) {
            try { 
                pifDir.mkdirs() 
            } catch (e: Exception) { 
                Log.e(TAG, "Critical: Could not create PIF directory", e) 
            }
        }
    }

    fun getActiveConfigName(): String = findActiveFile()?.name ?: ""

    fun getCurrentModel(): String = getCurrentProperties()["MODEL"] ?: ""

    fun getCurrentProperties(): Map<String, String> {
        val active = findActiveFile() ?: return emptyMap()
        return try {
            parseConfig(active.name, active.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read active config: ${active.name}", e)
            emptyMap()
        }
    }

    fun getConfigStates(): List<ConfigState> {
        var foundActive = false
        return configFiles.map { name ->
            val file = File(pifDir, name)
            val exists = file.exists() && file.canRead()
            val isActive = exists && !foundActive
            if (isActive) foundActive = true
            ConfigState(name, exists, isActive)
        }
    }

    fun applyPif(pifData: JSONObject) {
        val target = "custom.pif.json"
        clearAllConfigsExcept(target)
        writeConfig(target, jsonToMap(pifData))
    }

    fun importConfig(sourceName: String, content: String): String {
        val props = parseConfig(sourceName, content)
        if (props.isEmpty()) throw IllegalArgumentException("Config is empty or invalid")
        
        val isJson = sourceName.endsWith(".json") || content.trim().startsWith("{")
        val target = if (isJson) "custom.pif.json" else "custom.pif.prop"
        
        clearAllConfigsExcept(target)
        writeConfig(target, props)
        return target
    }

    fun deleteConfig(fileName: String) {
        val file = File(pifDir, fileName)
        if (file.exists()) file.delete()
        killPackage("com.android.vending")
    }

    private fun clearAllConfigsExcept(exceptName: String) {
        configFiles.forEach { name ->
            if (name != exceptName) {
                val file = File(pifDir, name)
                if (file.exists()) file.delete()
            }
        }
    }

    private fun writeConfig(fileName: String, props: Map<String, String>) {
        val file = File(pifDir, fileName)
        val content = if (fileName.endsWith(".json")) {
            JSONObject(props).toString(4).replace("\\/", "/")
        } else {
            props.entries.joinToString("\n") { "${it.key}=${it.value}" }
        }
        try {
            file.writeText(content)
            file.setReadable(true, false)
            killPackage("com.android.vending")
        } catch (e: Exception) {
            Log.e(TAG, "File write failed: $fileName", e)
        }
    }

    private fun findActiveFile() = configFiles.map { File(pifDir, it) }.firstOrNull { it.exists() && it.canRead() }

    private fun parseConfig(name: String, content: String): Map<String, String> {
        val trimmed = content.trim()
        val result = LinkedHashMap<String, String>()
        try {
            if (name.endsWith(".json") || trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                json.keys().forEach { result[it] = json.optString(it) }
            } else {
                trimmed.lines().forEach { line ->
                    val l = line.trim()
                    if (l.isNotEmpty() && !l.startsWith("#")) {
                        val parts = l.split("=", limit = 2)
                        if (parts.size == 2) result[parts[0].trim()] = parts[1].trim().removeSurrounding("\"")
                    }
                }
            }
        } catch (e: Exception) { 
            Log.e(TAG, "Config parsing error in $name", e) 
        }
        return result
    }

    fun isSpoofPhotosEnabled(): Boolean = try {
        File(pifDir, photosSpoofFile).let { 
            it.exists() && it.readText().trim().let { v -> v == "1" || v.equals("true", true) }
        }
    } catch (e: Exception) { false }

    fun setSpoofPhotos(enabled: Boolean) {
        try {
            File(pifDir, photosSpoofFile).writeText(if (enabled) "1\n" else "0\n")
            killPackage("com.google.android.apps.photos")
        } catch (e: Exception) { 
            Log.e(TAG, "Failed to toggle Photos spoof", e) 
        }
    }

    private fun killPackage(pkg: String) {
        try {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.forceStopPackage(pkg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kill package: $pkg", e)
        }
    }

    private fun jsonToMap(json: JSONObject): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        json.keys().forEach { map[it] = json.optString(it) }
        return map
    }

    data class ConfigState(val fileName: String, val exists: Boolean, val isActive: Boolean)
}