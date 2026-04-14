package com.hertzify.settings.fragments.miscellaneous

import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSpoofing : SettingsPreferenceFragment() {

    private var enabled  = false
    private val configs  = mutableListOf<AppConfig>()
    private val profiles = AppSpoofConstants.DEFAULT_PROFILES.toMutableList()

    private lateinit var controller: AppSpoofController
    private lateinit var dialogHelper: AppSpoofDialogHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.app_spoofing)

        controller = AppSpoofController(requireContext())
        dialogHelper = AppSpoofDialogHelper(
            context = requireContext(),
            scope = lifecycleScope,
            controller = controller,
            profiles = profiles,
            configs = configs,
            onDataChanged = { populateAppList() },
            getEnabled = { enabled }
        )

        findPreference<SwitchPreferenceCompat>("spoof_ap_enabled")?.setOnPreferenceChangeListener { _, v ->
            enabled = v as Boolean
            lifecycleScope.launch {
                controller.writeConfig(enabled, configs) 
            }
            true
        }

        findPreference<Preference>("spoof_ap_add_app")?.setOnPreferenceClickListener {
            dialogHelper.showAddAppDialog()
            true
        }

        findPreference<Preference>("spoof_ap_manage_profiles")?.setOnPreferenceClickListener {
            dialogHelper.showManageProfilesDialog()
            true
        }

        loadAll()
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.HERTZIFY

    private fun loadAll() {
        lifecycleScope.launch {
            val cfg = controller.readConfig()
            enabled = cfg.first

            val customProfiles = controller.readProfiles()
            profiles.clear()
            
            val hasCustomPresets = Settings.Secure.getString(
                requireContext().contentResolver, 
                AppSpoofController.PRESETS_KEY
            ) != null

            if (customProfiles.isEmpty() && !hasCustomPresets) {
                profiles.addAll(AppSpoofConstants.DEFAULT_PROFILES)
                controller.writeProfiles(profiles)
            } else {
                profiles.addAll(customProfiles)
            }

            val pm = requireContext().packageManager
            configs.clear()
            
            withContext(Dispatchers.IO) {
                val updatedConfigs = cfg.second.map { app ->
                    val label = try {
                        val ai = pm.getApplicationInfo(app.packageName, 0)
                        pm.getApplicationLabel(ai).toString()
                    } catch (_: PackageManager.NameNotFoundException) {
                        app.packageName
                    }
                    app.copy(appName = label)
                }
                withContext(Dispatchers.Main) {
                    configs.addAll(updatedConfigs)
                }
            }

            findPreference<SwitchPreferenceCompat>("spoof_ap_enabled")?.isChecked = enabled
            populateAppList()
        }
    }

    private fun populateAppList() {
        val category = findPreference<PreferenceCategory>("spoof_ap_apps_category") ?: return
        category.removeAll()

        if (configs.isEmpty()) {
            category.addPreference(Preference(requireContext()).apply {
                title = getString(R.string.spoof_ap_no_apps)
                summary = getString(R.string.spoof_ap_no_apps_summary)
                isSelectable = false
            })
            return
        }

        val pm = requireContext().packageManager
        configs.forEach { app ->
            val propsText = app.props.entries.joinToString(", ") { "${it.key}=${it.value}" }
            
            val pref = Preference(requireContext()).apply {
                title = app.appName
                summary = "${app.packageName}\n$propsText"
                setOnPreferenceClickListener {
                    dialogHelper.showAppEditorDialog(app.packageName, app.appName, app)
                    true
                }
            }
            category.addPreference(pref)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val ai = pm.getApplicationInfo(app.packageName, 0)
                    val iconDrawable = pm.getApplicationIcon(ai)
                    withContext(Dispatchers.Main) {
                        pref.icon = iconDrawable
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                    withContext(Dispatchers.Main) {
                        pref.setIcon(android.R.drawable.sym_def_app_icon)
                    }
                }
            }
        }
    }
}