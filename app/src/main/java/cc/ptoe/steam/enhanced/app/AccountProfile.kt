package cc.ptoe.steam.enhanced.app

import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount

/** Replace only display metadata, preserving the latest credentials and authenticator. */
internal fun SteamGuardAccount.withProfileFrom(profile: SteamGuardAccount): SteamGuardAccount = copy(
    personaName = profile.personaName,
    avatarUrl = profile.avatarUrl,
    animatedAvatarUrl = profile.animatedAvatarUrl,
    avatarFrameUrl = profile.avatarFrameUrl,
)
