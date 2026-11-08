package cc.ptoe.steam.enhanced.ui

import androidx.compose.ui.res.stringResource
import cc.ptoe.steam.enhanced.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.steam.enhanced.app.SteamServices
import cc.ptoe.steam.enhanced.core.model.Confirmation
import cc.ptoe.steam.enhanced.core.model.DeviceSessionList
import cc.ptoe.steam.enhanced.core.model.FamilyInvite
import cc.ptoe.steam.enhanced.core.model.FamilyUserState
import cc.ptoe.steam.enhanced.core.model.InviteChecks
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.core.model.TradeOffer
import cc.ptoe.steam.enhanced.core.model.TradeOfferState
import cc.ptoe.steam.enhanced.core.model.TradeOffersPage
import cc.ptoe.steam.enhanced.protocol.AuthSessionInfo
import cc.ptoe.steam.enhanced.protocol.QrChallenge
import cc.ptoe.steam.enhanced.store.AccountStore
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun PendingPage(account: SteamGuardAccount, services: SteamServices, work: AppWork) {
    val id = AccountStore.storageId(account)
    var tab by remember { mutableIntStateOf(0) }
    var confirmations by remember { mutableStateOf<List<Confirmation>?>(null) }
    var offers by remember { mutableStateOf<TradeOffersPage?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }

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
        val verb = if (accept) "Approve" else "Reject"
        work.ask("$verb ${items.size} confirmation(s)?",
            items.take(5).joinToString("\n") { it.headline.ifBlank { it.typeName } }, verb) {
            val current = services.session(id)
            if (items.size == 1) {
                check(services.confirmations.respond(current, items.single(), accept)) { "Steam declined the response." }
                work.feedback = "Confirmation updated."
            } else {
                val result = services.confirmations.respondMultiple(current, items, accept)
                work.feedback = "${result.ok} processed, ${result.failed} failed."
            }
            load(0)
        }
    }

    LaunchedEffect(tab) { work.run { load(tab) } }
    Heading(account.displayName)
    TabRow(listOf(stringResource(R.string.ava_pending_tab_confirmations), stringResource(R.string.ava_offers_seg_received), stringResource(R.string.ava_offers_seg_sent), stringResource(R.string.ava_offers_seg_history)), tab, { index ->
        if (!work.busy) {
            offers = null
            tab = index
        }
    })
    Entry(stringResource(R.string.ava_common_refresh), !work.busy) { work.run { load(tab) } }
    if (tab == 0) {
        val items = confirmations.orEmpty()
        if (confirmations != null && items.isEmpty()) Text(stringResource(R.string.ava_confirmations_empty))
        if (selected.isNotEmpty()) {
            val chosen = items.filter { it.id in selected }
            ActionPair("Approve selected", "Reject selected", !work.busy, { respond(chosen, true) }, { respond(chosen, false) })
        }
        for (item in items) Panel {
            Heading(item.headline.ifBlank { item.typeName.ifBlank { "Confirmation" } })
            item.summary.forEach { Text(it) }
            SwitchPreference(
                checked = item.id in selected,
                onCheckedChange = { checked -> selected = if (checked) selected + item.id else selected - item.id },
                title = "Select",
                enabled = !work.busy,
            )
            ActionPair("Approve", "Reject", !work.busy, { respond(listOf(item), true) }, { respond(listOf(item), false) })
        }
    } else {
        val list = when (tab) {
            1 -> offers?.received
            2 -> offers?.sent
            else -> offers?.let { (it.received + it.sent).sortedByDescending(TradeOffer::timeUpdated) }
        }
        if (list != null && list.isEmpty()) Text(stringResource(R.string.ava_offers_empty))
        for (offer in list.orEmpty()) TradePanel(offer, !work.busy && tab != 3) { action ->
            work.ask("$action offer?", "Review both sides of the trade before sending the response to Steam.", action) {
                val current = services.session(id)
                when (action) {
                    "Accept" -> {
                        val result = services.trades.accept(current, offer)
                        check(result.success) { result.message ?: "Steam declined the trade." }
                        if (result.needsMobileConfirmation) {
                            tab = 0
                            load(0)
                            work.feedback = "Complete the trade in Confirm."
                        } else {
                            load(tab)
                            work.feedback = "Trade accepted."
                        }
                    }
                    "Decline" -> {
                        check(services.trades.decline(current, offer.id)) { "Could not decline the offer." }
                        load(tab)
                    }
                    "Cancel" -> {
                        check(services.trades.cancel(current, offer.id)) { "Could not cancel the offer." }
                        load(tab)
                    }
                }
            }
        }
    }
}

@Composable
private fun TradePanel(offer: TradeOffer, enabled: Boolean, action: (String) -> Unit) = Panel {
    Heading("Trade with ${offer.partnerSteamId}")
    Text(offer.state.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
    if (offer.message.isNotBlank()) Text(offer.message)
    if (offer.isOneSidedGive) Text("You give items and receive nothing.")
    if (offer.isGift) Text("This offer gives you items without asking for any.")
    if (offer.escrowEndDate > 0) Text("This trade has an escrow hold.")
    Text(stringResource(R.string.ava_offer_give_label))
    if (offer.itemsToGive.isEmpty()) Text("No items")
    offer.itemsToGive.forEach { Text("${it.amount} × ${it.name.ifBlank { "Item ${it.assetId}" }}") }
    Text(stringResource(R.string.ava_market_you_receive))
    if (offer.itemsToReceive.isEmpty()) Text("No items")
    offer.itemsToReceive.forEach { Text("${it.amount} × ${it.name.ifBlank { "Item ${it.assetId}" }}") }
    if (offer.state == TradeOfferState.ACTIVE && offer.isOurOffer) {
        Entry(stringResource(R.string.ava_offer_cancel), enabled) { action("Cancel") }
    } else if (offer.state == TradeOfferState.ACTIVE) {
        ActionPair("Accept", "Decline", enabled, { action("Accept") }, { action("Decline") })
    }
}

@Composable
internal fun ApprovalPage(account: SteamGuardAccount, services: SteamServices, work: AppWork) {
    val id = AccountStore.storageId(account)
    var url by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf<List<AuthSessionInfo>?>(null) }
    var rememberSession by remember { mutableStateOf(false) }

    suspend fun load() {
        sessions = services.read(id) { current ->
            services.approval.pendingLoginClientIds(current).distinct().take(20).map {
                services.approval.sessionInfo(current, it)
            }
        }
    }

    LaunchedEffect(id) { work.run { load() } }
    Panel {
        Heading("Review a login request")
        Text("Scan the code from the Steam sign-in screen or paste its link.")
        QrInputButtons(!work.busy, work) { url = it }
        Field("Steam QR link", url, enabled = !work.busy) { url = it.take(1024) }
        Primary("Review request", url.isNotBlank() && !work.busy) {
            work.run {
                val challenge = QrChallenge.tryParse(url.trim()) ?: error("This is not a Steam login QR link.")
                val info = services.read(id) { services.approval.sessionInfo(it, challenge.clientId) }
                sessions = listOf(info.copy(version = challenge.version))
                url = ""
            }
        }
    }
    Entry(stringResource(R.string.ava_action_login_requests_51), !work.busy) { work.run { load() } }
    if (sessions?.isEmpty() == true) Text(stringResource(R.string.ava_login_no_pending))
    for (session in sessions.orEmpty()) Panel {
        Heading(session.deviceName.ifBlank { "Steam sign-in request" })
        if (session.location.isNotBlank()) Text(session.location)
        if (session.ip.isNotBlank()) Text(session.ip)
        SwitchPreference(
            checked = rememberSession, onCheckedChange = { rememberSession = it },
            title = "Keep that device signed in", enabled = !work.busy,
        )
        fun respond(approve: Boolean) {
            val verb = if (approve) "Approve" else "Deny"
            work.ask("$verb this sign-in?",
                if (approve) "Allow this device to access ${account.displayName}." else "Deny this Steam login request.", verb) {
                val current = services.session(id)
                val ok = services.approval.respondToSession(
                    current, session.version, session.clientId, approve, rememberSession,
                )
                check(ok) { "Steam did not accept the response." }
                sessions = sessions.orEmpty().filterNot { it.clientId == session.clientId }
                work.feedback = if (approve) "Sign-in approved." else "Sign-in denied."
            }
        }
        ActionPair("Approve", "Deny", !work.busy, { respond(true) }, { respond(false) })
    }
}

@Composable
internal fun DevicesPage(account: SteamGuardAccount, services: SteamServices, work: AppWork) {
    val id = AccountStore.storageId(account)
    var list by remember { mutableStateOf<DeviceSessionList?>(null) }

    suspend fun load() {
        list = services.read(id, services.devices::enumerate)
    }

    LaunchedEffect(id) { work.run { load() } }
    Entry(stringResource(R.string.ava_common_refresh_53), !work.busy) { work.run { load() } }
    val current = list ?: return
    if (current.devices.isEmpty()) Text("No device sessions.")
    for (device in current.devices) Panel {
        val name = device.description.ifBlank { "Steam device" }
        Heading(name)
        Text(
            if (current.isCurrent(device)) "Current device"
            else if (device.loggedIn) "Signed in" else "Signed out",
        )
        device.lastSeen?.locationLabel?.takeIf(String::isNotBlank)?.let { Text(it) }
        val timestamp = device.lastSeen?.time?.takeIf { it > 0 } ?: device.timeUpdated
        if (timestamp > 0) Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(timestamp * 1000)))
        if (!current.isCurrent(device) && current.requestingTokenId.isNotBlank()) {
            Entry(stringResource(R.string.ava_device_revoke_action), !work.busy && !account.sharedSecret.isNullOrBlank()) {
                work.ask("Sign out $name?", "Steam will revoke this device's session.", "Revoke") {
                    val authenticated = services.session(id)
                    val fresh = services.devices.enumerate(authenticated)
                    val selected = fresh.devices.firstOrNull { it.tokenId == device.tokenId }
                        ?: error("This session no longer exists.")
                    check(fresh.requestingTokenId.isNotBlank() && !fresh.isCurrent(selected)) { "The current device cannot be revoked." }
                    services.devices.revoke(authenticated, selected)
                    load()
                    work.feedback = "Session revoked."
                }
            }
        }
    }
}

@Composable
internal fun FamilyPage(account: SteamGuardAccount, services: SteamServices, work: AppWork) {
    val id = AccountStore.storageId(account)
    var family by remember { mutableStateOf<FamilyUserState?>(null) }
    var checks by remember { mutableStateOf<Pair<FamilyInvite, InviteChecks?>?>(null) }

    suspend fun load() {
        family = services.read(id) {
            val state = services.family.forUser(it)
            if (state.isMember && state.group == null) state.copy(group = services.family.groupInfo(it, state.familyGroupId))
            else state
        }
    }

    LaunchedEffect(id) { work.run { load() } }
    Entry(stringResource(R.string.ava_common_refresh_55), !work.busy) { work.run { load() } }
    val data = family ?: return
    val group = data.group
    if (group != null) Panel {
        Heading(group.name.ifBlank { "Steam Family" })
        Text("${group.members.size} of ${group.totalSlots} slots used")
        if (group.country.isNotBlank()) Text(group.country)
        if (group.slotCooldownRemainingSeconds > 0) Text("Slot cooldown: ${group.slotCooldownRemainingSeconds / 3600} h")
        group.members.forEach { Text("${it.steamId} · ${familyRole(it.role)}") }
    } else if (!data.isMember) Text("This account is not in a Steam Family.")
    if (data.cooldownSecondsRemaining > 0) Text("Account cooldown: ${data.cooldownSecondsRemaining / 3600} h")
    if (data.pendingInvites.isEmpty()) Text("No family invitations.")
    for (invite in data.pendingInvites) Panel {
        Heading("Invitation from ${invite.inviterSteamId}")
        Text(familyRole(invite.role))
        if (invite.awaiting2fa) Text("Open the pending center to complete Steam Guard confirmation.")
        else {
            val result = checks?.takeIf { it.first == invite }
            if (result == null) {
                Entry(stringResource(R.string.ava_fam_preflight_title), !work.busy) {
                    work.run { checks = invite to services.read(id) { services.family.inviteChecks(it, invite.familyGroupId) } }
                }
            } else {
                val eligibility = result.second
                Text(
                    if (eligibility == null) "Steam did not return eligibility details."
                    else "Wallet country match: ${eligibility.walletCountryMatches} · Network match: ${eligibility.ipMatch}",
                )
                if (eligibility != null && eligibility.joinRestriction != 0) Text("Steam restriction ${eligibility.joinRestriction}")
                Entry(stringResource(R.string.ava_fam_invite_join_hold), !work.busy && (eligibility?.joinRestriction ?: 0) == 0) {
                    work.ask("Join this Steam Family?", "Steam may apply a cooldown before you can join another family.", "Join") {
                        val joined = services.family.join(services.session(id), invite)
                        checks = null
                        load()
                        work.feedback = if (joined.needsTwoFactor) "Finish approval in the pending center." else "Family invitation accepted."
                    }
                }
            }
        }
    }
}

private fun familyRole(role: Int): String = when (role) {
    1 -> "Adult"
    2 -> "Child"
    else -> "Role $role"
}

@Composable
internal fun RedeemPage(account: SteamGuardAccount, services: SteamServices, work: AppWork) {
    val id = AccountStore.storageId(account)
    var key by remember { mutableStateOf("") }
    Panel {
        Heading(account.displayName)
        Text("Activation permanently adds the product to this account.")
        Field("Product key", key, secret = true, enabled = !work.busy) { key = it.take(128) }
        Primary(stringResource(R.string.ava_key_redeem_submit), key.isNotBlank() && !work.busy) {
            val submitted = key
            work.ask("Activate on ${account.displayName}?", "This key can only be used once.", "Activate") {
                val result = services.keys.redeem(services.session(id), submitted)
                if (result.success) key = ""
                work.feedback = if (result.success) {
                    result.products.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "Activated $it." } ?: "Product activated."
                } else {
                    val reason = result.error?.name?.replace('_', ' ')?.lowercase() ?: "unknown reason"
                    "Steam rejected the key: $reason."
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
