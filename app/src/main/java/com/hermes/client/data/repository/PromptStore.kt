package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/** A user-saved reusable prompt (device-local). */
@Serializable
data class SavedPrompt(val id: String, val title: String, val body: String)

private val promptJson = Json { ignoreUnknownKeys = true }

/** Decode the stored JSON list; never throws — corrupt/absent → empty. */
fun decodePrompts(raw: String?): List<SavedPrompt> =
    runCatching { promptJson.decodeFromString<List<SavedPrompt>>(raw ?: "[]") }.getOrDefault(emptyList())

fun encodePrompts(list: List<SavedPrompt>): String = promptJson.encodeToString(list)

/** Replace the element whose id matches [p], preserving position; otherwise append. */
fun upsertPrompt(list: List<SavedPrompt>, p: SavedPrompt): List<SavedPrompt> =
    if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p

fun deletePrompt(list: List<SavedPrompt>, id: String): List<SavedPrompt> = list.filterNot { it.id == id }

private val Context.promptDataStore by preferencesDataStore(name = "saved_prompts")

/** Device-local, global store of the user's saved prompts (JSON list under one key). */
class PromptStore(private val context: Context) {
    private val key = stringPreferencesKey("prompts")

    val prompts: Flow<List<SavedPrompt>> =
        context.promptDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { decodePrompts(it[key]) }

    suspend fun upsert(p: SavedPrompt) =
        context.promptDataStore.edit { it[key] = encodePrompts(upsertPrompt(decodePrompts(it[key]), p)) }

    suspend fun delete(id: String) =
        context.promptDataStore.edit { it[key] = encodePrompts(deletePrompt(decodePrompts(it[key]), id)) }
}
