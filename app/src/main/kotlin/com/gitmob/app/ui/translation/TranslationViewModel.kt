package com.gitmob.app.ui.translation

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.data.translation.*
import com.gitmob.app.data.repository.TranslationRepository
import com.gitmob.app.ui.common.AccountViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TranslationPhase { PREPARING, REQUESTING, WAITING, COMPLETE, CANCELLED, FAILED }

data class TranslationEntry(
    val document: TranslationDocument? = null,
    val translations: Map<Int, String> = emptyMap(),
    val preferences: TranslationPreferences = TranslationPreferences(),
    val credentials: TranslationCredentials = TranslationCredentials(),
    val phase: TranslationPhase = TranslationPhase.COMPLETE,
    val waitingUntil: Long = 0,
    val version: Long = 0,
    val generation: Long = 0,
    val showOriginal: Boolean = false,
    val displayProblem: Boolean = false,
    val displayAttempt: Int = 0,
    val problem: TranslationProblem? = null,
) {
    val running: Boolean get() = phase in setOf(TranslationPhase.PREPARING, TranslationPhase.REQUESTING, TranslationPhase.WAITING)
    val completed: Int get() = translations.size
    val total: Int get() = document?.parts?.size ?: 0
}

data class TranslationUiState(
    val configuration: TranslationConfiguration? = null,
    val entries: Map<String, TranslationEntry> = emptyMap(),
    val selected: TranslationTarget? = null,
    val options: TranslationPreferences = TranslationPreferences(),
    val optionsOpen: Boolean = false,
    val notice: TranslationProblem? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
    val testProblem: TranslationProblem? = null,
    val saving: Boolean = false,
)

@HiltViewModel
class TranslationViewModel @Inject constructor(
    private val repository: TranslationRepository,
    session: AccountSession,
) : AccountViewModel(session) {
    private val _state = MutableStateFlow(TranslationUiState())
    val state = _state.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val generations = mutableMapOf<String, Long>()
    private var generation = 0L
    private var testGeneration = 0L
    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            repository.configuration.collect { config ->
                if (_state.value.configuration != config) clearTest()
                if (!config.preferences.enabled || config.storageFailed) { cancelAll(); _state.update { it.copy(entries = emptyMap(), optionsOpen = false) } }
                _state.update { it.copy(configuration = config) }
            }
        }
    }

    fun savePreferences(value: TranslationPreferences) {
        viewModelScope.launch {
            try { repository.savePreferences(value).getOrThrow() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(notice = TranslationProblem.STORAGE) } }
        }
    }

    fun saveCredentials(provider: TranslationProvider, value: TranslationCredentials, onSaved: () -> Unit) {
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, notice = null) }
            try { repository.saveCredentials(provider, value).getOrThrow(); accountEpoch.ensureCurrent(); onSaved() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(notice = (e as? TranslationException)?.problem ?: TranslationProblem.STORAGE) } }
            finally { _state.update { it.copy(saving = false) } }
        }
    }

    fun open(target: TranslationTarget) {
        val config = _state.value.configuration ?: return
        if (!config.preferences.enabled || config.storageFailed) return
        _state.update { it.copy(selected = target.takeIf { it.content.isNotBlank() }, options = config.preferences, optionsOpen = true, notice = null) }
    }
    fun options(value: TranslationPreferences) = _state.update { it.copy(options = value, notice = null) }
    fun dismiss() = _state.update { it.copy(optionsOpen = false, notice = null) }
    fun clearNotice() = _state.update { it.copy(notice = null) }
    fun request() {
        val state = _state.value
        val config = state.configuration ?: return
        if (!config.preferences.enabled || config.storageFailed) return
        val target = state.selected ?: run { _state.update { it.copy(notice = TranslationProblem.EMPTY) }; return }
        _state.update { it.copy(optionsOpen = false) }
        translate(target, state.options, config.credentialsFor(state.options.provider))
    }

    fun retry(target: TranslationTarget) {
        val entry = _state.value.entries[target.identity] ?: return
        if (entry.displayProblem) {
            updateEntry(target.identity) { it.copy(displayProblem = false, displayAttempt = it.displayAttempt + 1) }
            return
        }
        if (entry.running) return
        _state.update { it.copy(selected = target, options = entry.preferences) }
        request()
    }
    fun displayFailed(target: TranslationTarget, generation: Long) {
        updateEntry(target.identity) { if (it.generation == generation) it.copy(displayProblem = true) else it }
    }
    fun cancel(target: TranslationTarget) {
        generations.remove(target.identity)
        jobs.remove(target.identity)?.cancel()
        updateEntry(target.identity) { it.copy(phase = if (it.running) TranslationPhase.CANCELLED else it.phase, waitingUntil = 0) }
    }
    fun restore(target: TranslationTarget) {
        cancel(target)
        updateEntry(target.identity) { it.copy(showOriginal = true, version = it.version + 1) }
        _state.update { it.copy(optionsOpen = false) }
    }
    private fun cancelAll() {
        generations.clear(); jobs.values.forEach(Job::cancel); jobs.clear(); clearTest()
        _state.update { it.copy(entries = it.entries.mapValues { (_, e) -> e.copy(phase = if (e.running) TranslationPhase.CANCELLED else e.phase) }, testing = false) }
    }
    private fun updateEntry(id: String, transform: (TranslationEntry) -> TranslationEntry) {
        _state.update { state -> state.entries[id]?.let { state.copy(entries = state.entries + (id to transform(it))) } ?: state }
    }

    private fun translate(target: TranslationTarget, preferences: TranslationPreferences, credentials: TranslationCredentials) {
        val id = target.identity
        jobs.remove(id)?.cancel()
        val requestGeneration = ++generation
        generations[id] = requestGeneration
        val previous = _state.value.entries[id]?.takeIf { it.preferences.copy(bilingual = preferences.bilingual) == preferences && it.credentials == credentials }
        // Bound per-page memory, and discard an obsolete revision of the same logical content.
        // Keys such as a thread URL and "URL:title" are distinct translation targets.
        val obsolete = _state.value.entries.keys.filter { it.substringBeforeLast(':') == target.key && it != id }
        obsolete.forEach { generations.remove(it); jobs.remove(it)?.cancel() }
        _state.update { it.copy(entries = (it.entries - obsolete).let { entries ->
            val retained = if (entries.size >= 32 && id !in entries) entries - entries.keys.first().also { key -> generations.remove(key); jobs.remove(key)?.cancel() } else entries
            retained + (id to (previous ?: TranslationEntry()).copy(preferences = preferences, credentials = credentials, phase = TranslationPhase.PREPARING, problem = null, showOriginal = false, generation = requestGeneration, version = (previous?.version ?: 0) + 1, waitingUntil = 0))
        }) }
        jobs[id] = viewModelScope.launch {
            try {
                val document = previous?.document ?: repository.document(target, preferences.provider).getOrThrow()
                if (generations[id] != requestGeneration) return@launch
                _state.update { it.copy(entries = it.entries + (id to it.entries.getValue(id).copy(document = document))) }
                repository.translateDocument(document, _state.value.entries[id]?.translations.orEmpty(), preferences, credentials).collect { update ->
                    accountEpoch.ensureCurrent()
                    if (generations[id] != requestGeneration) return@collect
                    updateEntry(id) { entry -> when (update) {
                        is TranslationUpdate.Batch -> entry.copy(translations = entry.translations + update.values, version = entry.version + 1)
                        is TranslationUpdate.Waiting -> entry.copy(phase = TranslationPhase.WAITING, waitingUntil = update.untilMillis)
                        TranslationUpdate.RequestStarted -> entry.copy(phase = TranslationPhase.REQUESTING, waitingUntil = 0)
                    } }
                }
                if (generations[id] == requestGeneration) updateEntry(id) { it.copy(phase = TranslationPhase.COMPLETE, waitingUntil = 0) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (generations[id] == requestGeneration) _state.update { state -> state.copy(entries = state.entries + (id to (state.entries[id] ?: TranslationEntry()).copy(phase = TranslationPhase.FAILED, waitingUntil = 0, problem = (e as? TranslationException)?.problem ?: TranslationProblem.RESPONSE))) }
            } finally {
                if (generations[id] == requestGeneration) jobs.remove(id)
            }
        }
    }

    fun clearTest() { testGeneration++; testJob?.cancel(); _state.update { it.copy(testing = false, testResult = null, testProblem = null) } }

    fun test(preferences: TranslationPreferences? = null, credentials: TranslationCredentials? = null) {
        val config = _state.value.configuration ?: return
        if (config.storageFailed) return
        clearTest()
        val testId = ++testGeneration
        testJob = viewModelScope.launch {
            _state.update { it.copy(testing = true, testResult = null, testProblem = null) }
            try {
                val p = preferences ?: config.preferences
                val result = repository.translate("Hello, world!", p, credentials ?: config.credentialsFor(p.provider)).getOrThrow()
                accountEpoch.ensureCurrent()
                if (testGeneration == testId) _state.update { it.copy(testResult = result) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (testGeneration == testId) _state.update { it.copy(testProblem = (e as? TranslationException)?.problem ?: TranslationProblem.RESPONSE) } }
            finally { if (testGeneration == testId) _state.update { it.copy(testing = false) } }
        }
    }
}
