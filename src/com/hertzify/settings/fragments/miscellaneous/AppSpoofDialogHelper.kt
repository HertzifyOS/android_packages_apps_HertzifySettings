package com.hertzify.settings.fragments.miscellaneous

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.settings.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class AppSpoofDialogHelper(
    private val fragment: Fragment,
    private val controller: AppSpoofController,
    private val profiles: MutableList<DeviceProfile>,
    private val configs: MutableList<AppConfig>,
    private val onDataChanged: () -> Unit
) {

    private val context  get() = fragment.requireContext()
    private val activity get() = fragment.requireActivity()

    private val themedContext: Context by lazy {
        ContextThemeWrapper(context, context.theme)
    }

    private fun isDark(): Boolean =
        (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun themeColor(attr: Int, fallbackLight: Int, fallbackDark: Int): Int {
        return try {
            val tv = TypedValue()
            if (themedContext.theme.resolveAttribute(attr, tv, true)) {
                when {
                    tv.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                    tv.type <= TypedValue.TYPE_LAST_COLOR_INT -> tv.data
                    tv.resourceId != 0 ->
                        themedContext.resources.getColor(tv.resourceId, themedContext.theme)
                    else -> if (isDark()) fallbackDark else fallbackLight
                }
            } else if (isDark()) fallbackDark else fallbackLight
        } catch (_: Exception) {
            if (isDark()) fallbackDark else fallbackLight
        }
    }

    private val clrSheet:      Int get() = themeColor(android.R.attr.colorBackground, 0xFFFFFFFF.toInt(), 0xFF1E1E1E.toInt())
    private val clrInputBg:    Int get() = if (isDark()) 0xFF2C2C2C.toInt() else 0xFFF1F3F4.toInt()
    private val clrOnSurface: Int get() = if (isDark()) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
    private val clrSubtext:    Int get() = if (isDark()) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
    private val clrDivider:    Int get() = if (isDark()) 0xFF3C3C3C.toInt() else 0xFFE0E0E0.toInt()

    private val BLUE        = 0xFF1A73E8.toInt()
    private val BLUE_DARK   = 0xFF8AB4F8.toInt()
    private val RED         = 0xFFD93025.toInt()
    private val RED_DARK    = 0xFFFF6659.toInt()

    private val clrAccent:    Int get() = if (isDark()) BLUE_DARK else BLUE
    private val clrOnAccent:  Int get() = if (isDark()) 0xFF002884.toInt() else Color.WHITE
    private val clrError:     Int get() = if (isDark()) RED_DARK else RED

    private fun dp(px: Int) = (px * context.resources.displayMetrics.density).toInt()

    private fun lp(w: Int = -1, h: Int = -2, topM: Int = 0, botM: Int = 0, startM: Int = 0, endM: Int = 0) =
        LinearLayout.LayoutParams(w, h).also {
            it.topMargin = topM; it.bottomMargin = botM
            it.marginStart = startM; it.marginEnd = endM
        }

    private fun pillFilled(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(color)
    }

    private fun pillOutline(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setStroke(dp(2), color)
        setColor(Color.TRANSPARENT)
    }

    private fun roundRect(color: Int, r: Int = 10) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(r).toFloat()
        setColor(color)
    }

    private fun sheetBg() = GradientDrawable().apply {
        setColor(clrSheet)
        cornerRadii = floatArrayOf(
            dp(24).toFloat(), dp(24).toFloat(),
            dp(24).toFloat(), dp(24).toFloat(),
            0f, 0f, 0f, 0f
        )
    }

    private fun label(text: String) = TextView(themedContext).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.08f
        setTextColor(clrSubtext)
        setTypeface(null, Typeface.BOLD)
        layoutParams = lp(topM = dp(14), botM = dp(4))
    }

    private fun editText(hint: String, value: String = "") = EditText(themedContext).apply {
        this.hint = hint
        setText(value)
        setHintTextColor(clrSubtext)
        setTextColor(clrOnSurface)
        background = roundRect(clrInputBg)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = lp(botM = dp(4))
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_NEXT
    }

    private fun btnFilled(label: String) = TextView(themedContext).apply {
        text = label
        setTextColor(clrOnAccent)
        setTypeface(null, Typeface.BOLD)
        textSize = 14f
        gravity = Gravity.CENTER
        isClickable = true; isFocusable = true
        background = pillFilled(clrAccent)
        layoutParams = lp(h = dp(52), topM = dp(8))
    }

    private fun btnOutline(label: String, color: Int) = TextView(themedContext).apply {
        text = label
        setTextColor(color)
        setTypeface(null, Typeface.BOLD)
        textSize = 14f
        gravity = Gravity.CENTER
        isClickable = true; isFocusable = true
        background = pillOutline(color)
        layoutParams = lp(h = dp(48), topM = dp(8))
    }

    private fun btnText(label: String) = TextView(themedContext).apply {
        text = label
        setTextColor(clrAccent)
        setTypeface(null, Typeface.BOLD)
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true; isFocusable = true
        background = roundRect(clrInputBg)
        setPadding(dp(12), dp(14), dp(12), dp(14))
        layoutParams = lp(botM = dp(6))
    }

    private fun iconBtn(icon: String, tint: Int) = TextView(themedContext).apply {
        text = icon
        textSize = 18f
        setTextColor(tint)
        isClickable = true; isFocusable = true
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    private fun divider() = View(themedContext).apply {
        setBackgroundColor(clrDivider)
        layoutParams = lp(h = dp(1), topM = dp(12), botM = dp(8))
    }

    private inner class MaxHeightScrollView(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
            )
        }
    }

    private inner class MaxHeightRecyclerView(ctx: Context, private val maxH: Int) : RecyclerView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
            )
        }
    }

    private fun makeSheet(content: View): BottomSheetDialog {
        val d = BottomSheetDialog(themedContext)
        d.setContentView(content)
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        
        d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
            background = sheetBg()
            elevation = dp(12).toFloat()
            BottomSheetBehavior.from(this).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isFitToContents = true
                maxHeight = (context.resources.displayMetrics.heightPixels * 0.92).toInt()
            }
        }
        d.window?.setDimAmount(0.5f)
        return d
    }

    private fun sheetRoot(
        title: String,
        scrollable: View,
        actions: LinearLayout? = null
    ) = LinearLayout(themedContext).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        setPadding(dp(20), dp(12), dp(20), dp(24))
        setBackgroundColor(Color.TRANSPARENT)

        addView(View(themedContext).apply {
            background = roundRect(clrDivider, 3)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
        })

        addView(TextView(themedContext).apply {
            text = title
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(clrOnSurface)
            layoutParams = lp(botM = dp(8))
        })

        if (scrollable.parent != null) (scrollable.parent as ViewGroup).removeView(scrollable)
        addView(scrollable)

        actions?.let {
            if (it.parent != null) (it.parent as ViewGroup).removeView(it)
            addView(it)
        }
    }

    private fun scrollMaxH() = (activity.resources.displayMetrics.heightPixels * 0.55).toInt()
    private fun listMaxH() = (activity.resources.displayMetrics.heightPixels * 0.62).toInt()

    fun showEditAppDialog(config: AppConfig) = showEditAppDialog(config.packageName, config.appName, config)

    fun showEditAppDialog(packageName: String, appName: String, existingConfig: AppConfig?) {
        val body = LinearLayout(themedContext).apply { orientation = LinearLayout.VERTICAL }

        val etModel = editText(context.getString(R.string.app_spoof_hint_model), existingConfig?.props?.get(AppSpoofConstants.FIELD_MODEL) ?: "")
        val etManu  = editText(context.getString(R.string.app_spoof_hint_manufacturer), existingConfig?.props?.get(AppSpoofConstants.FIELD_MANUFACTURER) ?: "")

        body.addView(label(context.getString(R.string.app_spoof_label_model)))
        body.addView(etModel)
        body.addView(label(context.getString(R.string.app_spoof_label_manufacturer)))
        body.addView(etManu)

        val propsCont = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = lp()
        }
        body.addView(propsCont)

        val propRows = mutableListOf<Array<EditText>>()

        fun addRow(k: String = "", v: String = "") {
            propRows.lastOrNull()?.get(1)?.imeOptions = EditorInfo.IME_ACTION_NEXT
            etManu.imeOptions = EditorInfo.IME_ACTION_NEXT

            val ek = editText(context.getString(R.string.app_spoof_hint_key), k)
            val ev = editText(context.getString(R.string.app_spoof_hint_value), v).apply {
                imeOptions = EditorInfo.IME_ACTION_DONE
            }

            val del = iconBtn("✕", clrError)
            val row = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp(topM = dp(2), botM = dp(2))
            }
            del.setOnClickListener { 
                propsCont.removeView(row); propRows.removeAll { it[0] == ek } 
                if (propRows.isEmpty()) etManu.imeOptions = EditorInfo.IME_ACTION_DONE
                else propRows.last().get(1).imeOptions = EditorInfo.IME_ACTION_DONE
            }
            row.addView(ek); row.addView(ev); row.addView(del)
            propsCont.addView(row); propRows.add(arrayOf(ek, ev))
        }

        etManu.imeOptions = EditorInfo.IME_ACTION_DONE
        existingConfig?.props
            ?.filter { it.key != AppSpoofConstants.FIELD_MODEL && it.key != AppSpoofConstants.FIELD_MANUFACTURER }
            ?.forEach { addRow(it.key, it.value) }

        val scroll = MaxHeightScrollView(themedContext, scrollMaxH()).apply {
            isNestedScrollingEnabled = true
            addView(body)
        }

        var dialog: BottomSheetDialog? = null
        val actions = LinearLayout(themedContext).apply { orientation = LinearLayout.VERTICAL }

        actions.addView(btnText(context.getString(R.string.app_spoof_btn_add_prop)).apply {
            setOnClickListener {
                addRow()
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        })

        actions.addView(btnText(context.getString(R.string.app_spoof_btn_use_profile)).apply {
            setOnClickListener {
                if (profiles.isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.app_spoof_msg_no_profiles), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                showProfileOverlay { p ->
                    etModel.setText(p.props[AppSpoofConstants.FIELD_MODEL] ?: "")
                    etManu.setText(p.props[AppSpoofConstants.FIELD_MANUFACTURER] ?: "")
                    Toast.makeText(context, context.getString(R.string.app_spoof_msg_applied, p.name), Toast.LENGTH_SHORT).show()
                }
            }
        })

        actions.addView(divider())
        actions.addView(btnFilled(context.getString(R.string.app_spoof_btn_save_config)).apply {
            setOnClickListener {
                val model = etModel.text.toString().trim()
                val manu  = etManu.text.toString().trim()
                var ok = true
                if (model.isEmpty()) { etModel.error = context.getString(R.string.app_spoof_error_required); ok = false }
                if (manu.isEmpty())  { etManu.error  = context.getString(R.string.app_spoof_error_required); ok = false }
                if (!ok) return@setOnClickListener
                val props = mutableMapOf(
                    AppSpoofConstants.FIELD_MODEL        to model,
                    AppSpoofConstants.FIELD_MANUFACTURER to manu
                )
                propRows.forEach { r ->
                    val k = r[0].text.toString().trim()
                    val v = r[1].text.toString().trim()
                    if (k.isNotEmpty()) props[k] = v
                }
                configs.removeAll { it.packageName == packageName }
                configs.add(AppConfig(packageName, appName, matchProfileName(props), props))
                saveAndRefresh()
                dialog?.dismiss()
            }
        })

        if (existingConfig != null) {
            actions.addView(btnOutline(context.getString(R.string.app_spoof_btn_delete_config), clrError).apply {
                setOnClickListener {
                    AlertDialog.Builder(themedContext)
                        .setTitle(context.getString(R.string.app_spoof_delete_config_confirm))
                        .setMessage(context.getString(R.string.app_spoof_msg_delete_config, appName))
                        .setPositiveButton(context.getString(R.string.app_spoof_delete)) { _, _ ->
                            configs.removeAll { it.packageName == packageName }
                            saveAndRefresh()
                            dialog?.dismiss()
                        }
                        .setNegativeButton(context.getString(R.string.app_spoof_cancel), null)
                        .show()
                }
            })
        }

        dialog = makeSheet(sheetRoot(appName, scroll, actions))
        dialog.show()
    }

    fun showManageProfilesDialog() {
        val rv = MaxHeightRecyclerView(themedContext, listMaxH()).apply {
            layoutManager = LinearLayoutManager(themedContext)
            layoutParams = lp()
        }

        val empty = TextView(themedContext).apply {
            text = context.getString(R.string.app_spoof_msg_empty_list)
            textSize = 13f; setTextColor(clrSubtext)
            gravity = Gravity.CENTER
            layoutParams = lp(h = dp(100))
            visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        }

        val listArea = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = lp()
            addView(rv); addView(empty)
        }

        val actions = LinearLayout(themedContext).apply { orientation = LinearLayout.VERTICAL }
        actions.addView(divider())
        actions.addView(btnFilled(context.getString(R.string.app_spoof_btn_new_profile)).apply {
            setOnClickListener {
                showEditProfileDialog(null) {
                    empty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
                    rv.adapter?.notifyDataSetChanged()
                }
            }
        })

        val dialog = makeSheet(sheetRoot(context.getString(R.string.app_spoof_profiles_title), listArea, actions))

        rv.adapter = ProfileListAdapter(
            data = profiles,
            onTap = { p ->
                showEditProfileDialog(p) {
                    empty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
                    rv.adapter?.notifyDataSetChanged()
                }
            },
            onDelete = { p ->
                AlertDialog.Builder(themedContext)
                    .setTitle(context.getString(R.string.app_spoof_delete_profile_confirm))
                    .setMessage(context.getString(R.string.app_spoof_msg_delete_profile, p.name))
                    .setPositiveButton(context.getString(R.string.app_spoof_delete)) { _, _ ->
                        profiles.remove(p)
                        controller.saveProfiles(profiles)
                        empty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
                        rv.adapter?.notifyDataSetChanged()
                    }
                    .setNegativeButton(context.getString(R.string.app_spoof_cancel), null)
                    .show()
            }
        )
        dialog.show()
    }

    private fun showEditProfileDialog(editing: DeviceProfile?, onSaved: () -> Unit) {
        val body = LinearLayout(themedContext).apply { orientation = LinearLayout.VERTICAL }

        val etName  = editText(context.getString(R.string.app_spoof_hint_profile_name), editing?.name ?: "")
        val etModel = editText(context.getString(R.string.app_spoof_hint_model), editing?.props?.get(AppSpoofConstants.FIELD_MODEL) ?: "")
        val etManu  = editText(context.getString(R.string.app_spoof_hint_manufacturer), editing?.props?.get(AppSpoofConstants.FIELD_MANUFACTURER) ?: "")

        body.addView(label(context.getString(R.string.app_spoof_label_profile_name)));  body.addView(etName)
        body.addView(label(context.getString(R.string.app_spoof_label_model)));  body.addView(etModel)
        body.addView(label(context.getString(R.string.app_spoof_label_manufacturer)));  body.addView(etManu)

        val propsCont = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = lp()
        }
        body.addView(propsCont)
        val propRows = mutableListOf<Array<EditText>>()

        fun addRow(k: String = "", v: String = "") {
            propRows.lastOrNull()?.get(1)?.imeOptions = EditorInfo.IME_ACTION_NEXT
            etManu.imeOptions = EditorInfo.IME_ACTION_NEXT

            val ek = editText(context.getString(R.string.app_spoof_hint_key), k)
            val ev = editText(context.getString(R.string.app_spoof_hint_value), v).apply {
                imeOptions = EditorInfo.IME_ACTION_DONE
            }
            val del = iconBtn("✕", clrError)
            val row = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp(topM = dp(2), botM = dp(2))
            }
            del.setOnClickListener { 
                propsCont.removeView(row); propRows.removeAll { it[0] == ek }
                if (propRows.isEmpty()) etManu.imeOptions = EditorInfo.IME_ACTION_DONE
                else propRows.last().get(1).imeOptions = EditorInfo.IME_ACTION_DONE
            }
            row.addView(ek); row.addView(ev); row.addView(del)
            propsCont.addView(row); propRows.add(arrayOf(ek, ev))
        }

        etManu.imeOptions = EditorInfo.IME_ACTION_DONE
        editing?.props
            ?.filter { it.key != AppSpoofConstants.FIELD_MODEL && it.key != AppSpoofConstants.FIELD_MANUFACTURER }
            ?.forEach { addRow(it.key, it.value) }

        val scroll = MaxHeightScrollView(themedContext, scrollMaxH()).apply {
            isNestedScrollingEnabled = true
            addView(body)
        }

        var dialog: BottomSheetDialog? = null
        val actions = LinearLayout(themedContext).apply { orientation = LinearLayout.VERTICAL }

        actions.addView(btnText(context.getString(R.string.app_spoof_btn_add_prop)).apply {
            setOnClickListener {
                addRow()
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        })

        actions.addView(divider())
        val saveBtnLabel = if (editing == null) R.string.app_spoof_btn_add_profile else R.string.app_spoof_btn_save_profile
        actions.addView(btnFilled(context.getString(saveBtnLabel)).apply {
            setOnClickListener {
                val name  = etName.text.toString().trim()
                val model = etModel.text.toString().trim()
                val manu  = etManu.text.toString().trim()
                if (name.isEmpty()) { etName.error = context.getString(R.string.app_spoof_error_required); return@setOnClickListener }
                var ok = true
                if (model.isEmpty()) { etModel.error = context.getString(R.string.app_spoof_error_required); ok = false }
                if (manu.isEmpty())  { etManu.error  = context.getString(R.string.app_spoof_error_required); ok = false }
                if (!ok) return@setOnClickListener
                if (editing != null) profiles.remove(editing)
                val extra = mutableMapOf(
                    AppSpoofConstants.FIELD_MODEL        to model,
                    AppSpoofConstants.FIELD_MANUFACTURER to manu
                )
                propRows.forEach { r ->
                    val k = r[0].text.toString().trim()
                    val v = r[1].text.toString().trim()
                    if (k.isNotEmpty()) extra[k] = v
                }
                profiles.add(DeviceProfile(name, extra))
                controller.saveProfiles(profiles)
                onSaved()
                dialog?.dismiss()
            }
        })

        if (editing != null) {
            actions.addView(btnOutline(context.getString(R.string.app_spoof_btn_delete_profile), clrError).apply {
                setOnClickListener {
                    AlertDialog.Builder(themedContext)
                        .setTitle(context.getString(R.string.app_spoof_delete_profile_confirm))
                        .setMessage(context.getString(R.string.app_spoof_msg_delete_profile, editing.name))
                        .setPositiveButton(context.getString(R.string.app_spoof_delete)) { _, _ ->
                            profiles.remove(editing)
                            controller.saveProfiles(profiles)
                            onSaved()
                            dialog?.dismiss()
                        }
                        .setNegativeButton(context.getString(R.string.app_spoof_cancel), null)
                        .show()
                }
            })
        }

        val title = context.getString(if (editing == null) R.string.app_spoof_new_profile_title else R.string.app_spoof_edit_profile_title)
        dialog = makeSheet(sheetRoot(title, scroll, actions))
        dialog.show()
    }

    private fun showProfileOverlay(onSelected: (DeviceProfile) -> Unit) {
        val rv = MaxHeightRecyclerView(themedContext, listMaxH()).apply {
            layoutManager = LinearLayoutManager(themedContext)
            layoutParams = lp()
        }
        val dialog = makeSheet(sheetRoot(context.getString(R.string.app_spoof_select_profile), rv))
        rv.adapter = ProfilePickerAdapter(profiles) { p -> onSelected(p); dialog.dismiss() }
        dialog.show()
    }

    fun showAddAppDialog() {
        val etSearch = editText(context.getString(R.string.app_spoof_hint_search)).apply { isSingleLine = true }
        val loading = ProgressBar(themedContext).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(20); bottomMargin = dp(20)
            }
        }
        val rv = MaxHeightRecyclerView(themedContext, listMaxH()).apply {
            layoutManager = LinearLayoutManager(themedContext)
            layoutParams = lp()
            visibility = View.GONE
        }
        val listArea = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = lp()
            addView(etSearch); addView(loading); addView(rv)
        }
        val dialog = makeSheet(sheetRoot(context.getString(R.string.app_spoof_add_app_title), listArea))

        Thread {
            val pm = context.packageManager
            val configured = configs.map { it.packageName }.toSet()
            val entries = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { i ->
                    (i.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        i.packageName !in configured
                }
                .map { i -> AppPickerEntry(i.packageName, pm.getApplicationLabel(i).toString(), i, pm) }
                .sortedBy { it.label.lowercase() }

            activity.runOnUiThread {
                loading.visibility = View.GONE
                rv.visibility = View.VISIBLE
                rv.adapter = AppPickerAdapter(entries) { e ->
                    dialog.dismiss()
                    showEditAppDialog(e.packageName, e.label, null)
                }
                etSearch.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        (rv.adapter as? AppPickerAdapter)?.filter(s?.toString() ?: "")
                    }
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                })
            }
        }.start()
        dialog.show()
    }

    private inner class ProfileListAdapter(
        private val data: MutableList<DeviceProfile>,
        private val onTap: (DeviceProfile) -> Unit,
        private val onDelete: (DeviceProfile) -> Unit
    ) : RecyclerView.Adapter<ProfileListAdapter.VH>() {
        inner class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row)
        override fun onCreateViewHolder(parent: ViewGroup, vt: Int) = VH(
            LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                layoutParams = lp(botM = dp(2))
                setPadding(dp(4), dp(12), dp(4), dp(12))
            }
        )
        override fun onBindViewHolder(h: VH, pos: Int) {
            val p = data[pos]; h.row.removeAllViews()
            val col = LinearLayout(themedContext).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            col.addView(TextView(themedContext).apply {
                text = p.name; textSize = 15f
                setTypeface(null, Typeface.BOLD); setTextColor(clrOnSurface)
            })
            col.addView(TextView(themedContext).apply {
                val model = p.props[AppSpoofConstants.FIELD_MODEL] ?: ""
                val manu  = p.props[AppSpoofConstants.FIELD_MANUFACTURER] ?: ""
                text = listOf(manu, model).filter { it.isNotEmpty() }.joinToString(" · ")
                textSize = 12f; setTextColor(clrSubtext)
            })
            h.row.addView(col)
            h.row.addView(TextView(themedContext).apply {
                text = "›"; textSize = 20f; setTextColor(clrSubtext)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            })
            h.row.addView(iconBtn("✕", clrError).apply {
                setOnClickListener { onDelete(p) }
            })
            h.row.setOnClickListener { onTap(p) }
        }
        override fun getItemCount() = data.size
    }

    private inner class ProfilePickerAdapter(
        private val data: List<DeviceProfile>,
        private val onPick: (DeviceProfile) -> Unit
    ) : RecyclerView.Adapter<ProfilePickerAdapter.VH>() {
        inner class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row)
        override fun onCreateViewHolder(parent: ViewGroup, vt: Int) = VH(
            LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                layoutParams = lp(botM = dp(2))
                setPadding(dp(4), dp(14), dp(4), dp(14))
            }
        )
        override fun onBindViewHolder(h: VH, pos: Int) {
            val p = data[pos]; h.row.removeAllViews()
            val col = LinearLayout(themedContext).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            col.addView(TextView(themedContext).apply {
                text = p.name; textSize = 15f
                setTypeface(null, Typeface.BOLD); setTextColor(clrOnSurface)
            })
            col.addView(TextView(themedContext).apply {
                val model = p.props[AppSpoofConstants.FIELD_MODEL] ?: ""
                val manu  = p.props[AppSpoofConstants.FIELD_MANUFACTURER] ?: ""
                text = listOf(manu, model).filter { it.isNotEmpty() }.joinToString(" · ")
                textSize = 12f; setTextColor(clrSubtext)
            })
            h.row.addView(col)
            h.row.addView(TextView(themedContext).apply {
                text = context.getString(R.string.app_spoof_action_apply)
                textSize = 13f; setTextColor(clrAccent)
                layoutParams = LinearLayout.LayoutParams(-2, -2)
                setPadding(dp(8), dp(4), dp(4), dp(4))
            })
            h.row.setOnClickListener { onPick(p) }
        }
        override fun getItemCount() = data.size
    }

    private fun matchProfileName(props: Map<String, String>) =
        profiles.find { it.props == props }?.name ?: context.getString(R.string.app_spoof_custom)

    private fun saveAndRefresh() {
        controller.saveAppConfigs(controller.isEnabled(), configs)
        onDataChanged()
    }
}