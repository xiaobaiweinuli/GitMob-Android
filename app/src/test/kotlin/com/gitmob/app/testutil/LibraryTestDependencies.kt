package com.gitmob.app.testutil

import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.data.local.library.LibraryRepository
import com.gitmob.app.data.model.LibraryState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

fun libraryRepositoryStub(): LibraryRepository = mockk {
    every { observe(any(), any()) } returns flowOf(LibraryState(loaded = true))
    every { observeSettings(any()) } returns flowOf(com.gitmob.app.data.model.LibrarySettings())
    every { observeGroups(any()) } returns flowOf(listOf(com.gitmob.app.data.model.FavoriteGroup("default", "", 0, 0)))
    every { observeHistoryCount(any()) } returns flowOf(0)
    coEvery { recordGistVisit(any(), any()) } returns ApiResult.Success(Unit)
    coEvery { refreshGistFavorite(any(), any()) } returns ApiResult.Success(Unit)
    coEvery { refreshFavorite(any(), any(), any()) } returns ApiResult.Success(Unit)
    coEvery { recordVisit(any(), any(), any()) } returns ApiResult.Success(Unit)
}
