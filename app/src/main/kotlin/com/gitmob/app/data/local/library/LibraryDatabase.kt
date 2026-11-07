package com.gitmob.app.data.local.library

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [LibrarySettingsEntity::class, FavoriteGroupEntity::class,
    FavoriteEntity::class, RepositoryVisitEntity::class,
    LibraryAliasEntity::class, GistVisitEntity::class], version = 3, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}
