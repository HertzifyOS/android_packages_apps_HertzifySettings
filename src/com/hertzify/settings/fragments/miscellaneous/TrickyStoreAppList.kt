package com.hertzify.settings.fragments.miscellaneous

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.*
import android.widget.ProgressBar
import android.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.settings.R
import kotlinx.coroutines.*

data class AppListItem(
    val info: ApplicationInfo,
    val label: String,
    val packageName: String,
    val isSystem: Boolean
)

class TrickyStoreAppList : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: TrickyStoreAdapter
    private lateinit var controller: TrickyStoreController
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var searchJob: Job? = null

    private var cachedAppItems = listOf<AppListItem>()
    private var showSystemApps = false
    private var currentQuery = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_tricky_apps, container, false)
        
        recyclerView = view.findViewById(R.id.rv_apps)
        progressBar = view.findViewById(R.id.loading_progress)
        
        recyclerView.layoutManager = LinearLayoutManager(context)
        controller = TrickyStoreController(requireContext())
        setHasOptionsMenu(true)
        
        loadApps()
        return view
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE

        scope.launch {
            val pm = requireContext().packageManager
            
            cachedAppItems = withContext(Dispatchers.IO) {
                pm.getInstalledApplications(PackageManager.GET_META_DATA).map {
                    AppListItem(
                        info = it,
                        label = it.loadLabel(pm).toString(),
                        packageName = it.packageName,
                        isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
            }
            updateList()
            
            progressBar.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun updateList() {
        scope.launch {
            val targetMap = withContext(Dispatchers.IO) { controller.readTargetMap() }

            val filteredList = withContext(Dispatchers.Default) {
                cachedAppItems.filter { item ->
                    val matchesQuery = item.label.contains(currentQuery, true) || 
                                     item.packageName.contains(currentQuery, true)
                    val isInTarget = targetMap.containsKey(item.packageName)
                    val shouldShow = (showSystemApps || !item.isSystem) || isInTarget
                    matchesQuery && shouldShow
                }.sortedWith(compareByDescending<AppListItem> { 
                    targetMap.containsKey(it.packageName) 
                }.thenBy { it.label.lowercase() })
            }

            adapter = TrickyStoreAdapter(
                filteredList.map { it.info }, 
                requireContext().packageManager, 
                targetMap
            ) { pkg, mode, isChecked ->
                scope.launch(Dispatchers.IO) {
                    val map = controller.readTargetMap().toMutableMap()
                    if (isChecked && mode != null) {
                        map[pkg] = mode
                    } else {
                        map.remove(pkg)
                    }
                    controller.saveTargetMap(map)
                }
            }
            recyclerView.adapter = adapter
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_tricky_store, menu)
        val searchItem = menu.findItem(R.id.search)
        val searchView = searchItem.actionView as SearchView
        
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false
            override fun onQueryTextChange(q: String?): Boolean {
                searchJob?.cancel()
                searchJob = scope.launch {
                    delay(250)
                    currentQuery = q ?: ""
                    updateList()
                }
                return true
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.show_system -> { showSystemApps = true; updateList(); true }
            R.id.hide_system -> { showSystemApps = false; updateList(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        activity?.title = getString(R.string.target_screen_title)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}