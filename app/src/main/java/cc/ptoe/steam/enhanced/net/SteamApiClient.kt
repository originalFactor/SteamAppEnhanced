package cc.ptoe.steam.enhanced.net

import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.protocol.EResult
import cc.ptoe.steam.enhanced.core.proto.ProtoReader
import cc.ptoe.steam.enhanced.core.proto.ProtoWriter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.io.encoding.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin HTTP client for Steam's web APIs.
 *
 * Ported from AVA's `services/steam_api_client.dart` (Dio → OkHttp). Handles the
 * two transports the app needs:
 *  - `api.steampowered.com/<Iface>/<Method>/v<n>` protobuf calls
 *    (IAuthenticationService, ITwoFactorService, IEconService …).
 *  - `steamcommunity.com` / `store.steampowered.com` JSON calls (mobileconf,
 *    market, tradeoffer, ajaxregisterkey).
 *
 * Every call is a suspend function running on OkHttp's dispatcher via the
 * callback bridge, so no thread is blocked waiting on the network.
 */
class SteamApiClient(
    /** Community language (`l` query param) for localized strings. */
    @Volatile var steamLanguage: String = "english",
    client: OkHttpClient? = null,
) {
    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Redirects are followed manually: the cookie jar and the Steam-origin
        // gate both live at that layer (see [communityGetText]).
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    // ---------------------------------------------------------------- protobuf

    /**
     * Calls a protobuf service method and returns the decoded response reader.
     *
     * [request] is the serialized request message. GET is used for methods that
     * are declared `bConstMethod` — Steam answers POST to those with HTTP 405, and
     * that is not discoverable from the response body. Everything else is POST.
     *
     * An `x-eresult` header other than OK throws [SteamApiException]; a bare
     * non-2xx with no eresult (an expired token surfaces as a naked 401/403) also
     * throws, because defaulting it to OK would fake a success whose empty body
     * then parses as "accepted".
     */
    suspend fun callProtobuf(
        iface: String,
        method: String,
        request: ProtoWriter,
        accessToken: String? = null,
        useGet: Boolean = false,
        version: Int = 1,
    ): ProtoReader {
        val url = "$API_BASE/$iface/$method/v$version/"
        val requestBytes = request.toBytes()
        val encoded = Base64.encode(requestBytes)

        dlog("→ ${if (useGet) "GET" else "POST"} $iface/$method (${requestBytes.size}B" +
            (if (accessToken != null) ", +token" else "") + ")")

        val baseUrl = url.toHttpUrlOrNull()
            ?: throw SteamApiException(EResult.FAIL, "bad url $url", method)
        val urlBuilder = baseUrl.newBuilder()
        if (accessToken != null) urlBuilder.addQueryParameter("access_token", accessToken)
        if (useGet) urlBuilder.addQueryParameter("input_protobuf_encoded", encoded)

        val httpRequest = Request.Builder().url(urlBuilder.build()).apply {
            if (useGet) {
                get()
            } else {
                post(FormBody.Builder().add("input_protobuf_encoded", encoded).build())
            }
        }.build()

        val response = execute(httpRequest)
        response.use { resp ->
            val eresult = resp.header("x-eresult")?.toIntOrNull()
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            dlog(
                "← $method  HTTP ${resp.code}  " +
                    "eresult=${eresult?.let { EResult.label(it) } ?: "none"}  ${bytes.size}B",
            )
            if (eresult != null && eresult != EResult.OK) {
                val message = resp.header("x-error_message") ?: EResult.label(eresult)
                dlog("  ✗ $method error: $message")
                throw SteamApiException(eresult, message, method)
            }
            // No x-eresult header at all: only a plain HTTP success may pass.
            if (eresult == null && (resp.code < 200 || resp.code >= 300)) {
                dlog("  ✗ $method HTTP ${resp.code} with no x-eresult")
                throw SteamApiException(EResult.FAIL, "HTTP ${resp.code}", method, resp.code)
            }
            return ProtoReader(bytes)
        }
    }

    /**
     * GET against `api.steampowered.com` returning decoded JSON (non-protobuf Web
     * API endpoints, e.g. IEconService).
     */
    suspend fun apiGetJson(
        iface: String,
        method: String,
        query: Map<String, String>,
        accessToken: String? = null,
        version: Int = 1,
    ): JsonObject {
        val url = "$API_BASE/$iface/$method/v$version/".toHttpUrlOrNull()
            ?: throw SteamApiException(EResult.FAIL, "bad url", method)
        val builder = url.newBuilder()
        if (accessToken != null) builder.addQueryParameter("access_token", accessToken)
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }

        dlog("→ GET $iface/$method (json)")
        val response = execute(Request.Builder().url(builder.build()).get().build())
        response.use { resp ->
            val body = resp.body?.string() ?: ""
            dlog("← $method  HTTP ${resp.code}  ${body.length}B")
            if (resp.code < 200 || resp.code >= 300) {
                throw SteamApiException(EResult.FAIL, "HTTP ${resp.code}", method, resp.code)
            }
            return cc.ptoe.steam.enhanced.core.JsonCoerce.parseObject(body) ?: JsonObject(emptyMap())
        }
    }

    // --------------------------------------------------------------- community

    /**
     * GET against `steamcommunity.com` (mobileconf, market, tradeoffer…),
     * returning decoded JSON.
     *
     * An empty body decodes to `{}`; a `success != true` object is logged but
     * still returned, because what a soft failure means differs per endpoint and
     * only the caller knows.
     */
    suspend fun communityGetJson(
        path: String,
        query: Map<String, String>,
        cookies: Map<String, String>? = null,
    ): JsonObject {
        val url = buildCommunityUrl(path, emptyMap(), query)
        dlog("→ GET $path  ${query["tag"] ?: ""}${query["op"]?.let { " op=$it" } ?: ""}")
        val request = Request.Builder().url(url)
            .header("X-Requested-With", MOBILE_APP_MARKER)
            .apply { if (cookies != null) header("Cookie", cookieHeader(cookies)) }
            .get()
            .build()
        val response = execute(request)
        response.use { return decodeCommunityJson(it, path) }
    }

    /**
     * POST form-encoded against `steamcommunity.com`, allowing **repeated keys**.
     *
     * Steam's `multiajaxop` expects `cid[]` and `ck[]` sent once per item. HTML form
     * encoding expresses that as repeated parameters, which a `Map` cannot, so the
     * body is built from an ordered pair list.
     */
    suspend fun communityPostForm(
        path: String,
        form: List<Pair<String, String>>,
        cookies: Map<String, String>? = null,
        referer: String? = null,
    ): JsonObject {
        val url = buildCommunityUrl(path, emptyMap(), emptyMap())
        val tag = form.firstOrNull { it.first == "tag" }?.second ?: ""
        val op = form.firstOrNull { it.first == "op" }?.second
        dlog("→ POST $path  $tag${op?.let { " op=$it" } ?: ""}")
        val body = FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder().url(url)
            .header("X-Requested-With", MOBILE_APP_MARKER)
            .apply {
                if (cookies != null) header("Cookie", cookieHeader(cookies))
                if (referer != null) header("Referer", referer)
            }
            .post(body)
            .build()
        val response = execute(request)
        response.use { return decodeCommunityJson(it, path) }
    }

    /**
     * POST form-encoded against `steamcommunity.com` — `/mobileconf/ajaxop`,
     * `/mobileconf/multiajaxop` and the market write endpoints expect POST.
     * [referer] is required by `/market/sellitem/`.
     */
    suspend fun communityPostJson(
        path: String,
        form: Map<String, String>,
        cookies: Map<String, String>? = null,
        referer: String? = null,
    ): JsonObject = communityPostForm(path, form.toList(), cookies, referer)

    /**
     * GETs a community URL and returns the raw response body (HTML/text). Used to
     * scrape page globals like `g_rgAppContextData` / `g_rgWalletInfo`.
     *
     * Follows redirects manually because we have no cookie jar: Steam's inventory
     * page bounces through `/market/eligibilitycheck`, which Set-Cookies a
     * "passed" flag and redirects back — without carrying that cookie the two
     * pages ping-pong forever.
     */
    suspend fun communityGetText(
        path: String,
        query: Map<String, String>? = null,
        cookies: Map<String, String>? = null,
        maxHops: Int = 6,
    ): String {
        val jar = LinkedHashMap<String, String>()
        cookies?.let { jar.putAll(it) }

        var url = if (path.startsWith("http")) {
            // An absolute `path` bypasses communityBase and could otherwise ship
            // cookies to a rogue host on the very first request.
            if (!isSteamOrigin(path)) {
                throw IllegalArgumentException("not a Steam origin: $path")
            }
            path
        } else {
            buildCommunityUrl(path, emptyMap(), query ?: emptyMap())
        }

        dlog("→ GET(text) $path")
        var resp = execute(textRequest(url, jar))
        var code = resp.code
        var body: String
        var hops = 0
        while (true) {
            body = resp.use { it.body?.string() ?: "" }
            absorbCookies(resp.headers, jar)
            if (code !in 300..399) break
            val location = resp.header("location")
            if (location.isNullOrEmpty() || hops++ >= maxHops) break
            url = resolveUrl(url, location)
            if (isLoginRedirect(url)) {
                dlog("  ✗ $path redirected to login (stale session?)")
                throw CommunityAuthException(code, path)
            }
            if (!isSteamOrigin(url)) {
                dlog("  ✕ refused off-origin redirect $url")
                break
            }
            dlog("  ↪ redirect $url")
            resp = execute(textRequest(url, jar))
            code = resp.code
        }
        // A bare 401/403 (initial or post-redirect) is a stale session too — the
        // caller would otherwise scrape an error page for its globals.
        if (code == 401 || code == 403) {
            dlog("  ✗ $path HTTP $code (stale session?)")
            throw CommunityAuthException(code, path)
        }
        dlog("← $path  HTTP $code  ${body.length}B")
        return body
    }

    /**
     * Warms up a `store.steampowered.com` session and returns the cookie jar to
     * use for the follow-up request.
     *
     * The store answers a request carrying unfamiliar cookies by Set-Cookieing
     * `steamCountry` + `browserid` and redirecting — **even when
     * `steamLoginSecure` is perfectly valid**. A browser absorbs those and
     * retries transparently; we have no cookie jar, so a first POST straight at an
     * ajax endpoint comes back as a bare 302 to itself and never runs. Doing this
     * GET first is what makes the POST reach the endpoint.
     *
     * Any `sessionid` Steam hands back replaces the caller's generated one: the
     * store is stricter than the community host, which only cross-checks that the
     * cookie and the form field agree.
     */
    suspend fun storeBootstrap(
        path: String,
        cookies: Map<String, String>,
        maxHops: Int = 4,
    ): MutableMap<String, String> {
        val jar = LinkedHashMap<String, String>()
        jar.putAll(cookies)

        var url = STORE_BASE + path
        dlog("→ GET(bootstrap) store$path")
        var resp = execute(textRequest(url, jar, includeSteamMarker = false))
        var code = resp.code
        var hops = 0
        while (true) {
            resp.use { it.body?.string() }
            absorbCookies(resp.headers, jar)
            if (code !in 300..399) break
            val location = resp.header("location")
            if (location.isNullOrEmpty() || hops++ >= maxHops) break
            url = resolveUrl(url, location)
            if (isLoginRedirect(url)) {
                dlog("  ✗ store$path bounced to login (stale session?)")
                throw CommunityAuthException(code, "store$path")
            }
            if (!isSteamOrigin(url)) {
                dlog("  ✕ refused off-origin redirect $url")
                break
            }
            dlog("  ↪ bootstrap redirect")
            resp = execute(textRequest(url, jar, includeSteamMarker = false))
            code = resp.code
        }
        // Cookie *names* only — steamLoginSecure and sessionid are credentials.
        dlog("  store session: ${jar.keys.joinToString(", ")}")
        return jar
    }

    /**
     * POST form-encoded against `store.steampowered.com` — the store hosts its own
     * ajax endpoints (`/account/ajaxregisterkey/`) that the community host does
     * not serve.
     *
     * `X-Requested-With` is the plain `XMLHttpRequest` a browser sends, NOT the
     * community app marker: these are the store's own page-backing ajax
     * endpoints, and the app marker is what makes Steam serve the stripped
     * mobile-app layout instead of the JSON we asked for.
     *
     * Note the store reports *endpoint-level* success as `success: 1` (an int),
     * not `true`, so callers must read the numeric result themselves.
     */
    suspend fun storePostJson(
        path: String,
        form: Map<String, String>,
        cookies: Map<String, String>? = null,
        referer: String? = null,
    ): JsonObject {
        dlog("→ POST store$path")
        val body = FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder().url(STORE_BASE + path)
            .header("X-Requested-With", "XMLHttpRequest")
            .apply {
                if (cookies != null) header("Cookie", cookieHeader(cookies))
                if (referer != null) header("Referer", referer)
            }
            .post(body)
            .build()
        val response = execute(request)
        response.use { return decodeCommunityJson(it, "store$path") }
    }

    // ---------------------------------------------------------------- internals

    private fun textRequest(
        url: String,
        jar: Map<String, String>,
        includeSteamMarker: Boolean = true,
    ): Request = Request.Builder().url(url)
        .apply {
            if (includeSteamMarker) header("X-Requested-With", MOBILE_APP_MARKER)
            if (jar.isNotEmpty()) header("Cookie", cookieHeader(jar))
        }
        .get()
        .build()

    /**
     * Shared response handling for the JSON helpers: runs the status gate, then
     * decodes the 2xx body.
     */
    private fun decodeCommunityJson(resp: Response, path: String): JsonObject {
        val body = resp.body?.string() ?: ""
        dlog("← $path  HTTP ${resp.code}  ${body.length}B")
        gateCommunityStatus(resp, path, body)?.let { return it }
        if (body.isEmpty()) return JsonObject(emptyMap())
        val decoded = cc.ptoe.steam.enhanced.core.JsonCoerce.parseObject(body)
            // Some write endpoints (e.g. removelisting) reply 200 with a bare
            // `[]` — treat a non-object body as a plain success.
            ?: return JsonObject(emptyMap())
        if (!cc.ptoe.steam.enhanced.core.JsonCoerce.asBool(decoded["success"])) {
            dlog(
                "  ⚠ $path success=${decoded["success"]} " +
                    (decoded["message"] ?: decoded["needauth"] ?: ""),
            )
        }
        return decoded
    }

    /**
     * Status gate for the community JSON helpers.
     *
     * Returns null for a 2xx (the caller parses the body as usual); otherwise
     * resolves the response here:
     *  - 401/403, or a redirect to the login page → stale session cookie,
     *    [CommunityAuthException] so callers route to re-auth.
     *  - any other redirect (e.g. the market eligibility bounce) is not an auth
     *    failure → [SteamApiException], never a success-shaped `{}`.
     *  - other non-2xx: Steam reports many command failures as a 4xx carrying a
     *    JSON diagnostic body (sellitem 400 + `{"message":...}`) — a body that
     *    decodes to an object is returned so the message reaches the caller;
     *    anything else → [SteamApiException].
     */
    private fun gateCommunityStatus(resp: Response, path: String, body: String): JsonObject? {
        val status = resp.code
        if (status in 200..299) return null
        if (status == 401 || status == 403) {
            dlog("  ✗ $path HTTP $status (stale session?)")
            throw CommunityAuthException(status, path)
        }
        if (status in 300..399) {
            val location = resp.header("location") ?: ""
            dlog("  ✗ $path HTTP $status → $location")
            if (isLoginRedirect(location)) throw CommunityAuthException(status, path)
            // Carry the target into the message: a bare "HTTP 302" tells the user
            // (and the debug log) nothing, and the store's *self*-redirect — its
            // way of saying "take these cookies and come back" — is
            // indistinguishable from any other bounce without it.
            throw SteamApiException(EResult.FAIL, "HTTP $status → $location", path, status)
        }
        if (body.isNotEmpty()) {
            val decoded = cc.ptoe.steam.enhanced.core.JsonCoerce.parseObject(body)
            if (decoded != null) {
                dlog("  ⚠ $path HTTP $status with diagnostic body")
                return decoded
            }
        }
        dlog("  ✗ $path HTTP $status")
        throw SteamApiException(EResult.FAIL, "HTTP $status", path, status)
    }

    private fun absorbCookies(headers: okhttp3.Headers, jar: MutableMap<String, String>) {
        for (setCookie in headers.values("set-cookie")) {
            val kv = setCookie.split(';').first().trim()
            val i = kv.indexOf('=')
            if (i > 0) jar[kv.substring(0, i)] = kv.substring(i + 1)
        }
    }

    private fun buildCommunityUrl(
        path: String,
        pathParams: Map<String, String>,
        query: Map<String, String>,
    ): String {
        var url = if (path.startsWith("http")) path else COMMUNITY_BASE + path
        if (query.isNotEmpty()) {
            val httpUrl = url.toHttpUrlOrNull() ?: return url
            val builder = httpUrl.newBuilder()
            query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            url = builder.build().toString()
        }
        return url
    }

    private fun cookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private fun resolveUrl(base: String, location: String): String {
        val baseUrl = base.toHttpUrlOrNull() ?: return location
        return baseUrl.resolve(location)?.toString() ?: location
    }

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    dlog("  ✗ ${request.url.encodedPath} network: ${e.message ?: e::class.simpleName}")
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }

    companion object {
        const val API_BASE = "https://api.steampowered.com"
        const val COMMUNITY_BASE = "https://steamcommunity.com"
        const val STORE_BASE = "https://store.steampowered.com"

        /** `X-Requested-With` value the official Steam mobile app sends. */
        const val MOBILE_APP_MARKER = "com.valvesoftware.android.steam.community"

        /**
         * Headers that make requests look like the official Steam mobile app (it
         * uses an OkHttp client + these API headers). Combined with the MobileApp
         * platform type and device details, this keeps legitimate logins from
         * tripping Steam's "unknown device" anti-fraud heuristics.
         */
        val MOBILE_HEADERS: Map<String, String> = mapOf(
            "User-Agent" to "okhttp/4.9.2",
            "Accept" to "application/json, text/plain, */*",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
        )

        /**
         * Whether [url] is a Steam-owned HTTPS origin we may keep sending session
         * cookies (`steamLoginSecure` etc.) to while following a redirect chain.
         *
         * A redirect to anything else — a non-HTTPS scheme or a non-Steam host —
         * must not carry the auth cookies (session hijack via a rogue redirect).
         */
        fun isSteamOrigin(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            if (parsed.scheme != "https") return false
            val host = parsed.host.lowercase()
            return host == "steamcommunity.com" ||
                host == "steampowered.com" ||
                host == "store.steampowered.com" ||
                host == "help.steampowered.com" ||
                host == "login.steampowered.com" ||
                host == "api.steampowered.com" ||
                host.endsWith(".steamcommunity.com") ||
                host.endsWith(".steampowered.com")
        }

        /**
         * Whether a redirect Location targets Steam's login page.
         *
         * Matched on the URL path only — a `/login` inside a query string (goto=)
         * must not count.
         */
        fun isLoginRedirect(location: String): Boolean {
            if (location.isEmpty()) return false
            val uri = java.net.URI(location)
            // steammobile://lostauth is Steam's other stale-mobile-session bounce;
            // its path is empty so the '/login' check alone would miss it.
            if (uri.scheme == "steammobile") return true
            val path = uri.path ?: return false
            return path.contains("/login")
        }

        /**
         * True when [e] means the account's web session is dead — a missing/expired
         * access token or a stale community cookie — so the caller should route to
         * re-auth (and retry) instead of surfacing the raw error.
         */
        fun isSessionDeadError(e: Throwable): Boolean =
            e is MissingAccessTokenException ||
                e is CommunityAuthException ||
                (e is SteamApiException && (e.httpStatus == 401 || e.httpStatus == 403))
    }
}
