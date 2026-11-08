package cc.ptoe.steam.enhanced.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Lenient accessors for Steam's JSON, which serializes the same field as an int,
 * a double, a decimal string, or a boolean depending on endpoint and value size.
 *
 * Ported from AVA's `core/json_coerce.dart`, extended to also tolerate strings
 * that hold numbers with separators ("1,234") and true/false spellings, because
 * the community/market endpoints do all of those.
 */
object JsonCoerce {
    /** Shared parser: never throws on unknown keys, keeps numbers as written. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Parses [text] as a JSON object, or null when it is not one. */
    fun parseObject(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return try {
            val element = json.parseToJsonElement(text)
            element as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    /** [element] as a number, or null when it cannot be read as one. */
    fun asLongOrNull(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        primitive.longOrNull?.let { return it }
        primitive.doubleOrNull?.let { return it.toLong() }
        if (primitive.isString) {
            val trimmed = primitive.content.trim().replace(",", "")
            return trimmed.toLongOrNull() ?: trimmed.toDoubleOrNull()?.toLong()
        }
        return null
    }

    /** [element] as an Int, or [fallback] when it cannot be read as one. */
    fun asInt(element: JsonElement?, fallback: Int = 0): Int =
        asLongOrNull(element)?.toInt() ?: fallback

    /** [element] as a Double, or null when it cannot be read as one. */
    fun asDoubleOrNull(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        primitive.doubleOrNull?.let { return it }
        if (primitive.isString) return primitive.content.trim().replace(",", "").toDoubleOrNull()
        return null
    }

    /** [element] as a String, or null when absent/null. Numbers keep their text. */
    fun asStringOrNull(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content
    }

    /** [element] as a String, or "" when absent/null. */
    fun asString(element: JsonElement?): String = asStringOrNull(element) ?: ""

    /**
     * [element] as a strict boolean.
     *
     * Steam is inconsistent here: JSON `true`, the number `1`, and the string
     * `"true"` all mean true on some endpoints. Anything else is false — which
     * matches AVA, where a missing flag reads as "not set" rather than throwing.
     */
    fun asBool(element: JsonElement?): Boolean {
        val primitive = element as? JsonPrimitive ?: return false
        if (primitive is JsonNull) return false
        primitive.booleanOrNull?.let { return it }
        val text = primitive.content.trim()
        if (text.equals("true", ignoreCase = true)) return true
        return asLongOrNull(primitive)?.let { it != 0L } ?: false
    }

    /** [element] as a JSON array of elements, or an empty list. */
    fun asArray(element: JsonElement?): List<JsonElement> =
        (element as? JsonArray) ?: emptyList()

    /** [element] as a JSON object, or an empty object. */
    fun asObject(element: JsonElement?): JsonObject =
        (element as? JsonObject) ?: JsonObject(emptyMap())

    /** [element] as a list of objects, skipping entries that are not objects. */
    fun asObjectArray(element: JsonElement?): List<JsonObject> =
        asArray(element).mapNotNull { it as? JsonObject }

    /** Convenience: the field named [key] of [obj]. */
    operator fun JsonObject.get(key: String): JsonElement? =
        (this as Map<String, JsonElement>)[key]

    /** Convenience: an Int field of [obj]. */
    fun JsonObject.int(key: String, fallback: Int = 0): Int = asInt(this[key], fallback)

    /** Convenience: a Long field of [obj]. */
    fun JsonObject.long(key: String): Long = asLongOrNull(this[key]) ?: 0L

    /** Convenience: a String field of [obj]. */
    fun JsonObject.str(key: String): String = asString(this[key])

    /** Convenience: a Boolean field of [obj]. */
    fun JsonObject.bool(key: String): Boolean = asBool(this[key])

    /** Convenience: an object field of [obj]. */
    fun JsonObject.obj(key: String): JsonObject = asObject(this[key])

    /** Convenience: an array field of [obj]. */
    fun JsonObject.arr(key: String): List<JsonElement> = asArray(this[key])
}
