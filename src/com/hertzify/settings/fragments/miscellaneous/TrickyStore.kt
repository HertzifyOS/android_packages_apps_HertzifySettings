@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.hertzify.settings.fragments.miscellaneous

import android.app.ActivityManager
import android.content.Context
import android.content.om.OverlayManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
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

private fun Context.getSecure(key: String): String? = Settings.Secure.getString(contentResolver, key)

private fun Context.putSecure(key: String, value: String?) {
    Settings.Secure.putString(contentResolver, key, value)
}

data class TargetApp(val info: ApplicationInfo, val label: String, val isSystem: Boolean) {
    val pkg: String get() = info.packageName
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

        private val KILL_PACKAGES = listOf(
            "com.google.android.gms.unstable", "com.google.android.gms", "com.android.vending"
        )

        val AUTO_SELECT_PACKAGES = setOf(
            "com.google.android.gms",
            "com.android.vending",
            "com.google.android.apps.nbu.paisa.user",
            "com.google.android.apps.walletnfcrel"
        )

        fun killPackages(context: Context) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            KILL_PACKAGES.forEach { runCatching { am.forceStopPackage(it) } }
        }

        private fun getOverlayPackages(context: Context): Set<String> = runCatching {
            val om = context.getSystemService(Context.OVERLAY_SERVICE) as OverlayManager
            val user = Process.myUserHandle()
            listOf("android", "com.android.systemui", "com.android.settings", "com.android.launcher3")
                .flatMap { om.getOverlayInfosForTarget(it, user) }
                .map { it.packageName }
                .toSet()
        }.getOrDefault(emptySet())

        fun readTargets(context: Context): Map<String, TargetMode> =
            context.getSecure(TS_TARGET_KEY).orEmpty().lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { TargetMode.fromLine(it.trim()) }

        fun saveTargets(context: Context, targets: Map<String, TargetMode>?) {
            context.putSecure(TS_TARGET_KEY, targets?.map { "${it.key}${it.value.symbol}" }?.joinToString("\n"))
        }

        suspend fun loadTargetManagerData(context: Context): Pair<List<TargetApp>, Map<String, TargetMode>> =
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val overlays = getOverlayPackages(context)
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.packageName !in overlays && !it.packageName.contains(".overlay") && !it.packageName.contains(".resources") }
                    .map { TargetApp(it, it.loadLabel(pm).toString(), (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0) }
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
    var targetCount by remember { mutableStateOf(0) }

    var showDeleteKeyboxDialog by remember { mutableStateOf(false) }
    var showClearTargetsDialog by remember { mutableStateOf(false) }
    var isLoadingTargetManager by remember { mutableStateOf(false) }
    var targetManagerData by remember {
        mutableStateOf<Pair<List<TargetApp>, Map<String, TrickyStore.TargetMode>>?>(null)
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun refreshState() {
        scope.launch {
            val (keybox, name, count) = withContext(Dispatchers.IO) {
                val installed = context.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
                Triple(
                    !context.getSecure(TrickyStore.TS_KEYBOX_KEY).isNullOrEmpty(),
                    context.getSecure(TrickyStore.TS_KEYBOX_NAME_KEY) ?: "keybox.xml",
                    TrickyStore.readTargets(context).keys.count { it in installed }
                )
            }
            hasKeybox = keybox
            keyboxName = name
            targetCount = count
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            item {
                HeaderCard(
                    title = stringResource(R.string.spoof_ts_title),
                    lines = listOf(
                        if (hasKeybox) stringResource(R.string.spoof_ts_keybox_active, keyboxName)
                        else stringResource(R.string.spoof_ts_no_keybox),
                        if (targetCount > 0) stringResource(R.string.spoof_ts_target_apps_count, targetCount)
                        else stringResource(R.string.spoof_ts_no_targets)
                    )
                )
            }

            item { Spacer(Modifier.height(12.dp)) }
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
                    summary = stringResource(R.string.spoof_ts_delete_keybox_summary),
                    enabled = hasKeybox,
                    onClick = { showDeleteKeyboxDialog = true }
                )
            }

            item { SectionTitle(stringResource(R.string.spoof_ts_target_configuration)) }

            item {
                PrefItem(
                    title = stringResource(R.string.spoof_ts_manage_target_apps),
                    summary = if (isLoadingTargetManager) stringResource(R.string.spoof_ts_loading_apps)
                    else stringResource(R.string.spoof_ts_manage_target_apps_summary),
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
                    title = stringResource(R.string.spoof_ts_import_target_list),
                    summary = stringResource(R.string.spoof_ts_import_target_list_summary),
                    onClick = { importTargetLauncher.launch(arrayOf("text/*")) }
                )
            }
            item {
                PrefItem(
                    title = stringResource(R.string.spoof_ts_clear_targets_title),
                    summary = stringResource(R.string.spoof_ts_clear_targets_summary),
                    enabled = targetCount > 0,
                    onClick = { showClearTargetsDialog = true }
                )
            }

            item { Spacer(Modifier.height(80.dp)) }
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
private fun TargetManagerBottomSheet(
    apps: List<TargetApp>,
    initialTargets: Map<String, TrickyStore.TargetMode>,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f

    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var pinned by remember { mutableStateOf(initialTargets.keys.toSet()) }
    val targets = remember { mutableStateMapOf<String, TrickyStore.TargetMode>().apply { putAll(initialTargets) } }

    fun commit() {
        TrickyStore.saveTargets(context, targets)
        onChanged()
    }

    fun setMode(pkg: String, mode: TrickyStore.TargetMode?) {
        if (mode == null) targets.remove(pkg) else targets[pkg] = mode
        commit()
    }

    val filteredApps = remember(query, showSystem, pinned, apps) {
        apps.filter {
            (showSystem || !it.isSystem || it.pkg in pinned) &&
                (it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true))
        }.sortedWith(compareByDescending<TargetApp> { it.pkg in pinned }.thenBy { it.label.lowercase() })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).animateContentSize().padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.spoof_ts_manage_target_apps),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("System") })
                TextButton(onClick = {
                    val missing = TrickyStore.AUTO_SELECT_PACKAGES.filter { it !in targets }
                    if (missing.isNotEmpty()) {
                        missing.forEach { targets[it] = TrickyStore.TargetMode.AUTO }
                        pinned = targets.keys.toSet()
                        commit()
                    }
                }) { Text("Auto") }
            }

            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrickyStore.TargetMode.entries.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == option, onClick = { onModeSelected(option) })
                            Text(stringResource(option.labelRes), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCard(title: String, lines: List<String>) {
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
                    imageVector = Icons.Default.EnhancedEncryption,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                lines.forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
        confirmButton = { Button(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
private fun PrefItem(title: String, summary: String, enabled: Boolean = true, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
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
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            Spacer(Modifier.height(2.dp))
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
    }
}