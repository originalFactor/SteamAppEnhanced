package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.proto.ProtoReader
import cc.ptoe.steam.enhanced.core.proto.ProtoWriter
import cc.ptoe.steam.enhanced.core.protocol.EResult
import cc.ptoe.steam.enhanced.net.SteamApiClient
import cc.ptoe.steam.enhanced.net.SteamApiException
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import kotlin.io.encoding.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class SteamAuthSessionTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api() = SteamApiClient(client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // Keep the real API path, query and body, but send synthetic
            // credentials only to our local fixture server.
            val url = server.url(chain.request().url.encodedPath).newBuilder()
                .encodedQuery(chain.request().url.encodedQuery).build()
            chain.proceed(chain.request().newBuilder().url(url).build())
        }.build())

    private fun enqueue(message: ProtoWriter) {
        server.enqueue(MockResponse().setHeader("x-eresult", "1")
            .setBody(Buffer().write(message.toBytes())))
    }

    private fun rsa(timestamp: Long? = TIMESTAMP): ProtoWriter {
        val key = keyPair.public as RSAPublicKey
        return ProtoWriter().writeString(1, key.modulus.toString(16))
            .writeString(2, key.publicExponent.toString(16)).apply {
                if (timestamp != null) writeUint64(3, timestamp)
            }
    }

    private fun begin(vararg guards: GuardType) = ProtoWriter()
        .writeUint64(1, CLIENT_ID).writeBytes(2, REQUEST_ID).writeUint64(5, STEAM_ID)
        .apply { guards.forEach { writeMessage(4, ProtoWriter().writeVarint(1, it.wireValue)) } }

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))

    private fun decode(request: RecordedRequest): ProtoReader {
        val encoded = if (request.method == "GET") {
            request.requestUrl!!.queryParameter("input_protobuf_encoded")
        } else {
            ("http://localhost/?" + request.body.readUtf8()).toHttpUrl()
                .queryParameter("input_protobuf_encoded")
        }
        return ProtoReader(Base64.decode(checkNotNull(encoded)))
    }

    @Test
    fun `password flow preserves RSA timestamp and encrypted password through guard and poll`() = runTest {
        enqueue(rsa())
        enqueue(begin(GuardType.DEVICE_CODE, GuardType.DEVICE_CONFIRMATION))
        enqueue(ProtoWriter())
        enqueue(ProtoWriter().writeUint64(1, CLIENT_ID + 1)
            .writeString(3, "synthetic-refresh-token").writeString(4, "synthetic-access-token")
            .writeString(6, "synthetic-account"))

        val session = SteamAuthSession(api(), deviceName = "SM-S928B")
        val password = "  Synthetic +/&=密碼  "
        session.beginWithCredentials("synthetic-account", password)
        assertTrue(session.acceptsApproval)
        session.submitSteamGuardCode("ABCDE", GuardType.DEVICE_CODE)
        val result = session.poll()
        assertTrue(result.complete)
        assertEquals(STEAM_ID, session.toSessionData(result).steamId)
        assertEquals("synthetic-refresh-token", result.refreshToken)
        assertEquals(CLIENT_ID + 1, session.clientId)

        val rsaRequest = takeRequest()
        assertEquals("GET", rsaRequest.method)
        assertEquals("synthetic-account", decode(rsaRequest).parse()[1]?.asString)
        assertEquals("okhttp/4.9.2", rsaRequest.getHeader("User-Agent"))
        assertEquals("cross-site", rsaRequest.getHeader("Sec-Fetch-Site"))

        val credentials = takeRequest()
        assertEquals("POST", credentials.method)
        assertTrue(credentials.path!!.contains("BeginAuthSessionViaCredentials"))
        val fields = decode(credentials).parse()
        assertEquals("SM-S928B", fields[1]?.asString)
        assertEquals("SM-S928B", ProtoReader(fields[9]!!.bytes!!).parse()[1]?.asString)
        assertEquals(TIMESTAMP, fields[4]?.varint)
        assertEquals("synthetic-account", fields[2]?.asString)
        val decrypted = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(Cipher.DECRYPT_MODE, keyPair.private)
        }.doFinal(Base64.decode(fields[3]!!.asString)).decodeToString()
        assertEquals(password, decrypted)
        assertEquals(3, fields[6]?.asInt)
        assertEquals("Mobile", fields[8]?.asString)

        val guard = decode(takeRequest()).parse()
        assertEquals(CLIENT_ID, guard[1]?.varint)
        assertEquals(1, guard[2]?.wireType)
        assertEquals(STEAM_ID, guard[2]?.asFixed64)
        assertEquals("ABCDE", guard[3]?.asString)
        val poll = decode(takeRequest()).parse()
        assertEquals(CLIENT_ID, poll[1]?.varint)
        assertContentEquals(REQUEST_ID, poll[2]?.bytes)
    }

    @Test
    fun `RSA key timestamps round trip across signed and unsigned 64 bit boundaries`() = runTest {
        for (timestamp in listOf(0xFFFF_FFFFL, TIMESTAMP, Long.MAX_VALUE, -1L)) {
            enqueue(rsa(timestamp))
            enqueue(begin(GuardType.NONE))
            SteamAuthSession(api()).beginWithCredentials("synthetic-account", "synthetic-password")
            takeRequest()
            assertEquals(timestamp, decode(takeRequest()).parse()[4]?.varint)
        }
    }

    @Test
    fun `missing RSA timestamp stops before sending credentials`() = runTest {
        enqueue(rsa(timestamp = null))
        assertFailsWith<IllegalStateException> {
            SteamAuthSession(api()).beginWithCredentials("synthetic-account", "synthetic-password")
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `wrong RSA timestamp wire type stops before sending credentials`() = runTest {
        enqueue(rsa(timestamp = null).writeString(3, TIMESTAMP.toString()))
        assertFailsWith<IllegalStateException> {
            SteamAuthSession(api()).beginWithCredentials("synthetic-account", "synthetic-password")
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `actual credential rejection preserves Steam result and method`() = runTest {
        enqueue(rsa())
        server.enqueue(MockResponse().setHeader("x-eresult", "5"))
        val error = assertFailsWith<SteamApiException> {
            SteamAuthSession(api()).beginWithCredentials("synthetic-account", "synthetic-password")
        }
        assertEquals(EResult.INVALID_PASSWORD, error.eresult)
        assertEquals("BeginAuthSessionViaCredentials", error.method)
    }

    @Test
    fun `an empty success response cannot create a usable sign in session`() = runTest {
        enqueue(rsa())
        enqueue(ProtoWriter())
        assertFailsWith<IllegalStateException> {
            SteamAuthSession(api()).beginWithCredentials("synthetic-account", "synthetic-password")
        }
    }

    @Test
    fun `a new session clears previous confirmation choices`() = runTest {
        val session = SteamAuthSession(api())
        enqueue(rsa())
        enqueue(begin(GuardType.DEVICE_CONFIRMATION))
        session.beginWithCredentials("synthetic-account", "synthetic-password")
        assertTrue(session.acceptsApproval)
        enqueue(rsa())
        enqueue(begin())
        session.beginWithCredentials("synthetic-account", "synthetic-password")
        assertTrue(session.allowedConfirmations.isEmpty())
        assertFalse(session.acceptsApproval)
    }

    @Test
    fun `QR login sends the model in both device name fields`() = runTest {
        enqueue(ProtoWriter().writeUint64(1, CLIENT_ID).writeBytes(3, REQUEST_ID)
            .writeString(2, "https://s.team/q/1/123"))
        SteamAuthSession(api(), deviceName = "Pixel 9").beginWithQr()
        val fields = decode(takeRequest()).parse()
        assertEquals("Pixel 9", fields[1]?.asString)
        assertEquals("Pixel 9", ProtoReader(fields[3]!!.bytes!!).parse()[1]?.asString)
    }

    @Test
    fun `automatic password login forwards the configured model`() = runTest {
        enqueue(rsa())
        enqueue(begin(GuardType.NONE))
        enqueue(ProtoWriter().writeString(3, "synthetic-refresh-token").writeString(4, "synthetic-access-token"))
        val account = cc.ptoe.steam.enhanced.core.model.SteamGuardAccount(
            accountName = "synthetic-account",
            session = cc.ptoe.steam.enhanced.core.model.SessionData(steamId = STEAM_ID),
        )
        val (_, outcome) = AutoLogin(api(), deviceName = "Xiaomi 14").reloginWithPassword(account, "synthetic-password")
        assertEquals(AutoLoginOutcome.OK, outcome)
        takeRequest()
        val fields = decode(takeRequest()).parse()
        assertEquals("Xiaomi 14", fields[1]?.asString)
        assertEquals("Xiaomi 14", ProtoReader(fields[9]!!.bytes!!).parse()[1]?.asString)
    }

    @Test
    fun `QR sign in requires a nonempty challenge and preserves mobile headers`() = runTest {
        enqueue(ProtoWriter().writeUint64(1, CLIENT_ID).writeBytes(3, REQUEST_ID))
        assertFailsWith<IllegalStateException> { SteamAuthSession(api()).beginWithQr() }
        assertEquals("okhttp/4.9.2", takeRequest().getHeader("User-Agent"))
    }

    private suspend fun recoverySession(): SteamAuthSession {
        enqueue(rsa())
        enqueue(begin(GuardType.DEVICE_CODE, GuardType.DEVICE_CONFIRMATION).writeString(6, WEAK_TOKEN))
        return SteamAuthSession(api()).also {
            it.beginWithCredentials("synthetic-account", "synthetic-password")
            takeRequest()
            takeRequest()
        }
    }

    private fun replacement() = ProtoWriter().writeBool(1, false).writeMessage(2, ProtoWriter()
        .writeBytes(1, ByteArray(20) { 7 })
        .writeString(3, "R-SYNTHETIC")
        .writeString(6, "synthetic-account")
        .writeBytes(8, ByteArray(20) { 8 }))

    @Test
    fun `weak credential recovers before guard authentication and new code completes login`() = runTest {
        val session = recoverySession()
        assertTrue(session.canRecoverAuthenticator)
        val recovery = session.newAuthenticatorRecovery()
        enqueue(ProtoWriter()) // Empty successful challenge response is valid.
        enqueue(replacement()) // Replacement wins over an explicit false success flag.
        enqueue(ProtoWriter()) // Newly generated guard code accepted.
        enqueue(ProtoWriter().writeString(3, "synthetic-refresh").writeString(4, "synthetic-access"))
        assertEquals(LinkResult.CHALLENGE_EMAIL_SENT, recovery.start())
        var saved: cc.ptoe.steam.enhanced.core.model.SteamGuardAccount? = null
        val store = cc.ptoe.steam.enhanced.app.AuthenticatorRecoveryStore(
            accountForId = { cc.ptoe.steam.enhanced.core.model.SteamGuardAccount(personaName = "Preserved nickname") },
            save = { saved = it },
        )
        val moved = store.finish(recovery, " 123456 ", "synthetic-account", null)
        assertEquals(moved, saved)
        assertEquals("Preserved nickname", moved.personaName)
        assertTrue(moved.fullyEnrolled)
        assertEquals(STEAM_ID, moved.steamId)
        assertEquals(null, moved.session.accessToken)
        assertEquals(null, moved.session.refreshToken)
        assertFalse(moved.toJson().toString().contains(WEAK_TOKEN))
        session.submitSteamGuardCode(moved.generateCode(1_700_000_000L), GuardType.DEVICE_CODE)
        val result = session.poll()
        assertTrue(result.complete)
        assertFalse(session.canRecoverAuthenticator)
        assertEquals("synthetic-access", session.toSessionData(result).accessToken)
        val start = takeRequest()
        val finish = takeRequest()
        assertTrue(start.path!!.contains("RemoveAuthenticatorViaChallengeStart"))
        assertTrue(finish.path!!.contains("RemoveAuthenticatorViaChallengeContinue"))
        for (request in listOf(start, finish)) assertEquals(WEAK_TOKEN, request.requestUrl!!.queryParameter("access_token"))
        val fields = decode(finish).parse()
        assertEquals("123456", fields[1]?.asString)
        assertTrue(fields[2]!!.asBool)
        assertEquals(2, fields[3]?.asInt)
        val guard = decode(takeRequest()).parse()
        assertEquals(moved.generateCode(1_700_000_000L), guard[3]?.asString)
        assertTrue(takeRequest().path!!.contains("PollAuthSessionStatus"))
    }

    @Test
    fun `missing weak credential never offers recovery and QR version is not a token`() = runTest {
        val session = SteamAuthSession(api())
        enqueue(rsa())
        enqueue(begin(GuardType.DEVICE_CODE))
        session.beginWithCredentials("synthetic-account", "synthetic-password")
        assertFalse(session.canRecoverAuthenticator)
        assertFailsWith<IllegalStateException> { session.newAuthenticatorRecovery() }
        enqueue(ProtoWriter().writeUint64(1, CLIENT_ID).writeBytes(3, REQUEST_ID)
            .writeString(2, "https://s.team/q/1/123").writeVarint(6, 1))
        session.beginWithQr()
        assertFalse(session.canRecoverAuthenticator)
    }

    @Test
    fun `failed new password attempt clears previous weak credential`() = runTest {
        val session = recoverySession()
        assertTrue(session.canRecoverAuthenticator)
        server.enqueue(MockResponse().setHeader("x-eresult", "5"))
        assertFailsWith<SteamApiException> { session.beginWithCredentials("other-account", "wrong-password") }
        assertFalse(session.canRecoverAuthenticator)
        assertEquals(0L, session.steamId)
    }

    @Test
    fun `wrong SMS code leaves old authenticator untouched and allows retry`() = runTest {
        val recovery = recoverySession().newAuthenticatorRecovery()
        enqueue(ProtoWriter())
        assertEquals(LinkResult.CHALLENGE_EMAIL_SENT, recovery.start())
        server.enqueue(MockResponse().setHeader("x-eresult", "94"))
        var saves = 0
        val store = cc.ptoe.steam.enhanced.app.AuthenticatorRecoveryStore({ null }, { saves++ })
        val failure = assertFailsWith<IllegalStateException> { store.finish(recovery, "wrong", "synthetic", null) }
        assertEquals(LinkResult.BAD_CHALLENGE_CODE.name, failure.message)
        assertEquals(0, saves)
        assertEquals(null, store.pending.value)
        enqueue(replacement())
        store.finish(recovery, "123456", "synthetic", null)
        assertEquals(1, saves)
    }

    @Test
    fun `local save failure retains new secret and retry never repeats Steam mutation`() = runTest {
        val recovery = recoverySession().newAuthenticatorRecovery()
        enqueue(ProtoWriter())
        enqueue(replacement())
        recovery.start()
        var fail = true
        val store = cc.ptoe.steam.enhanced.app.AuthenticatorRecoveryStore({ null }, {
            if (fail) throw java.io.IOException("synthetic disk failure")
        })
        assertFailsWith<java.io.IOException> { store.finish(recovery, "123456", "synthetic", "opt-in-password") }
        val pending = checkNotNull(store.pending.value)
        assertEquals("R-SYNTHETIC", pending.revocationCode)
        assertEquals("opt-in-password", pending.password)
        assertEquals(null, pending.session.accessToken)
        val requests = server.requestCount
        assertFailsWith<IllegalStateException> { store.finish(recovery, "123456", "synthetic", null) }
        assertEquals(requests, server.requestCount)
        fail = false
        store.retry()
        assertEquals(null, store.pending.value)
        assertEquals(requests, server.requestCount)
        assertEquals(pending.sharedSecret, recovery.complete("already-consumed-code").sharedSecret)
        assertEquals(requests, server.requestCount)
    }

    @Test
    fun `cancellation after transfer cannot interrupt local persistence`() = runTest {
        val recovery = recoverySession().newAuthenticatorRecovery()
        enqueue(ProtoWriter())
        enqueue(replacement())
        recovery.start()
        val saving = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var saved = false
        val store = cc.ptoe.steam.enhanced.app.AuthenticatorRecoveryStore({ null }, {
            saving.complete(Unit)
            release.await()
            saved = true
        })
        val job = launch {
            store.finish(recovery, "123456", "synthetic", null)
        }
        saving.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(saved)
        assertEquals(null, store.pending.value)
    }

    @Test
    fun `challenge must start before any replacement request`() = runTest {
        val recovery = recoverySession().newAuthenticatorRecovery()
        val requests = server.requestCount
        assertFailsWith<IllegalStateException> { recovery.complete("123456") }
        assertEquals(requests, server.requestCount)
    }

    companion object {
        private const val WEAK_TOKEN = "synthetic-weak-credential"
        private const val TIMESTAMP = 1_700_000_000_123L
        private const val CLIENT_ID = -7_000_000_000_000_000_001L
        private const val STEAM_ID = 76_561_198_000_000_001L
        private val REQUEST_ID = byteArrayOf(1, 2, 3, 4)
        private val keyPair by lazy {
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        }
    }
}
