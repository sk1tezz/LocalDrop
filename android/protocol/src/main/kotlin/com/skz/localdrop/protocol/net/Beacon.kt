package com.skz.localdrop.protocol.net

import kotlinx.serialization.json.JsonObject

/**
 * The UDP beacon of desktop/localdrop/net/discovery.py: a JSON announcement sent to the
 * multicast group and the subnet broadcast every few seconds; "q" asks others to answer,
 * "hidden" says the device is invisible (or leaving).
 */
const val SERVICE_TYPE = "_localdrop._tcp."
const val BEACON_GROUP = "224.0.0.167"
const val BEACON_PORT = 53317
const val BEACON_EVERY_MS = 5000L

data class Announcement(val id: String, val name: String, val os: String, val kind: String, val icon: String, val port: Int)

fun announce(a: Announcement, query: Boolean = false, hidden: Boolean = false): ByteArray {
    val m = linkedMapOf<String, Any?>("ld" to 1, "id" to a.id, "name" to a.name, "os" to a.os, "kind" to a.kind, "icon" to a.icon, "port" to a.port)
    if (query) m["q"] = 1
    if (hidden) m["hidden"] = 1
    return toJson(m).toString().toByteArray(Charsets.UTF_8)
}

class Heard(val id: String, val info: JsonObject, val query: Boolean)

/** Parses a beacon from [fromIp]; null for anything that is not a LocalDrop beacon or is our own. */
fun parseBeacon(data: ByteArray, len: Int, fromIp: String, myId: String): Heard? {
    val msg = runCatching { json.parseToJsonElement(String(data, 0, len, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return null
    if (msg.int("ld") != 1) return null
    val id = msg.str("id") ?: return null
    if (id == myId) return null
    val info = LinkedHashMap<String, Any?>()
    for (k in listOf("name", "os", "kind", "icon", "port")) msg[k]?.let { info[k] = it }
    info["ip"] = fromIp
    info["hidden"] = msg.truthy("hidden")
    return Heard(id, toJson(info) as JsonObject, msg.truthy("q"))
}

/** TXT record of the mDNS service: every announcement field as a string. */
fun txtRecord(a: Announcement): Map<String, String> =
    mapOf("id" to a.id, "name" to a.name, "os" to a.os, "kind" to a.kind, "icon" to a.icon, "port" to a.port.toString())
