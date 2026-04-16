package com.gitmob.android.local

import android.content.Context
import com.google.gson.Gson
import com.gitmob.android.auth.TokenStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

class LocalRepoStorage(private val context: Context) {

    private val tokenStorage = TokenStorage(context)
    private val gson = Gson()

    // JsonParser 逐元素解析，完全不依赖泛型签名，debug/release 行为一致
    private fun parseRepos(json: String?): List<LocalRepo> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = com.google.gson.JsonParser.parseString(json).asJsonArray
            array.mapNotNull { element ->
                try { gson.fromJson(element, LocalRepo::class.java) }
                catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    val repos: Flow<List<LocalRepo>> = tokenStorage.localReposJson.map { json ->
        parseRepos(json)
    }

    private suspend fun save(list: List<LocalRepo>) {
        tokenStorage.saveLocalReposJson(gson.toJson(list))
    }

    private suspend fun loadNow(): List<LocalRepo> =
        parseRepos(tokenStorage.localReposJson.first())

    suspend fun addOrUpdate(repo: LocalRepo) {
        val current = loadNow()
        val idx = current.indexOfFirst { it.id == repo.id }
        val updated = if (idx >= 0) current.toMutableList().also { it[idx] = repo }
                      else current + repo
        save(updated)
    }

    suspend fun remove(id: String) {
        val current = loadNow()
        save(current.filter { it.id != id })
    }

    suspend fun update(id: String, block: (LocalRepo) -> LocalRepo) {
        val current = loadNow()
        val updated = current.map { if (it.id == id) block(it) else it }
        save(updated)
    }

    fun newId(): String = UUID.randomUUID().toString()
}
