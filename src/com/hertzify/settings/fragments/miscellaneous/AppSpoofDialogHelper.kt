package com.hertzify.settings.fragments.miscellaneous

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.settings.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSpoofDialogHelper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val controller: AppSpoofController,
    private val profiles: MutableList<DeviceProfile>,
    private val configs: MutableList<AppConfig>,
    private val onDataChanged: () -> Unit,
    private val getEnabled: () -> Boolean
) {
    private fun px(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()

    private val colorError = android.graphics.Color.parseColor("#D93025")

    private fun validate(vararg fields: Pair<EditText, String>): Boolean {
        var ok = true
        fields.forEach { (et, msg) ->
            if (et.text.toString().trim().isEmpty()) {
                et.error = msg
                ok = false
            }
        }
        if (!ok) Toast.makeText(context, R.string.as_required_fields, Toast.LENGTH_SHORT).show()
        return ok
    }

    fun showAddAppDialog() {
        scope.launch {
            val progress = AlertDialog.Builder(context)
                .setMessage(R.string.as_loading_apps).setCancelable(false).show()

            val pm = context.packageManager
            val configured = configs.map { it.packageName }.toSet()

            val entries = withContext(Dispatchers.IO) {
                val overlayPkgs = getOverlayPackages()
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { app ->
                        app.packageName !in configured &&
                        app.packageName !in overlayPkgs &&
                        !app.packageName.contains(".overlay") &&
                        !app.packageName.contains(".resources")
                    }
                    .map { AppPickerEntry(it.packageName, pm.getApplicationLabel(it).toString(), it, pm) }
                    .sortedBy { it.label.lowercase() }
            }

            progress.dismiss()

            var showSystem = false
            val toolbar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(16), px(8), px(16), px(4))
            }
            val etSearch = EditText(context).apply {
                hint = context.getString(R.string.as_search_apps)
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val cbSystem = CheckBox(context).apply {
                text = context.getString(R.string.as_show_system)
                isChecked = showSystem
            }
            toolbar.addView(etSearch)
            toolbar.addView(cbSystem)

            val rv = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                layoutParams = ViewGroup.LayoutParams(-1, -1)
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(-1, -1)
                addView(toolbar)
                addView(rv)
            }

            val adapter = AppPickerAdapter(entries, pm) { entry ->
                showAppEditorDialog(entry.packageName, entry.label, null)
            }
            rv.adapter = adapter
            adapter.filter("", showSystem)

            etSearch.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                    adapter.filter(s?.toString() ?: "", cbSystem.isChecked)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            cbSystem.setOnCheckedChangeListener { _, checked ->
                showSystem = checked
                adapter.filter(etSearch.text.toString(), checked)
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.as_select_app)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.show()
            val dm = context.resources.displayMetrics
            dialog.window?.setLayout(
                (dm.widthPixels * 0.95).toInt(),
                (dm.heightPixels * 0.85).toInt()
            )
        }
    }

    fun showAppEditorDialog(packageName: String, appName: String, existing: AppConfig?) {
        val scroll = ScrollView(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(12))
        }
        scroll.addView(body)

        fun label(text: String) = TextView(context).apply {
            this.text = text; textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12); bottomMargin = px(2) }
        }
        fun field(hint: String, value: String = "") = EditText(context).apply {
            this.hint = hint; setText(value); setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        body.addView(label(context.getString(R.string.as_key_model)))
        val etModel = field(context.getString(R.string.as_hint_model),
            existing?.props?.get(AppSpoofConstants.FIELD_MODEL) ?: "")
        body.addView(etModel)

        body.addView(label(context.getString(R.string.as_key_manufacturer)))
        val etManu = field(context.getString(R.string.as_hint_manufacturer),
            existing?.props?.get(AppSpoofConstants.FIELD_MANUFACTURER) ?: "")
        body.addView(etManu)

        val propsCont = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(propsCont)
        val propRows = mutableListOf<Pair<EditText, EditText>>()

        fun addPropRow(k: String = "", v: String = "") {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) }
            }
            val ek = EditText(context).apply {
                hint = context.getString(R.string.as_key); setText(k); setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(4) }
            }
            val ev = EditText(context).apply {
                hint = context.getString(R.string.as_value); setText(v); setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1.3f)
            }
            val del = TextView(context).apply {
                text = "✕"; textSize = 16f; gravity = Gravity.CENTER
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(px(36), px(36))
                setOnClickListener { propsCont.removeView(row); propRows.removeAll { it.first == ek } }
            }
            row.addView(ek); row.addView(ev); row.addView(del)
            propsCont.addView(row); propRows.add(ek to ev)
        }

        existing?.props
            ?.filter { it.key != AppSpoofConstants.FIELD_MODEL && it.key != AppSpoofConstants.FIELD_MANUFACTURER }
            ?.forEach { addPropRow(it.key, it.value) }

        body.addView(Button(context).apply {
            text = context.getString(R.string.as_add_prop)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) }
            setOnClickListener { addPropRow() }
        })

        val profileButtonsCont = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) }
        }

        val btnUseProfile = Button(context).apply {
            text = context.getString(R.string.as_use_profile)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(4) }
            setOnClickListener {
                if (profiles.isEmpty()) {
                    Toast.makeText(context, R.string.as_no_profiles, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AlertDialog.Builder(context)
                    .setTitle(R.string.as_select_profile)
                    .setItems(profiles.map { it.name }.toTypedArray()) { _, which ->
                        etModel.setText(profiles[which].props[AppSpoofConstants.FIELD_MODEL] ?: "")
                        etManu.setText(profiles[which].props[AppSpoofConstants.FIELD_MANUFACTURER] ?: "")
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }

        val btnSaveAsProfile = Button(context).apply {
            text = context.getString(R.string.as_save_as_profile)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(4) }

            setOnClickListener {
                val currentModel = etModel.text.toString().trim()
                val currentManu = etManu.text.toString().trim()

                var mainError = false
                if (currentModel.isEmpty()) {
                    etModel.error = context.getString(R.string.as_required)
                    etModel.requestFocus()
                    mainError = true
                }
                if (currentManu.isEmpty()) {
                    etManu.error = context.getString(R.string.as_required)
                    if (!mainError) etManu.requestFocus()
                    mainError = true
                }
                if (mainError) return@setOnClickListener

                val etProfileName = EditText(context).apply {
                    hint = context.getString(R.string.as_hint_profile_name)
                    setSingleLine(true)
                }

                val profileDialog = AlertDialog.Builder(context)
                    .setTitle(R.string.as_save_as_profile)
                    .setView(
                        LinearLayout(context).apply {
                            setPadding(px(20), px(10), px(20), px(10))
                            addView(etProfileName, LinearLayout.LayoutParams(-1, -2))
                        }
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()

                profileDialog.setOnShowListener {
                    profileDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = etProfileName.text.toString().trim()

                        if (name.isEmpty()) {
                            etProfileName.error = context.getString(R.string.as_error_empty_name)
                            etProfileName.requestFocus()
                            return@setOnClickListener
                        }

                        if (profiles.any { it.name.equals(name, ignoreCase = true) }) {
                            etProfileName.error = context.getString(R.string.as_error_duplicate_name, name)
                            etProfileName.requestFocus()
                            return@setOnClickListener
                        }

                        val newProps = mutableMapOf(
                            AppSpoofConstants.FIELD_MODEL to currentModel,
                            AppSpoofConstants.FIELD_MANUFACTURER to currentManu
                        )
                        propRows.forEach { (k, v) ->
                            val key = k.text.toString().trim()
                            val value = v.text.toString().trim()
                            if (key.isNotEmpty()) newProps[key] = value
                        }

                        val duplicateProfile = profiles.find {
                            it.props.mapValues { e -> e.value.trim() } ==
                                    newProps.mapValues { e -> e.value.trim() }
                        }

                        if (duplicateProfile != null) {
                            AlertDialog.Builder(context)
                                .setTitle(R.string.as_duplicate_config_title)
                                .setMessage(context.getString(R.string.as_duplicate_config_message, duplicateProfile.name))
                                .setPositiveButton(R.string.as_keep_both) { _, _ ->
                                    profiles.add(DeviceProfile(name, newProps))
                                    controller.writeProfiles(profiles)
                                    Toast.makeText(context, R.string.as_profile_saved, Toast.LENGTH_SHORT).show()
                                    profileDialog.dismiss()
                                }
                                .setNeutralButton(R.string.as_overwrite_existing) { _, _ ->
                                    val index = profiles.indexOfFirst {
                                        it.props.mapValues { e -> e.value.trim() } ==
                                                newProps.mapValues { e -> e.value.trim() }
                                    }
                                    if (index != -1) {
                                        profiles[index] = DeviceProfile(name, newProps)
                                    } else {
                                        profiles.add(DeviceProfile(name, newProps))
                                    }
                                    controller.writeProfiles(profiles)
                                    Toast.makeText(context, R.string.as_profile_saved, Toast.LENGTH_SHORT).show()
                                    profileDialog.dismiss()
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                            return@setOnClickListener
                        }

                        profiles.add(DeviceProfile(name, newProps))
                        controller.writeProfiles(profiles)
                        Toast.makeText(context, R.string.as_profile_saved, Toast.LENGTH_SHORT).show()
                        profileDialog.dismiss()
                    }
                }
                profileDialog.show()
            }
        }

        profileButtonsCont.addView(btnUseProfile)
        profileButtonsCont.addView(btnSaveAsProfile)
        body.addView(profileButtonsCont)

        var dialog: AlertDialog? = null
        if (existing != null) {
            body.addView(Button(context).apply {
                text = context.getString(R.string.as_remove)
                setTextColor(colorError)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) }
                setOnClickListener {
                    AlertDialog.Builder(context)
                        .setTitle(R.string.as_remove_title)
                        .setMessage(context.getString(R.string.as_remove_message, appName))
                        .setPositiveButton(R.string.as_remove) { _, _ ->
                            configs.removeAll { it.packageName == packageName }
                            saveAndNotify()
                            dialog?.dismiss()
                        }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            })
        }

        dialog = AlertDialog.Builder(context)
            .setTitle(appName)
            .setView(scroll)
            .setPositiveButton(context.getString(R.string.as_save), null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val required = context.getString(R.string.as_required)
            if (!validate(etModel to required, etManu to required)) return@setOnClickListener
            val newProps = mutableMapOf(
                AppSpoofConstants.FIELD_MODEL        to etModel.text.toString().trim(),
                AppSpoofConstants.FIELD_MANUFACTURER to etManu.text.toString().trim()
            )
            propRows.forEach { (k, v) ->
                val key = k.text.toString().trim()
                if (key.isNotEmpty()) newProps[key] = v.text.toString().trim()
            }
            val newCfg = AppConfig(packageName, appName, newProps)
            val idx = configs.indexOfFirst { it.packageName == packageName }
            if (idx >= 0) configs[idx] = newCfg else configs.add(newCfg)
            saveAndNotify()
            dialog.dismiss()
        }

        val dm = context.resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    fun showManageProfilesDialog() {
        val names = if (profiles.isEmpty())
            arrayOf(context.getString(R.string.as_no_profiles))
        else
            profiles.map { it.name }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.as_manage_profiles))
            .setItems(names) { _, which ->
                if (profiles.isEmpty()) return@setItems
                showProfileEditorDialog(profiles[which]) { updated ->
                    if (updated == null) profiles.removeAt(which)
                    else profiles[which] = updated
                    controller.writeProfiles(profiles)
                }
            }
            .setPositiveButton(context.getString(R.string.as_add_profile)) { _, _ ->
                showProfileEditorDialog(null) { newProfile ->
                    if (newProfile != null) {
                        profiles.add(newProfile)
                        controller.writeProfiles(profiles)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileEditorDialog(
        existing: DeviceProfile?,
        onDone: (DeviceProfile?) -> Unit
    ) {
        val scroll = ScrollView(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(12))
        }
        scroll.addView(body)

        fun label(text: String) = TextView(context).apply {
            this.text = text; textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12); bottomMargin = px(2) }
        }
        fun field(hint: String, value: String = "") = EditText(context).apply {
            this.hint = hint; setText(value); setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        body.addView(label(context.getString(R.string.as_profile_name)))
        val etName = field(context.getString(R.string.as_hint_profile_name), existing?.name ?: "")
        body.addView(etName)

        body.addView(label(context.getString(R.string.as_key_model)))
        val etModel = field(context.getString(R.string.as_hint_model),
            existing?.props?.get(AppSpoofConstants.FIELD_MODEL) ?: "")
        body.addView(etModel)

        body.addView(label(context.getString(R.string.as_key_manufacturer)))
        val etManu = field(context.getString(R.string.as_hint_manufacturer),
            existing?.props?.get(AppSpoofConstants.FIELD_MANUFACTURER) ?: "")
        body.addView(etManu)

        val propsCont = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(propsCont)
        val propRows = mutableListOf<Pair<EditText, EditText>>()

        fun addPropRow(k: String = "", v: String = "") {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) }
            }
            val ek = EditText(context).apply {
                hint = context.getString(R.string.as_key); setText(k); setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(4) }
            }
            val ev = EditText(context).apply {
                hint = context.getString(R.string.as_value); setText(v); setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1.3f)
            }
            val del = TextView(context).apply {
                text = "✕"; textSize = 16f; gravity = Gravity.CENTER
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(px(36), px(36))
                setOnClickListener { propsCont.removeView(row); propRows.removeAll { it.first == ek } }
            }
            row.addView(ek); row.addView(ev); row.addView(del)
            propsCont.addView(row); propRows.add(ek to ev)
        }

        existing?.props
            ?.filter { it.key != AppSpoofConstants.FIELD_MODEL && it.key != AppSpoofConstants.FIELD_MANUFACTURER }
            ?.forEach { addPropRow(it.key, it.value) }

        body.addView(Button(context).apply {
            text = context.getString(R.string.as_add_prop)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) }
            setOnClickListener { addPropRow() }
        })

        var dialog: AlertDialog? = null
        if (existing != null) {
            body.addView(Button(context).apply {
                text = context.getString(R.string.as_remove)
                setTextColor(colorError)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) }
                setOnClickListener {
                    AlertDialog.Builder(context)
                        .setTitle(R.string.as_remove_title)
                        .setMessage(context.getString(R.string.as_remove_message, existing.name))
                        .setPositiveButton(R.string.as_remove) { _, _ ->
                            onDone(null)
                            dialog?.dismiss()
                        }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            })
        }

        val title = if (existing == null) context.getString(R.string.as_add_profile) else existing.name

        dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(context.getString(R.string.as_save_profile), null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val required = context.getString(R.string.as_required)
            if (!validate(etName to required, etModel to required, etManu to required)) return@setOnClickListener
            val props = mutableMapOf(
                AppSpoofConstants.FIELD_MODEL        to etModel.text.toString().trim(),
                AppSpoofConstants.FIELD_MANUFACTURER to etManu.text.toString().trim()
            )
            propRows.forEach { (k, v) ->
                val key = k.text.toString().trim()
                if (key.isNotEmpty()) props[key] = v.text.toString().trim()
            }
            onDone(DeviceProfile(etName.text.toString().trim(), props))
            dialog.dismiss()
        }

        val dm = context.resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private inner class AppPickerAdapter(
        private val all: List<AppPickerEntry>,
        private val pm: PackageManager,
        private val onClick: (AppPickerEntry) -> Unit
    ) : RecyclerView.Adapter<AppPickerAdapter.VH>() {

        private var shown = mutableListOf<AppPickerEntry>()

        fun filter(query: String, showSystem: Boolean) {
            val q = query.lowercase()
            shown = all.filter { e ->
                val isSystem = (e.info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val matchesQuery = e.label.lowercase().contains(q) || e.packageName.lowercase().contains(q)
                (!isSystem || showSystem) && matchesQuery
            }.toMutableList()
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon:    ImageView = v.findViewById(R.id.iv_app_icon)
            val label:   TextView  = v.findViewById(R.id.tv_app_label)
            val pkgName: TextView  = v.findViewById(R.id.tv_app_package)
        }

        override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_spoof_picker, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = shown[pos]
            h.label.text   = e.label
            h.pkgName.text = e.packageName
            h.icon.setImageDrawable(e.getIcon())
            h.itemView.setOnClickListener { onClick(e) }
        }

        override fun getItemCount() = shown.size
    }

    private fun getOverlayPackages(): Set<String> {
        return try {
            val om = context.getSystemService(Context.OVERLAY_SERVICE) as android.content.om.OverlayManager
            val handle = android.os.Process.myUserHandle()
            listOf("android", "com.android.systemui", "com.android.settings", "com.android.launcher3")
                .flatMap { om.getOverlayInfosForTarget(it, handle) }
                .map { it.packageName }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun saveAndNotify() {
        scope.launch(Dispatchers.IO) {
            controller.writeConfig(getEnabled(), configs)
            withContext(Dispatchers.Main) { onDataChanged() }
        }
    }
}