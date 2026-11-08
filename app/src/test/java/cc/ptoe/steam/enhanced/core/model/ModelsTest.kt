package cc.ptoe.steam.enhanced.core.model

import cc.ptoe.steam.enhanced.core.JsonCoerce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip and compatibility tests for the maFile model layer.
 *
 * The whole point of these models is losslessness: a user's existing `.maFile`
 * must load, and re-saving it must not silently drop a field some other tool
 * wrote. That is what the `extra` maps exist for.
 */
class ModelsTest {

    @Test
    fun `account round trips every known field`() {
        val json = JsonCoerce.parseObject(
            """
            {
              "shared_secret": "c2VjcmV0",
              "serial_number": "1234567890",
              "revocation_code": "R12345",
              "uri": "otpauth://totp/Steam:user?secret=X&issuer=Steam",
              "server_time": 1700000000,
              "account_name": "tester",
              "token_gid": "abc123",
              "identity_secret": "aWRlbnRpdHk=",
              "secret_1": "czE=",
              "status": 1,
              "device_id": "android:11111111-2222-3333-4444-555555555555",
              "fully_enrolled": true,
              "avatar_url": "https://example/avatar.jpg",
              "avatar_frame_url": "https://example/frame.png",
              "animated_avatar_url": "https://example/anim.png",
              "persona_name": "Tester",
              "password": "hunter2",
              "Session": {"SteamID": 76561197960265728, "AccessToken": "at", "RefreshToken": "rt"}
            }
            """.trimIndent(),
        )!!

        val account = SteamGuardAccount.fromJson(json)
        assertEquals("c2VjcmV0", account.sharedSecret)
        assertEquals("1234567890", account.serialNumber)
        assertEquals("R12345", account.revocationCode)
        assertEquals(1_700_000_000L, account.serverTime)
        assertEquals("tester", account.accountName)
        assertEquals("abc123", account.tokenGid)
        assertEquals("aWRlbnRpdHk=", account.identitySecret)
        assertEquals("czE=", account.secret1)
        assertEquals(1, account.status)
        assertTrue(account.fullyEnrolled)
        assertEquals("Tester", account.personaName)
        assertEquals("hunter2", account.password)
        assertEquals(76561197960265728L, account.steamId)
        assertTrue(account.session.hasAccessToken)

        // Re-serializing and re-parsing must produce an equal object.
        val again = SteamGuardAccount.fromJson(account.toJson())
        assertEquals(account, again)
    }

    @Test
    fun `unknown top-level keys survive a round trip`() {
        val json = JsonCoerce.parseObject(
            """{"shared_secret":"s","account_name":"a","some_future_field":{"nested":1}}""",
        )!!
        val account = SteamGuardAccount.fromJson(json)
        assertEquals(1, account.extra.size)
        assertTrue(account.extra.containsKey("some_future_field"))
        assertTrue(account.toJson().containsKey("some_future_field"))
    }

    @Test
    fun `unknown session keys survive a round trip`() {
        val json = JsonCoerce.parseObject(
            """{"Session":{"SteamID":1,"SteamLoginSecure":"7656||tok","OAuthToken":"x"}}""",
        )!!
        val account = SteamGuardAccount.fromJson(json)
        assertEquals(2, account.session.extra.size)
        val out = account.toJson()["Session"] as kotlinx.serialization.json.JsonObject
        assertTrue(out.containsKey("SteamLoginSecure"))
        assertTrue(out.containsKey("OAuthToken"))
    }

    @Test
    fun `export json drops the password by default`() {
        val account = SteamGuardAccount(
            sharedSecret = "s",
            accountName = "a",
            password = "hunter2",
        )
        assertFalse(account.toExportJson().containsKey("password"))
        assertEquals("hunter2", account.toExportJson(includePassword = true)["password"]!!.toString().trim('"'))
    }

    @Test
    fun `session only accepts access token as usable for direct API calls`() {
        val refreshOnly = SessionData(steamId = 1, refreshToken = "rt")
        assertTrue(refreshOnly.hasTokens)
        assertFalse(refreshOnly.hasAccessToken)

        val full = SessionData(steamId = 1, accessToken = "at", refreshToken = "rt")
        assertTrue(full.hasTokens)
        assertTrue(full.hasAccessToken)
    }

    @Test
    fun `manifest round trips SDA-compatible fields`() {
        val json = JsonCoerce.parseObject(
            """
            {
              "encrypted": true, "first_run": false,
              "entries": [{"encryption_iv":"iv","encryption_salt":"salt","filename":"1.maFile","steamid":1}],
              "periodic_checking": true, "periodic_checking_interval": 10,
              "periodic_checking_checkall": true,
              "auto_confirm_market_transactions": true, "auto_confirm_trades": true,
              "passkey_check": "s|i|c", "kdf_iterations": 50000,
              "vault": true, "schema_version": 2
            }
            """.trimIndent(),
        )!!

        val manifest = Manifest.fromJson(json)
        assertTrue(manifest.encrypted)
        assertFalse(manifest.firstRun)
        assertEquals(1, manifest.entries.size)
        assertEquals("1.maFile", manifest.entries[0].filename)
        assertEquals("salt", manifest.entries[0].salt)
        assertTrue(manifest.periodicChecking)
        assertEquals(10, manifest.periodicCheckingInterval)
        assertTrue(manifest.autoConfirmTrades)
        assertTrue(manifest.vault)
        assertEquals(2, manifest.schemaVersion)

        val again = Manifest.fromJson(manifest.toJson())
        assertEquals(manifest, again)
    }

    @Test
    fun `manifest defaults are SDA compatible`() {
        val manifest = Manifest.fromJson(kotlinx.serialization.json.JsonObject(emptyMap()))
        assertFalse(manifest.encrypted)
        assertTrue(manifest.firstRun)
        assertEquals(50_000, manifest.kdfIterations)
        assertEquals(5, manifest.periodicCheckingInterval)
        assertFalse(manifest.vault)
    }

    @Test
    fun `confirmation maps known type ids`() {
        assertEquals(ConfirmationType.TRADE, confirmOf(2))
        assertEquals(ConfirmationType.MARKET_LISTING, confirmOf(3))
        assertEquals(ConfirmationType.API_KEY, confirmOf(9))
        assertEquals(ConfirmationType.FAMILY_JOIN, confirmOf(11))
        assertEquals(ConfirmationType.OTHER, confirmOf(1))
        assertEquals(ConfirmationType.UNKNOWN, confirmOf(999))
    }

    private fun confirmOf(type: Int): ConfirmationType {
        val json = JsonCoerce.parseObject(
            """{"id":"1","nonce":"n","type":$type,"headline":"h","summary":["a","b"]}""",
        )!!
        return Confirmation.fromJson(json).type
    }

    @Test
    fun `confirmation reads summary entries as strings`() {
        val json = JsonCoerce.parseObject(
            """{"id":1,"nonce":"n","type":2,"summary":["first","second"],"creation_time":1700000000}""",
        )!!
        val c = Confirmation.fromJson(json)
        assertEquals("1", c.id)
        assertEquals(listOf("first", "second"), c.summary)
        assertEquals(1_700_000_000L, c.creationTime)
    }

    @Test
    fun `trade offer exposes the danger flags the UI depends on`() {
        val gift = JsonCoerce.parseObject(
            """
            {"tradeofferid":"1","accountid_other":100,"message":"","trade_offer_state":2,
             "is_our_offer":false,"items_to_receive":[{"appid":730,"classid":"1","instanceid":"0","assetid":"9","amount":1}]}
            """.trimIndent(),
        )!!
        val offer = TradeOffer.fromJson(gift, emptyMap())
        assertTrue(offer.isGift)
        assertFalse(offer.isOneSidedGive)
        assertEquals(TradeOfferState.ACTIVE, offer.state)
        assertEquals(76561197960265728L + 100, offer.partnerSteamId)

        val giveaway = JsonCoerce.parseObject(
            """
            {"tradeofferid":"2","accountid_other":100,"trade_offer_state":2,
             "items_to_give":[{"appid":730,"classid":"1","instanceid":"0","assetid":"9","amount":1}]}
            """.trimIndent(),
        )!!
        assertTrue(TradeOffer.fromJson(giveaway, emptyMap()).isOneSidedGive)
    }

    @Test
    fun `device session list flags the current device`() {
        val current = DeviceSession("111", "this", 1, 3, true)
        val other = DeviceSession("222", "other", 2, 2, true)
        val list = DeviceSessionList(listOf(current, other), requestingTokenId = "111")
        assertTrue(list.isCurrent(current))
        assertFalse(list.isCurrent(other))

        // With no echoed token nothing may be claimed as current.
        val unknown = DeviceSessionList(listOf(current), requestingTokenId = "")
        assertFalse(unknown.isCurrent(current))
    }

    @Test
    fun `wallet info tolerates a zero currency increment`() {
        val json = JsonCoerce.parseObject(
            """{"wallet_currency":"23","wallet_fee_percent":"0.05","wallet_currency_increment":"0"}""",
        )!!
        val wallet = WalletInfo.fromJson(json)
        assertEquals(23, wallet.currency)
        assertEquals(0.05, wallet.steamFeePct, 1e-9)
        // A zero increment would divide-by-zero the fee math.
        assertEquals(1, wallet.currencyIncrement)
    }

    @Test
    fun `empty strings and nulls coerce without throwing`() {
        val json = JsonCoerce.parseObject("""{"a":null,"b":"","c":"12","d":true}""")!!
        assertEquals(0, JsonCoerce.asInt(json["a"]))
        assertEquals("", JsonCoerce.asString(json["b"]))
        assertEquals(12, JsonCoerce.asInt(json["c"]))
        assertTrue(JsonCoerce.asBool(json["d"]))
        assertNull(JsonCoerce.asStringOrNull(json["a"]))
    }
}
