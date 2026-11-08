package cc.ptoe.steam.enhanced.ui

import androidx.compose.ui.res.stringResource
import cc.ptoe.steam.enhanced.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import cc.ptoe.steam.enhanced.app.SteamServices
import cc.ptoe.steam.enhanced.core.MarketFees
import cc.ptoe.steam.enhanced.core.model.ConfirmationType
import cc.ptoe.steam.enhanced.core.model.InventoryGame
import cc.ptoe.steam.enhanced.core.model.InventoryItem
import cc.ptoe.steam.enhanced.core.model.MarketListing
import cc.ptoe.steam.enhanced.core.model.MarketPrice
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.core.model.WalletInfo
import cc.ptoe.steam.enhanced.protocol.InventoryOverview
import cc.ptoe.steam.enhanced.store.AccountStore
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MarketPage(account: SteamGuardAccount, services: SteamServices, work: AppWork) {
    val id = AccountStore.storageId(account)
    var tab by remember { mutableIntStateOf(0) }
    var overview by remember { mutableStateOf<InventoryOverview?>(null) }
    var game by remember { mutableStateOf<InventoryGame?>(null) }
    var items by remember { mutableStateOf(emptyList<InventoryItem>()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var listings by remember { mutableStateOf<List<MarketListing>?>(null) }
    var listingPage by remember { mutableIntStateOf(0) }
    var moreListings by remember { mutableStateOf(false) }
    var sale by remember { mutableStateOf<List<InventoryItem>?>(null) }

    suspend fun loadGame(selected: InventoryGame, next: String? = null) {
        val page = services.read(id) { services.inventory.items(it, selected, next) }
        game = selected
        items = (if (next == null) page.items else items + page.items).distinctBy(InventoryItem::assetId)
        cursor = page.cursor
    }

    suspend fun loadListings(next: Int = 0) {
        val (data, more) = services.read(id) { services.market.listings(it, next) }
        listingPage = next
        listings = (if (next == 0) data else listings.orEmpty() + data).distinctBy(MarketListing::listingId)
        moreListings = more
    }

    suspend fun reload() {
        if (tab == 0) {
            overview = services.read(id, services.inventory::overview)
            game?.let { loadGame(it) }
        } else loadListings()
    }

    LaunchedEffect(tab) { work.run { reload() } }
    val selectedSale = sale
    val wallet = overview?.wallet
    if (selectedSale != null && wallet != null) {
        SellPanel(account, selectedSale, wallet, services, work, close = { sale = null }) {
            sale = null
            game?.let { loadGame(it) }
        }
        return
    }

    TabRow(listOf(stringResource(R.string.ava_market_tab_inventory), stringResource(R.string.ava_market_tab_listings)), tab, { if (!work.busy) tab = it })
    Entry(stringResource(R.string.ava_common_refresh), !work.busy) { work.run { reload() } }
    if (tab == 0) {
        val selectedGame = game
        if (selectedGame == null) {
            if (overview?.games?.isEmpty() == true) Text("No games with inventory.")
            overview?.games?.forEach { entry ->
                Entry("${entry.name.ifBlank { entry.appid.toString() }} · ${entry.itemCount}", !work.busy) {
                    work.run { loadGame(entry) }
                }
            }
        } else {
            Heading(selectedGame.name)
            Entry("Choose another game", !work.busy) { game = null }
            if (items.isEmpty()) Text(stringResource(R.string.ava_market_no_items))
            if (wallet == null) Text("Steam did not return wallet fees. Selling is unavailable.")
            val groups = items.groupBy { Triple(it.classId, it.instanceId, it.marketable) }
            for (group in groups.values) Panel {
                val item = group.first()
                Heading(item.name.ifBlank { "Inventory item" })
                if (item.type.isNotBlank()) Text(item.type)
                Text("Quantity ${group.sumOf { it.amount.toLong() }}")
                if (item.marketable && wallet != null) Entry(stringResource(R.string.ava_market_list_button), !work.busy) { sale = group }
            }
            cursor?.let { token ->
                Entry("Load more items", !work.busy) { work.run { loadGame(selectedGame, token) } }
            }
        }
    } else {
        if (listings?.isEmpty() == true) Text(stringResource(R.string.ava_market_no_listings))
        for (listing in listings.orEmpty()) Panel {
            Heading(listing.name.ifBlank { listing.marketHashName })
            Text("Buyer pays ${listing.buyerPrice} · Fee ${listing.fee} (minor units)")
            Entry(stringResource(R.string.ava_market_cancel), !work.busy) {
                work.ask("Cancel listing?", "Steam will return the unsold item to your inventory.", "Cancel listing") {
                    check(services.market.cancel(services.session(id), listing.listingId)) { "Steam did not cancel this listing." }
                    loadListings()
                }
            }
        }
        if (moreListings) Entry("Load more listings", !work.busy) { work.run { loadListings(listingPage + 100) } }
    }
}

@Composable
private fun SellPanel(
    account: SteamGuardAccount,
    items: List<InventoryItem>,
    wallet: WalletInfo,
    services: SteamServices,
    work: AppWork,
    close: () -> Unit,
    onSold: suspend () -> Unit,
) {
    val id = AccountStore.storageId(account)
    val item = items.first()
    val fees = remember(wallet) { MarketFees(wallet) }
    var receive by remember { mutableStateOf("") }
    var buyer by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var autoApprove by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf<MarketPrice?>(null) }
    var history by remember { mutableStateOf(emptyList<Double>()) }
    val unitPrice = receive.toIntOrNull()?.takeIf { it in 1..100_000_000 && fees.validPrice(it) == it }
    val maxQuantity = items.count { it.amount > 0 }.coerceAtMost(20)
    val count = quantity.toIntOrNull()?.takeIf { it in 1..maxQuantity }

    Panel {
        Heading(item.name)
        Text("Enter the unit price in your wallet's minor units (currency ${wallet.currency}).")
        Entry(stringResource(R.string.ava_common_refresh_67), !work.busy) {
            work.run {
                quote = services.market.price(item, wallet.currency)
                history = services.read(id) { services.market.priceHistory(it, item) }
                if (quote == null && history.isEmpty()) work.feedback = "Steam did not return price data."
            }
        }
        quote?.let { Text("Lowest ${it.lowest ?: "—"} · Median ${it.median ?: "—"}") }
        if (history.size > 1) PriceTrend(history)
        Field(stringResource(R.string.ava_market_you_receive), receive, number = true, enabled = !work.busy) {
            receive = it.filter(Char::isDigit).take(9)
            buyer = receive.toIntOrNull()?.takeIf { amount -> amount in 1..100_000_000 }
                ?.let { amount -> fees.buyerPays(amount, item.publisherFeePct).toString() }.orEmpty()
        }
        Field(stringResource(R.string.ava_market_buyer_pays), buyer, number = true, enabled = !work.busy) {
            buyer = it.filter(Char::isDigit).take(9)
            receive = buyer.toIntOrNull()?.takeIf { amount -> amount in 1..100_000_000 }
                ?.let { amount -> fees.receiveFromTotal(amount, item.publisherFeePct)?.toString() }.orEmpty()
        }
        Field("Quantity (up to $maxQuantity)", quantity, number = true, enabled = !work.busy) { quantity = it.take(2) }
        SwitchPreference(
            checked = autoApprove, onCheckedChange = { autoApprove = it },
            title = "Confirm these listings automatically",
            summary = "Requires an exact listing ID from Steam.",
            enabled = !work.busy && !account.identitySecret.isNullOrBlank(),
        )
        Primary(stringResource(R.string.ava_market_list_button), !work.busy && unitPrice != null && count != null) {
            val price = unitPrice ?: return@Primary
            val amount = count ?: return@Primary
            val total = fees.buyerPays(price, item.publisherFeePct)
            work.ask("List $amount item(s)?", "You receive $price and the buyer pays $total per item, in wallet minor units.", "List") {
                val current = services.session(id)
                if (autoApprove && !services.time.isAligned) services.time.align()
                var remaining = amount
                var confirmed = 0
                var pending = 0
                var failure: String? = null
                val ids = mutableSetOf<String>()
                for (asset in items.asSequence().filter { it.amount > 0 }.take(amount)) {
                    val result = try {
                        services.market.sell(current, asset, price)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failure = "Steam did not confirm the last request. Check your inventory and listings before retrying."
                        break
                    }
                    if (!result.success) {
                        failure = result.message ?: "Steam rejected a listing."
                        break
                    }
                    remaining--
                    if (result.confirmation) {
                        pending++
                        result.listingId?.let(ids::add)
                    }
                }
                if (autoApprove && ids.isNotEmpty()) {
                    try {
                        delay(600)
                        val matching = services.confirmations.fetch(current)
                            .filter { it.type == ConfirmationType.MARKET_LISTING && it.creatorId in ids }
                        for (confirmation in matching) {
                            if (services.confirmations.respond(current, confirmation, accept = true)) confirmed++
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The listing is already submitted. Keep manual approval available.
                    }
                }
                close()
                val summary = buildString {
                    append("Submitted ${amount - remaining} of $amount items.")
                    if (pending > confirmed) append(" Complete ${pending - confirmed} listing confirmations in the pending center.")
                    if (failure != null) append(" $failure")
                }
                work.feedback = summary
                try {
                    onSold()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    work.feedback = "$summary Refresh the inventory to see the latest status."
                }
            }
        }
        Entry("Back to inventory", !work.busy, close)
    }
}

@Composable
private fun PriceTrend(values: List<Double>) {
    val color = MiuixTheme.colorScheme.primary
    val low = values.min()
    val range = (values.max() - low).coerceAtLeast(0.001)
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        fun point(index: Int) = Offset(
            size.width * index / (values.size - 1),
            size.height * (1 - ((values[index] - low) / range).toFloat()),
        )
        for (index in 1 until values.size) drawLine(color, point(index - 1), point(index), strokeWidth = 2.dp.toPx())
    }
}
