package com.hertzify.settings.fragments.miscellaneous

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.android.settings.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpoofViewModel(application: Application) : AndroidViewModel(application) {

    private val pifManager = PifManager(application)
    private val repository = PifRepository()

    private val _isFetching = MutableLiveData(false)
    val isFetching: LiveData<Boolean> = _isFetching

    private val _toastEvent = MutableLiveData<Event<Pair<Int, String?>>>()
    val toastEvent: LiveData<Event<Pair<Int, String?>>> = _toastEvent

    private val _configSummary = MutableLiveData<String>()
    val configSummary: LiveData<String> = _configSummary

    init {
        refreshSummary()
    }

    fun fetchAndApply(source: PifRepository.Source) {
        _isFetching.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.fetchPif(source) }
            
            when (result) {
                is PifRepository.PifResult.Success -> {
                    try {
                        withContext(Dispatchers.IO) { pifManager.applyPif(result.pifData) }
                        refreshSummary()
                        showToast(R.string.pif_fetch_success, result.model)
                    } catch (e: Exception) {
                        showToast(R.string.pif_fetch_error, e.message)
                    }
                }
                is PifRepository.PifResult.Error -> {
                    showToast(result.messageRes, result.detail)
                }
            }
            _isFetching.value = false
        }
    }

    fun importConfig(name: String, content: String) {
        _isFetching.value = true
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching { pifManager.importConfig(name, content) }.isSuccess
            }
            if (success) {
                refreshSummary()
                showToast(R.string.pif_import_success, name)
            } else {
                showToast(R.string.pif_import_failed)
            }
            _isFetching.value = false
        }
    }

    fun deleteConfig(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            pifManager.deleteConfig(name)
            refreshSummary()
        }
    }

    fun setSpoofPhotos(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            pifManager.setSpoofPhotos(enabled)
        }
    }

    fun isSpoofPhotosEnabled() = pifManager.isSpoofPhotosEnabled()
    fun getCurrentProperties() = pifManager.getCurrentProperties()
    fun getConfigStates() = pifManager.getConfigStates()

    fun refreshSummary() {
        val model = pifManager.getCurrentModel()
        val name = pifManager.getActiveConfigName()
        _configSummary.postValue(if (model.isEmpty()) "" else "$name · $model")
    }

    private fun showToast(resId: Int, extra: String? = null) {
        _toastEvent.postValue(Event(resId to extra))
    }
}

open class Event<out T>(private val content: T) {
    var hasBeenHandled = false
        private set
    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) null else {
            hasBeenHandled = true
            content
        }
    }
}