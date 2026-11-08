package cc.ptoe.steam.enhanced.core

import cc.ptoe.steam.enhanced.core.crypto.MaFileCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class SdaImportTest {

    private val plainMaFile = """{"shared_secret":"c2VjcmV0","account_name":"tester"}"""

    @Test
    fun `plaintext maFiles import without a manifest`() {
        val result = readSdaBundle(
            files = mapOf("76561197960265728.maFile" to plainMaFile),
        )
        assertEquals(1, result.imported.size)
        assertFalse(result.wrongPassKey)
        assertEquals(plainMaFile, result.imported[0].plaintext)
    }

    @Test
    fun `non-mafile selections are counted as unlisted`() {
        val result = readSdaBundle(
            files = mapOf(
                "76561197960265728.maFile" to plainMaFile,
                "notes.txt" to "hello",
            ),
        )
        assertEquals(1, result.imported.size)
        assertEquals(listOf("notes.txt"), result.unlistedFiles)
    }

    @Test
    fun `encrypted bundle decrypts with the right passkey`() {
        val salt = MaFileCrypto.randomSalt()
        val iv = MaFileCrypto.randomIv()
        val ciphertext = MaFileCrypto.encrypt("pass", salt, iv, plainMaFile)

        val manifest = SdaManifest(
            encrypted = true,
            entries = listOf(
                SdaManifestEntry(
                    filename = "76561197960265728.maFile",
                    steamId = 76561197960265728L,
                    salt = salt,
                    iv = iv,
                ),
            ),
        )
        val result = readSdaBundle(
            files = mapOf("76561197960265728.maFile" to ciphertext),
            manifest = manifest,
            passKey = "pass",
        )
        assertEquals(1, result.imported.size)
        assertFalse(result.wrongPassKey)
        assertEquals(plainMaFile, result.imported[0].plaintext)
    }

    @Test
    fun `wrong passkey across every entry is reported as wrongPassKey`() {
        val salt = MaFileCrypto.randomSalt()
        val iv = MaFileCrypto.randomIv()
        val ciphertext = MaFileCrypto.encrypt("right", salt, iv, plainMaFile)
        val manifest = SdaManifest(
            encrypted = true,
            entries = listOf(
                SdaManifestEntry("a.maFile", 1, salt, iv),
            ),
        )
        val result = readSdaBundle(
            files = mapOf("a.maFile" to ciphertext),
            manifest = manifest,
            passKey = "wrong",
        )
        assertEquals(0, result.imported.size)
        assertTrue(result.wrongPassKey)
    }

    @Test
    fun `a bundle whose files were all missing is not called a bad password`() {
        val manifest = SdaManifest(
            encrypted = true,
            entries = listOf(SdaManifestEntry("gone.maFile", 1, "salt", "iv")),
        )
        val result = readSdaBundle(files = emptyMap(), manifest = manifest, passKey = "p")
        assertFalse(result.wrongPassKey)
        assertEquals(
            SdaEntryProblem.FILE_NOT_SELECTED,
            result.failed.single().problem,
        )
    }

    @Test
    fun `an encrypted row without salt or iv cannot be attempted`() {
        val manifest = SdaManifest(
            encrypted = true,
            entries = listOf(SdaManifestEntry("a.maFile", 1, salt = null, iv = null)),
        )
        val result = readSdaBundle(
            files = mapOf("a.maFile" to "ciphertext"),
            manifest = manifest,
            passKey = "p",
        )
        assertEquals(
            SdaEntryProblem.MISSING_CRYPTO_PARAMS,
            result.failed.single().problem,
        )
    }

    @Test
    fun `manifest parsing tolerates string steamids and infers encryption from params`() {
        val manifest = SdaManifest.parse(
            """
            {"entries":[{"filename":"a.maFile","steamid":"76561197960265728",
             "encryption_salt":"c2FsdA==","encryption_iv":"aXY="}]}
            """.trimIndent(),
        )
        assertEquals(76561197960265728L, manifest.entries[0].steamId)
        // No `encrypted` flag, but the per-entry params prove the files are ciphertext.
        assertTrue(manifest.encrypted)
    }

    @Test
    fun `manifest without an entries list is rejected`() {
        try {
            SdaManifest.parse("""{"encrypted":false}""")
            fail("expected SdaManifestException")
        } catch (_: SdaManifestException) {
            // expected
        }
    }

    @Test
    fun `manifest listing no accounts is rejected`() {
        try {
            SdaManifest.parse("""{"entries":[]}""")
            fail("expected SdaManifestException")
        } catch (_: SdaManifestException) {
            // expected
        }
    }

    @Test
    fun `filename matching is case insensitive`() {
        val result = readSdaBundle(
            files = mapOf("76561197960265728.MAFILE" to plainMaFile),
        )
        assertEquals(1, result.imported.size)
    }

    @Test
    fun `looksLikeMaFile distinguishes json from garbage`() {
        assertTrue(looksLikeMaFile(plainMaFile))
        assertFalse(looksLikeMaFile("random ciphertext bytes"))
        assertFalse(looksLikeMaFile("[1,2,3]")) // an array is not a maFile
    }
}

class MaFileNormalizerTest {

    @Test
    fun `base64 steamid is decoded into the session`() {
        // 76561197960265728 little-endian, base64 encoded.
        val le = java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(76561197960265728L)
            .array()
        val b64 = Base64.getEncoder().encodeToString(le)

        val json = JsonCoerce.parseObject("""{"steamid":"$b64","shared_secret":"s"}""")!!
        val normalized = MaFileNormalizer.normalize(json)
        val session = normalized["Session"] as kotlinx.serialization.json.JsonObject
        assertEquals(
            76561197960265728L,
            JsonCoerce.asLongOrNull(session["SteamID"]),
        )
    }

    @Test
    fun `steamid is recovered from the source filename when absent`() {
        val json = JsonCoerce.parseObject("""{"shared_secret":"s"}""")!!
        val normalized = MaFileNormalizer.normalize(
            json,
            sourceName = "76561197960265728.maFile",
        )
        val session = normalized["Session"] as kotlinx.serialization.json.JsonObject
        assertEquals(76561197960265728L, JsonCoerce.asLongOrNull(session["SteamID"]))
    }

    @Test
    fun `base32 secret from an otpauth uri becomes base64`() {
        // base32("12345678901234567890") = GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
        val json = JsonCoerce.parseObject(
            """{"uri":"otpauth://totp/Steam:user?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Steam"}""",
        )!!
        val normalized = MaFileNormalizer.normalize(json)
        val secret = JsonCoerce.asString(normalized["shared_secret"])
        assertEquals(
            Base64.getEncoder().encodeToString("12345678901234567890".toByteArray()),
            secret,
        )
    }

    @Test
    fun `steam uri secret is extracted and normalized`() {
        val json = JsonCoerce.parseObject(
            """{"uri":"steam://GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"}""",
        )!!
        val normalized = MaFileNormalizer.normalize(json)
        assertEquals(
            Base64.getEncoder().encodeToString("12345678901234567890".toByteArray()),
            JsonCoerce.asString(normalized["shared_secret"]),
        )
    }

    @Test
    fun `an already valid base64 secret is left alone`() {
        val secret = Base64.getEncoder().encodeToString("12345678901234567890".toByteArray())
        val json = JsonCoerce.parseObject("""{"shared_secret":"$secret"}""")!!
        val normalized = MaFileNormalizer.normalize(json)
        assertEquals(secret, JsonCoerce.asString(normalized["shared_secret"]))
    }

    @Test
    fun `an existing session steamid is never overwritten`() {
        val json = JsonCoerce.parseObject(
            """{"steamid":999,"Session":{"SteamID":76561197960265728}}""",
        )!!
        val normalized = MaFileNormalizer.normalize(json, sourceName = "76561197900000001.x")
        val session = normalized["Session"] as kotlinx.serialization.json.JsonObject
        assertEquals(76561197960265728L, JsonCoerce.asLongOrNull(session["SteamID"]))
    }

    @Test
    fun `no steamid anywhere leaves the session absent`() {
        val json = JsonCoerce.parseObject("""{"shared_secret":"s"}""")!!
        val normalized = MaFileNormalizer.normalize(json)
        assertNull(normalized["Session"])
        // The secret still normalizes, so a code-only account is importable.
        assertNotNull(normalized["shared_secret"])
    }
}
