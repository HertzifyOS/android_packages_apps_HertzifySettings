@file:OptIn(ExperimentalMaterial3Api::class)

package com.hertzify.settings.fragments.miscellaneous

import android.app.ActivityManager
import android.content.Context
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

class AppShield : SettingsPreferenceFragment() {

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.HERTZIFY

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { SettingsTheme { AppShieldScreen() } }
    }

    companion object {
        fun forceStop(context: Context, pkg: String) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            runCatching { am.forceStopPackage(pkg) }
        }

        suspend fun loadApps(context: Context): List<ShieldApp> = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map {
                    ShieldApp(it, it.loadLabel(pm).toString(),
                        (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                }
        }

        fun getCsvSet(cr: ContentResolver, key: String): Set<String> {
            val raw = Settings.Secure.getStringForUser(cr, key, UserHandle.USER_SYSTEM)
            if (raw.isNullOrEmpty()) return emptySet()
            return raw.split(",").filter { it.isNotEmpty() }.toSet()
        }
    }
}

data class ShieldApp(val info: ApplicationInfo, val label: String, val isSystem: Boolean) {
    val pkg: String get() = info.packageName
}

private enum class ShieldOption { HIDE_APPLIST, HIDE_LAUNCHER, HIDE_DEV, DETACH }

@Composable
fun AppShieldScreen() {
    val context = LocalContext.current
    val cr = context.contentResolver
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<ShieldApp>?>(null) }
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    var launcherHidden by remember { mutableStateOf(emptySet<String>()) }
    var devHidden by remember { mutableStateOf(emptySet<String>()) }
    var detached by remember { mutableStateOf(emptySet<String>()) }

    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val newHidden = AppShield.getCsvSet(cr, "appshield_hide_applist")
                val newLauncher = AppShield.getCsvSet(cr, "appshield_hide_launcher")
                val newDev = AppShield.getCsvSet(cr, "appshield_hide_devstatus")
                val newDetach = AppShield.getCsvSet(cr, "appshield_detached")
                withContext(Dispatchers.Main) {
                    hidden = newHidden
                    launcherHidden = newLauncher
                    devHidden = newDev
                    detached = newDetach
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        apps = AppShield.loadApps(context)
        reload()
    }

    fun setOption(pkg: String, option: ShieldOption, enabled: Boolean) {
        when (option) {
            ShieldOption.HIDE_APPLIST -> hidden = if (enabled) hidden + pkg else hidden - pkg
            ShieldOption.HIDE_LAUNCHER -> launcherHidden = if (enabled) launcherHidden + pkg else launcherHidden - pkg
            ShieldOption.HIDE_DEV -> devHidden = if (enabled) devHidden + pkg else devHidden - pkg
            ShieldOption.DETACH -> detached = if (enabled) detached + pkg else detached - pkg
        }

        scope.launch {
            withContext(Dispatchers.IO) {
                val key = when (option) {
                    ShieldOption.HIDE_APPLIST -> "appshield_hide_applist"
                    ShieldOption.HIDE_LAUNCHER -> "appshield_hide_launcher"
                    ShieldOption.HIDE_DEV -> "appshield_hide_devstatus"
                    ShieldOption.DETACH -> "appshield_detached"
                }

                val freshSet = AppShield.getCsvSet(cr, key).toMutableSet()
                if (enabled) freshSet.add(pkg) else freshSet.remove(pkg)
                Settings.Secure.putStringForUser(cr, key, freshSet.joinToString(","), UserHandle.USER_SYSTEM)

                when (option) {
                    ShieldOption.HIDE_LAUNCHER -> {
                        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        val resolveInfo = context.packageManager.resolveActivity(
                            homeIntent,
                            PackageManager.MATCH_DEFAULT_ONLY
                        )
                        val homePkg = resolveInfo?.activityInfo?.packageName
                        if (!homePkg.isNullOrEmpty()) {
                            AppShield.forceStop(context, homePkg)
                        }
                    }
                    ShieldOption.DETACH -> {
                        AppShield.forceStop(context, "com.android.vending")
                    }
                    else -> {
                        AppShield.forceStop(context, pkg)
                    }
                }
            }
        }
    }

    val list = apps
    val filtered = remember(list, query, showSystem) {
        (list ?: emptyList()).filter {
            val isCurrentlyActive = it.pkg in hidden || it.pkg in launcherHidden || it.pkg in devHidden || it.pkg in detached
            (showSystem || !it.isSystem || isCurrentlyActive) &&
                (it.label.contains(query, ignoreCase = true) ||
                    it.pkg.contains(query, ignoreCase = true))
        }.sortedWith(
            compareByDescending<ShieldApp> { 
                it.pkg in hidden || it.pkg in launcherHidden || it.pkg in devHidden || it.pkg in detached 
            }.thenBy { it.label.lowercase() }
        )
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            ShieldHeader(
                hiddenCount = hidden.size,
                launcherCount = launcherHidden.size,
                devCount = devHidden.size,
                detachCount = detached.size
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.appshield_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = showSystem,
                    onClick = { showSystem = !showSystem },
                    label = { Text(stringResource(R.string.appshield_filter_system)) }
                )
            }

            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.pkg }) { app ->
                        ShieldAppItem(
                            app = app,
                            expanded = expanded == app.pkg,
                            hideApplist = app.pkg in hidden,
                            hideLauncher = app.pkg in launcherHidden,
                            hideDev = app.pkg in devHidden,
                            detach = app.pkg in detached,
                            onToggleExpand = {
                                expanded = if (expanded == app.pkg) null else app.pkg
                            },
                            onOption = { option, on -> setOption(app.pkg, option, on) }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ShieldAppItem(
    app: ShieldApp,
    expanded: Boolean,
    hideApplist: Boolean,
    hideLauncher: Boolean,
    hideDev: Boolean,
    detach: Boolean,
    onToggleExpand: () -> Unit,
    onOption: (ShieldOption, Boolean) -> Unit
) {
    val pm = LocalContext.current.packageManager
    val icon by produceState<Drawable?>(null, app.pkg) {
        value = withContext(Dispatchers.IO) { runCatching { app.info.loadIcon(pm) }.getOrNull() }
    }
    val active = hideApplist || hideLauncher || hideDev || detach

    val summary = buildList {
        if (hideApplist) add(stringResource(R.string.appshield_tag_hidden))
        if (hideLauncher) add(stringResource(R.string.appshield_tag_launcher))
        if (hideDev) add(stringResource(R.string.appshield_tag_dev))
        if (detach) add(stringResource(R.string.appshield_tag_detach))
    }.joinToString(" • ")

    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggleExpand).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AndroidView(
                    factory = { ImageView(it) },
                    update = { it.setImageDrawable(icon) },
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        app.label, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (active && !expanded) summary else app.pkg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active && !expanded) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                    OptionRow(
                        title = stringResource(R.string.appshield_opt_hide_applist),
                        summary = stringResource(R.string.appshield_opt_hide_applist_summary),
                        checked = hideApplist,
                        onChange = { onOption(ShieldOption.HIDE_APPLIST, it) }
                    )
                    OptionRow(
                        title = stringResource(R.string.appshield_opt_hide_launcher),
                        summary = stringResource(R.string.appshield_opt_hide_launcher_summary),
                        checked = hideLauncher,
                        onChange = { onOption(ShieldOption.HIDE_LAUNCHER, it) }
                    )
                    OptionRow(
                        title = stringResource(R.string.appshield_opt_hide_dev),
                        summary = stringResource(R.string.appshield_opt_hide_dev_summary),
                        checked = hideDev,
                        onChange = { onOption(ShieldOption.HIDE_DEV, it) }
                    )
                    OptionRow(
                        title = stringResource(R.string.appshield_opt_detach),
                        summary = stringResource(R.string.appshield_opt_detach_summary),
                        checked = detach,
                        onChange = { onOption(ShieldOption.DETACH, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ShieldHeader(hiddenCount: Int, launcherCount: Int, devCount: Int, detachCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Shield, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    stringResource(R.string.appshield_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.appshield_counts, hiddenCount, launcherCount, devCount, detachCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}