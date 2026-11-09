package cc.ptoe.steam.enhanced.net

import cc.ptoe.steam.enhanced.core.proto.ProtoWriter
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SteamResponseBodyTest {
    @Test
    fun `streamed responses are read off caller thread and closed before returning`() = runTest {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
            withContext(caller) {
                val callerThread = Thread.currentThread()
                val closed = AtomicBoolean()
                val readThread = AtomicReference<Thread>()
                val html = "<html>" + "inventory".repeat(16_384) + "</html>"
                val api = client(html, closed) {
                    readThread.set(Thread.currentThread())
                    check(Thread.currentThread() != callerThread) { "Network response read on UI caller thread" }
                }
                assertEquals(html, api.communityGetText("/profiles/1/inventory/"))
                assertTrue(readThread.get() != callerThread)
                assertTrue(closed.get())
            }
        }
    }

    @Test
    fun `JSON and protobuf body reads also stay off caller thread`() = runTest {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
            withContext(caller) {
                val callerThread = Thread.currentThread()
                val closed = AtomicBoolean()
                val api = client("{}", closed) { check(Thread.currentThread() != callerThread) }
                api.communityGetJson("/inventory/1/753/6", emptyMap())
                assertTrue(closed.get())
                closed.set(false)
                api.apiGetJson("IPlayerService", "GetOwnedGames", emptyMap())
                assertTrue(closed.get())
                // Use an empty valid protobuf body in a separate response.
                val proto = client("", closed) { check(Thread.currentThread() != callerThread) }
                closed.set(false)
                proto.callProtobuf("ITest", "Read", ProtoWriter())
                assertTrue(closed.get())
            }
        }
    }

    @Test
    fun `failed body reads close the original response and propagate failure`() = runTest {
        val closed = AtomicBoolean()
        val api = client("partial response", closed) { throw IOException("synthetic read failure") }
        assertFailsWith<IOException> { api.communityGetText("/profiles/1/inventory/") }
        assertTrue(closed.get())
    }

    private fun client(text: String, closed: AtomicBoolean, onRead: () -> Unit): SteamApiClient {
        return SteamApiClient(client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = object : ResponseBody() {
                private val stream = object : ForwardingSource(Buffer().writeUtf8(text)) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        onRead()
                        return super.read(sink, minOf(byteCount, 1024))
                    }
                    override fun close() {
                        closed.set(true)
                        super.close()
                    }
                }.buffer()
                override fun contentType(): MediaType? = null
                override fun contentLength(): Long = -1
                override fun source(): BufferedSource = stream
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Synthetic response").body(body).build()
        }.build())
    }
}
