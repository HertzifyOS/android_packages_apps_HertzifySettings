@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.hertzify.settings.fragments.miscellaneous

import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.viewinterop.AndroidView
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

private fun Context.getSecure(key: String): String? = Settings.Secure.getString(contentResolver, key)

private fun Context.putSecure(key: String, value: String?) {
    Settings.Secure.putString(contentResolver, key, value)
}

data class TargetApp(val info: android.content.pm.ApplicationInfo, val label: String, val isSystem: Boolean) {
    val pkg: String get() = info.packageName
}

data class TargetStats(val total: Int = 0, val auto: Int = 0, val leaf: Int = 0, val cert: Int = 0) {
    fun formatSummary(context: Context): String =
        if (total > 0) {
            context.getString(R.string.spoof_ts_target_summary_format, total, auto, leaf, cert)
        } else {
            ""
        }
}

class TrickyStore : SettingsPreferenceFragment() {

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.HERTZIFY

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { SettingsTheme { TrickyStoreScreen() } }
    }

    enum class TargetMode(val symbol: String, @StringRes val labelRes: Int) {
        AUTO("", R.string.spoof_ts_target_mode_auto),
        LEAF_HACK("?", R.string.spoof_ts_target_mode_leaf),
        CERT_GEN("!", R.string.spoof_ts_target_mode_cert);

        companion object {
            fun fromLine(line: String): Pair<String, TargetMode> {
                val mode = entries.firstOrNull { it.symbol.isNotEmpty() && line.endsWith(it.symbol) } ?: AUTO
                return line.removeSuffix(mode.symbol) to mode
            }
        }
    }

    companion object {
        const val TS_KEYBOX_KEY = "spoof_trickystore_keybox"
        const val TS_KEYBOX_NAME_KEY = "spoof_trickystore_keybox_name"
        const val TS_TARGET_KEY = "spoof_trickystore_target"

        private const val TARGET_CACHE_KEY = "spoof_trickystore_target_cache"
        private const val TARGET_TIMESTAMP_KEY = "spoof_trickystore_target_timestamp"
        private const val TARGET_CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        private const val TARGET_LIST_URL = "https://raw.githubusercontent.com/HertzifyOS/OTA/refs/heads/main/data/trickystore/target.txt"

        private val KILL_PACKAGES = listOf(
            "com.google.android.gms.unstable", "com.google.android.gms", "com.android.vending"
        )

        private val EXCLUDED_SUFFIXES = listOf(
            ".auto_generated", ".appsearch", ".backup", ".carrier",
            ".cellbroadcast", ".cts", ".federated", ".ims", ".overlay",
            ".qti", ".qualcomm", ".resources", ".systemui.clocks",
            ".systemui.plugin", ".theme", ".iconpack",
        )

        fun killPackages(context: Context) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            KILL_PACKAGES.forEach { runCatching { am.forceStopPackage(it) } }
        }

        private fun getOverlayPackages(context: Context): Set<String> = runCatching {
            val om = context.getSystemService(Context.OVERLAY_SERVICE) as android.content.om.OverlayManager
            val user = Process.myUserHandle()
            listOf("android", "com.android.systemui", "com.android.settings", "com.android.launcher3")
                .flatMap { om.getOverlayInfosForTarget(it, user) }
                .map { it.packageName }
                .toSet()
        }.getOrDefault(emptySet())

        fun readTargets(context: Context): Map<String, TargetMode> =
            parseTargets(context.getSecure(TS_TARGET_KEY).orEmpty())

        fun saveTargets(context: Context, targets: Map<String, TargetMode>?) {
            context.putSecure(TS_TARGET_KEY, targets?.map { "${it.key}${it.value.symbol}" }?.joinToString("\n"))
        }

        fun parseTargets(raw: String): Map<String, TargetMode> =
            raw.lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { TargetMode.fromLine(it.trim()) }

        fun calculateStats(targets: Map<String, TargetMode>): TargetStats =
            TargetStats(
                total = targets.size,
                auto = targets.values.count { it == TargetMode.AUTO },
                leaf = targets.values.count { it == TargetMode.LEAF_HACK },
                cert = targets.values.count { it == TargetMode.CERT_GEN }
            )

        private fun isTargetCacheValid(context: Context): Boolean {
            val lastFetch = Settings.Secure.getLong(context.contentResolver, TARGET_TIMESTAMP_KEY, 0L)
            return (System.currentTimeMillis() - lastFetch) in 0 until TARGET_CACHE_TTL_MS
        }

        private fun getCachedTargetRaw(context: Context): String =
            context.getSecure(TARGET_CACHE_KEY).orEmpty()

        private fun saveCachedTargetRaw(context: Context, raw: String) {
            context.putSecure(TARGET_CACHE_KEY, raw)
            Settings.Secure.putLong(context.contentResolver, TARGET_TIMESTAMP_KEY, System.currentTimeMillis())
        }

        suspend fun fetchTargetList(context: Context, forceRefresh: Boolean = false): Map<String, TargetMode> =
            withContext(Dispatchers.IO) {
                if (!forceRefresh && isTargetCacheValid(context)) {
                    val cached = getCachedTargetRaw(context)
                    if (cached.isNotBlank()) return@withContext parseTargets(cached)
                }

                runCatching {
                    val url = URI(TARGET_LIST_URL).toURL()
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                    }
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    if (text.isNotBlank()) {
                        saveCachedTargetRaw(context, text)
                        parseTargets(text)
                    } else {
                        throw Exception("Empty response")
                    }
                }.getOrElse {
                    parseTargets(getCachedTargetRaw(context))
                }
            }

        suspend fun loadTargetManagerData(context: Context): Pair<List<TargetApp>, Map<String, TargetMode>> =
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val overlays = getOverlayPackages(context)
                val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                    .filter { app ->
                        val pkg = app.packageName
                        pkg !in overlays && EXCLUDED_SUFFIXES.none { suffix -> pkg.endsWith(suffix) }
                    }
                    .map { appInfo ->
                        TargetApp(appInfo, appInfo.loadLabel(pm).toString(), (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                    }
                apps to readTargets(context)
            }

        fun getFileNameFromUri(context: Context, uri: Uri): String {
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                }
            }.getOrNull()
            return name ?: uri.path?.substringAfterLast('/') ?: "keybox.xml"
        }
    }
}

@Composable
fun TrickyStoreScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasKeybox by remember { mutableStateOf(false) }
    var keyboxName by remember { mutableStateOf("") }
    var targetStats by remember { mutableStateOf("") }
    var isSyncingAuto by remember { mutableStateOf(false) }

    var showDeleteKeyboxDialog by remember { mutableStateOf(false) }
    var showClearTargetsDialog by remember { mutableStateOf(false) }
    var isLoadingTargetManager by remember { mutableStateOf(false) }
    var targetManagerData by remember {
        mutableStateOf<Pair<List<TargetApp>, Map<String, TrickyStore.TargetMode>>?>(null)
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun refreshState() {
        scope.launch {
            val (keybox, name, stats) = withContext(Dispatchers.IO) {
                val installed = context.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
                val savedTargets = TrickyStore.readTargets(context)
                val cleanedTargets = savedTargets.filterKeys { it in installed }

                if (cleanedTargets.size != savedTargets.size) {
                    TrickyStore.saveTargets(context, cleanedTargets)
                }

                val statsObj = TrickyStore.calculateStats(cleanedTargets)
                Triple(
                    !context.getSecure(TrickyStore.TS_KEYBOX_KEY).isNullOrEmpty(),
                    context.getSecure(TrickyStore.TS_KEYBOX_NAME_KEY) ?: "keybox.xml",
                    statsObj.formatSummary(context)
                )
            }
            hasKeybox = keybox
            keyboxName = name
            targetStats = stats
        }
    }

    LaunchedEffect(Unit) { refreshState() }

    val importKeyboxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    context.putSecure(TrickyStore.TS_KEYBOX_KEY, Base64.encodeToString(bytes, Base64.NO_WRAP))
                    context.putSecure(TrickyStore.TS_KEYBOX_NAME_KEY, TrickyStore.getFileNameFromUri(context, uri))
                    TrickyStore.killPackages(context)
                }
            }.onSuccess {
                toast(context.getString(R.string.spoof_ts_keybox_imported))
                refreshState()
            }.onFailure { toast(context.getString(R.string.spoof_ts_failed, it.message.orEmpty())) }
        }
    }

    val importTargetLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
                    context.putSecure(TrickyStore.TS_TARGET_KEY, text)
                }
            }.onSuccess {
                toast(context.getString(R.string.spoof_ts_target_list_imported))
                refreshState()
            }.onFailure { toast(context.getString(R.string.spoof_ts_failed, it.message.orEmpty())) }
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
                HeaderCard(
                    title = stringResource(R.string.spoof_ts_title),
                    keyboxStatus = if (hasKeybox) stringResource(R.string.spoof_ts_keybox_active, keyboxName)
                                   else stringResource(R.string.spoof_ts_no_keybox),
                    targetStatus = if (targetStats.isNotEmpty()) targetStats
                                   else stringResource(R.string.spoof_ts_no_targets)
                )
            }

            item { Spacer(Modifier.height(10.dp)) }
            item { SectionTitle(stringResource(R.string.spoof_ts_keybox_management)) }

            item {
                PrefItem(
                    title = stringResource(R.string.spoof_ts_import_keybox),
                    summary = if (hasKeybox) stringResource(R.string.spoof_ts_keybox_active, keyboxName)
                              else stringResource(R.string.spoof_ts_no_keybox),
                    onClick = { importKeyboxLauncher.launch(arrayOf("*/*")) }
                )
            }
            item {
                PrefItem(
                    title = stringResource(R.string.spoof_ts_delete_keybox),
                    summary = stringResource(R.string.spoof_ts_delete_keybox_message),
                    enabled = hasKeybox,
                    onClick = { showDeleteKeyboxDialog = true }
                )
            }

            item { Spacer(Modifier.height(10.dp)) }
            item { SectionTitle(stringResource(R.string.spoof_ts_target_configuration)) }

            item {
                PrefItem(
                    title = stringResource(R.string.spoof_ts_manage_target_apps),
                    summary = if (isLoadingTargetManager) stringResource(R.string.spoof_ts_loading_apps)
                              else if (targetStats.isNotEmpty()) targetStats
                              else stringResource(R.string.spoof_ts_no_targets),
                    enabled = !isLoadingTargetManager,
                    onClick = {
                        isLoadingTargetManager = true
                        scope.launch {
                            targetManagerData = TrickyStore.loadTargetManagerData(context)
                            isLoadingTargetManager = false
                        }
                    }
                )
            }
            item {
                PrefItem(
                    title = stringResource(R.string.spoof_ts_auto_sync_title),
                    summary = if (isSyncingAuto) stringResource(R.string.spoof_ts_loading_apps) else stringResource(R.string.spoof_ts_auto_sync_summary),
                    enabled = !isSyncingAuto,
                    onClick = {
                        isSyncingAuto = true
                        scope.launch {
                            val fetched = TrickyStore.fetchTargetList(context)
                            isSyncingAuto = false
                            if (fetched.isEmpty()) {
                                toast(context.getString(R.string.spoof_ts_failed, context.getString(R.string.spoof_ts_no_targets_available)))
                                return@launch
                            }

                            val installedPkgs = withContext(Dispatchers.IO) {
                                context.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
                            }
                            val currentTargets = TrickyStore.readTargets(context).toMutableMap()
                            var changed = false

                            fetched.forEach { (pkg, mode) ->
                                if (pkg in installedPkgs && pkg !in currentTargets) {
                                    currentTargets[pkg] = mode
                                    changed = true
                                }
                            }

                            if (changed) {
                                TrickyStore.saveTargets(context, currentTargets)
                                toast(context.getString(R.string.spoof_ts_target_list_imported))
                                refreshState()
                            } else {
                                toast(context.getString(R.string.spoof_ts_no_new_targets))
                            }
                        }
                    }
                )
            }
            item {
                PrefItem(
                    title = stringResource(R.string.spoof_ts_import_target_list),
                    summary = stringResource(R.string.spoof_ts_import_target_list_summary),
                    onClick = { importTargetLauncher.launch(arrayOf("text/*", "*/*")) }
                )
            }
            item {
                PrefItem(
                    title = stringResource(R.string.spoof_ts_clear_targets_title),
                    summary = stringResource(R.string.spoof_ts_clear_targets_msg),
                    enabled = targetStats.isNotEmpty(),
                    onClick = { showClearTargetsDialog = true }
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showDeleteKeyboxDialog) {
        ConfirmDialog(
            title = stringResource(R.string.spoof_ts_delete_keybox_title),
            message = stringResource(R.string.spoof_ts_delete_keybox_message),
            confirmText = stringResource(R.string.spoof_ts_delete),
            onDismiss = { showDeleteKeyboxDialog = false },
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        context.putSecure(TrickyStore.TS_KEYBOX_KEY, null)
                        context.putSecure(TrickyStore.TS_KEYBOX_NAME_KEY, null)
                        TrickyStore.killPackages(context)
                    }
                    showDeleteKeyboxDialog = false
                    toast(context.getString(R.string.spoof_ts_keybox_deleted))
                    refreshState()
                }
            }
        )
    }

    if (showClearTargetsDialog) {
        ConfirmDialog(
            title = stringResource(R.string.spoof_ts_clear_targets_title),
            message = stringResource(R.string.spoof_ts_clear_targets_msg),
            confirmText = stringResource(R.string.spoof_ts_delete),
            onDismiss = { showClearTargetsDialog = false },
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { TrickyStore.saveTargets(context, null) }
                    showClearTargetsDialog = false
                    toast(context.getString(R.string.spoof_ts_targets_cleared))
                    refreshState()
                }
            }
        )
    }

    targetManagerData?.let { (apps, targets) ->
        TargetManagerBottomSheet(
            apps = apps,
            initialTargets = targets,
            onChanged = ::refreshState,
            onDismiss = { targetManagerData = null }
        )
    }
}

@Composable
private fun HeaderCard(title: String, keyboxStatus: String, targetStatus: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(keyboxStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(targetStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            OutlinedButton(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun PrefItem(
    title: String,
    summary: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.45f else 0.2f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                Spacer(Modifier.height(2.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetManagerBottomSheet(
    apps: List<TargetApp>,
    initialTargets: Map<String, TrickyStore.TargetMode>,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f

    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }

    val targets = remember { mutableStateMapOf<String, TrickyStore.TargetMode>().apply { putAll(initialTargets) } }
    var stablePinned by remember { mutableStateOf(initialTargets.keys.toSet()) }

    LaunchedEffect(query) {
        if (query.isEmpty()) {
            stablePinned = targets.keys.toSet()
        }
    }

    fun commit() {
        TrickyStore.saveTargets(context, targets)
        onChanged()
    }

    fun setMode(pkg: String, mode: TrickyStore.TargetMode?) {
        if (mode == null) targets.remove(pkg) else targets[pkg] = mode
        commit()
    }

    val filteredApps = remember(query, showSystem, stablePinned, apps) {
        apps.filter {
            (showSystem || !it.isSystem || it.pkg in stablePinned) &&
                (it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true))
        }.sortedWith(compareByDescending<TargetApp> { it.pkg in stablePinned }.thenBy { it.label.lowercase() })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).animateContentSize().padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.spoof_ts_manage_target_apps),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.spoof_ts_search_apps)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = showSystem,
                    onClick = { showSystem = !showSystem },
                    leadingIcon = if (showSystem) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                    label = { Text(stringResource(R.string.spoof_ts_system_apps)) }
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredApps, key = { it.pkg }) { app ->
                    TargetAppItem(
                        app = app,
                        mode = targets[app.pkg],
                        expanded = expanded == app.pkg,
                        onToggleExpand = { expanded = if (expanded == app.pkg) null else app.pkg },
                        onCheckedChange = { checked ->
                            if (checked) {
                                expanded = app.pkg
                                setMode(app.pkg, TrickyStore.TargetMode.AUTO)
                            } else {
                                if (expanded == app.pkg) expanded = null
                                setMode(app.pkg, null)
                            }
                        },
                        onModeSelected = { setMode(app.pkg, it) }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TargetAppItem(
    app: TargetApp,
    mode: TrickyStore.TargetMode?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onModeSelected: (TrickyStore.TargetMode) -> Unit
) {
    val pm = LocalContext.current.packageManager
    val icon by produceState<Drawable?>(null, app.pkg) {
        value = withContext(Dispatchers.IO) { runCatching { app.info.loadIcon(pm) }.getOrNull() }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        color = if (mode != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpand).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AndroidView(
                    factory = { ImageView(it) },
                    update = { it.setImageDrawable(icon) },
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = app.pkg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (mode != null && !expanded) {
                    Text(
                        text = stringResource(mode.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Checkbox(checked = mode != null, onCheckedChange = onCheckedChange)
            }

            AnimatedVisibility(visible = expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrickyStore.TargetMode.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onModeSelected(option) }
                                .padding(end = 8.dp)
                        ) {
                            RadioButton(selected = mode == option, onClick = { onModeSelected(option) })
                            Text(stringResource(option.labelRes), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
