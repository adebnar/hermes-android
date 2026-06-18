package com.hermes.client.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun encodes_request_with_id_method_params() {
        val req = RpcRequest(7, "prompt.submit", buildJsonObject {
            put("text", "hi"); put("session_id", "s1")
        })
        val line = req.encode(json)
        val back = json.parseToJsonElement(line)
        assertTrue(line.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(line.contains("\"method\":\"prompt.submit\""))
        assertTrue(line.contains("\"id\":7"))
        assertTrue(back.toString().contains("\"text\":\"hi\""))
    }

    @Test fun parses_result_reply() {
        val line = """{"jsonrpc":"2.0","id":7,"result":{"ok":true}}"""
        val msg = parseInbound(json, line)
        assertTrue(msg is RpcResult && msg.id == 7L)
    }

    @Test fun parses_error_reply() {
        val line = """{"jsonrpc":"2.0","id":7,"error":{"code":-32601,"message":"nope"}}"""
        val msg = parseInbound(json, line)
        assertTrue(msg is RpcErrorReply && (msg as RpcErrorReply).error.code == -32601)
    }

    @Test fun parses_event_with_type_and_session() {
        val line = """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","payload":{"session_id":"s1","delta":"hello"}}}"""
        val msg = parseInbound(json, line)
        assertTrue(msg is RpcEvent)
        val ev = (msg as RpcEvent).event
        assertEquals("message.delta", ev.type)
        assertEquals("s1", ev.sessionId)
        assertEquals("hello", ev.payload["delta"]!!.toString().trim('"'))
    }
}
