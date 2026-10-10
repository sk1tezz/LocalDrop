package com.skz.localdrop.protocol.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Small helpers so message headers read like the desktop's python dicts. */
val json = Json { ignoreUnknownKeys = true; isLenient = true }

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
    is Iterable<*> -> JsonArray(v.map { toJson(it) })
    is Array<*> -> JsonArray(v.map { toJson(it) })
    is IntArray -> JsonArray(v.map { JsonPrimitive(it) })
    is LongArray -> JsonArray(v.map { JsonPrimitive(it) })
    else -> JsonPrimitive(v.toString())
}

fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { it.first to toJson(it.second) })

operator fun JsonObject.plus(other: Map<String, Any?>): JsonObject = JsonObject(this.toMap() + other.mapValues { toJson(it.value) })

private fun JsonElement?.prim(): JsonPrimitive? = this as? JsonPrimitive

fun JsonObject.str(k: String): String? = this[k].prim()?.takeIf { it.isString }?.content
fun JsonObject.long(k: String): Long? = this[k].prim()?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
fun JsonObject.int(k: String): Int? = long(k)?.toInt()
fun JsonObject.dbl(k: String): Double? = this[k].prim()?.doubleOrNull
fun JsonObject.bool(k: String): Boolean? = this[k].prim()?.let { it.booleanOrNull ?: it.longOrNull?.let { n -> n != 0L } }
fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
val JsonObject.type: String get() = str("type") ?: ""

/** Truthiness like python's `msg.get(k)`. */
fun JsonObject.truthy(k: String): Boolean = when (val e = this[k]) {
    null, JsonNull -> false
    is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else (e.booleanOrNull ?: (e.doubleOrNull?.let { it != 0.0 } ?: false))
    is JsonArray -> e.isNotEmpty()
    is JsonObject -> e.isNotEmpty()
}
