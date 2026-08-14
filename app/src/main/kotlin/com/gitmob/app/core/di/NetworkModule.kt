package com.gitmob.app.core.di

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
}
