package com.hermes.client.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class RpcRequest(val id: Long, val method: String, val params: JsonObject) {
    fun encode(json: Json): String {
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}

data class RpcError(val code: Int, val message: String)

sealed interface RpcInbound
data class RpcResult(val id: Long, val result: JsonElement) : RpcInbound
data class RpcErrorReply(val id: Long, val error: RpcError) : RpcInbound
data class RpcEvent(val event: ServerEvent) : RpcInbound

fun parseInbound(json: Json, line: String): RpcInbound {
    val obj = json.parseToJsonElement(line).jsonObject
    val method = obj["method"]?.jsonPrimitive?.content
    if (method == "event") {
        val params = obj.objOrEmpty("params")
        return RpcEvent(ServerEvent.from(params))
    }
    val id = obj["id"]?.jsonPrimitive?.long ?: -1L
    obj["error"]?.let {
        val e = it.jsonObject
        return RpcErrorReply(
            id,
            RpcError(
                code = e["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                message = e["message"]?.jsonPrimitive?.content ?: "error",
            ),
        )
    }
    return RpcResult(id, obj["result"] ?: JsonPrimitive("null"))
}
