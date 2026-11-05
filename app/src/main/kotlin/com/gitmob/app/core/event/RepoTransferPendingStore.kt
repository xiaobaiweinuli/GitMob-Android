package com.gitmob.app.core.event

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountEpoch
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
    private val partitions = mutableMapOf<AccountEpoch, MutableStateFlow<Map<String, PendingRepositoryTransfer>>>()

    private fun partition(epoch: AccountEpoch): MutableStateFlow<Map<String, PendingRepositoryTransfer>> =
        synchronized(partitions) {
            epoch.ensureCurrent()
            check(epoch.accountKey != null) { "An authenticated account is required for transfers" }
            partitions[epoch] ?: MutableStateFlow<Map<String, PendingRepositoryTransfer>>(emptyMap()).also {
                partitions[epoch] = it
                epoch.job.invokeOnCompletion { synchronized(partitions) { partitions.remove(epoch) } }
            }
        }

    fun transfersFor(epoch: AccountEpoch) = flow {
        partition(epoch).collect { epoch.ensureCurrent(); emit(it) }
    }

    fun mark(transfer: PendingRepositoryTransfer) {
        val epoch = AccountContext.ensureCurrent()
        partition(epoch).update { epoch.ensureCurrent(); it + (transfer.repositoryId to transfer) }
    }

    fun clear(repositoryId: String) {
        val epoch = AccountContext.ensureCurrent()
        partition(epoch).update { epoch.ensureCurrent(); it - repositoryId }
    }

    fun find(repositoryId: String?, owner: String, name: String): PendingRepositoryTransfer? {
        val transfers = partition(AccountContext.ensureCurrent()).value
        return repositoryId?.let(transfers::get) ?: transfers.values.firstOrNull {
            it.owner.equals(owner, true) && it.name.equals(name, true)
        }
    }
}
