package com.hertzify.settings.fragments.miscellaneous

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.settings.R
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import java.io.BufferedReader
import java.io.InputStreamReader

class AppSpoofFragment : SettingsBasePreferenceFragment() {

    private lateinit var controller: AppSpoofController
    private lateinit var dialogHelper: AppSpoofDialogHelper
    
    private val mConfigs = mutableListOf<AppConfig>()
    private val mProfiles = mutableListOf<DeviceProfile>()
    private var mEnabled = false

    companion object {
        private const val REQUEST_CODE_IMPORT = 100
        private const val REQUEST_CODE_EXPORT = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.app_spoof_settings, rootKey)
        
        controller = AppSpoofController(requireContext())
        
        mProfiles.clear()
        val savedProfiles = controller.loadProfiles()
        if (savedProfiles.isEmpty()) {
            mProfiles.addAll(AppSpoofConstants.DEFAULT_PROFILES)
            controller.saveProfiles(mProfiles)
        } else {
            mProfiles.addAll(savedProfiles)
        }

        dialogHelper = AppSpoofDialogHelper(
            fragment = this,
            controller = controller,
            profiles = mProfiles,
            configs = mConfigs,
            onDataChanged = { 
                loadConfig()
                refreshAppPreferences()
            }
        )

        loadConfig()
        bindPreferences()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.clear()
        
        menu.add(0, R.id.menu_add_app, 0, R.string.app_spoof_menu_new)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                
        menu.add(0, AppSpoofConstants.MENU_MANAGE_PROFILES, 0, R.string.app_spoof_manage_profiles)
        menu.add(0, AppSpoofConstants.MENU_IMPORT_JSON, 0, R.string.app_spoof_menu_import)
        menu.add(0, AppSpoofConstants.MENU_EXPORT_JSON, 0, R.string.app_spoof_menu_export)
        menu.add(0, AppSpoofConstants.MENU_CLEAR_ALL, 0, R.string.app_spoof_menu_clear_all)
        
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_add_app -> {
                dialogHelper.showAddAppDialog()
                true
            }
            AppSpoofConstants.MENU_MANAGE_PROFILES -> {
                dialogHelper.showManageProfilesDialog()
                true
            }
            AppSpoofConstants.MENU_IMPORT_JSON -> {
                handleImportJson()
                true
            }
            AppSpoofConstants.MENU_EXPORT_JSON -> {
                handleExportJson()
                true
            }
            AppSpoofConstants.MENU_CLEAR_ALL -> {
                handleClearAll()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadConfig() {
        mEnabled = controller.isEnabled()
        val rawConfigs = controller.loadAppConfigs()
        val pm = requireContext().packageManager
        
        mConfigs.clear()
        rawConfigs.forEach { config ->
            val label = try {
                val ai = pm.getApplicationInfo(config.packageName, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) { 
                config.packageName 
            }
            
            val profileName = mProfiles.find { it.props == config.props }?.name 
                ?: (config.props[AppSpoofConstants.FIELD_MODEL] ?: getString(R.string.app_spoof_custom))
            
            mConfigs.add(AppConfig(config.packageName, label, profileName, config.props))
        }
    }

    private fun bindPreferences() {
        findPreference<SwitchPreferenceCompat>(AppSpoofConstants.KEY_ENABLED)?.apply {
            isChecked = mEnabled
            setOnPreferenceChangeListener { _, newValue ->
                mEnabled = newValue as Boolean
                controller.saveAppConfigs(mEnabled, mConfigs)
                true
            }
        }

        refreshAppPreferences()
    }

    private fun refreshAppPreferences() {
        val cat = findPreference<PreferenceCategory>(AppSpoofConstants.KEY_APP_LIST_CAT) ?: return
        cat.removeAll()

        val pm = requireContext().packageManager
        mConfigs.forEach { config ->
            val pref = Preference(requireContext()).apply {
                key = "app_spoof_entry_${config.packageName}"
                summary = config.profileName
                try {
                    val ai = pm.getApplicationInfo(config.packageName, 0)
                    title = pm.getApplicationLabel(ai)
                    icon = ai.loadIcon(pm)
                } catch (e: Exception) {
                    title = config.packageName
                }
                setOnPreferenceClickListener { 
                    dialogHelper.showEditAppDialog(config)
                    true 
                }
            }
            cat.addPreference(pref)
        }

        if (mConfigs.isEmpty()) {
            cat.addPreference(Preference(requireContext()).apply {
                setTitle(R.string.app_spoof_no_apps)
                isEnabled = false
            })
        }
    }

    private fun handleImportJson() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.app_spoof_menu_import)
            .setMessage(R.string.app_spoof_msg_import_confirm)
            .setPositiveButton(R.string.app_spoof_btn_import_confirm) { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }
                startActivityForResult(intent, REQUEST_CODE_IMPORT)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun handleExportJson() {
        val jsonContent = controller.exportToJson()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "appprops.json")
        }
        
        requireActivity().intent.putExtra("export_json", jsonContent)
        startActivityForResult(intent, REQUEST_CODE_EXPORT)
    }

    private fun handleClearAll() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.app_spoof_clear_all_title)
            .setMessage(R.string.app_spoof_clear_all_msg)
            .setPositiveButton(R.string.app_spoof_btn_clear) { _, _ ->
                controller.clearAllApps()
                loadConfig()
                refreshAppPreferences()
                Toast.makeText(requireContext(), R.string.app_spoof_msg_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (resultCode != Activity.RESULT_OK || data == null) return
        
        when (requestCode) {
            REQUEST_CODE_IMPORT -> {
                data?.data?.let { uri ->
                    try {
                        val jsonContent = requireContext().contentResolver
                            .openInputStream(uri)
                            .use { BufferedReader(InputStreamReader(it)).readText() }

                        if (controller.importFromJson(jsonContent)) {
                            mEnabled = controller.isEnabled()
                            loadConfig()
                            refreshAppPreferences()
                            findPreference<SwitchPreferenceCompat>(AppSpoofConstants.KEY_ENABLED)?.isChecked = mEnabled
                            Toast.makeText(requireContext(), R.string.app_spoof_msg_import_success, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), R.string.app_spoof_msg_import_fail, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), getString(R.string.app_spoof_msg_import_error, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
            REQUEST_CODE_EXPORT -> {
                data.data?.let { uri ->
                    try {
                        val jsonContent = requireActivity().intent.getStringExtra("export_json") ?: return
                        val outputStream = requireContext().contentResolver.openOutputStream(uri)
                        outputStream?.write(jsonContent.toByteArray())
                        outputStream?.close()
                        Toast.makeText(requireContext(), R.string.app_spoof_msg_export_success, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), getString(R.string.app_spoof_msg_export_error, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}