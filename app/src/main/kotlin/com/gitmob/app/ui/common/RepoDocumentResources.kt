package com.gitmob.app.ui.common

import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountEpoch
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.repository.RepoDocumentRepository
import com.gitmob.app.data.repository.RepoDocumentResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import java.io.Closeable

/** A page owns only its waits; the repository shares downloads within the account. */
class RepoDocumentResources(
    val document: RepoDocumentContext,
    private val epoch: AccountEpoch,
    private val repository: RepoDocumentRepository,
) : Closeable {
    private val job = SupervisorJob(epoch.job)
    private val scope = CoroutineScope(job + Dispatchers.IO + AccountContext(epoch))

    internal val isActive: Boolean get() = job.isActive && epoch.isCurrent

    suspend fun load(path: String): RepoDocumentResource? {
        job.ensureActive()
        epoch.ensureCurrent()
        val pending = scope.async { (repository.load(document, path) as? ApiResult.Success)?.data }
        return try {
            pending.await().also { job.ensureActive(); epoch.ensureCurrent() }
        } finally {
            pending.cancel()
        }
    }

    override fun close() { job.cancel() }
}
