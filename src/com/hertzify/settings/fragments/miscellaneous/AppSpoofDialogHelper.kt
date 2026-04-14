package com.hertzify.settings.fragments.miscellaneous

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.settings.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Stack
import com.google.android.material.R as MaterialR

class AppSpoofDialogHelper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val controller: AppSpoofController,
    private val profiles: MutableList<DeviceProfile>,
    private val configs: MutableList<AppConfig>,
    private val onDataChanged: () -> Unit,
    private val getEnabled: () -> Boolean
) {

    private var showSystemApps = false
    private val backStack = Stack<() -> Unit>()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            handleBack()
        }
    }

    private val hostContainer = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private val bottomSheetDialog = BottomSheetDialog(context).apply {
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(hostContainer)

        onBackPressedDispatcher.addCallback(backCallback)

        setOnShowListener {
            val bottomSheet = findViewById<View>(MaterialR.id.design_bottom_sheet) as? FrameLayout
            bottomSheet?.let { sheet ->
                val tv = TypedValue()
                context.theme.resolveAttribute(android.R.attr.colorBackgroundFloating, tv, true)
                
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(tv.data)
                    val r = 28f * context.resources.displayMetrics.density
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
                sheet.background = shape
                sheet.clipToOutline = true

                val behavior = BottomSheetBehavior.from(sheet)
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        setOnDismissListener {
            hostContainer.removeAllViews()
            backStack.clear()
            backCallback.isEnabled = false
        }
    }

    private fun updateBackCallback() {
        backCallback.isEnabled = !backStack.isEmpty()
    }

    private fun handleBack() {
        if (!backStack.isEmpty()) {
            backStack.pop().invoke()
        } else {
            bottomSheetDialog.dismiss()
        }
        updateBackCallback()
    }

    private fun swapView(view: View, peekHeightPct: Double? = null) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(hostContainer.windowToken, 0)

        hostContainer.removeAllViews()
        hostContainer.addView(view)
        
        hostContainer.post {
            val parent = hostContainer.parent as? View
            parent?.let {
                val behavior = BottomSheetBehavior.from(it)
                if (peekHeightPct != null) {
                    val displayMetrics = context.resources.displayMetrics
                    behavior.peekHeight = (displayMetrics.heightPixels * peekHeightPct).toInt()
                }
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                it.requestLayout()
            }
        }
        
        if (!bottomSheetDialog.isShowing) {
            bottomSheetDialog.show()
        }
        updateBackCallback()
    }

    private fun dismissDialog() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(hostContainer.windowToken, 0)
        bottomSheetDialog.dismiss()
    }

    fun showAddAppDialog() {
        backStack.clear()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(16), 0, 0)
            clipChildren = true
        }

        val headerLayout = RelativeLayout(context).apply {
            setPadding(px(24), px(8), px(24), px(12))
        }
        
        val titleView = TextView(context).apply {
            text = context.getString(R.string.spoof_ap_select_app)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        }
        val titleParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_START)
            addRule(RelativeLayout.CENTER_VERTICAL)
        }
        headerLayout.addView(titleView, titleParams)
        layout.addView(headerLayout)

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))).apply {
                setMargins(0, 0, 0, px(4))
            }
            setBackgroundColor(Color.parseColor("#33888888"))
        }
        layout.addView(divider)

        val displayMetrics = context.resources.displayMetrics
        val maxViewHeight = (displayMetrics.heightPixels * 0.75).toInt()

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_app_picker, null)
        val contentParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            maxViewHeight
        )
        view.layoutParams = contentParams
        layout.addView(view)
        
        val searchInput = view.findViewById<EditText>(R.id.et_search)
        val systemCheckbox = view.findViewById<CheckBox>(R.id.cb_system)
        val autoButton = view.findViewById<Button>(R.id.btn_auto)
        autoButton?.visibility = View.GONE

        val loadingFrame = view.findViewById<FrameLayout>(R.id.fl_loading)
        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_apps)

        searchInput.hint = context.getString(R.string.spoof_ap_search_apps)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.clipToOutline = true
        recyclerView.clipChildren = true

        swapView(layout, peekHeightPct = 0.85)

        scope.launch {
            val pm = context.packageManager
            val currentPackages = configs.map { it.packageName }.toSet()
            
            val allApps = withContext(Dispatchers.IO) {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.packageName !in currentPackages }
                    .map { AppPickerEntry(it.packageName, it.loadLabel(pm).toString(), it, pm) }
                    .sortedBy { it.label.lowercase() }
            }

            if (allApps.isEmpty()) {
                dismissDialog()
                Toast.makeText(context, R.string.spoof_ap_no_more_apps, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val adapter = AppPickerAdapter(allApps) { app ->
                backStack.push { showAddAppDialog() }
                showAppEditorDialog(app.packageName, app.label, null)
            }
            recyclerView.adapter = adapter

            loadingFrame.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            
            searchInput.isEnabled = true
            systemCheckbox.isEnabled = true
            systemCheckbox.isChecked = showSystemApps

            adapter.filter(searchInput.text.toString(), showSystemApps)

            searchInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    adapter.filter(s.toString(), systemCheckbox.isChecked)
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            systemCheckbox.setOnCheckedChangeListener { _, isChecked ->
                showSystemApps = isChecked
                adapter.filter(searchInput.text.toString(), isChecked)
            }
        }
    }

    private fun showBaseEditorDialog(
        titleText: String,
        showNameInput: Boolean,
        initialName: String,
        initialProps: Map<String, String>?,
        showProfileTools: Boolean,
        onSave: (name: String, props: Map<String, String>, back: () -> Unit) -> Unit,
        onRemove: ((back: () -> Unit) -> Unit)?
    ) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p24 = px(24)
            setPadding(p24, p24, p24, p24)
            clipChildren = true
        }

        val titleView = TextView(context).apply {
            text = titleText
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, px(12))
        }
        layout.addView(titleView)

        val scroll = NestedScrollView(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(12))
        }
        scroll.addView(body)
        layout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun label(text: String) = TextView(context).apply {
            this.text = text; textSize = 11f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12); bottomMargin = px(4) }
        }
        fun field(hint: String, value: String = "", isLast: Boolean = false) = EditText(context).apply {
            this.hint = hint; setText(value); maxLines = 1; isSingleLine = true
            imeOptions = if (isLast) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        var etName: EditText? = null
        if (showNameInput) {
            body.addView(label(context.getString(R.string.spoof_ap_profile_name)))
            etName = field(context.getString(R.string.spoof_ap_hint_profile_name), initialName)
            body.addView(etName)
        }

        body.addView(label(context.getString(R.string.spoof_ap_key_model)))
        val etModel = field(context.getString(R.string.spoof_ap_hint_model),
            initialProps?.get(AppSpoofConstants.FIELD_MODEL) ?: "")
        body.addView(etModel)

        body.addView(label(context.getString(R.string.spoof_ap_key_manufacturer)))
        val etManu = field(context.getString(R.string.spoof_ap_hint_manufacturer),
            initialProps?.get(AppSpoofConstants.FIELD_MANUFACTURER) ?: "")
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
                hint = context.getString(R.string.spoof_ap_key); setText(k); maxLines = 1; isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_NEXT
                inputType = InputType.TYPE_CLASS_TEXT
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(4) }
            }
            val ev = EditText(context).apply {
                hint = context.getString(R.string.spoof_ap_value); setText(v); maxLines = 1; isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_DONE
                inputType = InputType.TYPE_CLASS_TEXT
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
            ek.requestFocus()
        }

        initialProps
            ?.filter { it.key != AppSpoofConstants.FIELD_MODEL && it.key != AppSpoofConstants.FIELD_MANUFACTURER }
            ?.forEach { addPropRow(it.key, it.value) }

        val bottomToolsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) }
        }
        layout.addView(bottomToolsLayout)

        val tvAccent = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorAccent, tvAccent, true)
        val accentColor = tvAccent.data
        val changeBg = (accentColor and 0x00FFFFFF) or 0x26000000

        val redSoftText = Color.parseColor("#D32F2F")
        val redSoftBg = Color.parseColor("#1AD32F2F")

        val createPillButton = { textStr: String, bgColor: Int, textColor: Int, isFullWidth: Boolean, action: () -> Unit ->
            TextView(context).apply {
                text = textStr
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(textColor)
                setTypeface(null, Typeface.BOLD)
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(bgColor)
                    cornerRadius = 100f * context.resources.displayMetrics.density
                }
                background = shape
                clipToOutline = true
                val w = if (isFullWidth) ViewGroup.LayoutParams.MATCH_PARENT else 0
                val wt = if (isFullWidth) 0f else 1f
                layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, wt).apply {
                    setMargins(px(4), px(4), px(4), px(4))
                }
                setPadding(px(16), px(12), px(16), px(12))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                isClickable = true
                isFocusable = true
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                foreground = context.getDrawable(outValue.resourceId)
                setOnClickListener { action() }
            }
        }

        val btnAddProp = createPillButton(context.getString(R.string.spoof_ap_add_prop), changeBg, accentColor, true) {
            addPropRow()
        }
        bottomToolsLayout.addView(btnAddProp)

        if (showProfileTools) {
            val profileButtonsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }

            val btnUseProfile = createPillButton(context.getString(R.string.spoof_ap_use_profile), changeBg, accentColor, false) {
                if (profiles.isEmpty()) {
                    Toast.makeText(context, R.string.spoof_ap_no_profiles, Toast.LENGTH_SHORT).show()
                    return@createPillButton
                }
                backStack.push { showBaseEditorDialog(titleText, showNameInput, initialName, initialProps, showProfileTools, onSave, onRemove) }
                showManageProfilesDialog(isPickerMode = true) { selectedProfile ->
                    val props = selectedProfile.props.toMutableMap()
                    onSave(etName?.text?.toString()?.trim() ?: "", props) { dismissDialog() }
                    Toast.makeText(context, context.getString(R.string.spoof_ap_profile_applied, selectedProfile.name), Toast.LENGTH_SHORT).show()
                }
            }

            val btnSaveAsProfile = createPillButton(context.getString(R.string.spoof_ap_save_as_profile), changeBg, accentColor, false) {
                val currentModel = etModel.text.toString().trim()
                val currentManu = etManu.text.toString().trim()
                if (currentModel.isEmpty() || currentManu.isEmpty()) {
                    Toast.makeText(context, R.string.spoof_ap_required, Toast.LENGTH_SHORT).show()
                    return@createPillButton
                }
                val etProfileName = EditText(context).apply {
                    hint = context.getString(R.string.spoof_ap_hint_profile_name)
                    maxLines = 1; isSingleLine = true
                }
                val profileDialog = AlertDialog.Builder(context)
                    .setTitle(R.string.spoof_ap_save_as_profile)
                    .setView(
                        LinearLayout(context).apply {
                            setPadding(px(24), px(12), px(24), px(12))
                            addView(etProfileName, LinearLayout.LayoutParams(-1, -2))
                        }
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                profileDialog.setOnShowListener {
                    profileDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = etProfileName.text.toString().trim()
                        if (name.isEmpty() || profiles.any { it.name.equals(name, ignoreCase = true) }) {
                            etProfileName.error = context.getString(R.string.spoof_ap_error_empty_name)
                            return@setOnClickListener
                        }
                        val newProps = mutableMapOf(AppSpoofConstants.FIELD_MODEL to currentModel, AppSpoofConstants.FIELD_MANUFACTURER to currentManu)
                        propRows.forEach { (k, v) ->
                            val key = k.text.toString().trim()
                            if (key.isNotEmpty()) newProps[key] = v.text.toString().trim()
                        }
                        profiles.add(DeviceProfile(name, newProps))
                        controller.writeProfiles(profiles)
                        Toast.makeText(context, R.string.spoof_ap_profile_saved, Toast.LENGTH_SHORT).show()
                        profileDialog.dismiss()
                    }
                }
                profileDialog.show()
            }

            profileButtonsRow.addView(btnUseProfile)
            profileButtonsRow.addView(btnSaveAsProfile)
            bottomToolsLayout.addView(profileButtonsRow)
        }

        val actionButtonsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        if (onRemove != null) {
            val removeText = if (showNameInput) context.getString(R.string.spoof_ap_remove_profile) else context.getString(R.string.spoof_ap_remove)
            val btnRemove = createPillButton(removeText, redSoftBg, redSoftText, false) {
                onRemove { handleBack() }
            }
            actionButtonsRow.addView(btnRemove)
        } else {
            val btnCancel = createPillButton(context.getString(android.R.string.cancel), Color.parseColor("#1A888888"), Color.GRAY, false) {
                handleBack()
            }
            actionButtonsRow.addView(btnCancel)
        }
        
        val saveText = if (showNameInput) context.getString(R.string.spoof_ap_save_profile) else context.getString(R.string.spoof_ap_save)
        val btnSave = createPillButton(saveText, changeBg, accentColor, false) {
            val name = etName?.text?.toString()?.trim() ?: ""
            val model = etModel.text.toString().trim()
            val manu = etManu.text.toString().trim()
            if ((showNameInput && name.isEmpty()) || model.isEmpty() || manu.isEmpty()) {
                Toast.makeText(context, R.string.spoof_ap_required, Toast.LENGTH_SHORT).show()
                return@createPillButton
            }
            val props = mutableMapOf(
                AppSpoofConstants.FIELD_MODEL to model,
                AppSpoofConstants.FIELD_MANUFACTURER to manu
            )
            propRows.forEach { (k, v) ->
                val key = k.text.toString().trim()
                if (key.isNotEmpty()) props[key] = v.text.toString().trim()
            }
            onSave(name, props) { handleBack() }
        }
        actionButtonsRow.addView(btnSave)
        bottomToolsLayout.addView(actionButtonsRow)
        
        swapView(layout)
    }

    fun showAppEditorDialog(packageName: String, appName: String, existing: AppConfig?) {
        showBaseEditorDialog(
            titleText = appName,
            showNameInput = false,
            initialName = "",
            initialProps = existing?.props,
            showProfileTools = true,
            onSave = { _, props, _ ->
                val newCfg = AppConfig(packageName, appName, props)
                val idx = configs.indexOfFirst { it.packageName == packageName }
                if (idx >= 0) configs[idx] = newCfg else configs.add(newCfg)
                scope.launch {
                    controller.writeConfig(getEnabled(), configs)
                    withContext(Dispatchers.Main) { 
                        onDataChanged()
                        dismissDialog()
                    }
                }
            },
            onRemove = if (existing != null) { _ ->
                showRemoveConfirmDialog(packageName, appName)
            } else null
        )
    }

    private fun showRemoveConfirmDialog(packageName: String, appName: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.spoof_ap_remove_app_title)
            .setMessage(context.getString(R.string.spoof_ap_remove_app_message, appName))
            .setPositiveButton(R.string.spoof_ap_remove) { _, _ ->
                configs.removeAll { it.packageName == packageName }
                scope.launch {
                    controller.writeConfig(getEnabled(), configs)
                    withContext(Dispatchers.Main) {
                        onDataChanged()
                        dismissDialog()
                        Toast.makeText(context, context.getString(R.string.spoof_ap_deleted_success, appName), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun px(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()

    fun showManageProfilesDialog(
        isPickerMode: Boolean = false, 
        onProfileSelected: ((DeviceProfile) -> Unit)? = null
    ) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p24 = px(24)
            setPadding(p24, p24, p24, p24)
            clipChildren = true
        }
        val titleView = TextView(context).apply {
            text = context.getString(if (isPickerMode) R.string.spoof_ap_select_profile else R.string.spoof_ap_manage_profiles)
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, px(12))
        }
        layout.addView(titleView)
        val scrollView = NestedScrollView(context).apply {
            clipToOutline = true
            clipChildren = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        }
        val listLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (profiles.isEmpty()) {
            val emptyView = TextView(context).apply {
                text = context.getString(R.string.spoof_ap_no_profiles)
                textSize = 16f
                alpha = 0.7f
                gravity = Gravity.CENTER
                setPadding(0, px(16), 0, px(16))
            }
            listLayout.addView(emptyView)
        } else {
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            profiles.forEachIndexed { index, profile ->
                val itemView = TextView(context).apply {
                    text = profile.name
                    textSize = 16f
                    setPadding(px(8), px(16), px(8), px(16))
                    isClickable = true
                    isFocusable = true
                    background = context.getDrawable(outValue.resourceId)
                    setOnClickListener {
                        if (isPickerMode && onProfileSelected != null) {
                            onProfileSelected(profile)
                        } else {
                            backStack.push { showManageProfilesDialog() }
                            showProfileEditorDialog(profile) { updated ->
                                if (updated == null) profiles.removeAt(index)
                                else profiles[index] = updated
                                controller.writeProfiles(profiles)
                            }
                        }
                    }
                }
                listLayout.addView(itemView)
            }
        }
        scrollView.addView(listLayout)
        layout.addView(scrollView)
        if (!isPickerMode) {
            val bottomContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16) }
            }
            val tvAccent = TypedValue()
            context.theme.resolveAttribute(android.R.attr.colorAccent, tvAccent, true)
            val accentColor = tvAccent.data
            val changeBg = (accentColor and 0x00FFFFFF) or 0x26000000
            val btnAdd = TextView(context).apply {
                text = context.getString(R.string.spoof_ap_add_profile)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(accentColor)
                setTypeface(null, Typeface.BOLD)
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(changeBg)
                    cornerRadius = 100f * context.resources.displayMetrics.density
                }
                background = shape
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(px(4), px(4), px(4), px(4)) }
                setPadding(px(16), px(12), px(16), px(12))
                isClickable = true
                isFocusable = true
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                foreground = context.getDrawable(outValue.resourceId)
                setOnClickListener {
                    backStack.push { showManageProfilesDialog() }
                    showProfileEditorDialog(null) { newProfile ->
                        if (newProfile != null) {
                            profiles.add(newProfile)
                            controller.writeProfiles(profiles)
                        }
                    }
                }
            }
            bottomContainer.addView(btnAdd)
            layout.addView(bottomContainer)
        }
        swapView(layout)
    }

    private fun showProfileEditorDialog(
        existing: DeviceProfile?,
        onDone: (DeviceProfile?) -> Unit
    ) {
        showBaseEditorDialog(
            titleText = if (existing == null) context.getString(R.string.spoof_ap_add_profile) else existing.name,
            showNameInput = true,
            initialName = existing?.name ?: "",
            initialProps = existing?.props,
            showProfileTools = false,
            onSave = { name, props, back ->
                onDone(DeviceProfile(name, props))
                back()
            },
            onRemove = if (existing != null) { back ->
                AlertDialog.Builder(context)
                    .setTitle(R.string.spoof_ap_remove_profile_title)
                    .setMessage(context.getString(R.string.spoof_ap_remove_profile_message, existing.name))
                    .setPositiveButton(R.string.spoof_ap_remove_profile) { _, _ ->
                        onDone(null)
                        back()
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            } else null
        )
    }

    private inner class AppPickerAdapter(
        private val originalApps: List<AppPickerEntry>,
        private val onSelect: (AppPickerEntry) -> Unit
    ) : RecyclerView.Adapter<AppPickerViewHolder>() {
        private var displayApps: List<AppPickerEntry> = emptyList()
        fun filter(query: String, showSystem: Boolean) {
            val lowerCaseQuery = query.lowercase().trim()
            displayApps = originalApps.filter { app ->
                val isSystemApp = (app.info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val matchesSystem = showSystem || !isSystemApp
                val matchesSearch = lowerCaseQuery.isEmpty() || 
                                   app.label.lowercase().contains(lowerCaseQuery) || 
                                   app.packageName.lowercase().contains(lowerCaseQuery)
                matchesSystem && matchesSearch
            }
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppPickerViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_app_props_picker, parent, false)
            return AppPickerViewHolder(view)
        }
        override fun onBindViewHolder(holder: AppPickerViewHolder, position: Int) {
            val app = displayApps[position]
            holder.bind(app)
            holder.itemView.setOnClickListener { onSelect(app) }
        }
        override fun getItemCount() = displayApps.size
    }

    private inner class AppPickerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val iconView: ImageView = view.findViewById(R.id.iv_app_icon)
        private val labelView: TextView = view.findViewById(R.id.tv_app_label)
        private val packageView: TextView = view.findViewById(R.id.tv_app_package)
        fun bind(app: AppPickerEntry) {
            labelView.text = app.label
            packageView.text = app.packageName
            iconView.setImageDrawable(null)
            scope.launch(Dispatchers.IO) {
                val drawable = app.getIcon()
                withContext(Dispatchers.Main) {
                    if (labelView.text == app.label) {
                        iconView.setImageDrawable(drawable)
                    }
                }
            }
        }
    }
}