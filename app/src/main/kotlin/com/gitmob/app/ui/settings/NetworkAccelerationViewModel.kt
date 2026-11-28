package com.gitmob.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.preferences.RawAccelerationService
import com.gitmob.app.core.preferences.RawAccelerationSettings
import com.gitmob.app.data.repository.AccelerationProbeResult
import com.gitmob.app.data.repository.NetworkAccelerationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NetworkAccelerationState(
    val settings: RawAccelerationSettings? = null,
    val results: Map<String, AccelerationProbeResult> = emptyMap(),
    val pending: Set<String> = emptySet(),
    val testing: Boolean = false,
    val testingOrder: List<String> = emptyList(),
    val saving: Boolean = false,
    val failed: Boolean = false,
) {
    val orderedServices: List<RawAccelerationService>
        get() {
            val services = settings?.services.orEmpty()
            if (testing) {
                val positions = testingOrder.withIndex().associate { it.value to it.index }
                return services.sortedBy { positions[it.id] ?: Int.MAX_VALUE }
            }
            return services.sortedWith(compareBy<RawAccelerationService> {
                when (results[it.id]) {
                    is AccelerationProbeResult.Success -> 0
                    null -> 1
                    else -> 2
                }
            }.thenBy { (results[it.id] as? AccelerationProbeResult.Success)?.millis ?: 0L })
        }
}

@HiltViewModel
class NetworkAccelerationViewModel @Inject constructor(
    private val repository: NetworkAccelerationRepository,
    private val errors: ErrorEventBus,
    session: AccountSession,
) : ViewModel() {
    private val epoch = session.epoch
    private val mutableState = MutableStateFlow(NetworkAccelerationState())
    val state = mutableState.asStateFlow()
    private var testJob: Job? = null
    private var testGeneration = 0L

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                mutableState.update { old ->
                    val unchanged = settings?.services.orEmpty().filter { service ->
                        old.settings?.services?.any { it.id == service.id && it.prefix == service.prefix } == true
                    }.map { it.id }.toSet()
                    old.copy(settings = settings, results = old.results.filterKeys { it in unchanged })
                }
            }
        }
        initialize()
    }

    fun initialize() { viewModelScope.launch {
        mutableState.update { it.copy(failed = false) }
        val result = repository.initialize()
        mutableState.update { it.copy(failed = result is ApiResult.Failure) }
        report(result)
    } }

    fun select(id: String) = change { repository.select(id) }
    fun enable(enabled: Boolean) {
        if (enabled && state.value.settings?.selectedId == null) {
            viewModelScope.launch { errors.emitNotice(com.gitmob.app.R.string.acceleration_choose, epoch) }
        } else change { repository.enable(enabled) }
    }
    fun delete(id: String) = change { repository.delete(id) }

    fun save(id: String?, name: String, address: String, done: () -> Unit) {
        if (state.value.saving) return
        stopTest()
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val result = repository.save(id, name, address)
                if (result is ApiResult.Success) done() else report(result)
            } finally { mutableState.update { it.copy(saving = false) } }
        }
    }

    fun test() {
        if (state.value.testing) { stopTest(); return }
        val settings = state.value.settings ?: return
        if (settings.services.isEmpty()) return
        mutableState.update { it.copy(testing = true, testingOrder = it.orderedServices.map { service -> service.id },
            pending = settings.services.map { service -> service.id }.toSet(), results = emptyMap()) }
        val generation = ++testGeneration
        testJob = viewModelScope.launch {
            try {
                report(repository.testAll(settings) { id, result ->
                    if (generation == testGeneration) mutableState.update { it.copy(results = it.results + (id to result), pending = it.pending - id) }
                })
            } finally { if (generation == testGeneration) mutableState.update { it.copy(testing = false, pending = emptySet()) } }
        }
    }

    private fun stopTest() {
        testGeneration++
        testJob?.cancel()
        testJob = null
        mutableState.update { it.copy(testing = false, pending = emptySet()) }
    }

    private fun change(block: suspend () -> ApiResult<*>) {
        stopTest()
        viewModelScope.launch { report(block()) }
    }

    private suspend fun report(result: ApiResult<*>) {
        if (result is ApiResult.Failure) errors.emit(result.error, epoch)
    }
}
