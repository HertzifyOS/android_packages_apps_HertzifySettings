package com.hertzify.settings.fragments.miscellaneous

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrickyStore : SettingsPreferenceFragment() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var targetManager: TrickyStoreTargetManager

    private val keyboxPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                importFile(uri, KEYBOX_FILE, R.string.ts_keybox_imported)
            }
        }
    }

    private val targetPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                importFile(uri, TARGET_FILE, R.string.ts_target_list_imported)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.tricky_store)

        targetManager = TrickyStoreTargetManager(
            context = requireContext(),
            scope = scope,
            storePath = TRICKYSTORE_PATH,
            targetFileName = TARGET_FILE,
            onSaved = {
                refreshStatus()
                killPackage(VENDING_PACKAGE)
            }
        )

        findPreference<Preference>("ts_import_keybox")?.setOnPreferenceClickListener {
            launchPicker(keyboxPicker, "*/*")
            true
        }

        findPreference<Preference>("ts_delete_keybox")?.setOnPreferenceClickListener {
            showDeleteKeyboxDialog()
            true
        }

        findPreference<Preference>("ts_manage_targets")?.setOnPreferenceClickListener {
            targetManager.showTargetAppPicker()
            true
        }

        findPreference<Preference>("ts_import_targets")?.setOnPreferenceClickListener {
            launchPicker(targetPicker, "text/*")
            true
        }

        refreshStatus()
    }

    private fun launchPicker(launcher: androidx.activity.result.ActivityResultLauncher<Intent>, mimeType: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
        }
        launcher.launch(intent)
    }

    private fun importFile(uri: android.net.Uri, fileName: String, successResId: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(TRICKYSTORE_PATH).also { if (!it.exists()) it.mkdirs() }
                val file = File(dir, fileName)
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file.setReadable(true, false)
                withContext(Dispatchers.Main) {
                    toast(getString(successResId))
                    if (fileName == KEYBOX_FILE) killPackage(VENDING_PACKAGE)
                    refreshStatus()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.ts_failed, e.message ?: ""))
                }
            }
        }
    }

    private fun refreshStatus() {
        scope.launch(Dispatchers.IO) {
            val keyboxExists = File(TRICKYSTORE_PATH, KEYBOX_FILE).exists()
            val targetFile = File(TRICKYSTORE_PATH, TARGET_FILE)
            val targetCount = if (targetFile.exists()) {
                targetFile.readLines().count { it.isNotBlank() }
            } else 0

            withContext(Dispatchers.Main) {
                findPreference<Preference>("ts_import_keybox")?.summary =
                    if (keyboxExists) getString(R.string.ts_keybox_installed)
                    else getString(R.string.ts_no_keybox)

                findPreference<Preference>("ts_delete_keybox")?.isEnabled = keyboxExists

                findPreference<Preference>("ts_manage_targets")?.summary =
                    if (targetCount > 0) getString(R.string.ts_target_apps_count, targetCount)
                    else getString(R.string.ts_no_targets)
            }
        }
    }

    private fun showDeleteKeyboxDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ts_delete_keybox_title)
            .setMessage(R.string.ts_delete_keybox_message)
            .setPositiveButton(R.string.ts_delete) { _, _ ->
                scope.launch(Dispatchers.IO) {
                    try {
                        File(TRICKYSTORE_PATH, KEYBOX_FILE).delete()
                        withContext(Dispatchers.Main) {
                            killPackage(VENDING_PACKAGE)
                            toast(getString(R.string.ts_keybox_deleted))
                            refreshStatus()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            toast(getString(R.string.ts_failed, e.message ?: ""))
                        }
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

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.HERTZIFY

    companion object {
        private const val TRICKYSTORE_PATH = "/data/adb/tricky_store"
        private const val KEYBOX_FILE = "keybox.xml"
        private const val TARGET_FILE = "target.txt"
        private const val VENDING_PACKAGE = "com.android.vending"
    }
}