# AVA → Steam Enhanced 移植说明

本项目是 Kotlin + Compose + Miuix 的 Android 应用。本文记录移植范围、源码进度和验证结果。参考项目为 Flutter 实现的 [AnotherVaporAuth (AVA)](https://github.com/freefrank/AnotherVaporAuth)。本次核对的 AVA 源码为 `b53fcd4`，移植源码与复用翻译的许可见 [AVA-LICENSE](AVA-LICENSE)。

已确认的范围

- 只搬 Android 相关能力，**桌面端全部跳过**（window_manager / portable_library / 安装器 / AppImage / 便携模式开关）。
- 第一阶段只落地**核心层 + 功能文档**，UI 后续分批。

---

## 1. 功能清单

### 1.1 Android 接入进度（2026-11-08）

Android 入口已接入账户库、Steam 服务和 Miuix 业务页。底层位置见下方清单，验证结果见[第 5 节](#5-验证记录)。

| 范围 | 应用中的入口与行为 |
|---|---|
| 账户与验证码 | 首页可导入单个或多份 maFile，选择 SDA 的 `manifest.json` 和加密账户文件批量解密，展示和复制 Steam Guard 验证码。多份缺少 SteamID 的离线账户使用各自的本地标识。管理页可导出 maFile、刷新头像或移除本地账户。 |
| 保险库 | 首次启动设置六位 PIN。Android Keystore 的 AES-GCM 主密钥保护 PIN 包裹记录，账户文件使用独立随机 DEK 加密。支持锁定、改 PIN 和 Android 11 及以上设备的生物识别或系统凭据解锁。 |
| Steam 登录与验证器 | 密码、Steam Guard 和 QR 登录已接入。访问令牌自动刷新，保存密码后可使用自身 TOTP 自动重登。支持添加、激活和转移验证器，转移后的秘密会立即存储，保存失败时可导出恢复副本。 |
| 待办与账户操作 | 可处理待批准登录、扫码批准、单条或批量确认、交易报价、设备会话、家庭组邀请和产品密钥。交易、转移、兑换及其他敏感操作有确认步骤。 |
| 库存与市场 | 按游戏和上下文浏览库存，支持分页、物品分组、参考价格和走势、按 Steam 钱包费率报价、批量上架及撤销。自动确认仅处理能用 Steam 返回的挂单 ID 精确匹配的确认。 |
| WebDAV | 支持手动同步和打开保险库时的定期同步。HTTPS 传输，账户快照在本地加密，使用 ETag 条件写入、逐账户合并、删除标记和冲突选择。被替换的账户版本可从本地加密记录恢复。 |
| 界面与诊断 | 支持跟随系统或切换浅色与深色，另有 Steam、Neon、Pixel 三套配色。主要界面标签覆盖七种语言。账户管理页支持 Steam 动态头像及 APNG 边框，设置页提供入门指引和可复制的脱敏日志。 |

| # | 功能 | AVA 源 | 本项目位置 |
|---|---|---|---|
| 1 | Steam Guard 验证码（30s TOTP，26 字符表） | `core/steam_totp.dart` | `core/SteamTotp.kt` |
| 2 | 确认哈希（HMAC-SHA1，时间+tag） | 同上 | 同上 |
| 3 | 确定性 device_id（`android:<uuid>`） | 同上 | 同上 |
| 4 | maFile 加解密（PBKDF2 50k/SHA1 + AES-256-CBC/PKCS7，字节级兼容 SDA） | `core/crypto/ma_file_crypto.dart` | `core/crypto/MaFileCrypto.kt` |
| 5 | 本地库加密（随机 256 位 DEK + AES-256-GCM，PIN 只包一层） | `core/crypto/vault_crypto.dart` | `core/crypto/VaultCrypto.kt` |
| 6 | Steam 密码 RSA 加密（PKCS#1 v1.5） | `core/crypto/steam_rsa.dart` | `core/crypto/SteamRsa.kt` |
| 7 | CSPRNG 封装（salt/IV/DEK/sessionid） | `core/crypto/secure_random.dart` | `core/crypto/SecureRandomBytes.kt` |
| 8 | 极简 protobuf wire 编解码（varint/fixed64/length-delimited） | `core/proto/protobuf_wire.dart` | `core/proto/ProtobufWire.kt` |
| 9 | EResult 映射（129 项）和可读标签 | `core/protocol/eresult.dart` | `core/protocol/EResult.kt` |
| 10 | JWT payload 解码（读 exp/sub，不验签） | `core/jwt.dart` | `core/Jwt.kt` |
| 11 | JSON 宽松取整（int/double/string 混用兼容） | `core/json_coerce.dart` | `core/JsonCoerce.kt` |
| 12 | 数据模型：账户 / manifest / session / 确认 / 报价 / 设备 / 家庭组 / 物品 | `core/models/*` | `core/model/*` |
| 13 | maFile 归一化（Steam++/Watt 变体、base32 secret、无 SteamID 导出） | `core/ma_file_normalizer.dart` | `core/MaFileNormalizer.kt` |
| 14 | SDA 批量解析和解密（接收文件内容及 manifest） | `core/sda_import.dart` | `core/SdaImport.kt` |
| 15 | 内存调试日志和密钥脱敏 | `services/debug_log.dart` | `core/DebugLog.kt` |
| 16 | Steam HTTP 客户端（api protobuf + community/store JSON 双传输） | `services/steam_api_client.dart` | `net/SteamApiClient.kt` |
| 17 | 异常分层（SteamApi / CommunityAuth / MissingAccessToken） | 同上 | `net/SteamExceptions.kt` |
| 18 | Steam 服务器校时 | `services/steam_time.dart` | `net/SteamTime.kt` |
| 19 | 共享会话管道（cookie 组装、access token 守卫） | `core/protocol/community_session.dart` | `net/CommunitySession.kt` |
| 20 | 密码及扫码登录会话（发起、提交 Steam Guard 码、轮询和提取令牌） | `core/protocol/steam_auth_session.dart` | `protocol/SteamAuthSession.kt` |
| 21 | 会话刷新（refresh_token → access_token） | `services/session_manager.dart` | `protocol/SessionManager.kt` |
| 22 | 无界面重登（存密码 + 自身 TOTP） | `services/auto_login.dart` | `protocol/AutoLogin.kt` |
| 23 | 确认列表 / 单条 / 批量处理（mobileconf） | `core/protocol/confirmations_client.dart` | `protocol/ConfirmationsClient.kt` |
| 24 | 交易报价（列表 / 接受 / 拒绝 / 取消） | `core/protocol/trade_offers_client.dart` | `protocol/TradeOffersClient.kt` |
| 25 | 设备会话（列举 / 远程注销，含逆向出的签名方案） | `core/protocol/sessions_client.dart` | `protocol/SessionsClient.kt` |
| 26 | 扫码批准登录（方向 B，含 HMAC-SHA256 签名） | `core/protocol/qr_approval_client.dart` | `protocol/QrApprovalClient.kt` |
| 27 | 家庭组（只读信息 / 邀请 / 加入） | `core/protocol/family_groups_client.dart` | `protocol/FamilyGroupsClient.kt` |
| 28 | 产品密钥兑换（store ajax + 拒绝原因映射） | `core/protocol/store_key_client.dart` | `protocol/StoreKeyClient.kt` |
| 29 | 验证器添加 / 迁移（AddAuthenticator / Remove…ViaChallenge） | `core/protocol/authenticator_linker.dart` | `protocol/AuthenticatorLinker.kt` |
| 30 | 存储抽象 + 原子写 | `services/storage_provider.dart` | `store/StorageProvider.kt` |
| 31 | `maFiles/` 加密库（vault + 旧格式 + 两阶段原子提交） | `services/account_store.dart` | `store/AccountStore.kt` |
| 32 | PIN 包裹 DEK，使用 Android Keystore 主密钥加密记录 | `services/vault_key_store.dart` | `store/VaultKeyStore.kt` |

### 1.2 Android 实现与 AVA 的差异

- WebDAV 使用 `steam-enhanced.sync.v1.json`，要求现有 HTTPS 目录、强 ETag 和条件写入。该格式与 AVA 的远端目录不互通。Steam 会话和密码留在本机，同步只包含已知账户字段。AVA 的 maFile 仍可通过本地导入迁移。
- 七种语言覆盖主要导航和按钮。部分流程提示和诊断消息仍使用英语。外观用 Miuix 配色实现，入门指南使用首页提示。
- QR 批准可打开相机拍摄、选择图片或粘贴 Steam 链接。市场批量上架按资产逐件提交，无法精确识别的确认留给用户在待办中心处理。

### 1.3 本期刻意省略的 AVA 模块

以下几块依赖 AVA 自己的后端或桌面平台，本项目既不需要也不应照搬：

- `services/entitlement_store.dart`、`pro_actions.dart`、`paywall_screen.dart`、`play_channel.dart` —— AVA Pro 权益（Ed25519 令牌校验 + Cloudflare worker），本项目无商业化。
- `infra/`（Cloudflare 权益/反馈 worker）、`tool/`（发布脚本）—— 与客户端无关。
- `services/window_service.dart`、`portable_library.dart`、`installer/` —— 桌面端。
- `services/feedback_service.dart`、`update_service.dart`、`image_disk_cache.dart` —— 走 AVA 自有服务或桌面路径，后续 UI 批次再按需重写。

---

## 2. 关键实现细节（移植时的坑）

### 2.1 验证码与签名

```
code_chars = "23456789BCDFGHJKMNPQRTVWXY"   // 26 字符，非标准 base32 表
counter    = serverTime / 30
hmac       = HMAC-SHA1(base64decode(shared_secret), bigEndian64(counter))
offset     = hmac[19] & 0x0F
full       = ((hmac[offset] & 0x7F) << 24) | ... // 取 4 字节，掩掉最高位
code       = 取 5 次 full % 26，每次 full /= 26
```

确认哈希：`HMAC-SHA1(identity_secret, bigEndian64(time) + tag[0..32))`，base64。
Steam 对 accept/deny 的签名 tag 用的是 `allow` / `cancel`，**不是** accept/reject。

两处签名易混淆，必须区分：

- QR 批准：`HMAC-SHA256(shared_secret, le16(version) + le64(client_id) + le64(steamid))`
- 设备注销：`HMAC-SHA256(shared_secret, ASCII(token_id 的十进制))`

### 2.2 时间必须用 Steam 服务器时间

`SteamTime.align()` 调用 `ITwoFactorService/QueryTime`，用 `server_time` 与本地时间计算偏移。确认签名和自动重登读取 `currentSteamTime()`。应用在保险库解锁后校时，并在前台定期更新偏移。未成功校时时使用设备时间。

### 2.3 protobuf 字段类型陷阱

同一份数据里 `steamid` 有时是 **fixed64**（写 8 字节小端），有时是 uint64 varint，取决于具体消息。AVA 的字段编号都在注释里标了，移植时逐字段对照：

| 消息 | 字段 | 类型 |
|---|---|---|
| `BeginAuthSessionViaCredentials` | 1 friendly/2 account/3 encrypted_pwd/4 timestamp/5 remember/6 platform/7 persistence/9 device/8 guard_data | 混合 |
| `UpdateAuthSessionWithSteamGuardCode` | 1 client_id(varint) / 2 steamid(**fixed64**) / 3 code / 4 code_type | 混合 |
| `AddAuthenticator` | 1 steamid(**fixed64**) / 2 time / 4 type / 5 device_id / 6 sms_phone_id / 8 version | 混合 |
| `GetFamilyGroupForUser` | 1 steamid(**varint**) | varint |

另一半陷阱是 **`optional bool success`**：Steam 常常 eresult=OK 但 body 空（success 字段根本不写）。把「字段缺失」当成 false 会把成功的调用当失败——在「迁移验证器」这种不可逆流程里会直接锁死账号。判定原则：**eresult 是权威信号**，只有 payload 里显式的失败证据才能覆盖它。

### 2.4 GET vs POST

`bConstMethod=true` 的方法必须用 GET，POST 会被 `api.steampowered.com` 以 HTTP 405 拒绝。已确认必须 GET 的：`GetPasswordRSAPublicKey`、`GetAuthSessionsForAccount`、`GetFamilyGroupForUser`、`GetFamilyGroup`、`GetInviteCheckResults`。
反之 `EnumerateTokens`、`RevokeRefreshToken` 不是 const 方法，必须 POST。

### 2.5 响应判定

- protobuf 调用：读 `x-eresult` 响应头，`!= 1` 即失败；没有该头时只接受 2xx，**裸 401/403 必须当会话失效**（不能当成「空 body 解析成功」）。
- community JSON：401/403 或重定向到 login 页 → 会话失效；`success != true` 也不能一律当失败（不同端点语义不同）。

### 2.6 存储安全

- maFile 加密参数（salt/iv）在 `manifest.json` 条目里，不在文件内 —— 加密 maFile 单独拿出没有任何工具能解开。
- 更新已加密账户时写到**新文件名**（`<id>.maFile` ↔ `<id>.b.maFile`），让 manifest 成为唯一原子提交点；否则崩溃会留下「manifest 的 salt/iv 对不上文件内容」的永久损坏。
- 改密钥是两阶段：先全量写新文件 → 一次性提交 manifest → 最后删旧文件。
- `filename` 来自可篡改的 manifest，拼接前必须校验（拒绝 `..`、绝对路径、含分隔符、控制字符）。

### 2.7 本地库加密与 Keystore 接入

`VaultCrypto` 用随机 256 位 DEK 和 AES-256-GCM 加密账户文件（`nonce(12) || ct || tag(16)`），用 PIN 派生的 KEK 包裹 DEK。`VaultKeyStore` 将盐、迭代次数和包裹结果写成一条记录。当前 PIN KDF 的迭代次数为 1，设计依赖 Keystore 主密钥保护该记录。应用用 Android Keystore 中不可导出的 AES 密钥加密这条记录，账户数据位于应用私有目录。

### 2.8 兑换密钥不可逆

激活成功的 key 无法撤销。`StoreKeyClient` 已用 `success == 1` 判定成功，即使响应的 `purchase_result_details` 非零。兑换页在提交前要求长按确认。默认 Steam HTTP 客户端关闭连接失败自动重试。

---

## 3. 目录映射

AVA（Flutter/Dart）：

```
lib/src/core/      纯 Dart，无 Flutter：crypto / proto / models / protocol / TOTP
lib/src/services/  存储、API 客户端、会话管理
lib/src/app/       Riverpod providers、设置
lib/src/ui/        Material 3 界面
```

本项目（Kotlin）：

```
app/src/main/java/cc/ptoe/steam/enhanced/
  core/            纯 Kotlin / JVM，无 Android 依赖，可跑单元测试
    crypto/        MaFileCrypto, VaultCrypto, SteamRsa, SecureRandom
    proto/         ProtobufWire（ProtoReader / ProtoWriter）
    model/         SteamGuardAccount, Manifest, SessionData, Confirmation, ...
    protocol/      EResult
    SteamTotp.kt, Jwt.kt, JsonCoerce.kt, DebugLog.kt, MaFileNormalizer.kt, SdaImport.kt
  net/             SteamApiClient, SteamTime（OkHttp）
  protocol/        各 Steam 服务客户端（依赖 net）
  store/           存储抽象、AccountStore、VaultKeyStore（依赖 Android SharedPreferences）
  app/             Android 账户库接入、设置、Steam 会话和系统凭据解锁
  sync/            WebDAV 传输、加密快照、冲突合并和恢复
  ui/              Compose / Miuix 业务页与主题
```

`core/` 无 Android 依赖，可直接运行 JVM 单测。`net/` 用 OkHttp 封装传输，`protocol/` 实现各 Steam 服务的协议。`store/` 负责账户和密钥记录，`app/` 和 `ui/` 将底层操作接入 Android 生命周期与用户操作。

---

## 4. 测试策略

AVA 的测试向量直接复用（跨实现校验过，不依赖 Flutter）：

| 用例 | 期望值 |
|---|---|
| `generateAuthCode(sharedSecret, 1700000000)` | `R87JJ` |
| `generateAuthCode(sharedSecret, 1634000000)` | `D6C35` |
| `generateConfirmationHash(1700000000, "conf", identitySecret)` | `lARGXtefNbogvcyP7DZJI0+XBYQ=` |
| `generateConfirmationHash(1700000000, "allow", identitySecret)` | `v53H1MfBVFOCKLLTFJpiE7RCHWY=` |
| PBKDF2 RFC 6070（c=4096, dkLen=20） | `4b007901b765489abead49d926f721d065a429c1` |
| PBKDF2 RFC 6070（c=1, dkLen=20） | `0c60c80f961f0e71f3a9b524af6012062fe037a6` |

其中 `sharedSecret = base64("12345678901234567890")`，`identitySecret = base64("abcdefghijklmnopqrst")`。

现有单测也覆盖 AES-GCM 往返和篡改检测、protobuf 往返与截断校验、maFile 加密往返与错误密码、SDA 加密 bundle 解析和 Steam 源白名单。

---

## 5. 验证记录

2026-11-08 使用 Gradle 9.9.0 执行 `:app:assembleDebug` 与 `:app:testDebugUnitTest`。Debug APK 构建成功，**151 项 JVM 单测全部通过**。新增覆盖离线账户隔离、WebDAV 三方合并与删除冲突、库存解析和市场费率。

Android 37 模拟器使用合成测试数据验证了首次创建保险库、手动锁定、错误 PIN 拒绝、正确 PIN 解锁、导入两份无 SteamID 的 maFile、各自显示验证码，以及进程重启后重新解锁并读回两份账户。

HTTP 底层单测使用 MockWebServer。真实 Steam 登录、验证器转移、市场操作、外部 WebDAV 服务器、系统凭据解锁和动态头像未做端到端联调。

| 测试类 | 覆盖 |
|---|---|
| `SteamTotpTest` | 两个 AVA 参考向量、窗口稳定性、确认哈希向量、标签截断、device_id 稳定性 |
| `MaFileCryptoTest` | RFC 6070 两组向量、加解密往返、错密码返回 null、Unicode、批处理容错 |
| `VaultCryptoTest` | DEK 包裹/解包、错 PIN/错 salt/篡改 blob、blob 布局、旧迭代次数兼容 |
| `ProtobufWireTest` | varint/fixed64/嵌套/打包往返、无符号 ID 上界（2^64 − 1）、截断/越界/超长 varint/未知 wire type |
| `ModelsTest` | maFile 全字段往返、未知键保留、导出剥离密码、manifest SDA 兼容、报价危险标记 |
| `AccountStoreTest` | vault 存取、无 DEK 拒绝落盘、错 DEK、alt-slot 换名、单文件损坏不清空列表、路径穿越拒绝、旧格式 passkey 校验、写盘失败回滚 |
| `SdaImportTest` / `MaFileNormalizerTest` | 明文/加密 bundle、错密码判定、缺 salt/iv、base64 SteamID、文件名回退、base32→base64、不覆盖已有 SteamID |
| `ProtocolClientsTest` | QR 解析白名单、两套签名方案（并断言二者**不**相等）、cookie 形状、兑换码原因映射、窗口等待 |
| `SteamApiClientTest` | eresult 判定、裸 401/403 当会话失效、4xx 诊断体透传、重定向链 cookie 携带、跨源拒绝跟随 |
| `DebugLogTest` / `JwtTest` | 密钥脱敏（keyed / padded base64 / JWT）、环缓冲、JWT 声明解析与畸形容错 |

移植过程中发现并修掉的真实缺陷：

1. `replaceFileAtomic` 先 `writeText` 再用 `FileOutputStream(..., false)` 同步会**清空文件** —— 已改为单个句柄写入 + `fsync`。
2. `QrChallenge.tryParse` 直接调 `java.net.URI`，畸形输入抛 `URISyntaxException` 而非返回 null —— 扫码是攻击者可控输入，已改为捕获后返回 null。
3. 调试日志的脱敏规则漏掉 JWT 的短中段（裸 token 规则排除了 `.`）—— 已补一条整段 JWT 规则。
