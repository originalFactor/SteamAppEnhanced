package cc.ptoe.steam.enhanced.core

import cc.ptoe.steam.enhanced.core.crypto.MaFileCrypto
import kotlinx.serialization.json.JsonObject

/**
 * Reading a Steam Desktop Authenticator `maFiles/` folder.
 *
 * Ported from AVA's `core/sda_import.dart`. SDA keeps a `manifest.json` next to
 * the account files. When the user turned on SDA's encryption — which SDA's own
 * README calls "highly recommended" — each `*.maFile` on disk is **base64
 * ciphertext, not JSON**, and the parameters needed to decrypt it are *not in the
 * file*: the salt and IV live in that account's manifest entry.
 *
 * That is the whole reason this module exists. A lone encrypted `.maFile` cannot
 * be imported by anyone, ever — without the manifest there is nothing to derive
 * the key with. Encrypted imports need both; plaintext maFiles can be imported
 * without a manifest.
 *
 * Everything here is pure: no file system, no Android. The UI layer reads the
 * bytes and shows the errors.
 */

/** One account's row in SDA's `manifest.json`. */
data class SdaManifestEntry(
    val filename: String,
    val steamId: Long,
    /** `encryption_salt` — base64, 8 bytes. Null on an unencrypted manifest. */
    val salt: String? = null,
    /** `encryption_iv` — base64, 16 bytes. Null on an unencrypted manifest. */
    val iv: String? = null,
) {
    val hasCryptoParams: Boolean
        get() = !salt.isNullOrEmpty() && !iv.isNullOrEmpty()
}

/** Thrown when the selected manifest is not an SDA manifest at all. */
class SdaManifestException(message: String) : Exception(message)

/** A parsed SDA `manifest.json`. */
data class SdaManifest(
    val encrypted: Boolean,
    val entries: List<SdaManifestEntry>,
) {
    companion object {
        @Throws(SdaManifestException::class)
        fun parse(jsonText: String): SdaManifest {
            val decoded = JsonCoerce.parseObject(jsonText)
                ?: throw SdaManifestException("manifest.json is not a JSON object")
            val rawEntries = JsonCoerce.asArray(decoded["entries"])
            if (rawEntries.isEmpty() && decoded["entries"] == null) {
                throw SdaManifestException("manifest.json has no \"entries\" list")
            }
            val entries = mutableListOf<SdaManifestEntry>()
            for (raw in JsonCoerce.asObjectArray(decoded["entries"])) {
                val filename = JsonCoerce.asString(raw["filename"])
                if (filename.isEmpty()) continue
                entries.add(
                    SdaManifestEntry(
                        filename = filename,
                        // SDA writes steamid as a JSON number; tolerate a string too,
                        // since third-party tools that rewrite manifests are not
                        // consistent here.
                        steamId = JsonCoerce.asLongOrNull(raw["steamid"]) ?: 0L,
                        salt = JsonCoerce.asStringOrNull(raw["encryption_salt"]),
                        iv = JsonCoerce.asStringOrNull(raw["encryption_iv"]),
                    ),
                )
            }
            if (entries.isEmpty()) {
                throw SdaManifestException("manifest.json lists no accounts")
            }
            // `encrypted` is the manifest's own flag, but a manifest written by an
            // older SDA can omit it while still carrying per-entry salt/iv. Trust the
            // parameters over the flag: if they are there, the files are ciphertext.
            val flagged = JsonCoerce.asBool(decoded["encrypted"])
            return SdaManifest(
                encrypted = flagged || entries.all { it.hasCryptoParams },
                entries = entries,
            )
        }
    }
}

/** Why one account in the bundle could not be imported. */
enum class SdaEntryProblem {
    /** Listed in the manifest, but the user did not select that file. */
    FILE_NOT_SELECTED,

    /** Manifest says encrypted, but this row has no salt or no IV. */
    MISSING_CRYPTO_PARAMS,

    /**
     * Decrypted (or read, when unencrypted) to something that is not a maFile.
     *
     * With the right passkey this means a corrupt file; across *every* entry it
     * means the passkey was wrong.
     */
    NOT_READABLE,
}

/** One account's outcome. Exactly one of [plaintext] / [problem] is set. */
class SdaEntryResult private constructor(
    val entry: SdaManifestEntry,
    val plaintext: String?,
    val problem: SdaEntryProblem?,
) {
    val ok: Boolean
        get() = plaintext != null

    companion object {
        fun ok(entry: SdaManifestEntry, plaintext: String) =
            SdaEntryResult(entry, plaintext, null)

        fun failed(entry: SdaManifestEntry, problem: SdaEntryProblem) =
            SdaEntryResult(entry, null, problem)
    }
}

/** The result of reading a whole selection. */
data class SdaImportResult(
    val results: List<SdaEntryResult>,
    /**
     * Every encrypted entry failed to produce a readable maFile.
     *
     * One corrupt file is possible; all of them at once is a wrong passkey, and
     * saying so is the difference between a user retrying and a user giving up.
     */
    val wrongPassKey: Boolean,
    /** Files the user selected that the manifest never mentions (a mis-selection cue). */
    val unlistedFiles: List<String>,
) {
    val imported: List<SdaEntryResult>
        get() = results.filter { it.ok }

    val failed: List<SdaEntryResult>
        get() = results.filter { !it.ok }
}

/**
 * Case-insensitive basename lookup.
 *
 * Users pick files through a system picker whose reported names can differ in case
 * from the manifest (and Windows, where SDA runs, is case-insensitive anyway).
 */
private fun keyOf(filename: String): String =
    filename.split(Regex("[/\\\\]")).last().lowercase()

/**
 * True when [text] parses as a JSON object — the check that separates a correct
 * passkey from a wrong one.
 *
 * AES-CBC with the wrong key usually fails PKCS7 unpadding, but roughly 1 in 256
 * wrong keys produces valid-looking padding and returns garbage, so padding alone
 * is not a decision.
 */
fun looksLikeMaFile(text: String): Boolean = JsonCoerce.parseObject(text) != null

/**
 * Reads a selected SDA bundle.
 *
 * [files] maps the *basename* of each selected file to its contents;
 * `manifest.json` itself may be present and is ignored. [passKey] is required when
 * [manifest] is encrypted and ignored otherwise. Without a manifest, selected
 * .maFile files are read as plaintext; unrelated files are skipped.
 */
fun readSdaBundle(
    files: Map<String, String>,
    manifest: SdaManifest? = null,
    passKey: String? = null,
): SdaImportResult {
    val effectiveManifest = manifest ?: SdaManifest(
        encrypted = false,
        entries = files.keys
            .filter { keyOf(it).endsWith(".mafile") }
            .map { SdaManifestEntry(filename = it, steamId = 0L) },
    )
    val byName = files.entries.associate { keyOf(it.key) to it.value }
    val results = mutableListOf<SdaEntryResult>()

    // Pass 1: pair rows with files and split out everything that cannot even be
    // attempted, so the decrypt batch only carries real work.
    val pending = mutableListOf<Pair<SdaManifestEntry, String>>()
    for (entry in effectiveManifest.entries) {
        val contents = byName[keyOf(entry.filename)]
        if (contents == null) {
            results.add(SdaEntryResult.failed(entry, SdaEntryProblem.FILE_NOT_SELECTED))
            continue
        }
        if (effectiveManifest.encrypted && !entry.hasCryptoParams) {
            results.add(SdaEntryResult.failed(entry, SdaEntryProblem.MISSING_CRYPTO_PARAMS))
            continue
        }
        pending.add(entry to contents)
    }

    var attemptedDecrypt = 0
    var readable = 0
    if (effectiveManifest.encrypted) {
        // One batch: 50000 PBKDF2 rounds per account.
        val plaintexts = MaFileCrypto.decryptBatch(
            passKey.orEmpty(),
            pending.map { (e, ct) -> Triple(e.salt!!, e.iv!!, ct) },
        )
        for (i in pending.indices) {
            val (entry, _) = pending[i]
            attemptedDecrypt++
            val text = plaintexts[i]
            if (text == null || !looksLikeMaFile(text)) {
                results.add(SdaEntryResult.failed(entry, SdaEntryProblem.NOT_READABLE))
                continue
            }
            readable++
            results.add(SdaEntryResult.ok(entry, text))
        }
    } else {
        for ((entry, contents) in pending) {
            if (!looksLikeMaFile(contents)) {
                results.add(SdaEntryResult.failed(entry, SdaEntryProblem.NOT_READABLE))
                continue
            }
            results.add(SdaEntryResult.ok(entry, contents))
        }
    }

    val listed = effectiveManifest.entries.map { keyOf(it.filename) }.toSet()
    val unlisted = files.keys.filter { name ->
        keyOf(name) != "manifest.json" && keyOf(name) !in listed
    }

    return SdaImportResult(
        results = results,
        // Only claim "wrong passkey" when decryption was actually attempted and
        // nothing came back readable. A bundle whose files were all missing must not
        // be reported as a bad password.
        wrongPassKey = effectiveManifest.encrypted && attemptedDecrypt > 0 && readable == 0,
        unlistedFiles = unlisted,
    )
}

/** Convenience: a parsed account JSON from an import result. */
fun SdaEntryResult.toAccountJson(): JsonObject? = plaintext?.let { JsonCoerce.parseObject(it) }
