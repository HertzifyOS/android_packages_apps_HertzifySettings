package com.hertzify.settings.fragments.miscellaneous

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.android.settings.R

class TrickyStoreAdapter(
    private var apps: List<ApplicationInfo>,
    private val pm: PackageManager,
    private val targetMap: Map<String, TrickyStoreController.TargetMode>,
    private val onAppChanged: (packageName: String, mode: TrickyStoreController.TargetMode?, isChecked: Boolean) -> Unit
) : RecyclerView.Adapter<TrickyStoreAdapter.AppViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tricky_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]
        val pkgName = app.packageName
        
        holder.tvAppLabel.text = app.loadLabel(pm)
        holder.tvAppPackage.text = pkgName
        
        holder.ivAppIcon.setImageDrawable(app.loadIcon(pm))

        val currentMode = targetMap[pkgName]
        val isChecked = currentMode != null

        setupInitialUI(holder, isChecked, currentMode ?: TrickyStoreController.TargetMode.AUTO)

        holder.itemView.setOnClickListener {
            if (!holder.cbAppEnabled.isChecked) {
                holder.cbAppEnabled.isChecked = true
                holder.rgMode.visibility = View.VISIBLE
            } else {
                toggleMenu(holder)
            }
        }

        holder.tvModeBadge.setOnClickListener {
            toggleMenu(holder)
        }

        holder.cbAppEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val mode = getSelectedMode(holder)
                updateBadge(holder, true, mode)
                onAppChanged(pkgName, mode, true)
            } else {
                updateBadge(holder, false, null)
                holder.rgMode.visibility = View.GONE
                onAppChanged(pkgName, null, false)
            }
        }

        holder.rgMode.setOnCheckedChangeListener { _, _ ->
            if (holder.cbAppEnabled.isChecked) {
                val mode = getSelectedMode(holder)
                updateBadge(holder, true, mode)
                onAppChanged(pkgName, mode, true)
            }
        }
    }

    private fun setupInitialUI(holder: AppViewHolder, isChecked: Boolean, mode: TrickyStoreController.TargetMode) {
        holder.cbAppEnabled.isChecked = isChecked
        updateBadge(holder, isChecked, mode)
        
        holder.rgMode.visibility = View.GONE
        
        when (mode) {
            TrickyStoreController.TargetMode.LEAF_HACK -> holder.rbLeafHack.isChecked = true
            TrickyStoreController.TargetMode.CERT_GEN -> holder.rbCertGen.isChecked = true
            else -> holder.rbAuto.isChecked = true
        }
    }

    private fun updateBadge(holder: AppViewHolder, isChecked: Boolean, mode: TrickyStoreController.TargetMode?) {
        holder.tvModeBadge.visibility = if (isChecked) View.VISIBLE else View.GONE
        mode?.let {
            holder.tvModeBadge.text = it.name.replace("_", " ")
        }
    }

    private fun toggleMenu(holder: AppViewHolder) {
        if (holder.cbAppEnabled.isChecked) {
            val isVisible = holder.rgMode.visibility == View.VISIBLE
            holder.rgMode.visibility = if (isVisible) View.GONE else View.VISIBLE
        }
    }

    private fun getSelectedMode(holder: AppViewHolder) = when (holder.rgMode.checkedRadioButtonId) {
        R.id.rb_leaf_hack -> TrickyStoreController.TargetMode.LEAF_HACK
        R.id.rb_cert_gen -> TrickyStoreController.TargetMode.CERT_GEN
        else -> TrickyStoreController.TargetMode.AUTO
    }

    override fun getItemCount() = apps.size

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAppIcon: ImageView = view.findViewById(R.id.iv_app_icon)
        val tvAppLabel: TextView = view.findViewById(R.id.tv_app_label)
        val tvAppPackage: TextView = view.findViewById(R.id.tv_app_package)
        val tvModeBadge: TextView = view.findViewById(R.id.tv_mode_badge)
        val cbAppEnabled: CheckBox = view.findViewById(R.id.cb_app_enabled)
        val rgMode: RadioGroup = view.findViewById(R.id.rg_mode)
        val rbAuto: RadioButton = view.findViewById(R.id.rb_auto)
        val rbLeafHack: RadioButton = view.findViewById(R.id.rb_leaf_hack)
        val rbCertGen: RadioButton = view.findViewById(R.id.rb_cert_gen)
    }
}