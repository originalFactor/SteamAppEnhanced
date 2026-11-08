package cc.ptoe.steam.enhanced.sync

import cc.ptoe.steam.enhanced.app.AccountVault
import cc.ptoe.steam.enhanced.core.JsonCoerce
import cc.ptoe.steam.enhanced.core.crypto.VaultCrypto
import cc.ptoe.steam.enhanced.core.model.SessionData
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.store.AccountStore
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal data class SyncConfig(
    val url: String,
    val user: String,
    val password: String,
    val passphrase: String,
    val synced: Boolean = false,
    val baseline: Map<Long, String> = emptyMap(),
) {
    fun toJson(): String = buildJsonObject {
        put("url", url)
        put("user", user)
        put("password", password)
        put("passphrase", passphrase)
        put("synced", synced)
        put("baseline", JsonObject(baseline.mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value) }))
    }.toString()

    companion object {
        fun parse(text: String): SyncConfig {
            val json = checkNotNull(JsonCoerce.parseObject(text))
            return SyncConfig(
                JsonCoerce.asString(json["url"]), JsonCoerce.asString(json["user"]),
                JsonCoerce.asString(json["password"]), JsonCoerce.asString(json["passphrase"]),
                JsonCoerce.asBool(json["synced"]),
                JsonCoerce.asObject(json["baseline"]).mapKeys { it.key.toLong() }
                    .mapValues { JsonCoerce.asString(it.value) },
            )
        }
    }
}

internal data class SyncChoice(val localHash: String, val remoteHash: String, val keepLocal: Boolean)
internal data class SyncConflict(
    val id: Long,
    val local: SteamGuardAccount?,
    val remote: SteamGuardAccount?,
    val localHash: String,
    val remoteHash: String,
)
internal data class SyncResult(val conflicts: List<SyncConflict>, val pulled: Int = 0, val pushed: Int = 0)
internal data class SyncRecovery(val id: Long, val savedAt: Long, val account: SteamGuardAccount)

internal class VaultSync(private val vault: AccountVault) {
    suspend fun config(): SyncConfig? = withContext(Dispatchers.IO) {
        vault.protectedSettings.readProtected(CONFIG)?.let(SyncConfig::parse)
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) { vault.protectedSettings.removeProtected(CONFIG) }

    suspend fun recoverable(): List<SyncRecovery> = withContext(Dispatchers.IO) {
        check(vault.state.value.unlocked)
        readHistory()
    }

    suspend fun restore(entry: SyncRecovery) {
        val current = vault.state.value.accounts.firstOrNull { AccountStore.storageId(it) == entry.id }
        vault.save(entry.account.copy(session = current?.session ?: entry.account.session, password = current?.password))
    }

    suspend fun sync(config: SyncConfig, choices: Map<Long, SyncChoice> = emptyMap()): SyncResult =
        withContext(Dispatchers.IO) {
            require(config.passphrase.length >= 8) { "Use a sync passphrase of at least eight characters." }
            val dav = WebDav(config)
            val state = vault.state.value
            check(state.unlocked && state.unreadable == 0) { "Unlock a readable vault before syncing." }
            val original = state.accounts.associateBy(AccountStore::storageId)
            val local = original.mapValues { (_, account) -> payload(account) }
            val snapshot = dav.read()
            check(snapshot != null || !config.synced) { "The previous sync file is missing. Check the WebDAV folder." }
            val salt = snapshot?.salt ?: VaultCrypto.randomSaltB64()
            val key = VaultCrypto.deriveSyncKey(config.passphrase, salt)
            try {
                val remote = if (snapshot == null) emptyMap() else readPayload(key, snapshot.body)
                val plan = merge(local, remote, config.baseline, choices)
                if (plan.conflicts.isNotEmpty()) return@withContext SyncResult(plan.conflicts)

                val merged = plan.entries
                val now = System.currentTimeMillis()
                val replaced = buildList {
                    for ((id, value) in merged) {
                        for (losing in listOfNotNull(local[id], remote[id]).distinct()) {
                            if (losing != JsonNull && losing != value) add(SyncRecovery(id, now, account(losing, id)))
                        }
                    }
                }
                if (replaced.isNotEmpty()) saveHistory(replaced)
                if (snapshot == null || merged != remote) {
                    val body = buildJsonObject {
                        put("version", 1)
                        put("salt", salt)
                        put("ciphertext", VaultCrypto.encryptPayload(key, JsonObject(merged.mapKeys { it.key.toString() }).toString()))
                    }.toString()
                    if (!config.synced) dav.verifyConditionalWrites()
                    dav.write(body, snapshot?.etag)
                }

                var pulled = 0
                for ((id, value) in merged) {
                    if ((value == JsonNull && id !in local) || local[id] == value) continue
                    val existing = original[id]
                    if (value == JsonNull) {
                        if (existing != null) vault.remove(existing)
                    } else {
                        val incoming = account(value, id)
                        // Live tokens and opt-in passwords stay local. A changed
                        // authenticator secret still belongs to the same SteamID.
                        vault.save(incoming.copy(
                            session = existing?.session ?: incoming.session,
                            password = existing?.password,
                            extra = existing?.extra ?: incoming.extra,
                            avatarUrl = existing?.avatarUrl,
                            animatedAvatarUrl = existing?.animatedAvatarUrl,
                            avatarFrameUrl = existing?.avatarFrameUrl,
                        ))
                    }
                    pulled++
                }
                val pushed = merged.count { (id, value) -> remote[id] != value }
                val baseline = merged.mapValues { (_, value) -> hash(value) }
                vault.protectedSettings.writeProtected(CONFIG, config.copy(synced = true, baseline = baseline).toJson())
                SyncResult(emptyList(), pulled, pushed)
            } finally {
                key.fill(0)
            }
        }

    private fun readHistory(): List<SyncRecovery> {
        val history = vault.protectedSettings.readProtected(HISTORY)
            ?.let(JsonCoerce::parseObject)?.get("entries")
        val cutoff = System.currentTimeMillis() - THIRTY_DAYS
        return JsonCoerce.asObjectArray(history).mapNotNull { row ->
            val id = JsonCoerce.asLongOrNull(row["id"]) ?: return@mapNotNull null
            val savedAt = JsonCoerce.asLongOrNull(row["at"]) ?: return@mapNotNull null
            if (savedAt < cutoff) return@mapNotNull null
            SyncRecovery(id, savedAt, account(row.getValue("account"), id))
        }
    }

    private fun saveHistory(replaced: List<SyncRecovery>) {
        val history = (replaced + readHistory()).distinctBy { it.id to hash(payload(it.account)) }.take(24)
        val record = buildJsonObject {
            put("entries", JsonArray(history.map {
                buildJsonObject {
                    put("id", it.id)
                    put("at", it.savedAt)
                    put("account", payload(it.account))
                }
            }))
        }
        vault.protectedSettings.writeProtected(HISTORY, record.toString())
    }

    companion object {
        private const val CONFIG = "webdav_sync"
        private const val HISTORY = "webdav_recovery"
        private const val THIRTY_DAYS = 30L * 24 * 60 * 60 * 1000

        private fun payload(account: SteamGuardAccount): JsonElement = canonical(
            account.copy(
                session = SessionData(steamId = account.steamId),
                password = null,
                avatarUrl = null,
                animatedAvatarUrl = null,
                avatarFrameUrl = null,
                extra = JsonObject(emptyMap()),
            ).toExportJson(),
        )

        private fun account(value: JsonElement, id: Long): SteamGuardAccount {
            val json = value as? JsonObject ?: error("Invalid account in the sync file.")
            val parsed = SteamGuardAccount.fromJson(json)
            check(AccountStore.storageId(parsed) == id) { "A synced account has an invalid ID." }
            if (parsed.sharedSecret != null) check(runCatching { parsed.generateCode(0) }.isSuccess) {
                "A synced account has an invalid authenticator secret."
            }
            return parsed.copy(session = SessionData(steamId = parsed.steamId), password = null)
        }

        private fun readPayload(key: ByteArray, ciphertext: String): Map<Long, JsonElement> {
            val plaintext = VaultCrypto.decryptPayload(key, ciphertext) ?: error("Incorrect sync passphrase or damaged snapshot.")
            val payload = JsonCoerce.parseObject(plaintext) ?: error("The sync file is invalid.")
            check(payload.size <= 4_096) { "The sync file has too many entries." }
            return payload.mapKeys { it.key.toLongOrNull() ?: error("Invalid sync account ID.") }
                .mapValues { (id, value) ->
                    if (value == JsonNull) JsonNull
                    else payload(account(value, id))
                }
        }

        internal data class Plan(val entries: Map<Long, JsonElement>, val conflicts: List<SyncConflict>)

        internal fun merge(
            local: Map<Long, JsonElement>,
            remote: Map<Long, JsonElement>,
            baseline: Map<Long, String>,
            choices: Map<Long, SyncChoice>,
        ): Plan {
            val entries = linkedMapOf<Long, JsonElement>()
            val conflicts = mutableListOf<SyncConflict>()
            for (id in (local.keys + remote.keys + baseline.keys).sorted()) {
                val known = baseline[id]
                val here = local[id] ?: if (known != null) JsonNull else null
                val there = remote[id]
                val localHash = here?.let(::hash)
                val remoteHash = there?.let(::hash)
                val choice = choices[id]?.takeIf { it.localHash == localHash && it.remoteHash == remoteHash }
                val value = when {
                    here == there -> here
                    here == null -> there
                    there == null -> here
                    localHash == known -> there
                    remoteHash == known -> here
                    choice != null -> if (choice.keepLocal) here else there
                    else -> {
                        conflicts += SyncConflict(
                            id,
                            here.takeUnless { it == JsonNull }?.let { account(it, id) },
                            there.takeUnless { it == JsonNull }?.let { account(it, id) },
                            checkNotNull(localHash),
                            checkNotNull(remoteHash),
                        )
                        null
                    }
                }
                if (value != null) entries[id] = value
            }
            return Plan(entries, conflicts)
        }

        internal fun hash(json: JsonElement): String = MessageDigest.getInstance("SHA-256")
            .digest(canonical(json).toString().encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

        private fun canonical(json: JsonElement): JsonElement = when (json) {
            is JsonObject -> JsonObject(json.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(json.map(::canonical))
            else -> json
        }
    }
}

private class WebDav(config: SyncConfig) {
    private val base = config.url.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    } ?: error("Enter an HTTPS WebDAV folder URL without embedded credentials.")
    private val auth = Credentials.basic(config.user, config.password, Charsets.UTF_8)
    private val target = url("steam-enhanced.sync.v1.json")

    data class Snapshot(val salt: String, val body: String, val etag: String)

    fun read(): Snapshot? = client.newCall(request(target).get().build()).execute().use { response ->
        if (response.code == 404) return null
        check(response.isSuccessful) { "WebDAV read failed (HTTP ${response.code})." }
        val etag = response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
            ?: error("The WebDAV server must return strong ETags.")
        val source = response.body.source()
        check(!source.request(MAX_BYTES + 1)) { "The WebDAV sync file is too large." }
        val json = JsonCoerce.parseObject(source.readUtf8()) ?: error("The WebDAV sync file is invalid.")
        check(JsonCoerce.asInt(json["version"]) == 1) { "Unsupported WebDAV sync version." }
        val salt = JsonCoerce.asString(json["salt"])
        val body = JsonCoerce.asString(json["ciphertext"])
        Snapshot(salt, body, etag)
    }

    fun write(body: String, etag: String?) {
        require(body.encodeToByteArray().size.toLong() <= MAX_BYTES) { "The WebDAV sync file is too large." }
        val request = request(target).put(body.toRequestBody(JSON))
        if (etag == null) request.header("If-None-Match", "*") else request.header("If-Match", etag)
        client.newCall(request.build()).execute().use {
            check(it.code != 412 && it.code != 409) { "The WebDAV copy changed. Run sync again." }
            check(it.isSuccessful) { "WebDAV write failed (HTTP ${it.code})." }
        }
    }

    fun verifyConditionalWrites() {
        val probe = url(".steam-enhanced-probe-${java.util.UUID.randomUUID()}")
        val body = "{}".toRequestBody(JSON)
        val create = request(probe).header("If-None-Match", "*").put(body).build()
        client.newCall(create).execute().use {
            check(it.isSuccessful) { "WebDAV needs a writable existing folder (HTTP ${it.code})." }
        }
        try {
            client.newCall(create).execute().use {
                check(it.code == 412) { "The WebDAV server does not enforce conditional creation." }
            }
            val invalidUpdate = request(probe).header("If-Match", "\"invalid-tag\"").put(body).build()
            client.newCall(invalidUpdate).execute().use {
                check(it.code == 412) { "The WebDAV server does not enforce conditional updates." }
            }
        } finally {
            runCatching { client.newCall(request(probe).delete().build()).execute().close() }
        }
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", auth)
    private fun url(file: String): HttpUrl =
        base.newBuilder().apply { if (base.pathSegments.last() == "") removePathSegment(base.pathSize - 1) }
            .addPathSegment(file).build()

    companion object {
        private const val MAX_BYTES = 16L * 1024 * 1024
        private val JSON = "application/json".toMediaType()
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(45, TimeUnit.SECONDS).build()
    }
}
