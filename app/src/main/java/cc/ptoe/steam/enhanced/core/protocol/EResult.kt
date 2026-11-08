package cc.ptoe.steam.enhanced.core.protocol

/**
 * Steam API result codes (`EResult`), generated from the canonical Steam enum
 * (identical values to SteamKit's `EResult.cs` / node-steam-user).
 *
 * Ported from AVA's `core/protocol/eresult.dart`. Do not hand-edit individual
 * entries; regenerate from the upstream enum.
 */
object EResult {
    const val OK: Int = 1
    const val FAIL: Int = 2
    const val INVALID_PASSWORD: Int = 5
    const val DUPLICATE_REQUEST: Int = 29
    const val ACCOUNT_LOCKED_DOWN: Int = 73
    const val RATE_LIMIT_EXCEEDED: Int = 84
    const val TWO_FACTOR_CODE_MISMATCH: Int = 88
    const val TWO_FACTOR_ACTIVATION_CODE_MISMATCH: Int = 89

    /** All known codes, keyed by their wire value. */
    val names: Map<Int, String> = mapOf(
        0 to "Invalid",
        1 to "OK",
        2 to "Fail",
        3 to "NoConnection",
        5 to "InvalidPassword",
        6 to "LoggedInElsewhere",
        7 to "InvalidProtocolVer",
        8 to "InvalidParam",
        9 to "FileNotFound",
        10 to "Busy",
        11 to "InvalidState",
        12 to "InvalidName",
        13 to "InvalidEmail",
        14 to "DuplicateName",
        15 to "AccessDenied",
        16 to "Timeout",
        17 to "Banned",
        18 to "AccountNotFound",
        19 to "InvalidSteamID",
        20 to "ServiceUnavailable",
        21 to "NotLoggedOn",
        22 to "Pending",
        23 to "EncryptionFailure",
        24 to "InsufficientPrivilege",
        25 to "LimitExceeded",
        26 to "Revoked",
        27 to "Expired",
        28 to "AlreadyRedeemed",
        29 to "DuplicateRequest",
        30 to "AlreadyOwned",
        31 to "IPNotFound",
        32 to "PersistFailed",
        33 to "LockingFailed",
        34 to "LogonSessionReplaced",
        35 to "ConnectFailed",
        36 to "HandshakeFailed",
        37 to "IOFailure",
        38 to "RemoteDisconnect",
        39 to "ShoppingCartNotFound",
        40 to "Blocked",
        41 to "Ignored",
        42 to "NoMatch",
        43 to "AccountDisabled",
        44 to "ServiceReadOnly",
        45 to "AccountNotFeatured",
        46 to "AdministratorOK",
        47 to "ContentVersion",
        48 to "TryAnotherCM",
        49 to "PasswordRequiredToKickSession",
        50 to "AlreadyLoggedInElsewhere",
        51 to "Suspended",
        52 to "Cancelled",
        53 to "DataCorruption",
        54 to "DiskFull",
        55 to "RemoteCallFailed",
        56 to "PasswordUnset",
        57 to "ExternalAccountUnlinked",
        58 to "PSNTicketInvalid",
        59 to "ExternalAccountAlreadyLinked",
        60 to "RemoteFileConflict",
        61 to "IllegalPassword",
        62 to "SameAsPreviousValue",
        63 to "AccountLogonDenied",
        64 to "CannotUseOldPassword",
        65 to "InvalidLoginAuthCode",
        66 to "AccountLogonDeniedNoMail",
        67 to "HardwareNotCapableOfIPT",
        68 to "IPTInitError",
        69 to "ParentalControlRestricted",
        70 to "FacebookQueryError",
        71 to "ExpiredLoginAuthCode",
        72 to "IPLoginRestrictionFailed",
        73 to "AccountLockedDown",
        74 to "AccountLogonDeniedVerifiedEmailRequired",
        75 to "NoMatchingURL",
        76 to "BadResponse",
        77 to "RequirePasswordReEntry",
        78 to "ValueOutOfRange",
        79 to "UnexpectedError",
        80 to "Disabled",
        81 to "InvalidCEGSubmission",
        82 to "RestrictedDevice",
        83 to "RegionLocked",
        84 to "RateLimitExceeded",
        85 to "AccountLoginDeniedNeedTwoFactor",
        86 to "ItemDeleted",
        87 to "AccountLoginDeniedThrottle",
        88 to "TwoFactorCodeMismatch",
        89 to "TwoFactorActivationCodeMismatch",
        90 to "AccountAssociatedToMultiplePartners",
        91 to "NotModified",
        92 to "NoMobileDevice",
        93 to "TimeNotSynced",
        94 to "SMSCodeFailed",
        95 to "AccountLimitExceeded",
        96 to "AccountActivityLimitExceeded",
        97 to "PhoneActivityLimitExceeded",
        98 to "RefundToWallet",
        99 to "EmailSendFailure",
        100 to "NotSettled",
        101 to "NeedCaptcha",
        102 to "GSLTDenied",
        103 to "GSOwnerDenied",
        104 to "InvalidItemType",
        105 to "IPBanned",
        106 to "GSLTExpired",
        107 to "InsufficientFunds",
        108 to "TooManyPending",
        109 to "NoSiteLicensesFound",
        110 to "WGNetworkSendExceeded",
        111 to "AccountNotFriends",
        112 to "LimitedUserAccount",
        113 to "CantRemoveItem",
        114 to "AccountDeleted",
        115 to "ExistingUserCancelledLicense",
        116 to "CommunityCooldown",
        117 to "NoLauncherSpecified",
        118 to "MustAgreeToSSA",
        119 to "LauncherMigrated",
        120 to "SteamRealmMismatch",
        121 to "InvalidSignature",
        122 to "ParseFailure",
        123 to "NoVerifiedPhone",
        124 to "InsufficientBattery",
        125 to "ChargerRequired",
        126 to "CachedCredentialInvalid",
        127 to "PhoneNumberIsVOIP",
        128 to "NotSupported",
        129 to "FamilySizeLimitExceeded",    )

    /** The name for [code], or null when the code is unknown to this table. */
    fun nameOf(code: Int): String? = names[code]

    /**
     * Returns a readable label like `AccountLockedDown (73)` for a known code, or
     * `EResult <n>` when the code is unknown.
     */
    fun label(code: Int): String {
        val name = names[code]
        return if (name != null) "$name ($code)" else "EResult $code"
    }
}
