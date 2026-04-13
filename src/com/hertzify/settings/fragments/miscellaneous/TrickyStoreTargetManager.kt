package com.hertzify.settings.fragments.miscellaneous

import android.content.Context
import android.content.om.OverlayManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.settings.R
import com.android.settingslib.Utils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.R as MaterialR
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrickyStoreTargetManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val storePath: String,
    private val targetFileName: String,
    private val onSaved: () -> Unit
) {

    private var showSystemApps = false
    private var hasPendingChanges = false

    private val AUTO_SELECT_PACKAGES = setOf(
        "com.google.android.gms",
        "com.android.vending",
        "com.google.android.apps.nbu.paisa.user",
        "com.google.android.apps.walletnfcrel"
    )

    enum class TargetMode(val symbol: String) {
        AUTO(""),
        LEAF_HACK("?"),
        CERT_GEN("!");

        companion object {
            fun fromLine(line: String): Pair<String, TargetMode> = when {
                line.endsWith("?") -> line.dropLast(1) to LEAF_HACK
                line.endsWith("!") -> line.dropLast(1) to CERT_GEN
                else -> line to AUTO
            }
        }
    }

    private fun getOverlayPackages(): Set<String> {
        return try {
            val om = context.getSystemService(Context.OVERLAY_SERVICE) as OverlayManager
            val userHandle = Process.myUserHandle()
            val targets = listOf("android", "com.android.systemui", "com.android.settings", "com.android.launcher3")
            targets.flatMap { om.getOverlayInfosForTarget(it, userHandle) }
                .map { it.packageName }
                .toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun triggerAutoSave(map: Map<String, TargetMode>) {
        hasPendingChanges = true
        scope.launch(Dispatchers.IO) {
            saveTargetMap(map)
        }
    }

    fun showTargetAppPicker() {
        scope.launch {
            hasPendingChanges = false
            
            val pm = context.packageManager
            val currentMap = withContext(Dispatchers.IO) { readTargetMap() }.toMutableMap()

            val themeContext = ContextThemeWrapper(context, R.style.Theme_Settings)
            val bottomSheetDialog = BottomSheetDialog(themeContext)
            bottomSheetDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

            bottomSheetDialog.setOnDismissListener {
                if (hasPendingChanges) {
                    onSaved()
                    hasPendingChanges = false
                }
            }

            val rootLayout = object : LinearLayout(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val displayMetrics = context.resources.displayMetrics
                    val maxHeight = (displayMetrics.heightPixels * 0.75).toInt()
                    var hSize = MeasureSpec.getSize(heightMeasureSpec)
                    val hMode = MeasureSpec.getMode(heightMeasureSpec)
                    
                    if (hMode == MeasureSpec.UNSPECIFIED || hSize > maxHeight) {
                        hSize = maxHeight
                    }
                    val newHeightSpec = MeasureSpec.makeMeasureSpec(hSize, MeasureSpec.AT_MOST)
                    super.onMeasure(widthMeasureSpec, newHeightSpec)
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, px(16), 0, 0)
                clipChildren = true
            }

            bottomSheetDialog.setOnShowListener {
                val bottomSheet = bottomSheetDialog.findViewById<FrameLayout>(MaterialR.id.design_bottom_sheet)
                bottomSheet?.let { sheet ->
                    val tv = TypedValue()
                    themeContext.theme.resolveAttribute(android.R.attr.colorBackgroundFloating, tv, true)
                    
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

            val headerLayout = RelativeLayout(context).apply {
                setPadding(px(24), px(8), px(24), px(12))
            }
            
            val titleView = TextView(context).apply {
                text = context.getString(R.string.spoof_ts_manage_target_apps)
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary))
            }
            val titleParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            headerLayout.addView(titleView, titleParams)
            rootLayout.addView(headerLayout)

            val divider = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))).apply {
                    setMargins(0, 0, 0, px(4))
                }
                val tv = TypedValue()
                context.theme.resolveAttribute(android.R.attr.dividerVertical, tv, true)
                setBackgroundColor(tv.data)
            }
            rootLayout.addView(divider)

            val view = LayoutInflater.from(context).inflate(R.layout.dialog_app_picker, null)
            if (view is ViewGroup) {
                view.clipChildren = true
            }
            
            val searchView = view.findViewById<EditText>(R.id.et_search)
            val systemToggle = view.findViewById<CheckBox>(R.id.cb_system)
            val autoButton = view.findViewById<Button>(R.id.btn_auto)
            val loadingContainer = view.findViewById<FrameLayout>(R.id.fl_loading)
            val rv = view.findViewById<RecyclerView>(R.id.rv_apps)

            rv.layoutManager = LinearLayoutManager(context)

            val contentParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            rootLayout.addView(view, contentParams)

            bottomSheetDialog.setContentView(rootLayout)

            searchView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val bottomSheet = bottomSheetDialog.findViewById<FrameLayout>(MaterialR.id.design_bottom_sheet)
                    bottomSheet?.let { BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED }
                }
            }

            bottomSheetDialog.show()

            val allInstalledApps = withContext(Dispatchers.IO) {
                val ov = getOverlayPackages()
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { app ->
                        val isOverlay = app.packageName in ov
                        val isExcluded = app.packageName.contains(".overlay") || app.packageName.contains(".resources")
                        !isOverlay && !isExcluded
                    }
            }

            val adapter = TrickyAppAdapter(allInstalledApps, currentMap, pm) {
                triggerAutoSave(currentMap)
            }
            rv.adapter = adapter
            adapter.updateList()

            loadingContainer.visibility = View.GONE
            rv.visibility = View.VISIBLE

            searchView.isEnabled = true
            systemToggle.isEnabled = true
            systemToggle.isChecked = showSystemApps
            autoButton.isEnabled = true

            searchView.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    adapter.setQuery(s.toString())
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            systemToggle.setOnCheckedChangeListener { _, isChecked ->
                showSystemApps = isChecked
                adapter.updateList()
            }

            autoButton.setOnClickListener {
                var changed = false
                AUTO_SELECT_PACKAGES.forEach { pkg ->
                    if (!currentMap.containsKey(pkg)) {
                        currentMap[pkg] = TargetMode.AUTO
                        changed = true
                    }
                }
                if (changed) {
                    adapter.updateList()
                    triggerAutoSave(currentMap)
                }
            }
        }
    }

    private suspend fun readTargetMap(): Map<String, TargetMode> = withContext(Dispatchers.IO) {
        val file = File(storePath, targetFileName)
        if (!file.exists()) return@withContext emptyMap()
        try {
            file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { TargetMode.fromLine(it.trim()) }
        } catch (e: Exception) { emptyMap() }
    }

    private suspend fun saveTargetMap(map: Map<String, TargetMode>) = withContext(Dispatchers.IO) {
        try {
            val file = File(storePath, targetFileName)
            file.writeText(map.map { "${it.key}${it.value.symbol}" }.joinToString("\n"))
            file.setReadable(true, false)
        } catch (e: Exception) { Log.e("TSManager", "Save error", e) }
    }

    private inner class TrickyAppAdapter(
        private val allApps: List<ApplicationInfo>,
        private val targetMap: MutableMap<String, TargetMode>,
        private val pm: PackageManager,
        private val onDataChanged: () -> Unit
    ) : RecyclerView.Adapter<TrickyAppAdapter.ViewHolder>() {

        private var displayList = mutableListOf<ApplicationInfo>()
        private var currentQuery = ""
        private var expandedPackageName: String? = null

        fun setQuery(query: String) {
            currentQuery = query
            updateList()
        }

        fun updateList() {
            displayList = allApps.filter { app ->
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val matchesQuery = app.loadLabel(pm).toString().lowercase().contains(currentQuery.lowercase()) ||
                                 app.packageName.lowercase().contains(currentQuery.lowercase())
                val shouldShow = (!isSystem || showSystemApps || targetMap.containsKey(app.packageName))
                shouldShow && matchesQuery
            }.sortedWith(compareByDescending<ApplicationInfo> { 
                targetMap.containsKey(it.packageName) 
            }.thenBy { 
                it.loadLabel(pm).toString().lowercase() 
            }).toMutableList()
            
            notifyDataSetChanged()
        }

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.iv_app_icon)
            val label: TextView = v.findViewById(R.id.tv_app_label)
            val pkgText: TextView = v.findViewById(R.id.tv_app_package)
            val badge: TextView = v.findViewById(R.id.tv_mode_badge)
            val checkbox: CheckBox = v.findViewById(R.id.cb_app_enabled)
            val rgMode: RadioGroup = v.findViewById(R.id.rg_mode)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tricky_app, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = displayList[position]
            val pkg = app.packageName
            val mode = targetMap[pkg]
            val isExpanded = expandedPackageName == pkg

            holder.label.text = app.loadLabel(pm)
            holder.pkgText.text = pkg
            holder.label.setTextColor(Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary))
            
            holder.icon.setImageDrawable(null)
            scope.launch(Dispatchers.IO) {
                val drawable = try { app.loadIcon(pm) } catch (e: Exception) { null }
                withContext(Dispatchers.Main) {
                    if (holder.pkgText.text == pkg) {
                        holder.icon.setImageDrawable(drawable)
                    }
                }
            }
            
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.rgMode.setOnCheckedChangeListener(null)
            
            val isChecked = targetMap.containsKey(pkg)
            holder.checkbox.isChecked = isChecked
            holder.badge.visibility = if (isChecked) View.VISIBLE else View.GONE
            
            holder.badge.text = when (mode) {
                TargetMode.LEAF_HACK -> context.getString(R.string.spoof_ts_target_mode_leaf)
                TargetMode.CERT_GEN -> context.getString(R.string.spoof_ts_target_mode_cert)
                else -> context.getString(R.string.spoof_ts_target_mode_auto)
            }
            
            holder.rgMode.visibility = if (isChecked && isExpanded) View.VISIBLE else View.GONE

            when (mode) {
                TargetMode.LEAF_HACK -> holder.rgMode.check(R.id.rb_leaf_hack)
                TargetMode.CERT_GEN -> holder.rgMode.check(R.id.rb_cert_gen)
                else -> holder.rgMode.check(R.id.rb_auto)
            }

            holder.itemView.setOnClickListener {
                val lastPkg = expandedPackageName
                if (!targetMap.containsKey(pkg)) {
                    targetMap[pkg] = TargetMode.AUTO
                    expandedPackageName = pkg
                    onDataChanged()
                } else {
                    expandedPackageName = if (isExpanded) null else pkg
                }
                
                if (lastPkg != null && lastPkg != pkg) {
                    val lastIdx = displayList.indexOfFirst { it.packageName == lastPkg }
                    if (lastIdx != -1) notifyItemChanged(lastIdx)
                }
                notifyItemChanged(position)
            }

            holder.checkbox.setOnClickListener {
                val cb = it as CheckBox
                val lastPkg = expandedPackageName
                if (cb.isChecked) {
                    targetMap[pkg] = TargetMode.AUTO
                    expandedPackageName = pkg
                } else {
                    targetMap.remove(pkg)
                    if (isExpanded) expandedPackageName = null
                }
                onDataChanged()
                if (lastPkg != null && lastPkg != pkg) {
                    val lastIdx = displayList.indexOfFirst { it.packageName == lastPkg }
                    if (lastIdx != -1) notifyItemChanged(lastIdx)
                }
                notifyItemChanged(position)
            }

            holder.rgMode.setOnCheckedChangeListener { _, id ->
                val newMode = when (id) {
                    R.id.rb_leaf_hack -> TargetMode.LEAF_HACK
                    R.id.rb_cert_gen -> TargetMode.CERT_GEN
                    else -> TargetMode.AUTO
                }
                targetMap[pkg] = newMode
                holder.badge.text = when (newMode) {
                    TargetMode.LEAF_HACK -> context.getString(R.string.spoof_ts_target_mode_leaf)
                    TargetMode.CERT_GEN -> context.getString(R.string.spoof_ts_target_mode_cert)
                    else -> context.getString(R.string.spoof_ts_target_mode_auto)
                }
                onDataChanged()
            }
        }

        override fun getItemCount() = displayList.size
    }

    private fun px(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()
}