package com.hermes.client.ui.setup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Credentials carried by a Hermes pairing QR. Primary payload is url + username + password. */
@Serializable
data class PairingPayload(
    val v: Int = 0,
    val url: String = "",
    val token: String = "",
    val username: String = "",
    val password: String = "",
)

private val pairingJson = Json { ignoreUnknownKeys = true }

/**
 * Parse a scanned QR string. Returns null (never throws) unless it is a valid v1 Hermes pairing
 * object with a non-blank url — so a random/non-Hermes QR is rejected cleanly.
 */
fun parsePairingPayload(raw: String): PairingPayload? =
    runCatching { pairingJson.decodeFromString<PairingPayload>(raw) }
        .getOrNull()
        ?.takeIf { it.v == 1 && it.url.isNotBlank() }
