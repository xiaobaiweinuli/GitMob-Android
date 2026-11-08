package com.gitmob.app.ui.translation

import com.gitmob.app.data.translation.*
import com.gitmob.app.data.repository.TranslationRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationViewModelTest {
    private val main = MainDispatcherRule()
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(main).around(accounts)
    private val preferences = TranslationPreferences(enabled = true)
    private val configuration = MutableStateFlow<TranslationConfiguration?>(TranslationConfiguration(preferences))
    private val repository = mockk<TranslationRepository>()

    private fun viewModel(): TranslationViewModel {
        every { repository.configuration } returns configuration
        coEvery { repository.document(any(), any()) } coAnswers {
            val target = firstArg<TranslationTarget>()
            val parts = target.content.split('|')
            Result.success(TranslationDocument(target, parts, listOf(TranslationSpan(0, target.content.length, parts.indices.toList()))))
        }
        every { repository.translateDocument(any(), any(), any(), any()) } answers {
            val document = firstArg<TranslationDocument>()
            val completed = secondArg<Map<Int, String>>()
            val preferences = thirdArg<TranslationPreferences>()
            val credentials = arg<TranslationCredentials>(3)
            flow {
                document.parts.forEachIndexed { index, text ->
                    if (index !in completed) {
                        emit(TranslationUpdate.RequestStarted)
                        emit(TranslationUpdate.Batch(mapOf(index to repository.translate(text, preferences, credentials).getOrThrow())))
                    }
                }
            }
        }
        return TranslationViewModel(repository, accounts.session)
    }

    @Test fun cachedConfigurationIsAvailableBeforeThePageCollectorStarts() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = viewModel()
        assertEquals(configuration.value, vm.state.value.configuration)
        assertEquals(0, configuration.subscriptionCount.value)
        runCurrent()
        assertEquals(1, configuration.subscriptionCount.value)
    }

    @Test fun enteringWithAStorageErrorRetriesAndThePageCanRetryAgain() = runTest {
        configuration.value = TranslationConfiguration(storageFailed = true)
        every { repository.retryConfiguration() } just Runs
        val vm = viewModel()
        verify(exactly = 1) { repository.retryConfiguration() }
        vm.retryConfiguration()
        verify(exactly = 2) { repository.retryConfiguration() }
        configuration.value = TranslationConfiguration(preferences)
        runCurrent()
        assertEquals(preferences, vm.state.value.configuration?.preferences)
    }

    @Test fun loadingDoesNotEnableActionsAndFirstConfigurationBecomesAvailable() = runTest {
        configuration.value = null
        val vm = viewModel()
        val target = TranslationTarget("body", "hello")
        assertNull(vm.state.value.configuration)
        vm.open(target)
        vm.request()
        assertFalse(vm.state.value.optionsOpen)
        assertTrue(vm.state.value.entries.isEmpty())
        configuration.value = TranslationConfiguration(preferences)
        runCurrent()
        vm.open(target)
        assertTrue(vm.state.value.optionsOpen)
        assertEquals(preferences, vm.state.value.options)
    }

    @Test fun sharedConfigurationDoesNotSharePageTranslationsOrConnectionTestResults() = runTest {
        coEvery { repository.translate(any(), any(), any()) } returns Result.success("translated")
        val first = viewModel()
        val target = TranslationTarget("body", "hello")
        first.open(target)
        first.request()
        first.test()
        advanceUntilIdle()
        assertTrue(first.state.value.entries.isNotEmpty())
        assertEquals("translated", first.state.value.testResult)

        val second = viewModel()
        assertEquals(first.state.value.configuration, second.state.value.configuration)
        assertTrue(second.state.value.entries.isEmpty())
        assertNull(second.state.value.selected)
        assertNull(second.state.value.testResult)
        assertFalse(second.state.value.testing)
    }

    @Test fun storageFailureCancelsPageWorkAndClearsVisibleResults() = runTest {
        var cancelled = false
        coEvery { repository.translate(any(), any(), any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        val vm = viewModel()
        vm.open(TranslationTarget("body", "hello"))
        vm.request()
        runCurrent()
        configuration.value = TranslationConfiguration(storageFailed = true)
        advanceUntilIdle()
        assertTrue(cancelled)
        assertTrue(vm.state.value.entries.isEmpty())
        assertFalse(vm.state.value.optionsOpen)
        assertEquals(true, vm.state.value.configuration?.storageFailed)
    }

    @Test fun selectedContentStartsWithoutASendingConfirmation() = runTest {
        coEvery { repository.translate(any(), any(), any()) } returns Result.success("translated")
        val vm = viewModel()
        val target = TranslationTarget("private:body", "hello")
        vm.open(target); vm.request(); advanceUntilIdle()
        assertEquals(mapOf(0 to "translated"), vm.state.value.entries.getValue(target.identity).translations)
        assertFalse(vm.state.value.optionsOpen)
    }

    @Test fun firstCompletedUnitIsPublishedWhileNextIsStillRunning() = runTest {
        val next = CompletableDeferred<String>()
        coEvery { repository.translate(any(), any(), any()) } coAnswers {
            Result.success(if (firstArg<String>() == "one") "第一段" else next.await())
        }
        val vm = viewModel()
        val target = TranslationTarget("body", "one|two")
        vm.open(target); vm.request(); runCurrent()
        val entry = vm.state.value.entries.getValue(target.identity)
        assertEquals(mapOf(0 to "第一段"), entry.translations)
        assertTrue(entry.running)
        next.complete("第二段"); advanceUntilIdle()
        assertEquals(TranslationPhase.COMPLETE, vm.state.value.entries.getValue(target.identity).phase)
    }

    @Test fun retryKeepsSuccessfulPartsAndDoesNotSilentlySwitchService() = runTest {
        var failSecond = true
        coEvery { repository.translate(any(), any(), any()) } coAnswers {
            val text = firstArg<String>()
            if (text == "two" && failSecond) { failSecond = false; throw TranslationException(TranslationProblem.LIMIT) }
            Result.success("translated-" + text)
        }
        val vm = viewModel()
        val target = TranslationTarget("body", "one|two|three")
        vm.open(target); vm.request(); advanceUntilIdle()
        assertEquals(TranslationProblem.LIMIT, vm.state.value.entries[target.identity]?.problem)
        assertEquals(1, vm.state.value.entries[target.identity]?.completed)
        vm.retry(target); advanceUntilIdle()
        assertEquals(3, vm.state.value.entries[target.identity]?.completed)
        assertNull(vm.state.value.entries[target.identity]?.problem)
        coVerify(exactly = 1) { repository.translate("one", preferences, TranslationCredentials()) }
        coVerify(exactly = 2) { repository.translate("two", preferences, TranslationCredentials()) }
        coVerify(exactly = 1) { repository.document(target, TranslationProvider.EDGE) }
        coVerify(exactly = 0) { repository.translate(any(), match { it.provider != TranslationProvider.EDGE }, any()) }
    }

    @Test fun cancelledOldWorkCannotClearOrOverwriteReplacementWork() = runTest {
        val old = CompletableDeferred<String>()
        val replacement = CompletableDeferred<String>()
        var calls = 0
        coEvery { repository.translate(any(), any(), any()) } coAnswers {
            val result = if (++calls == 1) old else replacement
            Result.success(withContext(NonCancellable) { result.await() })
        }
        val vm = viewModel()
        val target = TranslationTarget("body", "hello")
        vm.open(target); vm.request()
        vm.cancel(target)
        vm.retry(target)
        assertTrue(vm.state.value.entries.getValue(target.identity).running)
        old.complete("obsolete"); runCurrent()
        assertTrue(vm.state.value.entries.getValue(target.identity).running)
        assertTrue(vm.state.value.entries.getValue(target.identity).translations.isEmpty())
        replacement.complete("current"); advanceUntilIdle()
        assertEquals(mapOf(0 to "current"), vm.state.value.entries.getValue(target.identity).translations)
        assertFalse(vm.state.value.entries.getValue(target.identity).running)
    }

    @Test fun accountSwitchCancelsRequestsAndPreventsResultPublication() = runTest {
        var cancelled = false
        coEvery { repository.translate(any(), any(), any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        val vm = viewModel()
        val target = TranslationTarget("body", "hello")
        vm.open(target); vm.request()
        accounts.session.replace(emptyList(), null)
        advanceUntilIdle()
        assertTrue(cancelled)
        assertTrue(vm.state.value.entries.getValue(target.identity).translations.isEmpty())
    }

    @Test fun disablingTranslationClearsPageContentAndActiveWork() = runTest {
        coEvery { repository.translate(any(), any(), any()) } coAnswers { awaitCancellation() }
        val vm = viewModel()
        vm.open(TranslationTarget("body", "hello")); vm.request()
        configuration.value = TranslationConfiguration(preferences.copy(enabled = false))
        advanceUntilIdle()
        assertTrue(vm.state.value.entries.isEmpty())
        assertFalse(vm.state.value.optionsOpen)
    }

    @Test fun changingProviderDiscardsObsoleteConnectionTestResult() = runTest {
        val result = CompletableDeferred<String>()
        coEvery { repository.translate(any(), any(), any()) } coAnswers { Result.success(withContext(NonCancellable) { result.await() }) }
        val vm = viewModel()
        vm.test()
        assertTrue(vm.state.value.testing)
        configuration.value = TranslationConfiguration(preferences.copy(provider = TranslationProvider.TRANSMART))
        result.complete("old service result")
        advanceUntilIdle()
        assertFalse(vm.state.value.testing)
        assertNull(vm.state.value.testResult)
        assertNull(vm.state.value.testProblem)
    }

    @Test fun newContentRevisionEvictsOldTranslationAndRestoreKeepsCachedResult() = runTest {
        coEvery { repository.translate(any(), any(), any()) } returns Result.success("translated")
        val vm = viewModel()
        val original = TranslationTarget("body", "old text")
        val revised = TranslationTarget("body", "new text")
        vm.open(original); vm.request(); advanceUntilIdle()
        vm.open(revised); vm.request(); advanceUntilIdle()
        assertEquals(setOf(revised.identity), vm.state.value.entries.keys)
        vm.restore(revised)
        assertTrue(vm.state.value.entries.getValue(revised.identity).showOriginal)
        assertEquals(mapOf(0 to "translated"), vm.state.value.entries.getValue(revised.identity).translations)
        vm.retry(revised); advanceUntilIdle()
        coVerify(exactly = 2) { repository.translate(any(), any(), any()) }
    }

    @Test fun failedCredentialValidationKeepsSheetOpenWithConfigurationError() = runTest {
        coEvery { repository.saveCredentials(any(), any()) } throws TranslationException(TranslationProblem.CONFIGURATION)
        val vm = viewModel()
        var dismissed = false
        vm.saveCredentials(TranslationProvider.CUSTOM, TranslationCredentials()) { dismissed = true }
        advanceUntilIdle()
        assertFalse(dismissed)
        assertEquals(TranslationProblem.CONFIGURATION, vm.state.value.notice)
        assertFalse(vm.state.value.saving)
    }
    @Test fun titleOnlyRequestDoesNotSubmitBody() = runTest {
        coEvery { repository.translate(any(), any(), any()) } returns Result.success("Translated title")
        val vm = viewModel()
        val title = TranslationTarget("https://github.com/o/r/issues/1:title", "Original title")
        vm.open(title); vm.request(); advanceUntilIdle()
        assertEquals(title, vm.state.value.selected)
        assertEquals(setOf(title.identity), vm.state.value.entries.keys)
        coVerify(exactly = 1) { repository.document(title, preferences.provider) }
        coVerify(exactly = 1) { repository.translate("Original title", preferences, TranslationCredentials()) }
        coVerify(exactly = 1) { repository.translate(any(), any(), any()) }
    }

    @Test fun startingBodyDoesNotEvictRunningTitleAndBodyFailureLeavesTitleResult() = runTest {
        val titleResult = CompletableDeferred<String>()
        val bodyResult = CompletableDeferred<String>()
        coEvery { repository.translate(any(), any(), any()) } coAnswers {
            Result.success(if (firstArg<String>() == "Original title") titleResult.await() else bodyResult.await())
        }
        val vm = viewModel()
        val title = TranslationTarget("https://github.com/o/r/issues/1:title", "Original title")
        val body = TranslationTarget("https://github.com/o/r/issues/1", "Original body")
        vm.open(title); vm.request()
        vm.open(body); vm.request(); runCurrent()
        assertEquals(setOf(title.identity, body.identity), vm.state.value.entries.keys)
        assertTrue(vm.state.value.entries.getValue(title.identity).running)
        titleResult.complete("Translated title"); runCurrent()
        assertEquals(mapOf(0 to "Translated title"), vm.state.value.entries.getValue(title.identity).translations)
        assertFalse(vm.state.value.entries.getValue(title.identity).running)
        assertTrue(vm.state.value.entries.getValue(body.identity).running)
        bodyResult.completeExceptionally(TranslationException(TranslationProblem.LIMIT)); advanceUntilIdle()
        assertEquals(TranslationProblem.LIMIT, vm.state.value.entries.getValue(body.identity).problem)
        assertNull(vm.state.value.entries.getValue(title.identity).problem)
        assertEquals(mapOf(0 to "Translated title"), vm.state.value.entries.getValue(title.identity).translations)
    }

    @Test fun revisingAndRestoringTitlePreservesSeparatelyTranslatedBody() = runTest {
        coEvery { repository.translate(any(), any(), any()) } coAnswers { Result.success("Translated " + firstArg<String>()) }
        val vm = viewModel()
        val title = TranslationTarget("https://github.com/o/r/pull/1:title", "Title")
        val body = TranslationTarget("https://github.com/o/r/pull/1", "Body")
        vm.open(title); vm.request(); advanceUntilIdle()
        vm.open(body); vm.request(); advanceUntilIdle()
        assertEquals(setOf(title.identity, body.identity), vm.state.value.entries.keys)
        val translatedBody = vm.state.value.entries.getValue(body.identity)
        val revised = title.copy(content = "Updated title")
        vm.open(revised); vm.request(); advanceUntilIdle()
        assertEquals(setOf(revised.identity, body.identity), vm.state.value.entries.keys)
        assertEquals(translatedBody, vm.state.value.entries[body.identity])
        vm.restore(requireNotNull(vm.state.value.selected))
        assertEquals(translatedBody, vm.state.value.entries[body.identity])
        assertTrue(vm.state.value.entries.getValue(revised.identity).showOriginal)
    }

    @Test fun displayRetryReplaysExistingResultsWithoutCallingServiceAgain() = runTest {
        coEvery { repository.translate(any(), any(), any()) } returns Result.success("translated")
        val vm = viewModel()
        val target = TranslationTarget("body", "hello")
        vm.open(target); vm.request(); advanceUntilIdle()
        val generation = vm.state.value.entries.getValue(target.identity).generation
        vm.displayFailed(target, generation)
        vm.retry(target)
        assertFalse(vm.state.value.entries.getValue(target.identity).displayProblem)
        assertEquals(1, vm.state.value.entries.getValue(target.identity).displayAttempt)
        coVerify(exactly = 1) { repository.translate(any(), any(), any()) }
    }

}
