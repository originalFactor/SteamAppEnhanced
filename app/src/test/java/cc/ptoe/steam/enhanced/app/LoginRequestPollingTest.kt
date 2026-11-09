package cc.ptoe.steam.enhanced.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LoginRequestPollingTest {
    @Test
    fun `poll immediately and every two seconds after success`() = runTest {
        var calls = 0
        val job = launch { LoginRequestPolling().run { calls++; false } }
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1_999)
        assertEquals(1, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, calls)
        job.cancelAndJoin()
        advanceTimeBy(30_000)
        assertEquals(2, calls)
    }

    @Test
    fun `failures back off exponentially with a cap and success resets interval`() {
        val polling = LoginRequestPolling()
        assertEquals(listOf(4_000L, 8_000L, 16_000L, 30_000L, 30_000L), List(5) { polling.nextDelay(true) })
        assertEquals(2_000L, polling.nextDelay(false))
        assertEquals(4_000L, polling.nextDelay(true))
    }

    @Test
    fun `reopening cancels backoff and refreshes immediately without overlapping work`() = runTest {
        var calls = 0
        var active = 0
        var maxActive = 0
        suspend fun refresh(): Boolean {
            calls++
            active++
            maxActive = maxOf(maxActive, active)
            try { delay(100) } finally { active-- }
            return true
        }
        var job = launch { LoginRequestPolling().run { refresh() } }
        runCurrent()
        advanceTimeBy(101)
        job.cancelAndJoin()
        job = launch { LoginRequestPolling().run { refresh() } }
        runCurrent()
        assertEquals(2, calls)
        assertEquals(1, maxActive)
        job.cancelAndJoin()
        assertEquals(0, active)
    }
}
