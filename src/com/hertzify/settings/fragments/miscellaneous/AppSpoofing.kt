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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.SettingsPreferenceFragment
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
// DATA MODELS
// ============================================================================

data class AppConfig(
    val packageName: String,
    val appName: String,
    val props: Map<String, String>
)

data class DeviceProfile(
    val name: String,
    val props: Map<String, String>
)

data class AppPickerEntry(
    val packageName: String,
    val label: String,
    val info: ApplicationInfo,
    val pm: PackageManager
) {
    private var _icon: Drawable? = null

    fun getIcon(): Drawable? {
        if (_icon == null) {
            _icon = try {
                info.loadIcon(pm)
            } catch (e: Exception) {
                null
            }
        }
        return _icon
    }
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
}

// ============================================================================
// CONTROLLER
// ============================================================================

class AppSpoofController(private val context: Context) {
    companion object {
        private const val TAG = "AppSpoofController"
        private const val CONFIG_KEY = "spoof_appprops_config"
        const val PRESETS_KEY = "app_spoofing_user_presets"
    }

    suspend fun readConfig(): Pair<Boolean, List<AppConfig>> = withContext(Dispatchers.IO) {
        val content = Settings.Secure.getString(context.contentResolver, CONFIG_KEY)
            ?: return@withContext false to emptyList()

        return@withContext try {
            val json = JSONObject(content)
            val enabled = json.optBoolean("enabled", false)
            val appsObj = json.optJSONObject("apps") ?: return@withContext enabled to emptyList()

            val apps = appsObj.keys().asSequence().map { pkg ->
                val propsObj = appsObj.getJSONObject(pkg)
                val props = propsObj.keys().asSequence().associateWith { propsObj.getString(it) }
                AppConfig(pkg, pkg, props)
            }.toList()

            enabled to apps
        } catch (e: Exception) {
            Log.e(TAG, "readConfig error", e)
            false to emptyList()
        }
    }

    suspend fun writeConfig(enabled: Boolean, apps: List<AppConfig>) = withContext(Dispatchers.IO) {
        try {
            val appsObj = JSONObject().apply {
                apps.forEach { app ->
                    put(app.packageName, JSONObject().apply {
                        app.props.forEach { (k, v) -> put(k, v) }
                    })
                }
            }
            val json = JSONObject().apply {
                put("enabled", enabled)
                put("apps", appsObj)
            }

            Settings.Secure.putString(
                context.contentResolver,
                CONFIG_KEY,
                json.toString(2)
            )
        } catch (e: Exception) {
            Log.e(TAG, "writeConfig error", e)
        }
    }

    fun readProfiles(): List<DeviceProfile> {
        val jsonString = Settings.Secure.getString(context.contentResolver, PRESETS_KEY) ?: return emptyList()
        val profiles = mutableListOf<DeviceProfile>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.getString("name")
                val propsObj = obj.getJSONObject("props")
                val props = propsObj.keys().asSequence().associateWith { propsObj.getString(it) }
                profiles.add(DeviceProfile(name, props.toMutableMap()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom presets", e)
        }
        return profiles
    }

    fun writeProfiles(profiles: List<DeviceProfile>) {
        val jsonArray = JSONArray()
        profiles.forEach { p ->
            val obj = JSONObject()
            obj.put("name", p.name)
            val propsObj = JSONObject()
            p.props.forEach { (k, v) -> propsObj.put(k, v) }
            obj.put("props", propsObj)
            jsonArray.put(obj)
        }
        Settings.Secure.putString(context.contentResolver, PRESETS_KEY, jsonArray.toString())
    }
}

// ============================================================================
// UI FRAGMENT & COMPOSABLES
// ============================================================================

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
        setContent {
            SettingsTheme {
                AppSpoofingMainScreen(controller, requireContext().packageManager)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSpoofingMainScreen(controller: AppSpoofController, pm: PackageManager) {
    val scope = rememberCoroutineScope()
    var isMasterEnabled by remember { mutableStateOf(false) }
    var configs by remember { mutableStateOf<List<AppConfig>>(emptyList()) }
    var profiles by remember { mutableStateOf<List<DeviceProfile>>(emptyList()) }

    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<AppPickerEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var expandedPackage by remember { mutableStateOf<String?>(null) }
    var showProfileManager by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (enabledState, appsConfig) = controller.readConfig()
        isMasterEnabled = enabledState
        configs = appsConfig

        val customProfiles = controller.readProfiles()
        if (customProfiles.isEmpty()) {
            profiles = AppSpoofConstants.DEFAULT_PROFILES
            controller.writeProfiles(profiles)
        } else {
            profiles = customProfiles
        }

        withContext(Dispatchers.IO) {
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .map { AppPickerEntry(it.packageName, it.loadLabel(pm).toString(), it, pm) }
                .sortedBy { it.label.lowercase() }
            withContext(Dispatchers.Main) {
                installedApps = apps
                isLoading = false
            }
        }
    }

    fun saveConfigs(newConfigs: List<AppConfig>) {
        configs = newConfigs
        scope.launch { controller.writeConfig(isMasterEnabled, newConfigs) }
    }

    val filteredApps = remember(searchQuery, showSystemApps, installedApps, configs) {
        installedApps.filter { app ->
            val isSys = (app.info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val matchesSys = showSystemApps || !isSys
            val matchesSearch = searchQuery.isEmpty() ||
                    app.label.contains(searchQuery, true) ||
                    app.packageName.contains(searchQuery, true)
            matchesSys && matchesSearch
        }.sortedWith(
            compareByDescending<AppPickerEntry> { entry -> configs.any { it.packageName == entry.packageName } }
                .thenBy { it.label.lowercase() }
        )
    }

    Scaffold(containerColor = Color.Transparent) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Dashboard Card Header
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "App Spoofing",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isMasterEnabled) "Spoofing active" else "Spoofing disabled",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isMasterEnabled,
                        onCheckedChange = {
                            isMasterEnabled = it
                            scope.launch { controller.writeConfig(isMasterEnabled, configs) }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = showSystemApps,
                    onClick = { showSystemApps = !showSystemApps },
                    label = { Text("Show system apps") }
                )

                TextButton(onClick = { showProfileManager = true }) {
                    Text("Manage Profiles")
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val currentConfig = configs.find { it.packageName == app.packageName }
                        val isAppEnabled = currentConfig != null
                        val isExpanded = expandedPackage == app.packageName

                        ExpandableAppSpoofItem(
                            app = app,
                            config = currentConfig,
                            isExpanded = isExpanded,
                            isEnabled = isAppEnabled,
                            profiles = profiles,
                            onToggleExpand = {
                                expandedPackage = if (isExpanded) null else app.packageName
                            },
                            onCheckedChange = { checked ->
                                if (checked) {
                                    val newConfig = AppConfig(app.packageName, app.label, emptyMap())
                                    saveConfigs(configs + newConfig)
                                    expandedPackage = app.packageName
                                } else {
                                    saveConfigs(configs.filterNot { it.packageName == app.packageName })
                                    if (isExpanded) expandedPackage = null
                                }
                            },
                            onSaveConfig = { updatedConfig ->
                                val mutableConfigs = configs.toMutableList()
                                val idx = mutableConfigs.indexOfFirst { it.packageName == updatedConfig.packageName }
                                if (idx >= 0) mutableConfigs[idx] = updatedConfig else mutableConfigs.add(updatedConfig)
                                saveConfigs(mutableConfigs)
                                expandedPackage = null
                            }
                        )
                    }
                }
            }
        }
    }

    if (showProfileManager) {
        ProfileManagerDialog(
            profiles = profiles,
            onDismiss = { showProfileManager = false },
            onSaveProfiles = { newProfiles ->
                profiles = newProfiles
                controller.writeProfiles(newProfiles)
            }
        )
    }
}

@Composable
fun ExpandableAppSpoofItem(
    app: AppPickerEntry,
    config: AppConfig?,
    isExpanded: Boolean,
    isEnabled: Boolean,
    profiles: List<DeviceProfile>,
    onToggleExpand: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onSaveConfig: (AppConfig) -> Unit
) {
    var manufacturer by remember(config) { mutableStateOf(config?.props?.get(AppSpoofConstants.FIELD_MANUFACTURER) ?: "") }
    var model by remember(config) { mutableStateOf(config?.props?.get(AppSpoofConstants.FIELD_MODEL) ?: "") }
    val dynamicProps = remember(config) {
        mutableStateListOf(
            *(config?.props?.filter {
                it.key != AppSpoofConstants.FIELD_MODEL && it.key != AppSpoofConstants.FIELD_MANUFACTURER
            }?.toList()?.toTypedArray() ?: emptyArray())
        )
    }
    var showProfilePicker by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        color = if (isEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!isEnabled) {
                            onCheckedChange(true)
                        } else {
                            onToggleExpand()
                        }
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AndroidView(
                    factory = { ImageView(it).apply { setImageDrawable(app.getIcon()) } },
                    update = { view -> view.setImageDrawable(app.getIcon()) },
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label, 
                        style = MaterialTheme.typography.bodyLarge, 
                        fontWeight = FontWeight.SemiBold, 
                        maxLines = 1, 
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isEnabled) "$manufacturer $model".trim().ifEmpty { "Configured" } else app.packageName, 
                        style = MaterialTheme.typography.bodySmall, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant, 
                        maxLines = 1, 
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Checkbox(checked = isEnabled, onCheckedChange = onCheckedChange)
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 0.dp)
                ) {
                    OutlinedTextField(
                        value = manufacturer,
                        onValueChange = { manufacturer = it },
                        label = { Text("Manufacturer") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Custom Properties", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                    dynamicProps.forEachIndexed { index, prop ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = prop.first,
                                onValueChange = { dynamicProps[index] = it to prop.second },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Key") },
                                singleLine = true
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = prop.second,
                                onValueChange = { dynamicProps[index] = prop.first to it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Value") },
                                singleLine = true
                            )
                            IconButton(onClick = { dynamicProps.removeAt(index) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove")
                            }
                        }
                    }
                    TextButton(onClick = { dynamicProps.add("" to "") }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("+ Add Property")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { showProfilePicker = true }) {
                            Text("Load Profile")
                        }

                        Row {
                            TextButton(onClick = onToggleExpand) {
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                val newProps = mutableMapOf(
                                    AppSpoofConstants.FIELD_MANUFACTURER to manufacturer,
                                    AppSpoofConstants.FIELD_MODEL to model
                                )
                                dynamicProps.filter { it.first.isNotBlank() }.forEach { newProps[it.first] = it.second }
                                onSaveConfig(AppConfig(app.packageName, app.label, newProps))
                            }) {
                                Text("Save")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showProfilePicker) {
        AlertDialog(
            onDismissRequest = { showProfilePicker = false },
            title = { Text("Select Profile") },
            text = {
                LazyColumn {
                    items(profiles) { profile ->
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    manufacturer = profile.props[AppSpoofConstants.FIELD_MANUFACTURER] ?: ""
                                    model = profile.props[AppSpoofConstants.FIELD_MODEL] ?: ""
                                    dynamicProps.clear()
                                    profile.props.filter {
                                        it.key != AppSpoofConstants.FIELD_MODEL && it.key != AppSpoofConstants.FIELD_MANUFACTURER
                                    }.forEach { dynamicProps.add(it.key to it.value) }
                                    showProfilePicker = false
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProfilePicker = false }) { Text("Close") }
            }
        )
    }
}

@Composable
fun ProfileManagerDialog(
    profiles: List<DeviceProfile>,
    onDismiss: () -> Unit,
    onSaveProfiles: (List<DeviceProfile>) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Profiles") },
        text = {
            if (profiles.isEmpty()) {
                Text("No custom profiles.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn {
                    items(profiles) { profile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.name, fontWeight = FontWeight.Bold)
                                Text("${profile.props[AppSpoofConstants.FIELD_MANUFACTURER]} ${profile.props[AppSpoofConstants.FIELD_MODEL]}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = {
                                val newProfiles = profiles.toMutableList().apply { remove(profile) }
                                onSaveProfiles(newProfiles)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { showAddDialog = true }) { Text("Add Profile") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )

    if (showAddDialog) {
        var newName by remember { mutableStateOf("") }
        var newManu by remember { mutableStateOf("") }
        var newModel by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New Profile") },
            text = {
                Column {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Profile Name") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = newManu, onValueChange = { newManu = it }, label = { Text("Manufacturer") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = newModel, onValueChange = { newModel = it }, label = { Text("Model") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newName.isNotBlank() && newManu.isNotBlank() && newModel.isNotBlank()) {
                        val newProfile = DeviceProfile(
                            name = newName,
                            props = mapOf(AppSpoofConstants.FIELD_MANUFACTURER to newManu, AppSpoofConstants.FIELD_MODEL to newModel)
                        )
                        onSaveProfiles(profiles + newProfile)
                        showAddDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}