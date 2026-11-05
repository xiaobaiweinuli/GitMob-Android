package com.gitmob.app.core.auth

import com.gitmob.app.data.model.AccountState
import com.gitmob.app.data.model.GitHubAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 所有账号提交共用一把锁；凭据持久化成功后才发布新会话。 */
@Singleton
class AccountSession @Inject constructor() {
    val changes = Mutex()
    @Volatile
    var epoch: AccountEpoch = AccountEpoch(this, null, null)
        private set

    private val _state = MutableStateFlow(AccountState(sessionId = epoch.id))
    val state: StateFlow<AccountState> = _state.asStateFlow()

    fun replace(accounts: List<GitHubAccount>, activeKey: String?) {
        val previous = epoch
        val active = accounts.firstOrNull { it.key == activeKey }
        val next = AccountEpoch(this, active?.key, active?.login)
        epoch = next
        previous.job.cancel(CancellationException("Account session changed"))
        _state.value = AccountState(true, accounts, active, next.id)
    }

    fun updateAccounts(accounts: List<GitHubAccount>) {
        epoch.login = accounts.firstOrNull { it.key == epoch.accountKey }?.login
        _state.value = _state.value.copy(
            accounts = accounts,
            activeAccount = accounts.firstOrNull { it.key == epoch.accountKey },
        )
    }

    fun viewModelScope(): CoroutineScope {
        val captured = epoch
        return CoroutineScope(SupervisorJob(captured.job) + Dispatchers.Main.immediate + AccountContext(captured))
    }
}

/** 不保存 token；多步骤请求使用同一账号，刷新后仍从该账号读取新凭据。 */
class AccountEpoch internal constructor(
    private val owner: AccountSession,
    val accountKey: String?,
    login: String?,
) {
    @Volatile
    var login: String? = login
        internal set
    val id: String = UUID.randomUUID().toString()
    val job: Job = SupervisorJob()
    val isCurrent: Boolean get() = owner.epoch === this && job.isActive

    fun ensureCurrent() {
        if (!isCurrent) throw CancellationException("Account session changed")
    }
}

/** 同步缓存/事件代码与 suspend 网络代码共享协程继承的账号上下文。 */
class AccountContext(val epoch: AccountEpoch) :
    ThreadContextElement<AccountEpoch?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AccountContext> {
        private val local = ThreadLocal<AccountEpoch?>()
        fun current(): AccountEpoch? = local.get()
        fun ensureCurrent(): AccountEpoch = checkNotNull(current()) { "Account context is required" }
            .also { it.ensureCurrent() }
    }

    override fun updateThreadContext(context: CoroutineContext): AccountEpoch? =
        local.get().also { local.set(epoch) }

    override fun restoreThreadContext(context: CoroutineContext, oldState: AccountEpoch?) {
        if (oldState == null) local.remove() else local.set(oldState)
    }
}
