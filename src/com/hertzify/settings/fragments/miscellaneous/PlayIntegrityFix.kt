package com.hertzify.settings.fragments.miscellaneous

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import java.io.File
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PlayIntegrityFix : SettingsPreferenceFragment() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var activeConfigFileName: String? = null
    private var activeConfigData: Map<String, String> = emptyMap()

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val pifDir = File(PIF_PATH).also { if (!it.exists()) it.mkdirs() }

                        val content = requireContext().contentResolver
                            .openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?: throw IllegalStateException("Cannot read file")

                        val isJson = content.trim().startsWith("{")

                        val targetName =
                            if (isJson) "custom.pif.json"
                            else "custom.pif.prop"

                        val targetFile = File(pifDir, targetName)

                        targetFile.writeText(content)
                        targetFile.setReadable(true, false)

                        withContext(Dispatchers.Main) {
                            killPackage(VENDING_PACKAGE)
                            toast(getString(R.string.pif_imported_as, targetName))
                            refreshStatus()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            toast(getString(R.string.pif_failed, e.message ?: ""))
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.play_integrity_fix)

        findPreference<Preference>("pif_import_config")?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            importLauncher.launch(intent)
            true
        }
        
        findPreference<Preference>("pif_fetch_config")?.setOnPreferenceClickListener {
            showPifSourcePicker()
            true
        }

        findPreference<Preference>("pif_delete_config")?.setOnPreferenceClickListener {
            showDeleteDialog()
            true
        }

        findPreference<Preference>("pif_properties")?.setOnPreferenceClickListener {
            if (activeConfigData.isNotEmpty()) showConfigDetailsDialog(activeConfigData)
            true
        }

        val photosSpoofToggle = findPreference<SwitchPreferenceCompat>("pif_spoof_photos")
        photosSpoofToggle?.isChecked = isFlagEnabled(FLAG_SPOOF_PHOTOS)
        photosSpoofToggle?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean

            setFlagEnabled(FLAG_SPOOF_PHOTOS, enabled) {
                killPackage(PHOTOS_PACKAGE)
            }

            true
        }

        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun setFlagEnabled(
        name: String,
        enabled: Boolean,
        onDone: (() -> Unit)? = null
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val file = getFlagFile(name)

                if (enabled) {
                    if (!file.exists() && !file.createNewFile()) {
                        throw RuntimeException("Failed to create flag file")
                    }
                    file.setReadable(true, false)
                } else {
                    if (file.exists()) file.delete()
                }

                withContext(Dispatchers.Main) {
                    onDone?.invoke()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Flag $name failed", e)
            }
        }
    }

    private fun getFlagFile(name: String): File {
        val dir = File(PIF_PATH)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, name)
    }

    private fun isFlagEnabled(name: String): Boolean {
        return getFlagFile(name).exists()
    }

    private fun refreshStatus() {
        scope.launch(Dispatchers.IO) {
            var foundActive: String? = null
            var activeData: Map<String, String> = emptyMap()

            for (fileName in PIF_FILES) {
                val file = File(PIF_PATH, fileName)
                if (file.exists()) {
                    foundActive = fileName
                    activeData = readConfigData(file)
                    break
                }
            }

            withContext(Dispatchers.Main) {
                activeConfigFileName = foundActive
                activeConfigData = activeData

                val viewPref = findPreference<Preference>("pif_properties")
                val deletePref = findPreference<Preference>("pif_delete_config")

                if (foundActive != null) {
                    val model = activeData["MODEL"] ?: getString(android.R.string.unknownName)
                    viewPref?.summary = "$foundActive — $model"
                    viewPref?.isEnabled = true
                    deletePref?.isEnabled = true
                } else {
                    viewPref?.summary = getString(R.string.pif_no_config)
                    viewPref?.isEnabled = false
                    deletePref?.isEnabled = false
                }
            }
        }
    }

    private fun showPifSourcePicker() {
        val entries = arrayOf(
            getString(R.string.pif_source_google),
            getString(R.string.pif_source_official)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pif_select_source_title)
            .setItems(entries) { _, which ->
                when (which) {
                    0 -> fetchPixelBetaPif()
                    1 -> fetchOfficialPif()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fetchPixelBetaPif() {
        val fetchPref = findPreference<Preference>("pif_fetch_config")
        fetchPref?.summary = getString(R.string.pif_fetching)
        fetchPref?.isEnabled = false

        scope.launch {
            try {
                val devices = withContext(Dispatchers.IO) { fetchAvailableDevices() }
                if (devices.isEmpty()) {
                    toast(getString(R.string.pif_no_beta_devices))
                    return@launch
                }

                val modelNames = devices.map { it.model }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pif_select_device)
                    .setItems(modelNames) { _, which -> generateAndSavePif(devices[which]) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                toast(getString(R.string.pif_failed, e.message ?: ""))
            } finally {
                fetchPref?.summary = getString(R.string.pif_fetch_summary)
                fetchPref?.isEnabled = true
            }
        }
    }

    private fun generateAndSavePif(device: PifDevice) {
        val fetchPref = findPreference<Preference>("pif_fetch_config")
        fetchPref?.summary = getString(R.string.pif_generating)

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { buildPifFromDevice(device) }
                when (result) {
                    is PifFetchResult.Success -> savePifJson(result.pifData, result.model)
                    is PifFetchResult.Error -> toast(getString(R.string.pif_failed, result.message))
                }
            } catch (e: Exception) {
                toast(getString(R.string.pif_failed, e.message ?: ""))
            } finally {
                fetchPref?.summary = getString(R.string.pif_fetch_summary)
                fetchPref?.isEnabled = true
            }
        }
    }

    private fun fetchOfficialPif() {
        val fetchPref = findPreference<Preference>("pif_fetch_config")
        fetchPref?.summary = getString(R.string.pif_fetching_official)
        fetchPref?.isEnabled = false

        scope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    URL(OFFICIAL_PIF_URL).readText(StandardCharsets.UTF_8)
                }
                savePifJson(JSONObject(content), getString(R.string.pif_source_official))
            } catch (e: Exception) {
                toast(getString(R.string.pif_fetch_official_error, e.message ?: ""))
            } finally {
                fetchPref?.summary = getString(R.string.pif_fetch_summary)
                fetchPref?.isEnabled = true
            }
        }
    }

    private fun savePifJson(json: JSONObject, modelName: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val pifDir = File(PIF_PATH).also { if (!it.exists()) it.mkdirs() }
                val file = File(pifDir, "pif.json")
                file.writeText(json.toString(2))
                file.setReadable(true, false)

                withContext(Dispatchers.Main) {
                    killPackage(VENDING_PACKAGE)
                    toast(getString(R.string.pif_fetched_model, modelName))
                    refreshStatus()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.pif_failed, e.message ?: ""))
                }
            }
        }
    }

    private fun showConfigDetailsDialog(data: Map<String, String>) {
        val sb = StringBuilder()
        val displayOrder = listOf("MODEL", "MANUFACTURER", "FINGERPRINT", "SECURITY_PATCH", "ID", "RELEASE")

        displayOrder.forEach { key ->
            data[key]?.let {
                sb.append("<b>$key</b>: $it<br/>")
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pif_config_details)
            .setMessage(Html.fromHtml(sb.toString(), Html.FROM_HTML_MODE_COMPACT))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showDeleteDialog() {
        val fileName = activeConfigFileName ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pif_delete_title, fileName))
            .setMessage(R.string.pif_delete_message)
            .setPositiveButton(R.string.pif_delete) { _, _ ->
                scope.launch(Dispatchers.IO) {
                    try {
                        File(PIF_PATH, fileName).delete()
                        withContext(Dispatchers.Main) {
                            killPackage(VENDING_PACKAGE)
                            toast(getString(R.string.pif_delete_success))
                            refreshStatus()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            toast(getString(R.string.pif_failed, e.message ?: ""))
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun killPackage(packageName: String) {
        try {
            val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.forceStopPackage(packageName)
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun getMetricsCategory(): Int =
        MetricsProto.MetricsEvent.HERTZIFY

    companion object {
        private const val TAG = "PlayIntegrityFix"
        private const val PIF_PATH = "/data/adb/playintegrityfix"
        private val PIF_FILES = listOf("custom.pif.prop", "custom.pif.json", "pif.prop", "pif.json")
        private const val FLAG_SPOOF_PHOTOS = "spoof_google_photos"
        private const val GOOGLE_URL = "https://developer.android.com"
        private const val OFFICIAL_PIF_URL = "https://raw.githubusercontent.com/HertzifyOS/PIF/main/pif.json"
        private const val VENDING_PACKAGE = "com.android.vending"
        private const val PHOTOS_PACKAGE = "com.google.android.apps.photos"

        private val DEVICE_MODEL_MAP = mapOf(
            "oriole" to "Pixel 6",
            "raven" to "Pixel 6 Pro",
            "bluejay" to "Pixel 6a",
            "panther" to "Pixel 7",
            "cheetah" to "Pixel 7 Pro",
            "lynx" to "Pixel 7a",
            "shiba" to "Pixel 8",
            "tangorpro" to "Pixel Tablet",
            "felix" to "Pixel Fold",
            "husky" to "Pixel 8 Pro",
            "akita" to "Pixel 8a",
            "tokay" to "Pixel 9",
            "caiman" to "Pixel 9 Pro",
            "komodo" to "Pixel 9 Pro XL",
            "comet" to "Pixel 9 Pro Fold",
            "tegu" to "Pixel 9a",
            "frankel" to "Pixel 10",
            "blazer" to "Pixel 10 Pro",
            "mustang" to "Pixel 10 Pro XL",
            "rango" to "Pixel 10 Pro Fold",
            "stallion" to "Pixel 10a",
        )

        private fun readConfigData(file: File): Map<String, String> {
            if (!file.exists()) return emptyMap()

            return try {
                val content = file.readText()
                val result = mutableMapOf<String, String>()

                if (file.name.endsWith(".json")) {
                    val json = JSONObject(content)
                    json.keys().forEach { key ->
                        result[key.uppercase()] = json.optString(key, "")
                    }
                } else {
                    content.lines().forEach { line ->
                        val trimmed = line.trim()

                        if (trimmed.isNotEmpty()
                            && !trimmed.startsWith("#")
                            && !trimmed.startsWith("//")
                        ) {
                            val eq = trimmed.indexOf('=')
                            if (eq > 0) {
                                val key = trimmed.substring(0, eq).trim().uppercase()
                                val value = trimmed.substring(eq + 1).trim()
                                result[key] = value
                            }
                        }
                    }
                }

                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read config", e)
                emptyMap()
            }
        }

        private fun fetchPartialUrl(url: String, maxBytes: Int): String {
            val conn = URL(url).openConnection()
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.getInputStream().use { input ->
                val buf = ByteArray(512)
                val sb = StringBuilder()
                var total = 0
                while (total < maxBytes) {
                    val read = input.read(buf)
                    if (read == -1) break
                    sb.append(String(buf, 0, read, StandardCharsets.ISO_8859_1))
                    total += read
                }
                return sb.toString()
            }
        }

        data class PifDevice(
            val product: String,
            val device: String,
            val model: String,
            val otaUrl: String,
        )

        private sealed class PifFetchResult {
            data class Success(val model: String, val pifData: JSONObject) : PifFetchResult()
            data class Error(val message: String) : PifFetchResult()
        }

        private fun fetchAvailableDevices(): List<PifDevice> {
            val versionsHtml = URL("$GOOGLE_URL/about/versions").readText(StandardCharsets.UTF_8)
            val knownVersions = Regex("""https://developer\.android\.com/about/versions/(\d+)""")
                .findAll(versionsHtml).map { it.groupValues[1].toInt() }.toSet().sortedDescending()

            val maxVersion = knownVersions.firstOrNull() ?: return emptyList()
            val versions = listOf(maxVersion + 1) + knownVersions

            for (version in versions) {
                try {
                    val downloadUrl = "$GOOGLE_URL/about/versions/$version/download-ota"
                    val otaHtml = URL(downloadUrl).readText(StandardCharsets.UTF_8)
                    val otaList = Regex("""href="(https://dl\.google\.com/[^"]*ota/([^/"]+_beta)[^"]*?)"""")
                        .findAll(otaHtml).map { it.groupValues[1] to it.groupValues[2] }.toList()
                    if (otaList.isEmpty()) continue

                    val devices = mutableListOf<PifDevice>()
                    val seen = mutableSetOf<String>()
                    for ((otaUrl, product) in otaList) {
                        val device = product.replace("_beta", "")
                        if (device in seen) continue
                        seen.add(device)
                        val model = DEVICE_MODEL_MAP[device] ?: device
                        devices.add(PifDevice(product, device, model, otaUrl))
                    }
                    if (devices.isNotEmpty()) return devices
                } catch (_: Exception) { continue }
            }
            return emptyList()
        }

        private fun buildPifFromDevice(pifDevice: PifDevice): PifFetchResult {
            try {
                val partial = fetchPartialUrl(pifDevice.otaUrl, 4096)

                val fingerprint = Regex("""post-build=(.*)""").find(partial)?.groupValues?.get(1)?.trim()
                    ?: return PifFetchResult.Error("Could not extract fingerprint")
                val securityPatch = Regex("""security-patch-level=(.*)""").find(partial)?.groupValues?.get(1)?.trim()
                    ?: return PifFetchResult.Error("Could not extract security patch")

                val fpParts = fingerprint.split("/")
                val release = fpParts.getOrNull(2)?.substringAfter(":", "") ?: ""
                val buildId = fpParts.getOrNull(3) ?: ""

                val pifJson = JSONObject().apply {
                    put("TYPE", "user")
                    put("TAGS", "release-keys")
                    put("ID", buildId)
                    put("BRAND", "google")
                    put("DEVICE", pifDevice.device)
                    put("FINGERPRINT", fingerprint)
                    put("MANUFACTURER", "Google")
                    put("MODEL", pifDevice.model)
                    put("PRODUCT", pifDevice.product)
                    put("RELEASE", release)
                    put("SECURITY_PATCH", securityPatch)
                    put("DEVICE_INITIAL_SDK_INT", "21")
                    put("DEBUG", false)
                    put("SDK_INT", "32")
                }
                return PifFetchResult.Success(pifDevice.model, pifJson)
            } catch (e: Exception) {
                return PifFetchResult.Error("Failed: ${e.message}")
            }
        }
    }
}