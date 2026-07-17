package com.hermes.client.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
