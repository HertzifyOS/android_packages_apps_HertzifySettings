package com.hertzify.settings.fragments.miscellaneous

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrickyStore : SettingsPreferenceFragment() {

    private lateinit var targetManager: TrickyStoreTargetManager

    private val keyboxPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                lifecycleScope.launch {
                    try {
                        val resolver = requireContext().contentResolver
                        withContext(Dispatchers.IO) {
                            val keyboxFile = File(TRICKYSTORE_PATH, KEYBOX_FILE)
                            resolver.openInputStream(uri)?.use { input ->
                                keyboxFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            keyboxFile.setReadable(true, false)
                        }
                        
                        killPackage(VENDING_PACKAGE)
                        toast(getString(R.string.spoof_ts_keybox_imported))
                        refreshStatus()
                    } catch (e: Exception) {
                        toast(getString(R.string.spoof_ts_failed, e.message ?: ""))
                    }
                }
            }
        }
    }

    private val targetPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                lifecycleScope.launch {
                    try {
                        val resolver = requireContext().contentResolver
                        withContext(Dispatchers.IO) {
                            val targetFile = File(TRICKYSTORE_PATH, TARGET_FILE)
                            resolver.openInputStream(uri)?.use { input ->
                                targetFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            targetFile.setReadable(true, false)
                        }
                        
                        toast(getString(R.string.spoof_ts_target_list_imported))
                        refreshStatus()
                    } catch (e: Exception) {
                        toast(getString(R.string.spoof_ts_failed, e.message ?: ""))
                    }
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.tricky_store)

        targetManager = TrickyStoreTargetManager(
            context = requireContext(),
            scope = lifecycleScope, 
            storePath = TRICKYSTORE_PATH,
            targetFileName = TARGET_FILE,
            onSaved = {
                refreshStatus()
                killPackage(VENDING_PACKAGE)
                toast(getString(R.string.spoof_ts_targets_saved))
            }
        )

        findPreference<Preference>("spoof_ts_import_keybox")?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            keyboxPicker.launch(intent)
            true
        }

        findPreference<Preference>("spoof_ts_delete_keybox")?.setOnPreferenceClickListener {
            showDeleteKeyboxDialog()
            true
        }

        findPreference<Preference>("spoof_ts_manage_targets")?.setOnPreferenceClickListener {
            targetManager.showTargetAppPicker()
            true
        }

        findPreference<Preference>("spoof_ts_import_targets")?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/*"
            }
            targetPicker.launch(intent)
            true
        }

        findPreference<Preference>("spoof_ts_clear_targets")?.setOnPreferenceClickListener {
            showClearTargetsDialog()
            true
        }

        refreshStatus()
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val (keyboxExists, targetCount) = withContext(Dispatchers.IO) {
                val hasKeybox = File(TRICKYSTORE_PATH, KEYBOX_FILE).exists()
                val targetFile = File(TRICKYSTORE_PATH, TARGET_FILE)
                val count = if (targetFile.exists()) {
                    val installedPackages = requireContext().packageManager
                        .getInstalledApplications(PackageManager.GET_META_DATA)
                        .map { it.packageName }
                        .toHashSet()
                        
                    targetFile.readLines()
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .map { line ->
                            TrickyStoreTargetManager.TargetMode.fromLine(line.trim()).first 
                        }
                        .count { it in installedPackages }
                } else 0

                Pair(hasKeybox, count)
            }

            findPreference<Preference>("spoof_ts_import_keybox")?.summary =
                if (keyboxExists) getString(R.string.spoof_ts_keybox_installed)
                else getString(R.string.spoof_ts_no_keybox)
            findPreference<Preference>("spoof_ts_delete_keybox")?.isEnabled = keyboxExists
            findPreference<Preference>("spoof_ts_manage_targets")?.summary =
                if (targetCount > 0) getString(R.string.spoof_ts_target_apps_count, targetCount)
                else getString(R.string.spoof_ts_no_targets)
        }
    }

    private fun showDeleteKeyboxDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.spoof_ts_delete_keybox_title)
            .setMessage(R.string.spoof_ts_delete_keybox_message)
            .setPositiveButton(R.string.spoof_ts_delete) { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            File(TRICKYSTORE_PATH, KEYBOX_FILE).delete()
                        }
                        killPackage(VENDING_PACKAGE)
                        toast(getString(R.string.spoof_ts_keybox_deleted))
                        refreshStatus()
                    } catch (e: Exception) {
                        toast(getString(R.string.spoof_ts_failed, e.message ?: ""))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showClearTargetsDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.spoof_ts_clear_targets_title)
            .setMessage(R.string.spoof_ts_clear_targets_msg)
            .setPositiveButton(R.string.spoof_ts_delete) { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            File(TRICKYSTORE_PATH, TARGET_FILE).delete()
                        }
                        killPackage(VENDING_PACKAGE)
                        toast(getString(R.string.spoof_ts_targets_cleared))
                        refreshStatus()
                    } catch (e: Exception) {
                        toast(getString(R.string.spoof_ts_failed, e.message ?: ""))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun killPackage(packageName: String) {
        try {
            val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.forceStopPackage(packageName)
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.HERTZIFY

    companion object {
        private const val TRICKYSTORE_PATH = "/data/adb/tricky_store"
        private const val KEYBOX_FILE = "keybox.xml"
        private const val TARGET_FILE = "target.txt"
        private const val VENDING_PACKAGE = "com.android.vending"
    }
}