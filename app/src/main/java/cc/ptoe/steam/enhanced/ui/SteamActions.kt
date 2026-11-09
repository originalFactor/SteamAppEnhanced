package cc.ptoe.steam.enhanced.ui

import androidx.compose.ui.res.stringResource
import cc.ptoe.steam.enhanced.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.ptoe.steam.enhanced.app.SteamServices
import cc.ptoe.steam.enhanced.core.model.Confirmation
import cc.ptoe.steam.enhanced.core.model.FamilyInvite
import cc.ptoe.steam.enhanced.core.model.FamilyPersona
import cc.ptoe.steam.enhanced.core.model.FamilyUserState
import cc.ptoe.steam.enhanced.core.model.InviteChecks
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.core.model.TradeOffer
import cc.ptoe.steam.enhanced.core.model.TradeOfferState
import cc.ptoe.steam.enhanced.core.model.TradeOffersPage
import cc.ptoe.steam.enhanced.store.AccountStore
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun PendingPage(account: SteamGuardAccount, services: SteamServices, work: AppWork, modifier: Modifier = Modifier) {
    val id = AccountStore.storageId(account)
    var tab by remember { mutableIntStateOf(0) }
    var confirmations by remember { mutableStateOf<List<Confirmation>?>(null) }
    var offers by remember { mutableStateOf<TradeOffersPage?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var isRefreshing by remember { mutableStateOf(false) }

    suspend fun load(index: Int) {
        if (index == 0) {
            if (!services.time.isAligned) services.time.align()
            confirmations = services.read(id, services.confirmations::fetch)
            selected = selected.intersect(confirmations.orEmpty().map { it.id }.toSet())
        } else {
            offers = services.read(id) { services.trades.fetch(it, historical = index == 3) }
        }
    }

    fun respond(items: List<Confirmation>, accept: Boolean) {
        val verb = if (accept) work.text(R.string.msg_approve) else work.text(R.string.msg_reject)
        work.ask(work.text(if (accept) R.string.msg_approve_count else R.string.msg_reject_count, items.size),
            items.take(5).joinToString("\n") { it.headline.ifBlank { it.typeName } }, verb) {
            val current = services.session(id)
            if (items.size == 1) {
                checkUi(services.confirmations.respond(current, items.single(), accept)) { work.text(R.string.msg_confirmation_declined) }
                work.feedback = work.text(R.string.msg_confirmation_updated)
            } else {
                val result = services.confirmations.respondMultiple(current, items, accept)
                work.feedback = work.text(R.string.msg_processed_count, result.ok, result.failed)
            }
            load(0)
        }
    }

    fun refresh() {
        work.run {
            isRefreshing = true
            try { load(tab) } finally { isRefreshing = false }
        }
    }

    LaunchedEffect(id, tab) { refresh() }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TabRow(listOf(stringResource(R.string.ava_pending_tab_confirmations), stringResource(R.string.ava_offers_seg_received), stringResource(R.string.ava_offers_seg_sent), stringResource(R.string.ava_offers_seg_history)), tab, { index ->
            if (!work.busy) {
                offers = null
                tab = index
            }
        })
        PullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = ::refresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            refreshTexts = listOf(
                stringResource(R.string.ui_pull_refresh), stringResource(R.string.ui_release_refresh),
                stringResource(R.string.ui_refreshing), stringResource(R.string.ui_refresh_finished),
            ),
        ) {
            Column(
                Modifier.overScrollVertical().fillMaxSize().verticalScroll(rememberScrollState(), overscrollEffect = null).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                work.feedback?.let { Text(it, style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2) }
                if (tab == 0) {
                    val items = confirmations.orEmpty()
                    if (confirmations != null && items.isEmpty()) Text(stringResource(R.string.ava_confirmations_empty))
                    if (selected.isNotEmpty()) {
                        val chosen = items.filter { it.id in selected }
                        ActionPair(work.text(R.string.msg_approve_selected), work.text(R.string.msg_reject_selected), !work.busy, { respond(chosen, true) }, { respond(chosen, false) })
                    }
                    for (item in items) Panel {
                        Heading(item.headline.ifBlank { item.typeName.ifBlank { work.text(R.string.msg_confirmation) } })
                        item.summary.forEach { Text(it) }
                        SwitchPreference(
                            checked = item.id in selected,
                            onCheckedChange = { checked -> selected = if (checked) selected + item.id else selected - item.id },
                            title = work.text(R.string.msg_select),
                            enabled = !work.busy,
                        )
                        ActionPair(work.text(R.string.msg_approve), work.text(R.string.msg_reject), !work.busy, { respond(listOf(item), true) }, { respond(listOf(item), false) })
                    }
                } else {
                    val list = when (tab) {
                        1 -> offers?.received
                        2 -> offers?.sent
                        else -> offers?.let { (it.received + it.sent).sortedByDescending(TradeOffer::timeUpdated) }
                    }
                    if (list != null && list.isEmpty()) Text(stringResource(R.string.ava_offers_empty))
                    for (offer in list.orEmpty()) TradePanel(offer, work, !work.busy && tab != 3) { action ->
                        work.ask(work.text(action.question), work.text(R.string.msg_trade_review), work.text(action.label)) {
                            val current = services.session(id)
                            when (action) {
                                TradeAction.ACCEPT -> {
                                    val result = services.trades.accept(current, offer)
                                    checkUi(result.success) { result.message ?: work.text(R.string.msg_trade_declined) }
                                    if (result.needsMobileConfirmation) {
                                        tab = 0
                                        load(0)
                                        work.feedback = work.text(R.string.msg_trade_needs_confirmation)
                                    } else {
                                        load(tab)
                                        work.feedback = work.text(R.string.msg_trade_accepted)
                                    }
                                }
                                TradeAction.DECLINE -> {
                                    checkUi(services.trades.decline(current, offer.id)) { work.text(R.string.msg_trade_decline_failed) }
                                    load(tab)
                                }
                                TradeAction.CANCEL -> {
                                    checkUi(services.trades.cancel(current, offer.id)) { work.text(R.string.msg_trade_cancel_failed) }
                                    load(tab)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TradePanel(offer: TradeOffer, work: AppWork, enabled: Boolean, action: (TradeAction) -> Unit) = Panel {
    Heading(work.text(R.string.msg_trade_partner, offer.partnerSteamId.toString()))
    Text(work.text(tradeStateLabel(offer.state)))
    if (offer.message.isNotBlank()) Text(offer.message)
    if (offer.isOneSidedGive) Text(work.text(R.string.msg_trade_one_sided))
    if (offer.isGift) Text(work.text(R.string.msg_trade_gift))
    if (offer.escrowEndDate > 0) Text(work.text(R.string.msg_trade_hold))
    Text(stringResource(R.string.ava_offer_give_label))
    if (offer.itemsToGive.isEmpty()) Text(work.text(R.string.msg_no_items))
    offer.itemsToGive.forEach { Text("${it.amount} × ${it.name.ifBlank { work.text(R.string.msg_item_id, it.assetId) }}") }
    Text(stringResource(R.string.ava_market_you_receive))
    if (offer.itemsToReceive.isEmpty()) Text(work.text(R.string.msg_no_items))
    offer.itemsToReceive.forEach { Text("${it.amount} × ${it.name.ifBlank { work.text(R.string.msg_item_id, it.assetId) }}") }
    if (offer.state == TradeOfferState.ACTIVE && offer.isOurOffer) {
        Entry(stringResource(R.string.ava_offer_cancel), enabled) { action(TradeAction.CANCEL) }
    } else if (offer.state == TradeOfferState.ACTIVE) {
        ActionPair(work.text(R.string.msg_accept), work.text(R.string.msg_decline), enabled, { action(TradeAction.ACCEPT) }, { action(TradeAction.DECLINE) })
    }
}

@Composable
internal fun FamilyPage(
    account: SteamGuardAccount,
    services: SteamServices,
    work: AppWork,
    scrollBehavior: ScrollBehavior? = null,
    knownAccounts: List<SteamGuardAccount> = listOf(account),
) {
    val id = AccountStore.storageId(account)
    var family by remember { mutableStateOf<FamilyUserState?>(null) }
    var profiles by remember(id) { mutableStateOf<Map<Long, FamilyPersona>>(emptyMap()) }
    var checks by remember { mutableStateOf<Pair<FamilyInvite, InviteChecks?>?>(null) }

    suspend fun load() {
        val updated = services.read(id) {
            val state = services.family.forUser(it)
            if (state.isMember && state.group == null) state.copy(group = services.family.groupInfo(it, state.familyGroupId))
            else state
        }
        family = updated
        val steamIds = updated.group?.members.orEmpty().map { it.steamId } + updated.pendingInvites.map { it.inviterSteamId }
        // Keep resolved profile fields if an optional public-profile lookup fails.
        profiles = profiles.filterKeys { it in steamIds } + services.family.memberProfiles(steamIds).mapValues { (steamId, fresh) ->
            FamilyPersona(fresh.nickname ?: profiles[steamId]?.nickname, fresh.avatarUrl ?: profiles[steamId]?.avatarUrl)
        }
    }

    RefreshableColumn(work, onRefresh = { checks = null; load() }, scrollBehavior = scrollBehavior, initialRefresh = true, refreshKey = id) {
        val data = family ?: return@RefreshableColumn
        val group = data.group
        if (group != null) {
            Panel {
                Heading(group.name.ifBlank { work.text(R.string.msg_family_name) })
                Text(work.text(R.string.msg_family_slots, group.members.size, group.totalSlots))
                if (group.country.isNotBlank()) Text(group.country)
                if (group.slotCooldownRemainingSeconds > 0) Text(work.text(R.string.msg_family_slot_cooldown, group.slotCooldownRemainingSeconds / 3600))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                group.members.forEach { member ->
                    val local = knownAccounts.firstOrNull { it.steamId == member.steamId }
                    val profile = profiles[member.steamId]
                    FamilyMemberCard(
                        nickname = profile?.nickname ?: local?.personaName?.takeIf(String::isNotBlank) ?: member.steamId.toString(),
                        username = local?.accountName?.takeIf(String::isNotBlank),
                        avatarUrl = profile?.avatarUrl ?: local?.avatarUrl,
                        role = familyRole(member.role, work),
                    )
                }
            }
        } else if (!data.isMember) Text(work.text(R.string.msg_family_not_member))
        if (data.cooldownSecondsRemaining > 0) Text(work.text(R.string.msg_family_account_cooldown, data.cooldownSecondsRemaining / 3600))
        if (data.pendingInvites.isEmpty()) Text(work.text(R.string.msg_family_no_invites))
        for (invite in data.pendingInvites) Panel {
            Heading(work.text(R.string.msg_family_inviter, profiles[invite.inviterSteamId]?.nickname ?: invite.inviterSteamId.toString()))
            Text(familyRole(invite.role, work))
            if (invite.awaiting2fa) Text(work.text(R.string.msg_family_pending_approval))
            else {
                val result = checks?.takeIf { it.first == invite }
                if (result == null) {
                    Entry(stringResource(R.string.ava_fam_preflight_title), !work.busy) {
                        work.run { checks = invite to services.read(id) { services.family.inviteChecks(it, invite.familyGroupId) } }
                    }
                } else {
                    val eligibility = result.second
                    Text(
                        if (eligibility == null) work.text(R.string.msg_family_no_eligibility)
                        else work.text(R.string.msg_family_eligibility, work.text(if (eligibility.walletCountryMatches) R.string.msg_yes else R.string.msg_no), work.text(if (eligibility.ipMatch) R.string.msg_yes else R.string.msg_no)),
                    )
                    if (eligibility != null && eligibility.joinRestriction != 0) Text(work.text(R.string.msg_family_restriction, eligibility.joinRestriction))
                    Entry(stringResource(R.string.ava_fam_invite_join_hold), !work.busy && (eligibility?.joinRestriction ?: 0) == 0) {
                        work.ask(work.text(R.string.msg_family_join_question), work.text(R.string.msg_family_cooldown_warning), work.text(R.string.msg_join)) {
                            val joined = services.family.join(services.session(id), invite)
                            checks = null
                            load()
                            work.feedback = if (joined.needsTwoFactor) work.text(R.string.msg_family_finish_approval) else work.text(R.string.msg_family_invite_accepted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FamilyMemberCard(nickname: String, username: String?, avatarUrl: String?, role: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SteamAvatar(avatarUrl = avatarUrl)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(nickname, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                username?.let {
                    Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(role, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

private fun familyRole(role: Int, work: AppWork): String = when (role) {
    1 -> work.text(R.string.msg_family_adult)
    2 -> work.text(R.string.msg_family_child)
    else -> work.text(R.string.msg_family_role, role)
}

@Composable
internal fun RedeemPage(account: SteamGuardAccount, services: SteamServices, work: AppWork) {
    val id = AccountStore.storageId(account)
    var key by remember { mutableStateOf("") }
    Panel {
        Heading(account.displayName)
        Text(work.text(R.string.msg_product_warning))
        Field(work.text(R.string.msg_product_key), key, secret = true, enabled = !work.busy) { key = it.take(128) }
        Primary(stringResource(R.string.ava_key_redeem_submit), key.isNotBlank() && !work.busy) {
            val submitted = key
            work.ask(work.text(R.string.msg_product_activate_question, account.displayName), work.text(R.string.msg_product_once), work.text(R.string.msg_activate)) {
                val result = services.keys.redeem(services.session(id), submitted)
                if (result.success) key = ""
                work.feedback = if (result.success) {
                    result.products.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { work.text(R.string.msg_product_names_activated, it) } ?: work.text(R.string.msg_product_activated)
                } else {
                    val reason = work.text(keyErrorLabel(result.error))
                    work.text(R.string.msg_product_rejected, reason, result.detail)
                }
            }
        }
    }
}

@Composable
private fun ActionPair(
    first: String,
    second: String,
    enabled: Boolean,
    onFirst: () -> Unit,
    onSecond: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(first, onFirst, Modifier.weight(1f), enabled = enabled)
        TextButton(second, onSecond, Modifier.weight(1f), enabled = enabled)
    }
}
