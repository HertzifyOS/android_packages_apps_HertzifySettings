package com.hertzify.settings.fragments.miscellaneous

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class TrickyStoreController(private val context: Context) {

    companion object {
        private const val TAG = "TrickyStoreController"
        private const val TRICKY_DIR = "/data/adb/tricky_store"
        private const val KEYBOX_FILE = "keybox.xml"
        private const val TARGET_FILE = "target.txt"
    }

    enum class TargetMode(val symbol: String) {
        AUTO(""),
        LEAF_HACK("?"),
        CERT_GEN("!");

        companion object {
            fun fromLine(line: String): Pair<String, TargetMode> {
                return when {
                    line.endsWith("?") -> line.dropLast(1) to LEAF_HACK
                    line.endsWith("!") -> line.dropLast(1) to CERT_GEN
                    else -> line to AUTO
                }
            }
        }
    }

    init {
        val dir = File(TRICKY_DIR)
        if (!dir.exists()) dir.mkdirs()
    }

    fun keyboxExists() = File(TRICKY_DIR, KEYBOX_FILE).let { it.exists() && it.canRead() }

    fun importKeybox(uri: Uri) {
        try {
            copyUriToFile(uri, File(TRICKY_DIR, KEYBOX_FILE))
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.forceStopPackage("com.android.vending")
        } catch (e: Exception) {
            Log.e(TAG, "Import Keybox failed", e)
        }
    }

    fun deleteKeybox() {
        File(TRICKY_DIR, KEYBOX_FILE).takeIf { it.exists() }?.delete()
    }

    fun getTargetAppCount(): Int = readTargetMap().size

    fun readTargetMap(): Map<String, TargetMode> {
        val file = File(TRICKY_DIR, TARGET_FILE)
        if (!file.exists()) return emptyMap()
        val map = mutableMapOf<String, TargetMode>()
        try {
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val (pkg, mode) = TargetMode.fromLine(trimmed)
                    map[pkg] = mode
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Read target failed", e)
        }
        return map
    }

    fun saveTargetMap(map: Map<String, TargetMode>) {
        try {
            val file = File(TRICKY_DIR, TARGET_FILE)
            file.printWriter().use { out ->
                map.forEach { (pkg, mode) ->
                    out.println("$pkg${mode.symbol}")
                }
            }
            file.setReadable(true, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save target.txt", e)
        }
    }

    private fun copyUriToFile(uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        dest.setReadable(true, false)
    }
}