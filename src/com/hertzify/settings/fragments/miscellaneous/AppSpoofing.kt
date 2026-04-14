package com.hertzify.settings.fragments.miscellaneous

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSpoofing : SettingsPreferenceFragment() {

    private val scope      = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var enabled    = false
    private val configs    = mutableListOf<AppConfig>()
    private val profiles   = mutableListOf<DeviceProfile>()

    private lateinit var controller: AppSpoofController
    private lateinit var dialogHelper: AppSpoofDialogHelper

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { performExport(it) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { performImport(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.app_spoofing)

        controller   = AppSpoofController(requireContext())
        dialogHelper = AppSpoofDialogHelper(
            context       = requireContext(),
            scope         = scope,
            controller    = controller,
            profiles      = profiles,
            configs       = configs,
            onDataChanged = { populateAppList() },
            getEnabled    = { enabled }
        )

        findPreference<SwitchPreferenceCompat>("as_enabled")?.setOnPreferenceChangeListener { _, v ->
            enabled = v as Boolean
            scope.launch(Dispatchers.IO) { controller.writeConfig(enabled, configs) }
            true
        }

        findPreference<Preference>("as_add_app")?.setOnPreferenceClickListener {
            dialogHelper.showAddAppDialog(); true
        }

        findPreference<Preference>("as_manage_profiles")?.setOnPreferenceClickListener {
            dialogHelper.showManageProfilesDialog(); true
        }

        findPreference<Preference>("as_backup_manage")?.setOnPreferenceClickListener {
            showBackupRestoreDialog()
            true
        }

        loadAll()
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.HERTZIFY

    private fun showBackupRestoreDialog() {
        val options = arrayOf(
            getString(R.string.as_backup_export),
            getString(R.string.as_backup_import)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.as_backup_dialog_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportLauncher.launch("app_spoof.json")
                    1 -> importLauncher.launch("application/json")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performExport(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val success = controller.exportToJson(uri, configs, profiles)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, if (success) R.string.as_export_success else R.string.as_export_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performImport(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val success = controller.importFromJson(uri)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(context, R.string.as_import_success, Toast.LENGTH_SHORT).show()
                    loadAll()
                } else {
                    Toast.makeText(context, R.string.as_import_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadAll() {
        scope.launch {
            val (cfg, profs) = withContext(Dispatchers.IO) {
                controller.readConfig() to controller.readProfiles()
            }
            enabled = cfg.first
            profiles.clear(); profiles.addAll(profs)

            val pm = requireContext().packageManager
            configs.clear()
            configs.addAll(cfg.second.map { app ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(app.packageName, 0)).toString()
                } catch (_: PackageManager.NameNotFoundException) { app.packageName }
                app.copy(appName = label)
            })

            findPreference<SwitchPreferenceCompat>("as_enabled")?.isChecked = enabled
            populateAppList()
        }
    }

    private fun populateAppList() {
        val category = findPreference<PreferenceCategory>("as_apps_category") ?: return
        category.removeAll()

        if (configs.isEmpty()) {
            category.addPreference(Preference(requireContext()).apply {
                title   = getString(R.string.as_no_apps)
                summary = getString(R.string.as_no_apps_summary)
                isSelectable = false
            })
            return
        }

        val pm = requireContext().packageManager
        configs.forEach { app ->
            val propsText = app.props.entries.joinToString(", ") { "${it.key}=${it.value}" }
            category.addPreference(Preference(requireContext()).apply {
                title   = app.appName
                summary = "${app.packageName}\n$propsText"
                try {
                    val ai = pm.getApplicationInfo(app.packageName, 0)
                    icon = pm.getApplicationIcon(ai)
                } catch (_: PackageManager.NameNotFoundException) { }
                setOnPreferenceClickListener {
                    dialogHelper.showAppEditorDialog(app.packageName, app.appName, app)
                    true
                }
            })
        }
    }
}