package com.theemrock.smooth.sensus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.theemrock.smooth.sensus.discovery.AppDiscoveryManager
import com.theemrock.smooth.sensus.model.AiEnrichment
import com.theemrock.smooth.sensus.model.AppRegistryItem
import com.theemrock.smooth.sensus.model.AppReviewState
import com.theemrock.smooth.sensus.model.CapabilityRegistryItem
import com.theemrock.smooth.sensus.model.ProvenanceStatus
import com.theemrock.smooth.sensus.repository.AppRegistryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppRegistryRepository(application)
    private val discoveryManager = AppDiscoveryManager(application)

    private val _appItems = MutableStateFlow<List<AppRegistryItem>>(emptyList())
    val appItems: StateFlow<List<AppRegistryItem>> = _appItems

    private val _capabilities = MutableStateFlow<List<CapabilityRegistryItem>>(emptyList())
    val capabilities: StateFlow<List<CapabilityRegistryItem>> = _capabilities

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _appItems.value = repository.getAppRegistryItems()
            _capabilities.value = repository.getCapabilityRegistryItems()
        }
    }

    fun scanPhone() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val scanned = discoveryManager.scanInstalledApps()
                repository.saveDiscoveredApps(scanned)
                loadData()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateAppReviewState(packageName: String, reviewState: AppReviewState) {
        viewModelScope.launch {
            repository.updateAppReviewState(packageName, reviewState)
            _appItems.value = repository.getAppRegistryItems()
        }
    }

    fun importAiJson(jsonString: String): Boolean {
        val enrichments = parseAiEnrichmentsOrNull(jsonString) ?: return false
        viewModelScope.launch {
            repository.importAiEnrichments(enrichments)
            loadData()
        }
        return true
    }

    fun toggleHumanConfirmation(item: CapabilityRegistryItem) {
        viewModelScope.launch {
            val newStatus = if (item.provenance == ProvenanceStatus.HUMAN_CONFIRMED) {
                ProvenanceStatus.AI_SUGGESTED
            } else {
                ProvenanceStatus.HUMAN_CONFIRMED
            }
            repository.updateCapabilityStatus(item.id, newStatus)
            loadData()
        }
    }
}

internal fun parseAiEnrichmentsOrNull(jsonString: String): List<AiEnrichment>? =
    runCatching {
        val type = object : com.google.gson.reflect.TypeToken<List<AiEnrichment>>() {}.type
        com.google.gson.Gson().fromJson<List<AiEnrichment>>(jsonString, type)
    }.getOrNull()
