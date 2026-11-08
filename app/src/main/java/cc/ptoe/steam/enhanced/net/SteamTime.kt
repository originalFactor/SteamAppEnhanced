package cc.ptoe.steam.enhanced.net

import cc.ptoe.steam.enhanced.core.dlog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Aligns local time with Steam's server time (the `TimeAligner` equivalent).
 *
 * Ported from AVA's `services/steam_time.dart`. The TOTP codes and confirmation
 * hashes must use Steam server time: a device whose clock is even a minute off
 * will produce valid-looking codes Steam rejects, with no useful error.
 */
class SteamTime(client: OkHttpClient? = null) {
    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var offsetSeconds: Long = 0

    @Volatile
    var isAligned: Boolean = false
        private set

    /** Current Steam server time in unix seconds. */
    fun currentSteamTime(): Long =
        System.currentTimeMillis() / 1000 + offsetSeconds

    /**
     * Fetches the server time offset.
     *
     * Safe to call repeatedly; on failure it keeps the previous (or zero) offset
     * so codes still work approximately rather than the app refusing to show them.
     */
    suspend fun align(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$SteamApiClient.API_BASE/ITwoFactorService/QueryTime/v0001")
                .post(FormBody.Builder().add("steamid", "0").build())
                .build()
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                // Shape: {"response":{"server_time":"1700000000"}}
                val json = cc.ptoe.steam.enhanced.core.JsonCoerce.parseObject(body)
                val inner = json?.get("response") as? kotlinx.serialization.json.JsonObject
                val serverTime = inner
                    ?.let { cc.ptoe.steam.enhanced.core.JsonCoerce.asLongOrNull(it["server_time"]) }
                    ?: return@withContext false
                offsetSeconds = serverTime - System.currentTimeMillis() / 1000
                isAligned = true
                dlog("steam time aligned: offset=${offsetSeconds}s")
                return@withContext true
            }
        } catch (_: Exception) {
            // Keep the existing offset; never block code generation on a network
            // error.
            false
        }
    }

    /** The offset currently applied, in seconds (positive = device clock is slow). */
    fun offset(): Long = offsetSeconds

    companion object {
        /**
         * Process-wide aligner. The TOTP/confirmation clients read
         * [currentSteamTime] from here so one alignment covers the whole app.
         */
        val shared: SteamTime by lazy { SteamTime() }
    }
}
