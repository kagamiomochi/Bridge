package dev.kagamimochi.bridge

import org.json.JSONObject

const val PROTOCOL_VERSION = 1

fun envelope(type: String, data: JSONObject = JSONObject()): String =
    JSONObject().put("v", PROTOCOL_VERSION).put("type", type).put("data", data).toString()

fun interface Sender {
    fun send(type: String, data: JSONObject)
}

/** A plugin owns every message whose type starts with "<prefix>.". */
interface BridgePlugin {
    val prefix: String
    fun onConnected(sender: Sender) {}
    fun onDisconnected() {}
    fun onMessage(type: String, data: JSONObject)
}
