package com.gitmob.app.core.event

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class PendingRepositoryTransfer(
    val repositoryId: String,
    val owner: String,
    val name: String,
    val targetOwner: String,
    val targetName: String,
)

@Singleton
class RepoTransferPendingStore @Inject constructor() {
    private val _transfers = MutableStateFlow<Map<String, PendingRepositoryTransfer>>(emptyMap())
    val transfers: StateFlow<Map<String, PendingRepositoryTransfer>> = _transfers.asStateFlow()

    fun mark(transfer: PendingRepositoryTransfer) {
        _transfers.update { it + (transfer.repositoryId to transfer) }
    }

    fun clear(repositoryId: String) {
        _transfers.update { it - repositoryId }
    }

    fun find(repositoryId: String?, owner: String, name: String): PendingRepositoryTransfer? =
        repositoryId?.let(_transfers.value::get) ?: _transfers.value.values.firstOrNull {
            it.owner.equals(owner, true) && it.name.equals(name, true)
        }
}
