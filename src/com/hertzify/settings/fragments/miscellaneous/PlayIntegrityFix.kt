@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.hertzify.settings.fragments.miscellaneous

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.nio.charset.StandardCharsets

class PlayIntegrityFix : SettingsPreferenceFragment() {

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.HERTZIFY

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { SettingsTheme { PlayIntegrityFixScreen() } }
    }

    companion object {
        const val PIF_CONFIG_KEY = "spoof_pif_config"
        const val PIF_CONFIG_NAME = "pif.json"
        const val PHOTOS_CONFIG_KEY = "spoofPhotos"

        private const val PIF_DEVICES_CACHE_KEY = "spoof_pif_devices_cache"
        private const val PIF_DEVICES_TIMESTAMP_KEY = "spoof_pif_devices_timestamp"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // TTL 24h

        private const val GMS_PACKAGE = "com.google.android.gms"
        private const val GMS_UNSTABLE_PACKAGE = "com.google.android.gms.unstable"
        private const val VENDING_PACKAGE = "com.android.vending"
        private const val PHOTOS_PACKAGE = "com.google.android.apps.photos"

        private const val GOOGLE_URL = "https://developer.android.com"
        private const val FLASH_URL = "https://flash.android.com"
        private const val FLASH_API = "https://content-flashstation-pa.googleapis.com/v1/builds"
        private const val PIXEL_BULLETIN_URL = "https://source.android.com/docs/security/bulletin/pixel"

        val ADVANCED_FLAGS: Map<String, Boolean> = linkedMapOf(
            "spoofBuild" to true,
            "spoofProps" to true,
            "spoofProvider" to false,
            "spoofSignature" to false,
            "spoofVendingBuild" to true,
            "spoofVendingSdk" to false
        )

        private val PRESERVED_KEYS = ADVANCED_FLAGS.keys + listOf(PHOTOS_CONFIG_KEY, "DEBUG", "verboseLogs")

        data class PifDevice(val product: String, val device: String, val model: String)

        private class PifException(message: String) : Exception(message)

        fun readConfig(context: Context): Map<String, String> {
            val content = Settings.Secure.getString(context.contentResolver, PIF_CONFIG_KEY)
                ?: return emptyMap()
            return runCatching {
                val json = JSONObject(content)
                json.keys().asSequence().associateWith { json.optString(it, "") }
            }.getOrDefault(emptyMap())
        }

        private fun writeConfig(context: Context, json: JSONObject?) {
            val text = json?.takeIf { it.length() > 0 }?.toString(2)
            Settings.Secure.putString(context.contentResolver, PIF_CONFIG_KEY, text)
        }

        private fun restartTargetPackages(context: Context, includePhotos: Boolean = false) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            buildList {
                add(GMS_UNSTABLE_PACKAGE)
                add(GMS_PACKAGE)
                add(VENDING_PACKAGE)
                if (includePhotos) add(PHOTOS_PACKAGE)
            }.forEach { runCatching { am.forceStopPackage(it) } }
        }

        fun isFlagOn(config: Map<String, String>, key: String): Boolean =
            config[key]?.let { it == "true" || it == "1" } ?: (ADVANCED_FLAGS[key] ?: false)

        fun updateConfig(context: Context, key: String, value: Any) {
            val existing = Settings.Secure.getString(context.contentResolver, PIF_CONFIG_KEY)
            val json = runCatching { JSONObject(existing ?: "") }.getOrDefault(JSONObject())
            json.put(key, value)
            writeConfig(context, json)
            restartTargetPackages(context, includePhotos = key == PHOTOS_CONFIG_KEY)
        }

        fun resetAdvancedFlags(context: Context) {
            val existing = Settings.Secure.getString(context.contentResolver, PIF_CONFIG_KEY)
            val json = runCatching { JSONObject(existing ?: "") }.getOrDefault(JSONObject())
            ADVANCED_FLAGS.forEach { (k, v) -> json.put(k, v.toString()) }
            writeConfig(context, json)
            restartTargetPackages(context)
        }

        fun importConfig(context: Context, uri: Uri) {
            val raw = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                .orEmpty()
            val json = normalizePifPayload(raw)
            val photos = readConfig(context)[PHOTOS_CONFIG_KEY]
            if (photos != null && !json.has(PHOTOS_CONFIG_KEY)) json.put(PHOTOS_CONFIG_KEY, photos)
            writeConfig(context, json)
            restartTargetPackages(context)
        }

        fun deleteConfig(context: Context) {
            val photos = readConfig(context)[PHOTOS_CONFIG_KEY]
            writeConfig(context, photos?.let { JSONObject().put(PHOTOS_CONFIG_KEY, it) })
            restartTargetPackages(context, includePhotos = true)
        }

        fun applyFetched(context: Context, fetched: JSONObject) {
            val existing = readConfig(context)
            PRESERVED_KEYS.forEach { key ->
                existing[key]?.let { if (!fetched.has(key)) fetched.put(key, it) }
            }
            writeConfig(context, fetched)
            restartTargetPackages(context)
        }

        private fun normalizePifPayload(raw: String): JSONObject {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return JSONObject()
            if (trimmed.startsWith("{")) return JSONObject(trimmed)
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
            return json
        }

        private fun isDeviceCacheValid(context: Context): Boolean {
            val lastFetch = Settings.Secure.getLong(context.contentResolver, PIF_DEVICES_TIMESTAMP_KEY, 0L)
            return (System.currentTimeMillis() - lastFetch) in 0 until CACHE_TTL_MS
        }

        private fun getCachedDevices(context: Context): List<PifDevice> {
            val cachedJson = Settings.Secure.getString(context.contentResolver, PIF_DEVICES_CACHE_KEY)
                ?: return emptyList()
            return runCatching {
                val array = JSONArray(cachedJson)
                List(array.length()) { i ->
                    val obj = array.getJSONObject(i)
                    PifDevice(
                        product = obj.getString("product"),
                        device = obj.getString("device"),
                        model = obj.getString("model")
                    )
                }
            }.getOrDefault(emptyList())
        }

        private fun saveCachedDevices(context: Context, devices: List<PifDevice>) {
            if (devices.isEmpty()) return
            runCatching {
                val array = JSONArray()
                devices.forEach { d ->
                    array.put(JSONObject().apply {
                        put("product", d.product)
                        put("device", d.device)
                        put("model", d.model)
                    })
                }
                Settings.Secure.putString(context.contentResolver, PIF_DEVICES_CACHE_KEY, array.toString())
                Settings.Secure.putLong(context.contentResolver, PIF_DEVICES_TIMESTAMP_KEY, System.currentTimeMillis())
            }
        }

        suspend fun fetchAvailableDevices(context: Context, forceRefresh: Boolean = false): List<PifDevice> = withContext(Dispatchers.IO) {
            if (!forceRefresh && isDeviceCacheValid(context)) {
                val cached = getCachedDevices(context)
                if (cached.isNotEmpty()) return@withContext cached
            }

            runCatching {
                val versionsHtml = URL("$GOOGLE_URL/about/versions").readText(StandardCharsets.UTF_8)
                val latestVersion = Regex("""(?:https://developer\.android\.com)?/about/versions/(\d+)""")
                    .findAll(versionsHtml)
                    .map { it.groupValues[1].toInt() }
                    .toSortedSet()
                    .maxOrNull()
                    ?: throw Exception("No versions found")

                val latestHtml = URL("$GOOGLE_URL/about/versions/$latestVersion").readText(StandardCharsets.UTF_8)
                val qprPath = Regex("""href="(/about/versions/$latestVersion/qpr(\d+)/download-ota)"""")
                    .findAll(latestHtml)
                    .map { it.groupValues[2].toInt() to it.groupValues[1] }
                    .maxByOrNull { it.first }
                    ?.second
                    ?: throw Exception("No QPR path found")

                val fiHtml = URL("$GOOGLE_URL$qprPath").readText(StandardCharsets.UTF_8)
                val devices = Regex("""<tr id="([^"]+)">\s*<td[^>]*>([^<]+)</td>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(fiHtml)
                    .map { PifDevice("${it.groupValues[1]}_beta", it.groupValues[1], it.groupValues[2].trim()) }
                    .toList()

                if (devices.isNotEmpty()) {
                    saveCachedDevices(context, devices)
                }
                devices
            }.getOrElse {
                getCachedDevices(context)
            }
        }

        suspend fun buildPif(context: Context, device: PifDevice): Result<JSONObject> = withContext(Dispatchers.IO) {
            fun fail(id: Int, vararg args: Any): Nothing = throw PifException(context.getString(id, *args))

            runCatching {
                val flashHtml = URL(FLASH_URL).readText(StandardCharsets.UTF_8)
                val apiKey = Regex("""AIza[0-9A-Za-z_-]{35}""").find(flashHtml)?.value
                    ?: fail(R.string.spoof_pif_error_no_apikey)

                val buildsJson = URL("$FLASH_API?product=${device.product}&key=$apiKey").openConnection().apply {
                    setRequestProperty("Referer", FLASH_URL)
                    setRequestProperty("X-Goog-Api-Key", apiKey)
                    connectTimeout = 15000
                    readTimeout = 15000
                }.getInputStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }

                val builds = JSONObject(buildsJson).optJSONArray("flashstationBuild")
                    ?: fail(R.string.spoof_pif_error_no_build_array)

                val (id, incremental, canaryId) = (builds.length() - 1 downTo 0).firstNotNullOfOrNull { i ->
                    val b = builds.optJSONObject(i) ?: return@firstNotNullOfOrNull null
                    val meta = b.optJSONObject("previewMetadata")?.takeIf { it.optBoolean("canary") }
                        ?: return@firstNotNullOfOrNull null
                    val rc = b.optString("releaseCandidateName")
                    val bid = b.optString("buildId")
                    val cid = meta.optString("id").takeIf { it.contains("canary-") }
                    if (rc.isEmpty() || bid.isEmpty()) null else Triple(rc, bid, cid)
                } ?: fail(R.string.spoof_pif_error_no_build, device.product)

                val canaryMonth = canaryId
                    ?.let { Regex("""canary-(\d{4})(\d{2})""").find(it) }
                    ?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }
                    ?: fail(R.string.spoof_pif_error_no_canary_month)

                val securityPatch = runCatching {
                    val bulletinHtml = URL(PIXEL_BULLETIN_URL).readText(StandardCharsets.UTF_8)
                    Regex("""<td>($canaryMonth-\d{2})</td>""").find(bulletinHtml)?.groupValues?.get(1)
                }.getOrNull() ?: "$canaryMonth-05"

                val initialSdkInt = when (device.device) {
                    "bluejay" -> "32"
                    "panther", "cheetah", "lynx", "felix", "tangorpro" -> "33"
                    "shiba", "husky", "akita", "tokay", "caiman", "komodo", "comet" -> "34"
                    "tegu", "stallion" -> "35"
                    "frankel", "blazer", "mustang", "rango" -> "36"
                    "cubs", "grizzly", "kodiak", "yogi" -> "37"
                    else -> "37"
                }

                JSONObject().apply {
                    put("MANUFACTURER", "Google")
                    put("MODEL", device.model)
                    put("PRODUCT", device.product)
                    put("DEVICE", device.device)
                    put("FINGERPRINT", "google/${device.product}/${device.device}:CANARY/$id/$incremental:user/release-keys")
                    put("SECURITY_PATCH", securityPatch)
                    put("DEVICE_INITIAL_SDK_INT", initialSdkInt)
                    ADVANCED_FLAGS.forEach { (k, v) -> put(k, v.toString()) }
                }
            }.recoverCatching {
                if (it is PifException) throw it
                fail(R.string.spoof_pif_error_fetch_failed, it.message.orEmpty())
            }
        }
    }
}

@Composable
fun PlayIntegrityFixScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeConfig by remember { mutableStateOf(PlayIntegrityFix.readConfig(context)) }
    var isFetching by remember { mutableStateOf(false) }
    var deviceList by remember { mutableStateOf<List<PlayIntegrityFix.Companion.PifDevice>?>(null) }
    var showConfigDetails by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val hasConfig = activeConfig.keys.any { it != "DEBUG" && it != "verboseLogs" && !it.startsWith("spoof") }
    val isSpoofPhotos = PlayIntegrityFix.isFlagOn(activeConfig, PlayIntegrityFix.PHOTOS_CONFIG_KEY)

    fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(context, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    fun refresh() {
        activeConfig = PlayIntegrityFix.readConfig(context)
    }

    fun toggleKey(key: String, checked: Boolean) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { PlayIntegrityFix.updateConfig(context, key, checked.toString()) }
            }.onFailure { toast(it.message.orEmpty(), long = true) }
            refresh()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { PlayIntegrityFix.importConfig(context, uri) } }
                .onSuccess { toast(context.getString(R.string.spoof_pif_imported_success)) }
                .onFailure { toast(it.message.orEmpty(), long = true) }
            refresh()
        }
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(rememberNestedScrollInteropConnection())
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            item {
                val model = activeConfig["MODEL"].orEmpty()
                val device = activeConfig["DEVICE"].orEmpty()
                val subtitle = when {
                    !hasConfig -> stringResource(R.string.spoof_pif_no_config)
                    model.isNotEmpty() && device.isNotEmpty() -> "$model ($device)"
                    model.isNotEmpty() -> model
                    device.isNotEmpty() -> device
                    else -> stringResource(R.string.spoof_pif_active)
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isFetching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.5.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Fingerprint,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.spoof_pif_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    isFetching = true
                                    scope.launch {
                                        val devices = PlayIntegrityFix.fetchAvailableDevices(context)
                                        isFetching = false
                                        if (devices.isEmpty()) toast(context.getString(R.string.spoof_pif_no_canary_devices))
                                        else deviceList = devices
                                    }
                                },
                                enabled = !isFetching,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.spoof_pif_fetch_title),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("*/*")) },
                                enabled = !isFetching,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.spoof_pif_import_title),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(
                                onClick = { showDeleteConfirm = true },
                                enabled = hasConfig && !isFetching,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.spoof_pif_delete_title),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(10.dp)) }
            item { SectionTitle(stringResource(R.string.spoof_pif_category)) }

            item {
                PrefItem(
                    title = stringResource(R.string.spoof_pif_properties),
                    summary = if (hasConfig) activeConfig["MODEL"] ?: stringResource(R.string.spoof_pif_unknown)
                    else stringResource(R.string.spoof_pif_no_config),
                    icon = Icons.Default.Fingerprint,
                    enabled = hasConfig,
                    onClick = { showConfigDetails = true }
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { SectionTitle(stringResource(R.string.spoof_pif_options_category)) }

            item {
                PrefItem(
                    title = stringResource(R.string.spoof_pif_photos_title),
                    summary = stringResource(R.string.spoof_pif_photos_summary),
                    icon = Icons.Default.Image,
                    highlight = isSpoofPhotos,
                    trailing = {
                        SwitchWithCheck(
                            checked = isSpoofPhotos,
                            onCheckedChange = { toggleKey(PlayIntegrityFix.PHOTOS_CONFIG_KEY, it) }
                        )
                    },
                    onClick = { toggleKey(PlayIntegrityFix.PHOTOS_CONFIG_KEY, !isSpoofPhotos) }
                )
            }

            if (hasConfig) {
                item { Spacer(Modifier.height(8.dp)) }
                item { SectionTitle(stringResource(R.string.spoof_pif_advanced)) }

                items(PlayIntegrityFix.ADVANCED_FLAGS.keys.toList()) { key ->
                    val on = PlayIntegrityFix.isFlagOn(activeConfig, key)
                    val (titleRes, summaryRes) = getAdvancedFlagStringRes(key)

                    PrefItem(
                        title = stringResource(titleRes),
                        summary = stringResource(summaryRes),
                        highlight = on,
                        trailing = {
                            SwitchWithCheck(
                                checked = on,
                                onCheckedChange = { toggleKey(key, it) }
                            )
                        },
                        onClick = { toggleKey(key, !on) }
                    )
                }

                item {
                    PrefItem(
                        title = stringResource(R.string.spoof_pif_reset_defaults),
                        summary = stringResource(R.string.spoof_pif_reset_defaults_summary),
                        icon = Icons.Default.Refresh,
                        onClick = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { PlayIntegrityFix.resetAdvancedFlags(context) } }
                                    .onSuccess { toast(context.getString(R.string.spoof_pif_reset_done)) }
                                    .onFailure { toast(it.message.orEmpty(), long = true) }
                                refresh()
                            }
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    deviceList?.let { devices ->
        Sheet(onDismiss = { deviceList = null }, title = stringResource(R.string.spoof_pif_select_device)) {
            items(devices) { device ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            deviceList = null
                            isFetching = true
                            scope.launch {
                                PlayIntegrityFix.buildPif(context, device)
                                    .onSuccess {
                                        withContext(Dispatchers.IO) { PlayIntegrityFix.applyFetched(context, it) }
                                        toast(context.getString(R.string.spoof_pif_fetched_model, device.model))
                                        refresh()
                                    }
                                    .onFailure { toast(it.message.orEmpty(), long = true) }
                                isFetching = false
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = device.model,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Codename: ${device.device} (${device.product})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showConfigDetails) {
        Sheet(onDismiss = { showConfigDetails = false }, title = stringResource(R.string.spoof_pif_config_details)) {
            val important = listOf(
                "MANUFACTURER", "MODEL", "PRODUCT", "DEVICE",
                "FINGERPRINT", "SECURITY_PATCH", "DEVICE_INITIAL_SDK_INT"
            )
            val keys = activeConfig.keys
                .filter { !it.startsWith("spoof") && it != "DEBUG" && it != "verboseLogs" }
                .sortedBy { k -> important.indexOf(k).let { if (it < 0) 99 else it } }
            items(keys) { key ->
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = activeConfig[key].orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.spoof_pif_delete_confirm_title)) },
            text = { Text(stringResource(R.string.spoof_pif_delete_confirm_message)) },
            confirmButton = {
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { PlayIntegrityFix.deleteConfig(context) } }
                            .onSuccess { toast(context.getString(R.string.spoof_pif_delete_success)) }
                            .onFailure { toast(it.message.orEmpty(), long = true) }
                        showDeleteConfirm = false
                        refresh()
                    }
                }) {
                    Text(stringResource(R.string.spoof_pif_delete_button))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

private fun getAdvancedFlagStringRes(key: String): Pair<Int, Int> {
    return when (key) {
        "spoofBuild" -> R.string.spoof_pif_flag_build_title to R.string.spoof_pif_flag_build_summary
        "spoofProps" -> R.string.spoof_pif_flag_props_title to R.string.spoof_pif_flag_props_summary
        "spoofProvider" -> R.string.spoof_pif_flag_provider_title to R.string.spoof_pif_flag_provider_summary
        "spoofSignature" -> R.string.spoof_pif_flag_signature_title to R.string.spoof_pif_flag_signature_summary
        "spoofVendingBuild" -> R.string.spoof_pif_flag_vending_build_title to R.string.spoof_pif_flag_vending_build_summary
        "spoofVendingSdk" -> R.string.spoof_pif_flag_vending_sdk_title to R.string.spoof_pif_flag_vending_sdk_summary
        else -> R.string.spoof_pif_unknown to R.string.spoof_pif_unknown
    }
}

@Composable
private fun SwitchWithCheck(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        thumbContent = if (checked) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            }
        } else null
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun Sheet(onDismiss: () -> Unit, title: String, content: LazyListScope.() -> Unit) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).animateContentSize().padding(bottom = 24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )
            LazyColumn(Modifier.weight(1f, fill = false), content = content)
        }
    }
}

@Composable
private fun PrefItem(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    highlight: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.45f else 0.2f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                if (summary != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
                }
            }
            trailing?.invoke()
        }
    }
}
