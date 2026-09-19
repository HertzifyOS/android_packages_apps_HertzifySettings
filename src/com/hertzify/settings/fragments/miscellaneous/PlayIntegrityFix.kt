@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.hertzify.settings.fragments.miscellaneous

import android.app.ActivityManager
import android.content.Context
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
import androidx.compose.material.icons.filled.Fingerprint
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
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
        const val PHOTOS_CONFIG_KEY = "spoof_pif_photos"
        const val PHOTOS_PACKAGE = "com.google.android.apps.photos"

        private const val GOOGLE_URL = "https://developer.android.com"
        private const val FLASH_URL = "https://flash.android.com"
        private const val FLASH_API = "https://content-flashstation-pa.googleapis.com/v1/builds"
        private const val PIXEL_BULLETIN_URL = "https://source.android.com/docs/security/bulletin/pixel"
        private val PIF_PACKAGES = arrayOf(
            "com.google.android.gms.unstable", "com.google.android.gms", "com.android.vending"
        )

        data class PifDevice(val product: String, val device: String, val model: String)

        private class PifException(message: String) : Exception(message)

        private fun String.fetch() = URL(this).readText()

        private fun killPackages(context: Context, vararg packages: String) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            packages.forEach { runCatching { am.forceStopPackage(it) } }
        }

        fun applyPif(context: Context, json: JSONObject?) {
            Settings.Secure.putString(context.contentResolver, PIF_CONFIG_KEY, json?.toString(2))
            killPackages(context, *PIF_PACKAGES)
        }

        fun isPhotosSpoofed(context: Context): Boolean =
            Settings.Secure.getInt(context.contentResolver, PHOTOS_CONFIG_KEY, 1) != 0

        fun setPhotosSpoofed(context: Context, enabled: Boolean) {
            Settings.Secure.putInt(context.contentResolver, PHOTOS_CONFIG_KEY, if (enabled) 1 else 0)
            killPackages(context, PHOTOS_PACKAGE)
        }

        fun readConfig(context: Context): Map<String, String> = runCatching {
            val json = JSONObject(Settings.Secure.getString(context.contentResolver, PIF_CONFIG_KEY))
            json.keys().asSequence().associateWith { json.optString(it) }
        }.getOrDefault(emptyMap())

        suspend fun fetchAvailableDevices(): List<PifDevice> = withContext(Dispatchers.IO) {
            runCatching {
                val latest = Regex("""https://developer\.android\.com/about/versions/(\d+)""")
                    .findAll("$GOOGLE_URL/about/versions".fetch())
                    .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
                    ?: return@runCatching emptyList()

                val qprPath = Regex("""href="(/about/versions/$latest/qpr(\d+)/download-ota)"""")
                    .findAll("$GOOGLE_URL/about/versions/$latest".fetch())
                    .maxByOrNull { it.groupValues[2].toIntOrNull() ?: 0 }?.groupValues?.get(1)
                    ?: return@runCatching emptyList()

                Regex("""<tr id="([^"]+)">\s*<td[^>]*>([^<]+)</td>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll("$GOOGLE_URL$qprPath".fetch())
                    .map { PifDevice("${it.groupValues[1]}_beta", it.groupValues[1], it.groupValues[2].trim()) }
                    .toList()
            }.getOrDefault(emptyList())
        }

        suspend fun buildPif(context: Context, device: PifDevice): Result<JSONObject> = withContext(Dispatchers.IO) {
            fun fail(id: Int, vararg args: Any): Nothing = throw PifException(context.getString(id, *args))

            runCatching {
                val apiKey = Regex("""AIza[0-9A-Za-z_-]{35}""").find(FLASH_URL.fetch())?.value
                    ?: fail(R.string.spoof_pif_error_no_apikey)

                val buildsJson = URL("$FLASH_API?product=${device.product}&key=$apiKey").openConnection().apply {
                    setRequestProperty("Referer", FLASH_URL)
                    setRequestProperty("X-Goog-Api-Key", apiKey)
                    connectTimeout = 15000
                    readTimeout = 15000
                }.getInputStream().use { it.readBytes().decodeToString() }

                val builds = JSONObject(buildsJson).optJSONArray("flashstationBuild")
                    ?: fail(R.string.spoof_pif_error_no_build_array)

                val (id, incremental, canaryId) = (builds.length() - 1 downTo 0).firstNotNullOfOrNull { i ->
                    val b = builds.optJSONObject(i) ?: return@firstNotNullOfOrNull null
                    val meta = b.optJSONObject("previewMetadata")?.takeIf { it.optBoolean("canary") }
                        ?: return@firstNotNullOfOrNull null
                    val rc = b.optString("releaseCandidateName")
                    val bid = b.optString("buildId")
                    if (rc.isEmpty() || bid.isEmpty()) null else Triple(rc, bid, meta.optString("id"))
                } ?: fail(R.string.spoof_pif_error_no_build, device.product)

                val canaryMonth = Regex("""canary-(\d{4})(\d{2})""").find(canaryId)
                    ?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }
                    ?: fail(R.string.spoof_pif_error_no_canary_month)

                val securityPatch = runCatching {
                    Regex("""<td>($canaryMonth-\d{2})</td>""").find(PIXEL_BULLETIN_URL.fetch())?.groupValues?.get(1)
                }.getOrNull() ?: "$canaryMonth-05"

                JSONObject().apply {
                    put("MANUFACTURER", "Google")
                    put("MODEL", device.model)
                    put("PRODUCT", device.product)
                    put("DEVICE", device.device)
                    put("FINGERPRINT", "google/${device.product}/${device.device}:CANARY/$id/$incremental:user/release-keys")
                    put("SECURITY_PATCH", securityPatch)
                    put("DEVICE_INITIAL_SDK_INT", "32")
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
    var isPhotosSpoofed by remember { mutableStateOf(PlayIntegrityFix.isPhotosSpoofed(context)) }
    var isFetching by remember { mutableStateOf(false) }
    var deviceList by remember { mutableStateOf<List<PlayIntegrityFix.Companion.PifDevice>?>(null) }
    var showConfigDetails by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val hasConfig = activeConfig.keys.any { it != "DEBUG" && !it.startsWith("spoof") }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    fun refresh() {
        activeConfig = PlayIntegrityFix.readConfig(context)
        isPhotosSpoofed = PlayIntegrityFix.isPhotosSpoofed(context)
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: "{}"
                    val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                    PlayIntegrityFix.applyPif(context, json)
                }.isSuccess
            }
            if (ok) {
                toast(context.getString(R.string.spoof_pif_imported_success))
                refresh()
            }
        }
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
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
                HeaderCard(stringResource(R.string.spoof_pif_title), subtitle)
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionTitle(stringResource(R.string.spoof_pif_category)) }

            item {
                PrefItem(
                    title = stringResource(R.string.spoof_pif_fetch_title),
                    summary = stringResource(if (isFetching) R.string.spoof_pif_fetching else R.string.spoof_pif_fetch_summary),
                    enabled = !isFetching,
                    onClick = {
                        isFetching = true
                        scope.launch {
                            val devices = PlayIntegrityFix.fetchAvailableDevices()
                            isFetching = false
                            if (devices.isEmpty()) toast(context.getString(R.string.spoof_pif_no_canary_devices))
                            else deviceList = devices
                        }
                    }
                )
            }
            item {
                PrefItem(
                    title = stringResource(R.string.spoof_pif_import_title),
                    summary = stringResource(R.string.spoof_pif_import_summary),
                    onClick = { importLauncher.launch(arrayOf("*/*")) }
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
                    summary = if (hasConfig) activeConfig["MODEL"] ?: stringResource(R.string.spoof_pif_unknown)
                    else stringResource(R.string.spoof_pif_no_config),
                    enabled = hasConfig,
                    onClick = { showConfigDetails = true }
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { SectionTitle(stringResource(R.string.spoof_pif_options_category)) }

            item {
                val onToggle = { checked: Boolean ->
                    isPhotosSpoofed = checked
                    scope.launch(Dispatchers.IO) { PlayIntegrityFix.setPhotosSpoofed(context, checked) }
                    Unit
                }
                PrefItem(
                    title = stringResource(R.string.spoof_pif_photos_title),
                    summary = stringResource(R.string.spoof_pif_photos_summary),
                    highlight = isPhotosSpoofed,
                    trailing = { Switch(checked = isPhotosSpoofed, onCheckedChange = onToggle) },
                    onClick = { onToggle(!isPhotosSpoofed) }
                )
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    deviceList?.let { devices ->
        Sheet(onDismiss = { deviceList = null }, title = stringResource(R.string.spoof_pif_select_device)) {
            items(devices) { device ->
                Text(
                    text = device.model,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            deviceList = null
                            isFetching = true
                            scope.launch {
                                PlayIntegrityFix.buildPif(context, device)
                                    .onSuccess {
                                        PlayIntegrityFix.applyPif(context, it)
                                        toast(context.getString(R.string.spoof_pif_fetched_model, device.model))
                                        refresh()
                                    }
                                    .onFailure { toast(it.message.orEmpty()) }
                                isFetching = false
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                )
            }
        }
    }

    if (showConfigDetails) {
        Sheet(onDismiss = { showConfigDetails = false }, title = stringResource(R.string.spoof_pif_config_details)) {
            val important = listOf("MANUFACTURER", "MODEL", "PRODUCT", "DEVICE", "FINGERPRINT", "SECURITY_PATCH")
            val keys = activeConfig.keys
                .filter { !it.startsWith("spoof") }
                .sortedBy { k -> important.indexOf(k).let { if (it < 0) 99 else it } }
            items(keys) { key ->
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(2.dp))
                    Text(activeConfig[key].orEmpty(), style = MaterialTheme.typography.bodyLarge)
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
                Button(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { PlayIntegrityFix.applyPif(context, null) }
                        showDeleteConfirm = false
                        toast(context.getString(R.string.spoof_pif_delete_success))
                        refresh()
                    }
                }) { Text(stringResource(R.string.spoof_pif_delete_button)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun HeaderCard(title: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)
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
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
            LazyColumn(Modifier.weight(1f, fill = false), content = content)
        }
    }
}

@Composable
private fun PrefItem(
    title: String,
    summary: String,
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
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                Spacer(Modifier.height(2.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
            trailing?.invoke()
        }
    }
}