package com.hertzify.settings.fragments.miscellaneous

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment

class SpoofFragment : SettingsPreferenceFragment() {

    private val viewModel: SpoofViewModel by viewModels()

    private val pifFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> importPifFile(uri) }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.spoof_settings, rootKey)

        setupPifPreferences()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        observeViewModel()
    }

    private fun setupPifPreferences() {
        findPreference<Preference>("pif_fetch")?.setOnPreferenceClickListener {
            showPifSourcePicker(); true
        }
        
        findPreference<Preference>("pif_import_file")?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            pifFilePicker.launch(intent); true
        }

        findPreference<Preference>("pif_delete")?.setOnPreferenceClickListener {
            showDeletePifDialog(); true
        }

        findPreference<Preference>("pif_show_props")?.setOnPreferenceClickListener {
            showCurrentProps(); true
        }

        findPreference<SwitchPreferenceCompat>("pif_spoof_photos")?.apply {
            isChecked = viewModel.isSpoofPhotosEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                viewModel.setSpoofPhotos(newValue as Boolean); true
            }
        }
    }

    private fun observeViewModel() {
        viewModel.isFetching.observe(viewLifecycleOwner) { fetching ->
            findPreference<Preference>("pif_fetch")?.isEnabled = !fetching
            findPreference<Preference>("pif_import_file")?.isEnabled = !fetching
        }

        viewModel.configSummary.observe(viewLifecycleOwner) { summary ->
            val hasConfig = summary.isNotEmpty()
            findPreference<Preference>("pif_show_props")?.apply {
                this.summary = summary.ifEmpty { getString(R.string.pif_no_props) }
                this.isEnabled = hasConfig
            }
            findPreference<Preference>("pif_delete")?.isEnabled = hasConfig
        }

        viewModel.toastEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { (resId, extra) ->
                val message = if (extra != null) getString(resId, extra) else getString(resId)
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importPifFile(uri: Uri) {
        try {
            val content = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            val name = getFileName(uri) ?: "imported.json"
            if (!content.isNullOrBlank()) {
                viewModel.importConfig(name, content)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Import PIF failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        requireContext().contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
        return null
    }

    private fun showPifSourcePicker() {
        val sources = arrayOf(getString(R.string.pif_source_google), getString(R.string.pif_source_hertzify))
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pif_fetch_select_source)
            .setItems(sources) { _, which ->
                viewModel.fetchAndApply(if (which == 0) PifRepository.Source.GOOGLE else PifRepository.Source.HERTZIFY)
            }.show()
    }

    private fun showCurrentProps() {
        val props = viewModel.getCurrentProperties()
        if (props.isEmpty()) return
        val message = props.entries.joinToString("\n") { "${it.key} = ${it.value}" }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pif_show_props_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun showDeletePifDialog() {
        val activeState = viewModel.getConfigStates().find { it.exists } ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pif_delete_title)
            .setMessage(getString(R.string.pif_delete_confirm, activeState.fileName))
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.deleteConfig(activeState.fileName) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        activity?.title = getString(R.string.spoof_screen_title)
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.HERTZIFY
}