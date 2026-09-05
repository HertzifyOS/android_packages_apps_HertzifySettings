@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.hertzify.settings.fragments.miscellaneous

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.nio.charset.StandardCharsets

class PlayIntegrityFix : SettingsPreferenceFragment() {

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.HERTZIFY

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SettingsTheme {
                PlayIntegrityFixScreen()
            }
        }
    }

    companion object {
        const val TAG = "PlayIntegrityFix"
        const val PIF_CONFIG_KEY = "spoof_pif_config"
        const val PHOTOS_CONFIG_KEY = "spoof_pif_photos"
        const val GOOGLE_URL = "https://developer.android.com"
        const val FLASH_URL = "https://flash.android.com"
        const val FLASH_API = "https://content-flashstation-pa.googleapis.com/v1/builds"
        const val PIXEL_BULLETIN_URL = "https://source.android.com/docs/security/bulletin/pixel"
        const val DROIDGUARD_PACKAGE = "com.google.android.gms.unstable"
        const val GMS_PACKAGE = "com.google.android.gms"
        const val VENDING_PACKAGE = "com.android.vending"
        const val PHOTOS_PACKAGE = "com.google.android.apps.photos"

        sealed class PifFetchResult {
            data class Success(val model: String, val pifData: JSONObject) : PifFetchResult()
            data class Error(val message: String) : PifFetchResult()
        }

        data class PifDevice(val product: String, val device: String, val model: String)

        fun killPackages(context: Context, vararg packages: String) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            packages.forEach {
                try { am.forceStopPackage(it) } catch (_: Exception) {}
            }
        }

        suspend fun fetchAvailableDevices(): List<PifDevice> = withContext(Dispatchers.IO) {
            try {
                val versionsHtml = URL("$GOOGLE_URL/about/versions").readText(StandardCharsets.UTF_8)
                val latestVersion = Regex("""https://developer\.android\.com/about/versions/(\d+)""")
                    .findAll(versionsHtml).mapNotNull { it.groupValues[1].toIntOrNull() }
                    .toSortedSet().maxOrNull() ?: return@withContext emptyList()

                val latestHtml = URL("$GOOGLE_URL/about/versions/$latestVersion").readText(StandardCharsets.UTF_8)
                val qprPath = Regex("""href="(/about/versions/$latestVersion/qpr(\d+)/download-ota)"""")
                    .findAll(latestHtml).map { (it.groupValues[2].toIntOrNull() ?: 0) to it.groupValues[1] }
                    .maxByOrNull { it.first }?.second ?: return@withContext emptyList()

                val fiHtml = URL("$GOOGLE_URL$qprPath").readText(StandardCharsets.UTF_8)
                Regex("""<tr id="([^"]+)">\s*<td[^>]*>([^<]+)</td>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(fiHtml).map {
                        PifDevice("${it.groupValues[1]}_beta", it.groupValues[1], it.groupValues[2].trim())
                    }.toList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        suspend fun buildPifFromDevice(context: Context, device: PifDevice): PifFetchResult = withContext(Dispatchers.IO) {
            try {
                val flashHtml = URL(FLASH_URL).readText(StandardCharsets.UTF_8)
                val apiKey = Regex("""AIza[0-9A-Za-z_-]{35}""").find(flashHtml)?.value
                    ?: return@withContext PifFetchResult.Error(context.getString(R.string.spoof_pif_error_no_apikey))

                val buildsUrl = "$FLASH_API?product=${device.product}&key=$apiKey"
                val buildsConn = URL(buildsUrl).openConnection().apply {
                    setRequestProperty("Referer", FLASH_URL)
                    setRequestProperty("X-Goog-Api-Key", apiKey)
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val buildsJson = buildsConn.getInputStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }

                val root = JSONObject(buildsJson)
                val buildsArray = root.optJSONArray("flashstationBuild")
                    ?: return@withContext PifFetchResult.Error(context.getString(R.string.spoof_pif_error_no_build_array))

                var id: String? = null
                var incremental: String? = null
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
                    canaryId = meta.optString("id").takeIf { it.contains("canary-") }
                    break
                }

                if (id == null || incremental == null) {
                    return@withContext PifFetchResult.Error(context.getString(R.string.spoof_pif_error_no_build, device.product))
                }

                val fingerprint = "google/${device.product}/${device.device}:CANARY/$id/$incremental:user/release-keys"

                val canaryMonth = canaryId?.let {
                    Regex("""canary-(\d{4})(\d{2})""").find(it)?.let { m ->
                        "${m.groupValues[1]}-${m.groupValues[2]}"
                    }
                } ?: return@withContext PifFetchResult.Error(context.getString(R.string.spoof_pif_error_no_canary_month))

                val securityPatch = try {
                    val bulletinHtml = URL(PIXEL_BULLETIN_URL).readText(StandardCharsets.UTF_8)
                    Regex("""<td>($canaryMonth-\d{2})</td>""").find(bulletinHtml)?.groupValues?.get(1)
                        ?: "$canaryMonth-05"
                } catch (e: Exception) {
                    "$canaryMonth-05"
                }

                val pifJson = JSONObject().apply {
                    put("MANUFACTURER", "Google")
                    put("MODEL", device.model)
                    put("PRODUCT", device.product)
                    put("DEVICE", device.device)
                    put("FINGERPRINT", fingerprint)
                    put("SECURITY_PATCH", securityPatch)
                    put("DEVICE_INITIAL_SDK_INT", "32")
                }

                PifFetchResult.Success(device.model, pifJson)
            } catch (e: Exception) {
                PifFetchResult.Error(context.getString(R.string.spoof_pif_error_fetch_failed, e.message ?: ""))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayIntegrityFixScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var activeConfig by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    
    var isPhotosSpoofed by remember { 
        mutableStateOf(Settings.Secure.getInt(context.contentResolver, PlayIntegrityFix.PHOTOS_CONFIG_KEY, 1) != 0) 
    }
    
    var isFetching by remember { mutableStateOf(false) }
    var showDeviceSelector by remember { mutableStateOf<List<PlayIntegrityFix.Companion.PifDevice>?>(null) }
    var showConfigDetails by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun refreshState() {
        val content = Settings.Secure.getString(context.contentResolver, PlayIntegrityFix.PIF_CONFIG_KEY)
        if (!content.isNullOrEmpty()) {
            try {
                val json = JSONObject(content)
                val map = mutableMapOf<String, String>()
                json.keys().forEach { map[it] = json.optString(it, "") }
                activeConfig = map
            } catch (e: Exception) {
                activeConfig = emptyMap()
            }
        } else {
            activeConfig = emptyMap()
        }
    }

    LaunchedEffect(Unit) { refreshState() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val raw = context.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(StandardCharsets.UTF_8)
                        } ?: "{}"
                        
                        val json = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }

                        Settings.Secure.putString(context.contentResolver, PlayIntegrityFix.PIF_CONFIG_KEY, json.toString(2))
                        PlayIntegrityFix.killPackages(context, PlayIntegrityFix.DROIDGUARD_PACKAGE, PlayIntegrityFix.GMS_PACKAGE, PlayIntegrityFix.VENDING_PACKAGE)
                        
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.spoof_pif_imported_success), Toast.LENGTH_SHORT).show()
                            refreshState()
                        }
                    } catch (e: Exception) {}
                }
            }
        }
    }

    val hasConfig = activeConfig.keys.any { it != "DEBUG" && !it.startsWith("spoof") }

    Scaffold(containerColor = Color.Transparent) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Dashboard Card Header
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceBright
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Play Integrity Fix",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (hasConfig) 
                                    activeConfig["MODEL"] ?: "Active" 
                                else 
                                    stringResource(R.string.spoof_pif_no_config),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                Text(
                    text = stringResource(R.string.spoof_pif_category),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)
                )
            }

            item {
                PrefItem(
                    title = stringResource(R.string.spoof_pif_fetch_title),
                    summary = if (isFetching) stringResource(R.string.spoof_pif_fetching) else stringResource(R.string.spoof_pif_fetch_summary),
                    enabled = !isFetching,
                    onClick = {
                        isFetching = true
                        scope.launch {
                            val devices = PlayIntegrityFix.fetchAvailableDevices()
                            isFetching = false
                            if (devices.isEmpty()) {
                                Toast.makeText(context, context.getString(R.string.spoof_pif_no_canary_devices), Toast.LENGTH_SHORT).show()
                            } else {
                                showDeviceSelector = devices
                            }
                        }
                    }
                )
            }

            item {
                PrefItem(
                    title = stringResource(R.string.spoof_pif_import_title),
                    summary = stringResource(R.string.spoof_pif_import_summary),
                    onClick = {
                        importLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        })
                    }
                )
            }

            item {
                PrefItem(
                    title = stringResource(R.string.spoof_pif_delete_title),
                    summary = stringResource(R.string.spoof_pif_delete_summary),
                    enabled = hasConfig,
                    onClick = { showDeleteConfirm = true }
                )
            }

            item {
                PrefItem(
                    title = stringResource(R.string.spoof_pif_properties),
                    summary = if (hasConfig) activeConfig["MODEL"] ?: "Unknown" else stringResource(R.string.spoof_pif_no_config),
                    enabled = hasConfig,
                    onClick = { showConfigDetails = true }
                )
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                Text(
                    text = stringResource(R.string.spoof_pif_options_category),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)
                )
            }

            item {
                PrefSwitchItem(
                    title = stringResource(R.string.spoof_pif_photos_title),
                    summary = stringResource(R.string.spoof_pif_photos_summary),
                    checked = isPhotosSpoofed,
                    onCheckedChange = { checked ->
                        isPhotosSpoofed = checked
                        scope.launch(Dispatchers.IO) {
                            Settings.Secure.putInt(context.contentResolver, PlayIntegrityFix.PHOTOS_CONFIG_KEY, if (checked) 1 else 0)
                            PlayIntegrityFix.killPackages(context, PlayIntegrityFix.PHOTOS_PACKAGE)
                        }
                    }
                )
            }
            
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    showDeviceSelector?.let { devices ->
        ModalBottomSheet(onDismissRequest = { showDeviceSelector = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.spoof_pif_select_device),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
                LazyColumn {
                    items(devices) { device ->
                        Text(
                            text = device.model,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showDeviceSelector = null
                                    isFetching = true
                                    scope.launch {
                                        val result = PlayIntegrityFix.buildPifFromDevice(context, device)
                                        if (result is PlayIntegrityFix.Companion.PifFetchResult.Success) {
                                            Settings.Secure.putString(context.contentResolver, PlayIntegrityFix.PIF_CONFIG_KEY, result.pifData.toString(2))
                                            PlayIntegrityFix.killPackages(context, PlayIntegrityFix.DROIDGUARD_PACKAGE, PlayIntegrityFix.GMS_PACKAGE, PlayIntegrityFix.VENDING_PACKAGE)
                                            Toast.makeText(context, context.getString(R.string.spoof_pif_fetched_model, result.model), Toast.LENGTH_SHORT).show()
                                            refreshState()
                                        }
                                        isFetching = false
                                    }
                                }
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }

    if (showConfigDetails) {
        AlertDialog(
            onDismissRequest = { showConfigDetails = false },
            title = { Text(stringResource(R.string.spoof_pif_config_details)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    val importantKeys = listOf("MANUFACTURER", "MODEL", "PRODUCT", "DEVICE", "FINGERPRINT", "SECURITY_PATCH")
                    val sortedKeys = activeConfig.keys.sortedBy { importantKeys.indexOf(it).takeIf { idx -> idx >= 0 } ?: 99 }

                    sortedKeys.forEach { key ->
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(activeConfig[key] ?: "", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConfigDetails = false }) { Text("Close") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.spoof_pif_delete_confirm_title)) },
            text = { Text(stringResource(R.string.spoof_pif_delete_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            Settings.Secure.putString(context.contentResolver, PlayIntegrityFix.PIF_CONFIG_KEY, null)
                            PlayIntegrityFix.killPackages(context, PlayIntegrityFix.DROIDGUARD_PACKAGE, PlayIntegrityFix.GMS_PACKAGE, PlayIntegrityFix.VENDING_PACKAGE)
                            withContext(Dispatchers.Main) {
                                showDeleteConfirm = false
                                Toast.makeText(context, context.getString(R.string.spoof_pif_delete_success), Toast.LENGTH_SHORT).show()
                                refreshState()
                            }
                        }
                    }
                ) { Text(stringResource(R.string.spoof_pif_delete_button)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PrefItem(title: String, summary: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.45f else 0.2f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title, 
                style = MaterialTheme.typography.titleMedium, 
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary, 
                style = MaterialTheme.typography.bodyMedium, 
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f)
            )
        }
    }
}

@Composable
private fun PrefSwitchItem(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        color = if (checked)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = title, 
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary, 
                    style = MaterialTheme.typography.bodyMedium, 
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked, 
                onCheckedChange = onCheckedChange
            )
        }
    }
}