package cc.ptoe.steam.enhanced.core

import cc.ptoe.steam.enhanced.core.model.WalletInfo
import cc.ptoe.steam.enhanced.protocol.InventoryClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketFeesTest {
    @Test
    fun `steam and publisher fees are charged to the buyer`() {
        val fees = MarketFees(WalletInfo.FALLBACK)
        assertEquals(115, fees.buyerPays(100))
        assertEquals(105, fees.buyerPays(100, publisherFee = 0.0))
        assertEquals(3, fees.buyerPays(1))
        assertNull(fees.receiveFromTotal(2))
    }

    @Test
    fun `reverse price honors the wallet increment and never exceeds the entered total`() {
        val fees = MarketFees(WalletInfo.FALLBACK.copy(marketMinimum = 5, currencyIncrement = 5))
        assertEquals(15, fees.buyerPays(5))
        assertEquals(10, fees.receiveFromTotal(21))
        assertEquals(100, fees.receiveFromTotal(fees.buyerPays(100)))
    }

    @Test
    fun `embedded inventory JSON handles braces and escaped quotes in item names`() {
        val html = """<script>var g_rgWalletInfo = {"name":"test } \" escaped","wallet_currency":23};</script>"""
        val data = InventoryClient.embeddedObject(html, "g_rgWalletInfo")
        assertEquals(23, JsonCoerce.asInt(data?.get("wallet_currency")))
        assertNull(InventoryClient.embeddedObject(html, "not_present"))
    }
}
