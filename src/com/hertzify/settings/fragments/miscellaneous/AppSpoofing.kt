@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.hertzify.settings.fragments.miscellaneous

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AppConfig(val packageName: String, val props: Map<String, String>)

data class DeviceProfile(val name: String, val props: Map<String, String>) {
    val summary: String
        get() = "${props[AppSpoofConstants.FIELD_MANUFACTURER].orEmpty()} ${props[AppSpoofConstants.FIELD_MODEL].orEmpty()}".trim()
}

private data class AppPickerEntry(val info: ApplicationInfo, val label: String, val isSystem: Boolean) {
    val packageName: String get() = info.packageName
}

object AppSpoofConstants {
    const val FIELD_MODEL = "MODEL"
    const val FIELD_MANUFACTURER = "MANUFACTURER"

    val DEFAULT_PROFILES = listOf(
        DeviceProfile("ROG Phone 8 Pro", mapOf(FIELD_MODEL to "ASUS_AI2401_A", FIELD_MANUFACTURER to "asus")),
        DeviceProfile("Galaxy S24 Ultra", mapOf(FIELD_MODEL to "SM-S928B", FIELD_MANUFACTURER to "samsung")),
        DeviceProfile("Xiaomi 13 Pro", mapOf(FIELD_MODEL to "2210132C", FIELD_MANUFACTURER to "Xiaomi")),
        DeviceProfile("OnePlus 9 Pro", mapOf(FIELD_MODEL to "LE2101", FIELD_MANUFACTURER to "OnePlus")),
        DeviceProfile("Black Shark 4", mapOf(FIELD_MODEL to "2SM-X706B", FIELD_MANUFACTURER to "blackshark")),
        DeviceProfile("Lenovo Y700", mapOf(FIELD_MODEL to "Lenovo TB-9707F", FIELD_MANUFACTURER to "Lenovo"))
    )

    val CORE_KEYS = setOf(FIELD_MODEL, FIELD_MANUFACTURER)
}

private fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }

private fun Context.toast(@StringRes id: Int) = Toast.makeText(this, getString(id), Toast.LENGTH_SHORT).show()

class AppSpoofController(private val context: Context) {
    companion object {
        private const val TAG = "AppSpoofController"
        private const val CONFIG_KEY = "spoof_appprops_config"
        private const val PRESETS_KEY = "app_spoofing_user_presets"
    }

    private fun getSecure(key: String): String? = Settings.Secure.getString(context.contentResolver, key)

    private fun putSecure(key: String, value: String) {
        Settings.Secure.putString(context.contentResolver, key, value)
    }

    suspend fun readConfig(): Pair<Boolean, List<AppConfig>> = withContext(Dispatchers.IO) {
        val content = getSecure(CONFIG_KEY) ?: return@withContext false to emptyList()
        runCatching {
            val json = JSONObject(content)
            val apps = json.optJSONObject("apps")
            json.optBoolean("enabled", false) to
                apps?.keys()?.asSequence()?.map { AppConfig(it, apps.getJSONObject(it).toStringMap()) }?.toList().orEmpty()
        }.onFailure { Log.e(TAG, "readConfig error", it) }.getOrDefault(false to emptyList())
    }

    suspend fun writeConfig(enabled: Boolean, apps: List<AppConfig>) = withContext(Dispatchers.IO) {
        runCatching {
            val appsJson = JSONObject(apps.associate { it.packageName to JSONObject(it.props) })
            val json = JSONObject().put("enabled", enabled).put("apps", appsJson)
            putSecure(CONFIG_KEY, json.toString(2))
        }.onFailure { Log.e(TAG, "writeConfig error", it) }
        Unit
    }

    suspend fun readProfiles(): List<DeviceProfile> = withContext(Dispatchers.IO) {
        val content = getSecure(PRESETS_KEY) ?: return@withContext emptyList()
        runCatching {
            val array = JSONArray(content)
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                DeviceProfile(obj.getString("name"), obj.getJSONObject("props").toStringMap())
            }
        }.onFailure { Log.e(TAG, "Failed to load custom presets", it) }.getOrDefault(emptyList())
    }

    suspend fun writeProfiles(profiles: List<DeviceProfile>) = withContext(Dispatchers.IO) {
        val array = JSONArray(profiles.map { JSONObject().put("name", it.name).put("props", JSONObject(it.props)) })
        putSecure(PRESETS_KEY, array.toString())
    }
}

class AppSpoofing : SettingsPreferenceFragment() {
    private lateinit var controller: AppSpoofController

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.HERTZIFY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = AppSpoofController(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { SettingsTheme { AppSpoofingMainScreen(controller, requireContext().packageManager) } }
    }
}

@Stable
private class PropsForm(props: Map<String, String> = emptyMap()) {
    var manufacturer by mutableStateOf("")
    var model by mutableStateOf("")
    val extras = mutableStateListOf<Pair<String, String>>()

    init { load(props) }

    val isValid: Boolean get() = manufacturer.isNotBlank() && model.isNotBlank()

    fun load(props: Map<String, String>) {
        manufacturer = props[AppSpoofConstants.FIELD_MANUFACTURER].orEmpty()
        model = props[AppSpoofConstants.FIELD_MODEL].orEmpty()
        extras.clear()
        props.filterKeys { it !in AppSpoofConstants.CORE_KEYS }.forEach { extras.add(it.key to it.value) }
    }

    fun toProps(): Map<String, String> = buildMap {
        put(AppSpoofConstants.FIELD_MANUFACTURER, manufacturer)
        put(AppSpoofConstants.FIELD_MODEL, model)
        extras.filter { it.first.isNotBlank() }.forEach { put(it.first, it.second) }
    }
}

@Composable
fun AppSpoofingMainScreen(controller: AppSpoofController, pm: PackageManager) {
    val scope = rememberCoroutineScope()

    var isMasterEnabled by remember { mutableStateOf(false) }
    var configs by remember { mutableStateOf<List<AppConfig>>(emptyList()) }
    var profiles by remember { mutableStateOf<List<DeviceProfile>>(emptyList()) }
    var installedApps by remember { mutableStateOf<List<AppPickerEntry>>(emptyList()) }
    var pinned by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }

    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var expandedPackage by remember { mutableStateOf<String?>(null) }
    var showProfileManager by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (enabled, apps) = controller.readConfig()
        isMasterEnabled = enabled
        configs = apps
        pinned = apps.map { it.packageName }.toSet()
        profiles = controller.readProfiles().ifEmpty {
            AppSpoofConstants.DEFAULT_PROFILES.also { controller.writeProfiles(it) }
        }
        installedApps = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(PackageManager.GET_META_DATA).map {
                AppPickerEntry(it, it.loadLabel(pm).toString(), (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
            }
        }
        isLoading = false
    }

    fun saveConfigs(newConfigs: List<AppConfig>) {
        configs = newConfigs
        scope.launch { controller.writeConfig(isMasterEnabled, newConfigs) }
    }

    fun setConfig(pkg: String, props: Map<String, String>?) {
        saveConfigs(configs.filterNot { it.packageName == pkg } + listOfNotNull(props?.let { AppConfig(pkg, it) }))
    }

    val configMap = remember(configs) { configs.associateBy { it.packageName } }

    val filteredApps = remember(query, showSystem, installedApps, pinned) {
        installedApps.filter {
            (showSystem || !it.isSystem) &&
                (query.isEmpty() || it.label.contains(query, true) || it.packageName.contains(query, true))
        }.sortedWith(compareByDescending<AppPickerEntry> { it.packageName in pinned }.thenBy { it.label.lowercase() })
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))

            HeaderCard(
                title = stringResource(R.string.spoof_ap_title),
                summary = when {
                    !isMasterEnabled -> stringResource(R.string.spoof_ap_disabled)
                    configs.isNotEmpty() -> stringResource(R.string.spoof_ap_configured_apps, configs.size)
                    else -> stringResource(R.string.spoof_ap_no_apps)
                },
                checked = isMasterEnabled,
                onCheckedChange = {
                    isMasterEnabled = it
                    scope.launch { controller.writeConfig(it, configs) }
                }
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.spoof_ap_search_apps)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("System") })
                TextButton(onClick = { showProfileManager = true }) { Text(stringResource(R.string.spoof_ap_manage_profiles)) }
            }

            if (isLoading) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        AppSpoofItem(
                            app = app,
                            config = configMap[app.packageName],
                            expanded = expandedPackage == app.packageName,
                            profiles = profiles,
                            onExpandedChange = { expand ->
                                if (expand) expandedPackage = app.packageName
                                else if (expandedPackage == app.packageName) expandedPackage = null
                            },
                            onSave = { setConfig(app.packageName, it) }
                        )
                    }
                }
            }
        }
    }

    if (showProfileManager) {
        ProfileManagerBottomSheet(
            profiles = profiles,
            onDismiss = { showProfileManager = false },
            onSaveProfiles = {
                profiles = it
                scope.launch { controller.writeProfiles(it) }
            }
        )
    }
}

@Composable
private fun AppSpoofItem(
    app: AppPickerEntry,
    config: AppConfig?,
    expanded: Boolean,
    profiles: List<DeviceProfile>,
    onExpandedChange: (Boolean) -> Unit,
    onSave: (Map<String, String>?) -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val isEnabled = config != null

    val form = remember(config) { PropsForm(config?.props.orEmpty()) }
    var attempted by remember(app.packageName) { mutableStateOf(false) }
    var showProfilePicker by remember { mutableStateOf(false) }

    val icon by produceState<Drawable?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) { runCatching { app.info.loadIcon(pm) }.getOrNull() }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        color = if (isEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) }.padding(12.dp),
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
                        text = app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isEnabled) "${form.manufacturer} ${form.model}".trim().ifEmpty { stringResource(R.string.spoof_ap_configured) }
                        else app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Checkbox(
                    checked = isEnabled,
                    onCheckedChange = { checked ->
                        when {
                            !checked -> { onSave(null); onExpandedChange(false) }
                            form.isValid -> { onSave(form.toProps()); onExpandedChange(true) }
                            else -> onExpandedChange(true)
                        }
                    }
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    PropsEditor(form, showError = attempted)
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { showProfilePicker = true }) { Text(stringResource(R.string.spoof_ap_use_profile)) }
                        Row {
                            TextButton(onClick = { onExpandedChange(false) }) { Text(stringResource(android.R.string.cancel)) }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                if (!form.isValid) {
                                    attempted = true
                                    context.toast(R.string.spoof_ap_required)
                                } else {
                                    onSave(form.toProps())
                                    onExpandedChange(false)
                                }
                            }) { Text(stringResource(R.string.spoof_ap_save)) }
                        }
                    }
                }
            }
        }
    }

    if (showProfilePicker) {
        Sheet(onDismiss = { showProfilePicker = false }) {
            SheetTitle(stringResource(R.string.spoof_ap_select_profile))
            ProfileList(profiles, onClick = {
                form.load(it.props)
                showProfilePicker = false
            })
        }
    }
}

@Composable
private fun ProfileManagerBottomSheet(
    profiles: List<DeviceProfile>,
    onDismiss: () -> Unit,
    onSaveProfiles: (List<DeviceProfile>) -> Unit
) {
    val context = LocalContext.current
    val form = remember { PropsForm() }
    var editing by remember { mutableStateOf<DeviceProfile?>(null) }
    var editName by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }

    fun openEditor(profile: DeviceProfile?) {
        editing = profile ?: DeviceProfile("", emptyMap())
        editName = profile?.name.orEmpty()
        form.load(profile?.props.orEmpty())
        attempted = false
    }

    fun saveEditor() {
        if (editName.isBlank() || !form.isValid) {
            attempted = true
            context.toast(if (editName.isBlank()) R.string.spoof_ap_error_empty_name else R.string.spoof_ap_required)
            return
        }
        val saved = DeviceProfile(editName, form.toProps())
        val index = profiles.indexOfFirst { it.name == editing?.name }
        onSaveProfiles(profiles.toMutableList().apply { if (index >= 0) set(index, saved) else add(saved) })
        editing = null
        context.toast(R.string.spoof_ap_profile_saved)
    }

    Sheet(onDismiss = onDismiss, minHeight = 400.dp) {
        val current = editing
        if (current == null) {
            SheetTitle(stringResource(R.string.spoof_ap_manage_profiles))
            Text(
                text = stringResource(R.string.spoof_ap_manage_profiles_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            ProfileList(profiles, onClick = ::openEditor) { profile ->
                IconButton(onClick = { onSaveProfiles(profiles.filterNot { it.name == profile.name }) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { openEditor(null) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.spoof_ap_add_profile))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                IconButton(onClick = { editing = null }, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = stringResource(if (current.name.isEmpty()) R.string.spoof_ap_add_profile else R.string.spoof_ap_edit_profile),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                PropField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = stringResource(R.string.spoof_ap_profile_name),
                    hint = stringResource(R.string.spoof_ap_hint_profile_name),
                    isError = attempted && editName.isBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                PropsEditor(form, showError = attempted, hints = true)
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { editing = null }) { Text(stringResource(android.R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = ::saveEditor) { Text(stringResource(R.string.spoof_ap_save)) }
            }
        }
    }
}

@Composable
private fun HeaderCard(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
                    imageVector = Icons.Default.Devices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun Sheet(onDismiss: () -> Unit, minHeight: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight, max = maxHeight)
                .animateContentSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            content = content
        )
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun ColumnScope.ProfileList(
    profiles: List<DeviceProfile>,
    onClick: (DeviceProfile) -> Unit,
    trailing: @Composable (DeviceProfile) -> Unit = {}
) {
    if (profiles.isEmpty()) {
        Text(
            text = stringResource(R.string.spoof_ap_no_profiles),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
        )
    } else {
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(profiles) { profile ->
                ProfileCard(profile, onClick = { onClick(profile) }) { trailing(profile) }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: DeviceProfile, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(profile.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing()
        }
    }
}

@Composable
private fun PropsEditor(form: PropsForm, showError: Boolean, hints: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        PropField(
            value = form.manufacturer,
            onValueChange = { form.manufacturer = it },
            label = stringResource(R.string.spoof_ap_key_manufacturer),
            hint = if (hints) stringResource(R.string.spoof_ap_hint_manufacturer) else null,
            isError = showError && form.manufacturer.isBlank(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        PropField(
            value = form.model,
            onValueChange = { form.model = it },
            label = stringResource(R.string.spoof_ap_key_model),
            hint = if (hints) stringResource(R.string.spoof_ap_hint_model) else null,
            isError = showError && form.model.isBlank(),
            modifier = Modifier.fillMaxWidth()
        )

        form.extras.forEachIndexed { index, (key, value) ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                PropField(
                    value = key,
                    onValueChange = { form.extras[index] = it to value },
                    label = stringResource(R.string.spoof_ap_key),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                PropField(
                    value = value,
                    onValueChange = { form.extras[index] = key to it },
                    label = stringResource(R.string.spoof_ap_value),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { form.extras.removeAt(index) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove")
                }
            }
        }

        TextButton(onClick = { form.extras.add("" to "") }, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.spoof_ap_add_prop))
        }
    }
}

@Composable
private fun PropField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        placeholder = { hint?.let { Text(it) } },
        singleLine = true,
        isError = isError
    )
}