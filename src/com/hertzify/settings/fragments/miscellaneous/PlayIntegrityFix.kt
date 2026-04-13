package com.hertzify.settings.fragments.miscellaneous

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settingslib.Utils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.nio.charset.StandardCharsets
import com.google.android.material.R as MaterialR

class PlayIntegrityFix : SettingsPreferenceFragment() {

    companion object {
        private const val TAG = "PlayIntegrityFix"
        private const val PIF_CONFIG_KEY = "spoof_pif_config"
        private const val GOOGLE_URL = "https://developer.android.com"
        private const val FLASH_URL = "https://flash.android.com"
        private const val FLASH_API = "https://content-flashstation-pa.googleapis.com/v1/builds"
        private const val PIXEL_BULLETIN_URL = "https://source.android.com/docs/security/bulletin/pixel"
        private const val DROIDGUARD_PACKAGE = "com.google.android.gms.unstable"
        private const val GMS_PACKAGE = "com.google.android.gms"
        private const val VENDING_PACKAGE = "com.android.vending"
        private const val PHOTOS_PACKAGE = "com.google.android.apps.photos"

        private sealed class PifFetchResult {
            data class Success(val model: String, val pifData: JSONObject) : PifFetchResult()
            data class Error(val message: String) : PifFetchResult()
        }

        private data class PifDevice(val product: String, val device: String, val model: String)

        private fun readConfigData(content: String): Map<String, String> {
            return try {
                val result = mutableMapOf<String, String>()
                val trimmed = content.trim()
                if (trimmed.startsWith("{")) {
                    val json = JSONObject(trimmed)
                    json.keys().forEach { key -> result[key] = json.optString(key, "") }
                } else {
                    trimmed.lines().forEach { line ->
                        val l = line.trim()
                        if (l.isNotEmpty() && !l.startsWith("#") && !l.startsWith("//")) {
                            val eq = l.indexOf('=')
                            if (eq > 0) result[l.substring(0, eq).trim()] = l.substring(eq + 1).trim()
                        }
                    }
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read config: ${e.message}")
                emptyMap()
            }
        }

        private fun normalizePifPayload(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "{}"
            if (trimmed.startsWith("{")) return trimmed
            val json = JSONObject()
            trimmed.lines().forEach { line ->
                val stripped = line.trim()
                if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("//")) return@forEach
                val eq = stripped.indexOf('=')
                if (eq > 0) {
                    val key = stripped.substring(0, eq).trim()
                    val value = stripped.substring(eq + 1).trim().substringBefore('#').trim()
                    if (key.isNotEmpty()) json.put(key, value)
                }
            }
            return json.toString(2)
        }

        private fun fetchAvailableDevices(context: Context): List<PifDevice> {
            try {
                Log.d(TAG, "Fetching Pixel Canary metadata...")

                val versionsHtml = URL("$GOOGLE_URL/about/versions").readText(StandardCharsets.UTF_8)
                val latestVersion = Regex("""https://developer\.android\.com/about/versions/(\d+)""")
                    .findAll(versionsHtml)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                    .toSortedSet()
                    .maxOrNull()
                    ?: return emptyList()

                val latestHtml = URL("$GOOGLE_URL/about/versions/$latestVersion").readText(StandardCharsets.UTF_8)
                val qprPath = Regex("""href="(/about/versions/$latestVersion/qpr(\d+)/download-ota)"""")
                    .findAll(latestHtml)
                    .map { (it.groupValues[2].toIntOrNull() ?: 0) to it.groupValues[1] }
                    .maxByOrNull { it.first }
                    ?.second
                    ?: return emptyList()

                val fiHtml = URL("$GOOGLE_URL$qprPath").readText(StandardCharsets.UTF_8)
                val rowPattern = Regex(
                    """<tr id="([^"]+)">\s*<td[^>]*>([^<]+)</td>""",
                    RegexOption.DOT_MATCHES_ALL
                )

                val devices = rowPattern.findAll(fiHtml)
                    .map {
                        PifDevice(
                            product = "${it.groupValues[1]}_beta",
                            device = it.groupValues[1],
                            model = it.groupValues[2].trim()
                        )
                    }
                    .toList()

                if (devices.isEmpty()) {
                    Log.d(TAG, "No canary devices found on page")
                }

                return devices

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching available devices", e)
                return emptyList()
            }
        }

        private fun buildPifFromDevice(context: Context, pifDevice: PifDevice): PifFetchResult {
            try {
                val flashHtml = URL(FLASH_URL).readText(StandardCharsets.UTF_8)
                val apiKey = Regex("""AIza[0-9A-Za-z_-]{35}""").find(flashHtml)?.value
                    ?: return PifFetchResult.Error(context.getString(R.string.spoof_pif_error_no_apikey))

                val buildsUrl = "$FLASH_API?product=${pifDevice.product}&key=$apiKey"
                val buildsConn = URL(buildsUrl).openConnection().apply {
                    setRequestProperty("Referer", FLASH_URL)
                    setRequestProperty("X-Goog-Api-Key", apiKey)
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val buildsJson = buildsConn.getInputStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }

                val root = JSONObject(buildsJson)
                val buildsArray = root.optJSONArray("flashstationBuild")
                    ?: return PifFetchResult.Error(context.getString(R.string.spoof_pif_error_no_build_array))

                var id: String? = null
                var incremental: String? = null
                var androidVersion = ""
                var canaryId: String? = null

                for (i in buildsArray.length() - 1 downTo 0) {
                    val b = buildsArray.optJSONObject(i) ?: continue
                    val meta = b.optJSONObject("previewMetadata") ?: continue
                    if (!meta.optBoolean("canary")) continue

                    val rc = b.optString("releaseCandidateName")
                    val bid = b.optString("buildId")
                    if (rc.isEmpty() || bid.isEmpty()) continue

                    id = rc
                    incremental = bid
                    androidVersion = meta.optString("releaseTrackVersionName")
                    canaryId = meta.optString("id").takeIf { it.contains("canary-") }
                    break
                }

                if (id == null || incremental == null) {
                    return PifFetchResult.Error(context.getString(R.string.spoof_pif_error_no_build, pifDevice.product))
                }

                val fingerprint = "google/${pifDevice.product}/${pifDevice.device}:CANARY/$id/$incremental:user/release-keys"
                Log.d(TAG, "Fingerprint: $fingerprint (Android $androidVersion)")

                val canaryMonth = canaryId?.let {
                    Regex("""canary-(\d{4})(\d{2})""").find(it)?.let { m ->
                        "${m.groupValues[1]}-${m.groupValues[2]}"
                    }
                } ?: return PifFetchResult.Error(context.getString(R.string.spoof_pif_error_no_canary_month))

                val securityPatch = try {
                    val bulletinHtml = URL(PIXEL_BULLETIN_URL).readText(StandardCharsets.UTF_8)
                    Regex("""<td>($canaryMonth-\d{2})</td>""").find(bulletinHtml)?.groupValues?.get(1)
                        ?: "$canaryMonth-05"
                } catch (e: Exception) {
                    Log.d(TAG, "Bulletin fetch failed, using estimated patch: ${e.message}")
                    "$canaryMonth-05"
                }

                Log.d(TAG, "Security Patch: $securityPatch")

                val pifJson = JSONObject().apply {
                    put("MANUFACTURER", "Google")
                    put("MODEL", pifDevice.model)
                    put("PRODUCT", pifDevice.product)
                    put("DEVICE", pifDevice.device)
                    put("FINGERPRINT", fingerprint)
                    put("SECURITY_PATCH", securityPatch)
                    put("DEVICE_INITIAL_SDK_INT", "32")
                }

                return PifFetchResult.Success(pifDevice.model, pifJson)

            } catch (e: Exception) {
                Log.e(TAG, "Canary fetch failed", e)
                return PifFetchResult.Error(context.getString(R.string.spoof_pif_error_fetch_failed, e.message ?: ""))
            }
        }
    }

    private var activeConfigData: Map<String, String> = emptyMap()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val isPhotosEnabled = findPreference<SwitchPreferenceCompat>("spoof_pif_photos")?.isChecked ?: false
                lifecycleScope.launch {
                    try {
                        val raw = withContext(Dispatchers.IO) {
                            requireContext().contentResolver.openInputStream(uri)?.use {
                                it.readBytes().toString(StandardCharsets.UTF_8)
                            } ?: ""
                        }

                        val normalized = normalizePifPayload(raw)
                        val json = try { JSONObject(normalized) } catch (e: Exception) { JSONObject() }
                        json.put("spoofPhotos", isPhotosEnabled.toString())

                        Settings.Secure.putString(
                            requireContext().contentResolver,
                            PIF_CONFIG_KEY,
                            json.toString(2)
                        )

                        killGms()
                        toast(getString(R.string.spoof_pif_imported_success))
                        refreshStatus()
                    } catch (e: Exception) {
                        toast(getString(R.string.spoof_pif_failed, e.message ?: ""))
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.play_integrity_fix)

        findPreference<Preference>("spoof_pif_fetch_config")?.setOnPreferenceClickListener {
            fetchPifFromGoogle()
            true
        }

        findPreference<Preference>("spoof_pif_import_config")?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            importLauncher.launch(intent)
            true
        }

        findPreference<Preference>("spoof_pif_delete_config")?.setOnPreferenceClickListener {
            showDeleteDialog()
            true
        }

        findPreference<Preference>("spoof_pif_properties")?.setOnPreferenceClickListener {
            if (activeConfigData.isNotEmpty()) showConfigDetailsDialog(activeConfigData)
            true
        }

        findPreference<SwitchPreferenceCompat>("spoof_pif_photos")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            updateConfigValue("spoofPhotos", enabled.toString())
            killPackage(PHOTOS_PACKAGE)
            true
        }

        refreshStatus()
    }

    private fun updateConfigValue(key: String, value: String) {
        lifecycleScope.launch {
            try {
                val existing = Settings.Secure.getString(requireContext().contentResolver, PIF_CONFIG_KEY)
                val json = try { JSONObject(existing ?: "") } catch (e: Exception) { JSONObject() }
                json.put(key, value)
                Settings.Secure.putString(
                    requireContext().contentResolver,
                    PIF_CONFIG_KEY,
                    json.toString(2)
                )
                refreshStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update config value", e)
            }
        }
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val content = Settings.Secure.getString(requireContext().contentResolver, PIF_CONFIG_KEY)
            activeConfigData = if (!content.isNullOrEmpty()) readConfigData(content) else emptyMap()
            val hasValidPifData = activeConfigData.keys.any { it != "spoofPhotos" && it != "DEBUG" && !it.startsWith("spoof") }

            val viewPref = findPreference<Preference>("spoof_pif_properties")
            val deletePref = findPreference<Preference>("spoof_pif_delete_config")
            val photosPref = findPreference<SwitchPreferenceCompat>("spoof_pif_photos")

            if (hasValidPifData) {
                val model = activeConfigData["MODEL"] ?: getString(android.R.string.unknownName)
                viewPref?.summary = model
                viewPref?.isEnabled = true
                deletePref?.isEnabled = true
            } else {
                viewPref?.summary = getString(R.string.spoof_pif_no_config)
                viewPref?.isEnabled = false
                deletePref?.isEnabled = false
            }

            photosPref?.isChecked = activeConfigData["spoofPhotos"]?.let { it == "true" || it == "1" } ?: false
        }
    }

    private fun createDivider(): View {
        return View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, px(1))
            ).apply {
                setMargins(0, 0, 0, px(4))
            }
            val dividerColor = Utils.getColorAttrDefaultColor(
                requireContext(),
                com.google.android.material.R.attr.colorOutlineVariant
            )
            setBackgroundColor(dividerColor)
        }
    }

    private fun setupBottomSheet(dialog: BottomSheetDialog) {
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(MaterialR.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val tv = TypedValue()
                requireContext().theme.resolveAttribute(android.R.attr.colorBackgroundFloating, tv, true)

                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(tv.data)
                    val r = 28f * resources.displayMetrics.density
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
                sheet.background = shape
                sheet.clipToOutline = true

                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    private fun createBottomSheetBaseLayout(titleResId: Int): Pair<LinearLayout, LinearLayout> {
        val rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(16), 0, 0)
            clipChildren = true
        }

        val titleView = TextView(requireContext()).apply {
            text = getString(titleResId)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(px(24), px(8), px(24), px(12))
            setTextColor(Utils.getColorAttrDefaultColor(requireContext(), android.R.attr.textColorPrimary))
        }
        rootLayout.addView(titleView)
        rootLayout.addView(createDivider())

        val displayMetrics = resources.displayMetrics
        val maxScrollViewHeight = (displayMetrics.heightPixels * 0.6).toInt()

        val scrollView = object : NestedScrollView(requireContext()) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxSpec = MeasureSpec.makeMeasureSpec(maxScrollViewHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, maxSpec)
            }
        }.apply {
            clipToOutline = true
            clipChildren = true
        }

        val itemsLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        scrollView.addView(itemsLayout)
        rootLayout.addView(scrollView)

        return Pair(rootLayout, itemsLayout)
    }

    private fun fetchPifFromGoogle() {
        val fetchPref = findPreference<Preference>("spoof_pif_fetch_config") ?: return
        updateFetchState(fetchPref, getString(R.string.spoof_pif_fetching), false)

        val isPhotosEnabled = findPreference<SwitchPreferenceCompat>("spoof_pif_photos")?.isChecked ?: false

        lifecycleScope.launch {
            try {
                val devices = withContext(Dispatchers.IO) { fetchAvailableDevices(requireContext()) }
                if (devices.isEmpty()) {
                    toast(getString(R.string.spoof_pif_no_canary_devices))
                    return@launch
                }

                val bottomSheetDialog = BottomSheetDialog(ContextThemeWrapper(requireContext(), R.style.Theme_Settings))
                setupBottomSheet(bottomSheetDialog)

                val (rootLayout, itemsLayout) = createBottomSheetBaseLayout(R.string.spoof_pif_select_device)

                val outValue = TypedValue()
                requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)

                devices.forEach { device ->
                    val itemView = TextView(requireContext()).apply {
                        text = device.model
                        textSize = 16f
                        setPadding(px(24), px(16), px(24), px(16))
                        setBackgroundResource(outValue.resourceId)
                        isClickable = true
                        setTextColor(Utils.getColorAttrDefaultColor(requireContext(), android.R.attr.textColorPrimary))
                        setOnClickListener {
                            generateAndSavePif(device, isPhotosEnabled)
                            bottomSheetDialog.dismiss()
                        }
                    }
                    itemsLayout.addView(itemView)
                }

                bottomSheetDialog.setContentView(rootLayout)
                bottomSheetDialog.show()
            } catch (e: Exception) {
                toast(getString(R.string.spoof_pif_failed, e.message ?: ""))
            } finally {
                updateFetchState(fetchPref, getString(R.string.spoof_pif_fetch_summary), true)
            }
        }
    }

    private fun generateAndSavePif(device: PifDevice, isPhotosEnabled: Boolean = false) {
        val fetchPref = findPreference<Preference>("spoof_pif_fetch_config") ?: return
        updateFetchState(fetchPref, getString(R.string.spoof_pif_generating), false)
        val appContext = requireContext()

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { buildPifFromDevice(appContext, device) }
                when (result) {
                    is PifFetchResult.Success -> {
                        result.pifData.put("spoofPhotos", isPhotosEnabled.toString())

                        Settings.Secure.putString(
                            requireContext().contentResolver,
                            PIF_CONFIG_KEY,
                            result.pifData.toString(2)
                        )
                        killGms()
                        toast(getString(R.string.spoof_pif_fetched_model, result.model))
                        refreshStatus()
                    }
                    is PifFetchResult.Error -> {
                        toast(getString(R.string.spoof_pif_failed, result.message))
                    }
                }
            } catch (e: Exception) {
                toast(getString(R.string.spoof_pif_failed, e.message ?: ""))
            } finally {
                updateFetchState(fetchPref, getString(R.string.spoof_pif_fetch_summary), true)
            }
        }
    }

    private fun showConfigDetailsDialog(data: Map<String, String>) {
        val bottomSheetDialog = BottomSheetDialog(ContextThemeWrapper(requireContext(), R.style.Theme_Settings))
        setupBottomSheet(bottomSheetDialog)

        val (rootLayout, itemsLayout) = createBottomSheetBaseLayout(R.string.spoof_pif_config_details)
        itemsLayout.setPadding(px(24), 0, px(24), px(16))

        val importantKeys = listOf("MANUFACTURER", "MODEL", "PRODUCT", "DEVICE", "FINGERPRINT", "SECURITY_PATCH")
        val sortedKeys = data.keys.sortedWith { a, b ->
            val indexA = importantKeys.indexOf(a).takeIf { it != -1 } ?: Int.MAX_VALUE
            val indexB = importantKeys.indexOf(b).takeIf { it != -1 } ?: Int.MAX_VALUE
            if (indexA != indexB) indexA.compareTo(indexB) else a.compareTo(b)
        }

        sortedKeys.forEach { key ->
            val value = data[key]
            if (!value.isNullOrEmpty()) {
                val propContainer = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, px(8), 0, px(12))
                }

                val keyView = TextView(requireContext()).apply {
                    text = key
                    textSize = 12f
                    alpha = 0.7f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Utils.getColorAttrDefaultColor(requireContext(), android.R.attr.textColorSecondary))
                }

                val valueView = TextView(requireContext()).apply {
                    text = value
                    textSize = 16f
                    setTextIsSelectable(true)
                    setTextColor(Utils.getColorAttrDefaultColor(requireContext(), android.R.attr.textColorPrimary))
                }

                propContainer.addView(keyView)
                propContainer.addView(valueView)
                itemsLayout.addView(propContainer)
            }
        }

        bottomSheetDialog.setContentView(rootLayout)
        bottomSheetDialog.show()
    }

    private fun showDeleteDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.spoof_pif_delete_confirm_title))
            .setMessage(R.string.spoof_pif_delete_confirm_message)
            .setPositiveButton(R.string.spoof_pif_delete_button) { _, _ ->
                lifecycleScope.launch {
                    try {
                        val isPhotosEnabled = findPreference<SwitchPreferenceCompat>("spoof_pif_photos")?.isChecked ?: false

                        val newJsonString = if (isPhotosEnabled) {
                            JSONObject().apply { put("spoofPhotos", "true") }.toString(2)
                        } else {
                            null
                        }

                        Settings.Secure.putString(
                            requireContext().contentResolver,
                            PIF_CONFIG_KEY,
                            newJsonString
                        )

                        killGms()
                        toast(getString(R.string.spoof_pif_delete_success))

                        refreshStatus()
                    } catch (e: Exception) {
                        toast(getString(R.string.spoof_pif_failed, e.message ?: ""))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateFetchState(pref: Preference, summary: String, enabled: Boolean) {
        pref.summary = summary
        pref.isEnabled = enabled
    }

    private fun killGms() {
        killPackage(DROIDGUARD_PACKAGE)
        killPackage(GMS_PACKAGE)
        killPackage(VENDING_PACKAGE)
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

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}