package com.gitmob.app.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.InboxNotification
import com.gitmob.app.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboxUiState(
    val notifications: List<InboxNotification> = emptyList(),
    val showAll: Boolean = false, // false=只看未读，true=连已读也显示
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val loadFailed: Boolean = false,
    val currentPage: Int = 1,
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

    fun loadIfNeeded() {
        if (loadedOnce) return
        loadedOnce = true
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            applyFirstPage()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            val result = if (_state.value.showAll) {
                notificationRepository.getNotifications(page = 1, all = true)
            } else {
                notificationRepository.getNotificationsFresh()
            }
            applyFirstPage(result)
        }
    }

    fun toggleShowAll() {
        _state.update { it.copy(showAll = !it.showAll) }
        load()
    }

    private suspend fun applyFirstPage() {
        applyFirstPage(
            notificationRepository.getNotifications(page = 1, all = _state.value.showAll),
        )
    }

    private suspend fun applyFirstPage(result: ApiResult<List<InboxNotification>>) {
        when (result) {
            is ApiResult.Success -> _state.update {
                it.copy(
                    notifications = result.data, currentPage = 1,
                    hasNextPage = result.data.size >= 30, // 满页才认为可能还有下一页，REST per_page=30
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
            val nextPage = current.currentPage + 1
            when (val result = notificationRepository.getNotifications(page = nextPage, all = current.showAll)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        notifications = it.notifications + result.data,
                        currentPage = nextPage,
                        hasNextPage = result.data.size >= 30,
                    )
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    fun markAsRead(notification: InboxNotification) {
        viewModelScope.launch {
            when (val result = notificationRepository.markAsRead(notification.id)) {
                is ApiResult.Success -> _state.update { state ->
                    state.copy(notifications = state.notifications.map {
                        if (it.id == notification.id) it.copy(isUnread = false) else it
                    })
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    fun retry() = load()
}
