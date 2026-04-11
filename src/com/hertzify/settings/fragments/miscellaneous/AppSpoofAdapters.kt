package com.hertzify.settings.fragments.miscellaneous

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppPickerAdapter(
    private val allEntries: List<AppPickerEntry>,
    private val onPick: (AppPickerEntry) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.VH>() {

    private var shownEntries = allEntries.toList()

    fun filter(query: String) {
        val lower = query.trim().lowercase()
        shownEntries = if (lower.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { 
                it.label.lowercase().contains(lower) || it.packageName.lowercase().contains(lower) 
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val context = parent.context
        val dp = context.resources.displayMetrics.density
        
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        }

        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply {
                marginEnd = (12 * dp).toInt()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvLabel = TextView(context).apply { textSize = 15f }
        val tvPkg = TextView(context).apply { 
            textSize = 11f
            alpha = 0.6f 
        }

        textContainer.addView(tvLabel)
        textContainer.addView(tvPkg)
        row.addView(icon)
        row.addView(textContainer)
        
        return VH(row, icon, tvLabel, tvPkg)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = shownEntries[position]
        holder.icon.setImageDrawable(entry.getIcon())
        holder.tvLabel.text = entry.label
        holder.tvPkg.text = entry.packageName
        holder.itemView.setOnClickListener { onPick(entry) }
    }

    override fun getItemCount() = shownEntries.size
    class VH(v: View, val icon: ImageView, val tvLabel: TextView, val tvPkg: TextView) : RecyclerView.ViewHolder(v)
}

class GenericProfileAdapter(
    private val profiles: List<DeviceProfile>,
    private val onPick: (DeviceProfile) -> Unit
) : RecyclerView.Adapter<GenericProfileAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            val dp = resources.displayMetrics.density
            setPadding((12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt())
            textSize = 15f
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = profiles[position]
        holder.tv.text = p.name
        holder.tv.setOnClickListener { onPick(p) }
    }

    override fun getItemCount() = profiles.size
    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
}