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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import java.net.HttpURLConnection
import java.net.URI

data class AppConfig(val packageName: String, val props: Map<String, String>)

data class DeviceProfile(val name: String, val props: Map<String, String>, val isCustom: Boolean = false) {
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

    val EXCLUDED_SUFFIXES = listOf(
        ".auto_generated", ".appsearch", ".backup", ".carrier",
        ".cellbroadcast", ".cts", ".federated", ".ims", ".overlay",
        ".qti", ".qualcomm", ".resources", ".systemui.clocks",
        ".systemui.plugin", ".theme", ".iconpack"
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
        private const val PRESET_CACHE_KEY = "app_spoofing_preset_cache"
        private const val PRESET_TIMESTAMP_KEY = "app_spoofing_preset_timestamp"
        private const val PRESET_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
        private const val REMOTE_SYNC_URL = "https://raw.githubusercontent.com/HertzifyOS/OTA/refs/heads/main/data/appspoofing/app_spoof_presets.json"
    }

    private fun getSecure(key: String): String? = Settings.Secure.getString(context.contentResolver, key)

    private fun putSecure(key: String, value: String) {
        Settings.Secure.putString(context.contentResolver, key, value)
    }

    suspend fun readConfig(): Pair<Boolean, List<AppConfig>> = withContext(Dispatchers.IO) {
        val content = getSecure(CONFIG_KEY) ?: return@withContext false to emptyList()
        runCatching {
            val json = JSONObject(content)
            val enabled = json.optBoolean("enabled", false)
            val appsObj = json.optJSONObject("apps")
            enabled to appsObj?.keys()?.asSequence()?.map { 
                AppConfig(it, appsObj.getJSONObject(it).toStringMap()) 
            }?.toList().orEmpty()
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
                DeviceProfile(obj.getString("name"), obj.getJSONObject("props").toStringMap(), true)
            }
        }.onFailure { Log.e(TAG, "Failed to load custom presets", it) }.getOrDefault(emptyList())
    }

    suspend fun writeProfiles(profiles: List<DeviceProfile>) = withContext(Dispatchers.IO) {
        val array = JSONArray(profiles.filter { it.isCustom }.map { 
            JSONObject().put("name", it.name).put("props", JSONObject(it.props)) 
        })
        putSecure(PRESETS_KEY, array.toString())
    }

    private fun isCacheValid(): Boolean {
        val lastFetch = Settings.Secure.getLong(context.contentResolver, PRESET_TIMESTAMP_KEY, 0L)
        return (System.currentTimeMillis() - lastFetch) in 0 until PRESET_CACHE_TTL_MS
    }

    fun parseJsonData(jsonStr: String): Pair<List<AppConfig>, List<DeviceProfile>> = runCatching {
        val json = JSONObject(jsonStr)
        val appsObj = json.optJSONObject("apps")
        val parsedApps = appsObj?.keys()?.asSequence()?.map { pkg ->
            AppConfig(pkg, appsObj.getJSONObject(pkg).toStringMap())
        }?.toList().orEmpty()

        val profilesArray = json.optJSONArray("profiles")
        val parsedProfiles = if (profilesArray != null) {
            List(profilesArray.length()) { i ->
                val obj = profilesArray.getJSONObject(i)
                DeviceProfile(
                    name = obj.getString("name"),
                    props = obj.getJSONObject("props").toStringMap(),
                    isCustom = obj.optBoolean("isCustom", true)
                )
            }
        } else emptyList()

        parsedApps to parsedProfiles
    }.getOrDefault(emptyList<AppConfig>() to emptyList<DeviceProfile>())

    suspend fun fetchRemoteData(forceRefresh: Boolean = false): Pair<List<AppConfig>, List<DeviceProfile>> = withContext(Dispatchers.IO) {
        if (!forceRefresh && isCacheValid()) {
            val cached = getSecure(PRESET_CACHE_KEY)
            if (!cached.isNullOrBlank()) {
                return@withContext parseJsonData(cached)
            }
        }

        runCatching {
            val url = URI(REMOTE_SYNC_URL).toURL()
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if (text.isNotBlank()) {
                putSecure(PRESET_CACHE_KEY, text)
                Settings.Secure.putLong(context.contentResolver, PRESET_TIMESTAMP_KEY, System.currentTimeMillis())
                parseJsonData(text)
            } else {
                throw Exception("Empty response")
            }
        }.getOrElse {
            val cached = getSecure(PRESET_CACHE_KEY)
            if (!cached.isNullOrBlank()) parseJsonData(cached) else emptyList<AppConfig>() to emptyList<DeviceProfile>()
        }
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
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var isMasterEnabled by remember { mutableStateOf(false) }
    var configs by remember { mutableStateOf<List<AppConfig>>(emptyList()) }
    var profiles by remember { mutableStateOf<List<DeviceProfile>>(emptyList()) }
    var installedApps by remember { mutableStateOf<List<AppPickerEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var isAutoSyncing by remember { mutableStateOf(false) }
    var expandedPackage by remember { mutableStateOf<String?>(null) }
    var showProfileManager by remember { mutableStateOf(false) }

    var stablePinned by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(query) {
        if (query.isEmpty()) {
            stablePinned = configs.map { it.packageName }.toSet()
        }
    }

    LaunchedEffect(Unit) {
        val installed = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { appInfo ->
                    val pkg = appInfo.packageName
                    AppSpoofConstants.EXCLUDED_SUFFIXES.none { suffix -> pkg.endsWith(suffix) }
                }
                .map { appInfo ->
                    AppPickerEntry(appInfo, appInfo.loadLabel(pm).toString(), (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                }
        }
        installedApps = installed
        val validPkgs = installed.map { it.packageName }.toSet()

        val (enabled, apps) = controller.readConfig()
        val cleanedApps = apps.filter { it.packageName in validPkgs }
        
        if (cleanedApps.size != apps.size) {
            controller.writeConfig(enabled, cleanedApps)
        }

        isMasterEnabled = enabled
        configs = cleanedApps
        stablePinned = cleanedApps.map { it.packageName }.toSet()
        
        val customProfiles = controller.readProfiles()
        val (_, remoteProfiles) = controller.fetchRemoteData()
        val mergedProfiles = (AppSpoofConstants.DEFAULT_PROFILES + remoteProfiles + customProfiles).distinctBy { it.name }
        profiles = mergedProfiles
        
        isLoading = false
    }

    val importJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
                    val (importedApps, importedProfiles) = controller.parseJsonData(text)
                    val currentInstalled = installedApps.map { it.packageName }.toSet()

                    if (importedApps.isNotEmpty()) {
                        val validApps = importedApps.filter { it.packageName in currentInstalled }
                        controller.writeConfig(isMasterEnabled, validApps)
                        configs = validApps
                        if (query.isEmpty()) stablePinned = validApps.map { it.packageName }.toSet()
                    }

                    if (importedProfiles.isNotEmpty()) {
                        val mergedProfiles = (profiles + importedProfiles).distinctBy { it.name }
                        profiles = mergedProfiles
                        controller.writeProfiles(mergedProfiles)
                    }
                }
            }.onSuccess {
                Toast.makeText(context, context.getString(R.string.spoof_ap_import_success), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, context.getString(R.string.spoof_ap_import_failed, it.message.orEmpty()), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveConfigs(newConfigs: List<AppConfig>) {
        configs = newConfigs
        scope.launch { controller.writeConfig(isMasterEnabled, newConfigs) }
    }

    fun setConfig(pkg: String, props: Map<String, String>?) {
        val updated = if (props == null) {
            configs.filterNot { it.packageName == pkg }
        } else {
            configs.filterNot { it.packageName == pkg } + AppConfig(pkg, props)
        }
        saveConfigs(updated)
    }

    val configMap = remember(configs) { configs.associateBy { it.packageName } }

    val filteredApps = remember(query, showSystem, installedApps, stablePinned) {
        installedApps.filter {
            (showSystem || !it.isSystem || it.packageName in stablePinned) &&
            (query.isEmpty() || it.label.contains(query, true) || it.packageName.contains(query, true))
        }.sortedWith(compareByDescending<AppPickerEntry> { it.packageName in stablePinned }.thenBy { it.label.lowercase() })
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        LazyColumn(
            state = listState,
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
                    title = stringResource(R.string.spoof_ap_title),
                    summary = when {
                        !isMasterEnabled -> stringResource(R.string.spoof_ap_disabled)
                        configs.isNotEmpty() -> stringResource(R.string.spoof_ap_configured_apps, configs.size)
                        else -> stringResource(R.string.spoof_ap_no_apps)
                    },
                    checked = isMasterEnabled,
                    onCheckedChange = { checked ->
                        isMasterEnabled = checked
                        scope.launch { controller.writeConfig(checked, configs) }
                    }
                )
            }

            item { Spacer(Modifier.height(10.dp)) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { importJsonLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.spoof_ap_import), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { showProfileManager = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.spoof_ap_manage_profiles), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            item { Spacer(Modifier.height(6.dp)) }

            item {
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
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = showSystem,
                        onClick = { showSystem = !showSystem },
                        leadingIcon = if (showSystem) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        label = { Text(stringResource(R.string.spoof_ap_system_apps)) }
                    )

                    OutlinedButton(
                        onClick = {
                            isAutoSyncing = true
                            scope.launch {
                                val (remoteApps, remoteProfiles) = controller.fetchRemoteData(forceRefresh = true)
                                if (remoteProfiles.isNotEmpty()) {
                                    val mergedProfiles = (AppSpoofConstants.DEFAULT_PROFILES + remoteProfiles + profiles.filter { it.isCustom }).distinctBy { it.name }
                                    profiles = mergedProfiles
                                }
                                if (remoteApps.isNotEmpty()) {
                                    val currentInstalled = installedApps.map { it.packageName }.toSet()
                                    val validRemoteApps = remoteApps.filter { it.packageName in currentInstalled }
                                    if (validRemoteApps.isNotEmpty()) {
                                        controller.writeConfig(isMasterEnabled, validRemoteApps)
                                        configs = validRemoteApps
                                        if (query.isEmpty()) stablePinned = validRemoteApps.map { it.packageName }.toSet()
                                    }
                                }
                                isAutoSyncing = false
                                Toast.makeText(context, context.getString(R.string.spoof_ap_auto_sync_success), Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !isAutoSyncing,
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = if (isAutoSyncing) stringResource(R.string.spoof_ap_syncing) else stringResource(R.string.spoof_ap_auto_sync),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            if (isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
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

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showProfileManager) {
        ProfileManagerBottomSheet(
            profiles = profiles,
            onDismiss = { showProfileManager = false },
            onSaveProfiles = { newProfiles ->
                profiles = newProfiles
                scope.launch { controller.writeProfiles(newProfiles) }
            }
        )
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
        editing = profile ?: DeviceProfile("", emptyMap(), true)
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
        val saved = DeviceProfile(editName, form.toProps(), true)
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
            ProfileList(profiles, onClick = { profile ->
                if (profile.isCustom) openEditor(profile)
                else context.toast(R.string.spoof_ap_cannot_edit_builtin)
            }) { profile ->
                if (profile.isCustom) {
                    IconButton(onClick = { onSaveProfiles(profiles.filterNot { it.name == profile.name }) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
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
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SwitchWithCheck(checked = checked, onCheckedChange = onCheckedChange)
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
        isError = isError,
        shape = RoundedCornerShape(12.dp)
    )
}
