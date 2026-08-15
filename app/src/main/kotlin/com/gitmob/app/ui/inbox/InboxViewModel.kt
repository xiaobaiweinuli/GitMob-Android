package com.gitmob.app.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.InboxNotification
import com.gitmob.app.data.model.InboxReadFilter
import com.gitmob.app.data.model.PagedNotifications
import com.gitmob.app.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboxUiState(
    val notifications: List<InboxNotification> = emptyList(),
    val readFilter: InboxReadFilter = InboxReadFilter.UNREAD,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val loadFailed: Boolean = false,
    val nextSourcePage: Int = 1,
    val hasNextPage: Boolean = true, // REST 页码分页，服务端不直接告诉你是否还有下一页，用"这页是否满页"推断
)

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    private var loadedOnce = false
    private var firstPageJob: Job? = null

    fun loadIfNeeded() {
        if (loadedOnce) return
        loadedOnce = true
        load()
    }

    fun load() {
        firstPageJob?.cancel()
        firstPageJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            val filter = _state.value.readFilter
            applyFirstPage(notificationRepository.getNotifications(sourcePage = 1, filter = filter), filter)
        }
    }

    fun refresh() {
        firstPageJob?.cancel()
        firstPageJob = viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            val filter = _state.value.readFilter
            val result = notificationRepository.getNotificationsFresh(filter)
            applyFirstPage(result, filter)
        }
    }

    fun setReadFilter(filter: InboxReadFilter) {
        if (_state.value.readFilter == filter) return
        _state.update {
            it.copy(
                readFilter = filter,
                notifications = emptyList(),
                nextSourcePage = 1,
                hasNextPage = true,
                loadFailed = false,
                isLoading = false,
                isRefreshing = false,
            )
        }
        load()
    }

    private suspend fun applyFirstPage(
        result: ApiResult<PagedNotifications>,
        filter: InboxReadFilter,
    ) {
        if (_state.value.readFilter != filter) return
        when (result) {
            is ApiResult.Success -> _state.update {
                it.copy(
                    notifications = result.data.items,
                    nextSourcePage = result.data.nextSourcePage,
                    hasNextPage = result.data.hasNextPage,
                    isLoading = false, isRefreshing = false, loadFailed = false,
                )
            }
            is ApiResult.Failure -> {
                errorEventBus.emit(result.error)
                _state.update { it.copy(isLoading = false, isRefreshing = false, loadFailed = it.notifications.isEmpty()) }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (!current.hasNextPage) return
        viewModelScope.launch {
            when (val result = notificationRepository.getNotifications(
                sourcePage = current.nextSourcePage,
                filter = current.readFilter,
            )) {
                is ApiResult.Success -> {
                    if (_state.value.readFilter == current.readFilter &&
                        _state.value.nextSourcePage == current.nextSourcePage
                    ) {
                        _state.update {
                            it.copy(
                                notifications = it.notifications + result.data.items,
                                nextSourcePage = result.data.nextSourcePage,
                                hasNextPage = result.data.hasNextPage,
                            )
                        }
                    }
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    fun markAsRead(notification: InboxNotification) {
        viewModelScope.launch {
            when (val result = notificationRepository.markAsRead(notification.id)) {
                is ApiResult.Success -> _state.update { state ->
                    state.copy(
                        notifications = when (state.readFilter) {
                            InboxReadFilter.UNREAD -> state.notifications.filterNot { it.id == notification.id }
                            InboxReadFilter.READ -> state.notifications.filterNot { it.id == notification.id && it.isUnread }
                            InboxReadFilter.ALL -> state.notifications.map {
                                if (it.id == notification.id) it.copy(isUnread = false) else it
                            }
                        },
                    )
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    fun retry() = load()
}
