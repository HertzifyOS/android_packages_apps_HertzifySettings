package com.hertzify.settings.fragments.miscellaneous

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.om.OverlayManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import java.nio.charset.StandardCharsets

class TrickyStore : SettingsPreferenceFragment() {

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.HERTZIFY

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SettingsTheme {
                TrickyStoreScreen()
            }
        }
    }

    companion object {
        const val TAG = "TrickyStore"
        const val DROIDGUARD_PACKAGE = "com.google.android.gms.unstable"
        const val GMS_PACKAGE = "com.google.android.gms"
        const val VENDING_PACKAGE = "com.android.vending"
        const val TS_KEYBOX_KEY = "spoof_trickystore_keybox"
        const val TS_TARGET_KEY = "spoof_trickystore_target"

        val AUTO_SELECT_PACKAGES = setOf(
            "com.google.android.gms",
            "com.android.vending",
            "com.google.android.apps.nbu.paisa.user",
            "com.google.android.apps.walletnfcrel"
        )

        fun killPackages(context: Context) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            listOf(DROIDGUARD_PACKAGE, GMS_PACKAGE, VENDING_PACKAGE).forEach {
                try { am.forceStopPackage(it) } catch (_: Exception) {}
            }
        }

        fun getOverlayPackages(context: Context): Set<String> {
            return try {
                val om = context.getSystemService(Context.OVERLAY_SERVICE) as OverlayManager
                val userHandle = Process.myUserHandle()
                val targets = listOf("android", "com.android.systemui", "com.android.settings", "com.android.launcher3")
                targets.flatMap { om.getOverlayInfosForTarget(it, userHandle) }
                    .map { it.packageName }
                    .toSet()
            } catch (_: Exception) {
                emptySet()
            }
        }
    }

    enum class TargetMode(val symbol: String) {
        AUTO(""),
        LEAF_HACK("?"),
        CERT_GEN("!");

        companion object {
            fun fromLine(line: String): Pair<String, TargetMode> = when {
                line.endsWith("?") -> line.dropLast(1) to LEAF_HACK
                line.endsWith("!") -> line.dropLast(1) to CERT_GEN
                else -> line to AUTO
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrickyStoreScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var hasKeybox by remember { mutableStateOf(false) }
    var targetCount by remember { mutableStateOf(0) }

    var showDeleteKeyboxDialog by remember { mutableStateOf(false) }
    var showClearTargetsDialog by remember { mutableStateOf(false) }
    var showTargetManager by remember { mutableStateOf(false) }

    fun refreshState() {
        scope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val keyboxExists = !Settings.Secure.getString(resolver, TrickyStore.TS_KEYBOX_KEY).isNullOrEmpty()
            val targetContent = Settings.Secure.getString(resolver, TrickyStore.TS_TARGET_KEY)
            
            val count = if (!targetContent.isNullOrEmpty()) {
                val installedPackages = context.packageManager
                    .getInstalledPackages(0)
                    .map { it.packageName }
                    .toHashSet()
                    
                targetContent.lines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { TrickyStore.TargetMode.fromLine(it.trim()).first }
                    .count { it in installedPackages }
            } else 0
            
            withContext(Dispatchers.Main) {
                hasKeybox = keyboxExists
                targetCount = count
            }
        }
    }

    LaunchedEffect(Unit) { refreshState() }

    val importKeyboxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        Settings.Secure.putString(context.contentResolver, TrickyStore.TS_KEYBOX_KEY, encoded)
                        TrickyStore.killPackages(context)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.spoof_ts_keybox_imported), Toast.LENGTH_SHORT).show()
                            refreshState()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.spoof_ts_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    val importTargetLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val text = context.contentResolver.openInputStream(uri)?.use { 
                            it.readBytes().toString(StandardCharsets.UTF_8) 
                        } ?: ""
                        Settings.Secure.putString(context.contentResolver, TrickyStore.TS_TARGET_KEY, text)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.spoof_ts_target_list_imported), Toast.LENGTH_SHORT).show()
                            refreshState()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.spoof_ts_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

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
                                Icons.Default.Build,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "TrickyStore",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (hasKeybox) 
                                    "Keybox Active • $targetCount Targets" 
                                else 
                                    "Keybox Missing",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            // Keybox Management
            item {
                Text(
                    text = stringResource(R.string.spoof_ts_keybox_management),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)
                )
            }
            item {
                PrefItem(
                    title = stringResource(R.string.spoof_ts_import_keybox),
                    summary = if (hasKeybox) stringResource(R.string.spoof_ts_keybox_installed) else stringResource(R.string.spoof_ts_no_keybox),
                    onClick = {
                        importKeyboxLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        })
                    }
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

            // Target Configuration
            item {
                Text(
                    text = stringResource(R.string.spoof_ts_target_configuration),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)
                )
            }
            item {
                PrefItem(
                    title = stringResource(R.string.spoof_ts_manage_target_apps),
                    summary = if (targetCount > 0) stringResource(R.string.spoof_ts_target_apps_count, targetCount) else stringResource(R.string.spoof_ts_no_targets),
                    onClick = { showTargetManager = true }
                )
            }
            item {
                PrefItem(
                    title = stringResource(R.string.spoof_ts_import_target_list),
                    summary = stringResource(R.string.spoof_ts_import_target_list_summary),
                    onClick = {
                        importTargetLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/*"
                        })
                    }
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

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    // Dialogs
    if (showDeleteKeyboxDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteKeyboxDialog = false },
            title = { Text(stringResource(R.string.spoof_ts_delete_keybox_title)) },
            text = { Text(stringResource(R.string.spoof_ts_delete_keybox_message)) },
            confirmButton = {
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        Settings.Secure.putString(context.contentResolver, TrickyStore.TS_KEYBOX_KEY, null)
                        TrickyStore.killPackages(context)
                        withContext(Dispatchers.Main) {
                            showDeleteKeyboxDialog = false
                            Toast.makeText(context, context.getString(R.string.spoof_ts_keybox_deleted), Toast.LENGTH_SHORT).show()
                            refreshState()
                        }
                    }
                }) { Text(stringResource(R.string.spoof_ts_delete)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteKeyboxDialog = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    if (showClearTargetsDialog) {
        AlertDialog(
            onDismissRequest = { showClearTargetsDialog = false },
            title = { Text(stringResource(R.string.spoof_ts_clear_targets_title)) },
            text = { Text(stringResource(R.string.spoof_ts_clear_targets_msg)) },
            confirmButton = {
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        Settings.Secure.putString(context.contentResolver, TrickyStore.TS_TARGET_KEY, null)
                        withContext(Dispatchers.Main) {
                            showClearTargetsDialog = false
                            Toast.makeText(context, context.getString(R.string.spoof_ts_targets_cleared), Toast.LENGTH_SHORT).show()
                            refreshState()
                        }
                    }
                }) { Text(stringResource(R.string.spoof_ts_delete)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearTargetsDialog = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    if (showTargetManager) {
        TargetManagerBottomSheet(
            onDismiss = { 
                showTargetManager = false
                refreshState()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetManagerBottomSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val scope = rememberCoroutineScope()
    
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    
    var installedApps by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    val targetMap = remember { mutableStateMapOf<String, TrickyStore.TargetMode>() }
    var expandedPackage by remember { mutableStateOf<String?>(null) }

    fun saveTargets() {
        val text = targetMap.map { "${it.key}${it.value.symbol}" }.joinToString("\n")
        Settings.Secure.putString(context.contentResolver, TrickyStore.TS_TARGET_KEY, text)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val overlays = TrickyStore.getOverlayPackages(context)
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA).filter { app ->
                val isOverlay = app.packageName in overlays
                val isExcluded = app.packageName.contains(".overlay") || app.packageName.contains(".resources")
                !isOverlay && !isExcluded
            }
            
            val content = Settings.Secure.getString(context.contentResolver, TrickyStore.TS_TARGET_KEY) ?: ""
            val parsedMap = content.lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { TrickyStore.TargetMode.fromLine(it.trim()) }

            withContext(Dispatchers.Main) {
                installedApps = apps
                targetMap.putAll(parsedMap)
                isLoading = false
            }
        }
    }

    val filteredApps = remember(searchQuery, showSystemApps, installedApps, targetMap.size) {
        installedApps.filter { app ->
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val matchesQuery = app.loadLabel(pm).toString().contains(searchQuery, ignoreCase = true) ||
                               app.packageName.contains(searchQuery, ignoreCase = true)
            val isSelected = targetMap.containsKey(app.packageName)
            val shouldShow = (!isSystem || showSystemApps || isSelected)
            shouldShow && matchesQuery
        }.sortedWith(
            compareByDescending<ApplicationInfo> { targetMap.containsKey(it.packageName) }
                .thenBy { it.loadLabel(pm).toString().lowercase() }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.9f)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.spoof_ts_manage_target_apps),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.spoof_ts_search_apps)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
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
                FilterChip(
                    selected = showSystemApps,
                    onClick = { showSystemApps = !showSystemApps },
                    label = { Text("System") }
                )
                TextButton(
                    onClick = {
                        var changed = false
                        TrickyStore.AUTO_SELECT_PACKAGES.forEach { pkg ->
                            if (!targetMap.containsKey(pkg)) {
                                targetMap[pkg] = TrickyStore.TargetMode.AUTO
                                changed = true
                            }
                        }
                        if (changed) saveTargets()
                    }
                ) {
                    Text("Auto")
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val pkg = app.packageName
                        val isEnabled = targetMap.containsKey(pkg)
                        val mode = targetMap[pkg] ?: TrickyStore.TargetMode.AUTO
                        val isExpanded = expandedPackage == pkg

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize(),
                            shape = RoundedCornerShape(14.dp),
                            color = if (isEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (!isEnabled) {
                                                targetMap[pkg] = TrickyStore.TargetMode.AUTO
                                                expandedPackage = pkg
                                                saveTargets()
                                            } else {
                                                expandedPackage = if (isExpanded) null else pkg
                                            }
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AndroidView(
                                        factory = { ImageView(it) },
                                        update = { iv ->
                                            scope.launch(Dispatchers.IO) {
                                                val icon = try { app.loadIcon(pm) } catch (e: Exception) { null }
                                                withContext(Dispatchers.Main) { iv.setImageDrawable(icon) }
                                            }
                                        },
                                        modifier = Modifier.size(44.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app.loadLabel(pm).toString(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (isEnabled && !isExpanded) {
                                        Text(
                                            text = when (mode) {
                                                TrickyStore.TargetMode.LEAF_HACK -> stringResource(R.string.spoof_ts_target_mode_leaf)
                                                TrickyStore.TargetMode.CERT_GEN -> stringResource(R.string.spoof_ts_target_mode_cert)
                                                else -> stringResource(R.string.spoof_ts_target_mode_auto)
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                    }
                                    Checkbox(
                                        checked = isEnabled,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                targetMap[pkg] = TrickyStore.TargetMode.AUTO
                                                expandedPackage = pkg
                                            } else {
                                                targetMap.remove(pkg)
                                                if (isExpanded) expandedPackage = null
                                            }
                                            saveTargets()
                                        }
                                    )
                                }
                                
                                AnimatedVisibility(visible = isExpanded && isEnabled) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(start = 64.dp, end = 12.dp, bottom = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        TrickyStore.TargetMode.entries.forEach { targetMode ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = mode == targetMode,
                                                    onClick = {
                                                        targetMap[pkg] = targetMode
                                                        saveTargets()
                                                    }
                                                )
                                                Text(
                                                    text = when(targetMode) {
                                                        TrickyStore.TargetMode.LEAF_HACK -> stringResource(R.string.spoof_ts_target_mode_leaf)
                                                        TrickyStore.TargetMode.CERT_GEN -> stringResource(R.string.spoof_ts_target_mode_cert)
                                                        else -> stringResource(R.string.spoof_ts_target_mode_auto)
                                                    },
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
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