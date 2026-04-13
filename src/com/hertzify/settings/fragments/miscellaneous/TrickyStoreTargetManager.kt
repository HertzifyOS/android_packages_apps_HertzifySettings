package com.hertzify.settings.fragments.miscellaneous

import android.content.Context
import android.content.om.OverlayManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.settings.R
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

    fun showTargetAppPicker() {
        scope.launch {
            val pm = context.packageManager
            val currentMap = withContext(Dispatchers.IO) { readTargetMap() }.toMutableMap()

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 0)
            }

            val toolBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val searchView = EditText(context).apply {
                hint = context.getString(R.string.ts_search_apps)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setSingleLine(true)
                isEnabled = false
            }

            val systemToggle = CheckBox(context).apply {
                text = "System"
                isChecked = showSystemApps
                isEnabled = false
            }

            toolBar.addView(searchView)
            toolBar.addView(systemToggle)

            val loadingContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    200.dpToPx()
                )
            }

            val progressBar = ProgressBar(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
            }
            loadingContainer.addView(progressBar)

            val rv = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                visibility = View.GONE
            }

            container.addView(toolBar)
            container.addView(loadingContainer)
            container.addView(rv)

            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.ts_manage_target_apps)
                .setView(container)
                .setPositiveButton(R.string.ts_save) { _, _ ->
                    scope.launch(Dispatchers.IO) {
                        saveTargetMap(currentMap)
                        withContext(Dispatchers.Main) { onSaved() }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

            val (allInstalledApps, _) = withContext(Dispatchers.IO) {
                val ov = getOverlayPackages()
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { app ->
                        val isOverlay = app.packageName in ov
                        val isExcluded = app.packageName.contains(".overlay") || app.packageName.contains(".resources")
                        !isOverlay && !isExcluded
                    }
                apps to ov
            }

            val adapter = TrickyAppAdapter(allInstalledApps, currentMap, pm)
            rv.adapter = adapter
            adapter.updateList()

            loadingContainer.visibility = View.GONE
            rv.visibility = View.VISIBLE

            searchView.isEnabled = true
            systemToggle.isEnabled = true

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
        }
    }

    private fun readTargetMap(): Map<String, TargetMode> {
        val file = File(storePath, targetFileName)
        if (!file.exists()) return emptyMap()
        return try {
            file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { TargetMode.fromLine(it.trim()) }
        } catch (e: Exception) { emptyMap() }
    }

    private fun saveTargetMap(map: Map<String, TargetMode>) {
        try {
            val file = File(storePath, targetFileName)
            file.writeText(map.map { "${it.key}${it.value.symbol}" }.joinToString("\n"))
            file.setReadable(true, false)
        } catch (e: Exception) { Log.e("TSManager", "Save error", e) }
    }

    private inner class TrickyAppAdapter(
        private val allApps: List<ApplicationInfo>,
        private val targetMap: MutableMap<String, TargetMode>,
        private val pm: PackageManager
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
            holder.icon.setImageDrawable(app.loadIcon(pm))
            
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.rgMode.setOnCheckedChangeListener(null)
            
            val isChecked = targetMap.containsKey(pkg)
            holder.checkbox.isChecked = isChecked
            holder.badge.visibility = if (isChecked) View.VISIBLE else View.GONE
            holder.badge.text = mode?.name?.replace("_", " ") ?: ""
            
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
                holder.badge.text = newMode.name.replace("_", " ")
            }
        }

        override fun getItemCount() = displayList.size
    }

    private fun Int.dpToPx(): Int = (this * context.resources.displayMetrics.density).toInt()
}