package com.gitmob.app.core.di

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.auth.TokenStorage
import com.gitmob.app.core.markdown.CommonMarkRenderer
import com.gitmob.app.core.markdown.MarkdownRenderer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BindsModule {
    @Binds
    abstract fun bindAccessTokenProvider(impl: TokenStorage): AccessTokenProvider

    @Binds
    abstract fun bindMarkdownRenderer(impl: CommonMarkRenderer): MarkdownRenderer
}
