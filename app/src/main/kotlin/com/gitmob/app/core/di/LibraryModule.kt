package com.gitmob.app.core.di

import android.content.Context
import androidx.room.Room
import com.gitmob.app.data.local.library.LibraryDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): LibraryDatabase =
        Room.databaseBuilder(context, LibraryDatabase::class.java, "repository-library.db")
            .build()
}
