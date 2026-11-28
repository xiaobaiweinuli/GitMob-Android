package com.gitmob.app.ui.settings

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.*
import com.gitmob.app.core.preferences.*
import com.gitmob.app.data.repository.*
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class NetworkAccelerationViewModelTest {
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts)
    private val service = RawAccelerationService("a", "One", "https://one.example/")
    private val settings = MutableStateFlow<RawAccelerationSettings?>(RawAccelerationSettings(listOf(service), "a", false))
    private val repository = mockk<NetworkAccelerationRepository>()
    private fun viewModel(): NetworkAccelerationViewModel {
        every { repository.settings } returns settings
        coEvery { repository.initialize() } returns ApiResult.Success(settings.value!!)
        return NetworkAccelerationViewModel(repository, ErrorEventBus(), accounts.session)
    }

    @Test fun cancelledProbeCannotWriteProgressIntoNextRun() = runTest {
        val callbacks = mutableListOf<(String, AccelerationProbeResult) -> Unit>()
        var cancelled = 0
        coEvery { repository.testAll(any(), any()) } coAnswers {
            callbacks.add(secondArg())
            try { awaitCancellation() } finally { cancelled++ }
        }
        val vm = viewModel()
        vm.test()
        assertTrue(vm.state.value.testing)
        vm.test()
        assertEquals(1, cancelled)
        assertFalse(vm.state.value.testing)
        assertEquals("a", vm.state.value.settings!!.selectedId)
        assertFalse(vm.state.value.settings!!.enabled)
        vm.test()
        callbacks[0]("a", AccelerationProbeResult.Success(1))
        assertTrue(vm.state.value.results.isEmpty())
        assertEquals(setOf("a"), vm.state.value.pending)
        callbacks[1]("a", AccelerationProbeResult.Success(50))
        assertEquals(AccelerationProbeResult.Success(50), vm.state.value.results["a"])
        vm.viewModelScope.cancel()
    }

    @Test fun resultsSortSuccessBeforeUntestedBeforeFailuresAndStayInPlaceDuringTesting() = runTest {
        val second = service.copy(id = "b", prefix = "https://two.example/")
        val third = service.copy(id = "c", prefix = "https://three.example/")
        settings.value = settings.value!!.copy(services = listOf(service, second, third))
        val state = NetworkAccelerationState(settings.value, results = mapOf(
            "a" to AccelerationProbeResult.Failed, "c" to AccelerationProbeResult.Success(20)))
        assertEquals(listOf("c", "b", "a"), state.orderedServices.map { it.id })
        var callbacks: ((String, AccelerationProbeResult) -> Unit)? = null
        coEvery { repository.testAll(any(), any()) } coAnswers {
            callbacks = secondArg()
            awaitCancellation()
        }
        val vm = viewModel()
        vm.test()
        callbacks!!("a", AccelerationProbeResult.Success(100))
        callbacks!!("b", AccelerationProbeResult.Success(20))
        callbacks!!("c", AccelerationProbeResult.Failed)
        assertEquals(listOf("a", "b", "c"), vm.state.value.orderedServices.map { it.id })
        vm.test()
        assertEquals(listOf("b", "a", "c"), vm.state.value.orderedServices.map { it.id })
        vm.test()
        callbacks!!("a", AccelerationProbeResult.Success(1))
        assertEquals(listOf("b", "a", "c"), vm.state.value.orderedServices.map { it.id })
        vm.viewModelScope.cancel()
    }

    @Test fun manualSelectionStopsProbeAndUrlEditInvalidatesOnlyThatResult() = runTest {
        coEvery { repository.testAll(any(), any()) } coAnswers {
            secondArg<(String, AccelerationProbeResult) -> Unit>()("a", AccelerationProbeResult.Success(20))
            awaitCancellation()
        }
        coEvery { repository.select("a") } returns ApiResult.Success(settings.value!!)
        val vm = viewModel()
        vm.test()
        vm.select("a")
        assertFalse(vm.state.value.testing)
        coVerify(exactly = 1) { repository.select("a") }
        settings.value = settings.value!!.copy(services = listOf(service.copy(name = "Renamed")), revision = 1)
        assertEquals(AccelerationProbeResult.Success(20), vm.state.value.results["a"])
        settings.value = settings.value!!.copy(services = listOf(service.copy(prefix = "https://other.example/")), revision = 2)
        assertTrue(vm.state.value.results.isEmpty())
        vm.viewModelScope.cancel()
    }
}
