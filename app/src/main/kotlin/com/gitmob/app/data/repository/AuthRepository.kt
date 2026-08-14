package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.TokenStorage
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.data.model.ViewerProfile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 登录用的是用户自己创建的 Personal Access Token（不走 OAuth 授权码流程）。
 * 不单独发验证请求——直接用主页第一次要用的那次 GraphQL/REST 聚合查询顺手验证，
 * 成功即代表 token 有效，同时拿到数据；401 才代表 token 真的无效，
 * 其余异常（网络问题等）不代表 token 无效，不要误判、不要保存。
 * 见技能 references/token-storage.md。
 */
@Singleton
class AuthRepository @Inject constructor(
    private val tokenStorage: TokenStorage,
    private val userRepository: UserRepository,
    private val starRepository: StarRepository,
    private val repoRepository: RepoRepository,
    private val gistRepository: GistRepository,
    private val repoDetailRepository: RepoDetailRepository,
    private val notificationRepository: NotificationRepository,
) {
    /**
     * 登录核心流程：临时写入 -> 调 viewer 查询验证 -> 成功则规范化存储/失败则回滚。
     *
     * @param tokenInput 用户手动粘贴的 PAT，登录前先临时写入 TokenStorage，
     *                   请求失败则回滚，不留下无效 token。
     */
    suspend fun loginWithToken(tokenInput: String): ApiResult<ViewerProfile> {
        val tempLogin = "__pending__"

        // Step 1: 临时写入（激活为当前账号），让后续 userRepository 拿这个 token 去发请求
        tokenStorage.saveToken(tempLogin, tokenInput, setActive = true)

        // Step 2: 用 viewer 查询"顺便"验证 token —— 成功就说明有效，401 就说明无效
        val result = userRepository.getViewerProfile()

        return when (result) {
            is ApiResult.Success -> {
                val realLogin = result.data.user.login

                // Step 3a: 规范化存储（用真实 login 当 key），然后删掉临时账号
                tokenStorage.saveToken(realLogin, tokenInput, setActive = true)
                tokenStorage.removeAccount(tempLogin)

                result
            }
            is ApiResult.Failure -> {
                // Step 3b: 明确失败（401/网络等），回滚临时写入，不留下无效 token
                tokenStorage.removeAccount(tempLogin)
                result
            }
        }
    }

    suspend fun isLoggedIn(): Boolean = tokenStorage.isLoggedIn()

    /**
     * 登出流程：清除账号数据 + 清空全部 Repository 的内存缓存。
     * 缓存必须清空，避免切换账号后残留上一个账号的星标/仓库/通知等数据。
     *
     * @param login 要登出的账号 login（tokenStorage 的 key）
     */
    suspend fun logout(login: String) {
        tokenStorage.removeAccount(login)

        // 按依赖注入顺序清空各 Repository 的内存缓存
        userRepository.invalidateAllCaches()
        starRepository.invalidateAllCaches()
        repoRepository.invalidateAllCaches()
        gistRepository.invalidateAllCaches()
        repoDetailRepository.invalidateAllCaches()
        notificationRepository.invalidateAllCaches()
    }
}
