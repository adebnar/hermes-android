package com.hermes.client.data.audio

/** Build a base64 data URL the gateway's transcribe endpoint accepts: data:<mime>;base64,<b64>. */
fun audioDataUrl(bytes: ByteArray, mime: String): String =
    "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
