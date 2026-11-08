package cc.ptoe.steam.enhanced.core.model

import cc.ptoe.steam.enhanced.core.JsonCoerce
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One account's row in `manifest.json`.
 *
 * The salt and IV live here, **not** in the maFile — an encrypted `.maFile` on
 * its own cannot be decrypted by anyone, because the parameters needed to derive
 * the key are in this entry.
 */
data class ManifestEntry(
    val iv: String? = null, // encryption_iv
    val salt: String? = null, // encryption_salt
    val filename: String = "", // filename
    val steamId: Long = 0L, // steamid
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("encryption_iv", iv)
        put("encryption_salt", salt)
        put("filename", filename)
        put("steamid", steamId)
    }

    companion object {
        fun fromJson(json: JsonObject): ManifestEntry = ManifestEntry(
            iv = JsonCoerce.asStringOrNull(json["encryption_iv"]),
            salt = JsonCoerce.asStringOrNull(json["encryption_salt"]),
            filename = JsonCoerce.asString(json["filename"]),
            steamId = JsonCoerce.asLongOrNull(json["steamid"]) ?: 0L,
        )
    }
}

/**
 * In-memory model of `maFiles/manifest.json`.
 *
 * Ported from AVA's `core/models/manifest.dart`. Field names match the legacy C#
 * `Manifest` JsonProperty names exactly so the file stays interoperable with the
 * .NET version — which is also why SDA's own fields (`periodic_checking`,
 * `auto_confirm_trades`, …) are round-tripped even though this app ignores them.
 */
data class Manifest(
    val encrypted: Boolean = false,
    val firstRun: Boolean = true,
    val entries: List<ManifestEntry> = emptyList(),
    val periodicChecking: Boolean = false,
    val periodicCheckingInterval: Int = 5,
    val checkAllAccounts: Boolean = false,
    val autoConfirmMarketTransactions: Boolean = false,
    val autoConfirmTrades: Boolean = false,
    /**
     * `salt|iv|ciphertext` of a known plaintext, used to verify the passkey even
     * when there are no accounts (lets a PIN be set on an empty store).
     */
    val passkeyCheck: String? = null,
    /**
     * PBKDF2 rounds for this store's legacy scheme. Imported maFiles use the
     * 50000-round default. Unused once [vault] is true.
     */
    val kdfIterations: Int = 50_000,
    /**
     * True once the store uses the vault scheme (random DEK + AES-256-GCM, DEK
     * held in Android Keystore-backed storage). When true the PIN no longer
     * derives the file key and [kdfIterations]/[passkeyCheck] are unused.
     */
    val vault: Boolean = false,
    /** Internal manifest schema version. 1 = legacy PIN/CBC, 2 = vault/GCM. */
    val schemaVersion: Int = 1,
) {
    /** The plaintext used for the [passkeyCheck] verification token. */
    val checkPlaintext: String
        get() = CHECK_PLAINTEXT

    fun toJson(): JsonObject = buildJsonObject {
        put("encrypted", encrypted)
        put("first_run", firstRun)
        put("entries", buildJsonArray { entries.forEach { add(it.toJson()) } })
        put("periodic_checking", periodicChecking)
        put("periodic_checking_interval", periodicCheckingInterval)
        put("periodic_checking_checkall", checkAllAccounts)
        put("auto_confirm_market_transactions", autoConfirmMarketTransactions)
        put("auto_confirm_trades", autoConfirmTrades)
        if (passkeyCheck != null) put("passkey_check", passkeyCheck)
        put("kdf_iterations", kdfIterations)
        put("vault", vault)
        put("schema_version", schemaVersion)
    }

    companion object {
        /** Schema version written by current builds (vault/GCM). */
        const val SCHEMA_VAULT = 2

        /**
         * The plaintext sealed into [passkeyCheck].
         *
         * Must stay byte-identical to AVA's `_checkPlaintext`: the token is
         * already written into existing manifests, and a different string would
         * make every existing encrypted store reject its correct passkey.
         */
        const val CHECK_PLAINTEXT = "AVA-PASSKEY-CHECK"

        fun fromJson(json: JsonObject): Manifest = Manifest(
            encrypted = JsonCoerce.asBool(json["encrypted"]),
            firstRun = if (json.containsKey("first_run")) {
                JsonCoerce.asBool(json["first_run"])
            } else {
                true
            },
            entries = JsonCoerce.asObjectArray(json["entries"]).map { ManifestEntry.fromJson(it) },
            periodicChecking = JsonCoerce.asBool(json["periodic_checking"]),
            periodicCheckingInterval = JsonCoerce.asInt(json["periodic_checking_interval"], 5),
            checkAllAccounts = JsonCoerce.asBool(json["periodic_checking_checkall"]),
            autoConfirmMarketTransactions =
                JsonCoerce.asBool(json["auto_confirm_market_transactions"]),
            autoConfirmTrades = JsonCoerce.asBool(json["auto_confirm_trades"]),
            passkeyCheck = JsonCoerce.asStringOrNull(json["passkey_check"]),
            kdfIterations = JsonCoerce.asInt(json["kdf_iterations"], 50_000),
            vault = JsonCoerce.asBool(json["vault"]),
            schemaVersion = JsonCoerce.asInt(json["schema_version"], 1),
        )
    }
}
