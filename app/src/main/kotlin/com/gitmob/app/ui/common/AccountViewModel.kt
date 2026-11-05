package com.gitmob.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountSession

/** 切换账号立即取消旧 ViewModel 的全部工作，包括嵌套请求和更新事件。 */
abstract class AccountViewModel(session: AccountSession) : ViewModel(session.viewModelScope()) {
    val accountEpoch = checkNotNull(viewModelScope.coroutineContext[AccountContext]).epoch
}
