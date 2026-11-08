package cc.ptoe.steam.enhanced.core

import cc.ptoe.steam.enhanced.core.model.WalletInfo
import kotlin.math.floor
import kotlin.math.roundToInt

class MarketFees(private val wallet: WalletInfo) {
    fun validPrice(price: Int): Int {
        val minimum = wallet.marketMinimum.coerceAtLeast(1)
        val increment = wallet.currencyIncrement.coerceAtLeast(1)
        if (price <= minimum) return minimum
        if (price <= increment) return increment
        return (price.toDouble() / increment).roundToInt() * increment
    }

    fun buyerPays(receive: Int, publisherFee: Double? = null): Int {
        fun fee(percent: Double) = if (percent > 0) validPrice(floor(receive * percent).toInt()) else 0
        return validPrice(receive) + fee(wallet.steamFeePct) + fee(publisherFee ?: wallet.publisherFeePct)
    }

    fun receiveFromTotal(total: Int, publisherFee: Double? = null): Int? {
        val min = wallet.marketMinimum.coerceAtLeast(1)
        val inc = wallet.currencyIncrement.coerceAtLeast(1)
        if (buyerPays(min, publisherFee) > total) return null
        var low = 0
        var high = total / inc
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            val amount = validPrice(mid * inc)
            if (buyerPays(amount, publisherFee) <= total) low = mid else high = mid - 1
        }
        return validPrice(low * inc)
    }
}
