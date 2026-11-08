package cc.ptoe.steam.enhanced.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny in-app log so networked flows can be debugged on a real device without
 * adb. Holds the most recent lines in a ring buffer.
 *
 * Ported from AVA's `services/debug_log.dart`. Android's equivalent of Flutter's
 * `debugPrint` is `android.util.Log`, but this class stays Android-free so it
 * can be unit-tested and so the ring buffer has one definition.
 */
object DebugLog {
    private const val MAX_LINES = 400

    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    private val lines = ArrayDeque<String>()

    /** Optional sink for a platform logger (set from the app's Application). */
    @Volatile
    var printer: ((String) -> Unit)? = null

    /** Snapshot of the most recent lines, oldest first. */
    @Synchronized
    fun lines(): List<String> = lines.toList()

    @Synchronized
    fun log(message: String) {
        val stamp = timeFormatter.format(Date())
        val line = "$stamp  ${redactSecrets(message)}"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        printer?.invoke(line)
    }

    @Synchronized
    fun clear() = lines.clear()

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    /**
     * Defence-in-depth for the opt-in "attach debug log" feedback path: even
     * though current call sites avoid logging secrets, a future `dlog` that slips
     * in a token / cookie / secret must not be mailed to the developer inbox.
     *
     * Masks the values of known-sensitive keys and long token-shaped blobs while
     * leaving the surrounding message readable.
     */
    fun redactSecrets(input: String): String {
        var out = input

        // key=value / key: value / "key":"value" for sensitive key names.
        out = SENSITIVE_KEYED.replace(out) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}<redacted>"
        }

        // Padded standard-base64 blobs — Steam shared_secret / identity_secret
        // are 20 raw bytes → 28 chars ALWAYS ending with '='. Anchoring on the
        // padding lets '/' into the class (the bare pass below can't allow it):
        // URL paths never end in a literal '=', and snake_case identifiers are
        // excluded because '_' is not in the class.
        out = PADDED_BASE64.replace(out) { m -> "<redacted:${m.value.length}>" }

        // A whole JWT: three dot-separated base64url segments. Needed because the
        // bare pass below excludes '.', so it would only redact the long segments
        // and leave a short payload segment in the clear. A URL path has no
        // dot-separated triple of base64url runs, so this cannot mangle one.
        out = JWT.replace(out) { m -> "<redacted-jwt:${m.value.length}>" }

        // Bare long token-shaped blobs (>= 24 chars): base64url / hex / JWT-ish.
        // Excludes '/' so URL paths (e.g. Iface/Method) are not mistaken for keys.
        out = BARE_TOKEN.replace(out) { m -> "<redacted:${m.value.length}>" }

        return out
    }

    private val SENSITIVE_KEYED = Regex(
        """(access_token|refresh_token|steamLoginSecure|shared_secret|identity_secret|secret_1|password|passwd|sessionid|token)("?\s*[:=]\s*"?)([^\s"&,;}]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val PADDED_BASE64 = Regex("""[A-Za-z0-9+/]{20,}={1,2}""")

    private val JWT = Regex("""\b[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\b""")

    private val BARE_TOKEN = Regex("""\b[A-Za-z0-9+_-]{24,}={0,2}\b""")
}

/** Convenience top-level logger, matching AVA's `dlog(...)` call style. */
fun dlog(message: String) = DebugLog.log(message)
