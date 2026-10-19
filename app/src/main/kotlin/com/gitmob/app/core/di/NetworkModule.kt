package com.gitmob.app.core.di

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * 只放 @Provides（造具体对象）。@Binds（接口->实现类绑定）放 BindsModule，两者分开写。
 * 不在这里加统一的 Authorization/Accept 拦截器——REST 和 GraphQL 需要的头不同，
 * 头的拼接放在 GHApiClient 内部按请求类型分别处理，见 references/http-headers.md。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Named("restBaseUrl")
    fun provideRestBaseUrl(): String = "https://api.github.com"

    @Provides
    @Named("graphQLUrl")
    fun provideGraphQLUrl(): String = "https://api.github.com/graphql"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /**
     * Apollo GraphQL 通道（B0 基建）。复用现有 OkHttpClient（超时/连接池一致），
     * 鉴权与 UA 头由 AuthHttpInterceptor 注入，与 GHApiClient 的 GraphQL 分支语义对齐。
     */
    @Provides
    @Singleton
    fun provideApolloClient(
        okHttpClient: OkHttpClient,
        tokenProvider: AccessTokenProvider,
        @Named("graphQLUrl") graphQLUrl: String,
    ): ApolloClient =
        ApolloClient.Builder()
            .serverUrl(graphQLUrl)
            .okHttpClient(okHttpClient)
            .addHttpInterceptor(AuthHttpInterceptor(tokenProvider))
            .build()
}
