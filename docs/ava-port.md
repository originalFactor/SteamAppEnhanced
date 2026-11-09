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
| 保险库 | 首次启动自动创建加密 Vault。打开应用时，已启用设备解锁则请求生物识别或系统凭据，否则自动无密码解密；旧 PIN 库保留迁移入口。Android Keystore 保护随机 DEK，账户文件使用 AES-GCM 加密。 |
| Steam 登录与验证器 | 密码、Steam Guard 和 QR 登录已接入。访问令牌自动刷新，保存密码后可使用自身 TOTP 自动重登。支持添加、激活和转移验证器，转移后的秘密会立即存储，保存失败时可导出恢复副本。 |
| 待办与账户操作 | 可处理待批准登录、扫码批准、单条或批量确认、交易报价、设备会话、家庭组邀请和产品密钥。交易、转移、兑换及其他敏感操作有确认步骤。 |
| 库存与市场 | 按游戏和上下文浏览库存，支持分页、物品分组、参考价格和走势、按 Steam 钱包费率报价、批量上架及撤销。自动确认仅处理能用 Steam 返回的挂单 ID 精确匹配的确认。 |
| WebDAV | 底层及 SyncPage 已实现加密同步、ETag 条件写入、逐账户合并、删除标记、冲突选择和历史恢复。当前两页布局没有同步设置入口；已配置的自动同步逻辑仍保留。 |
| 界面与诊断 | 底部为“验证器”和“我的”。资料卡通过 BottomSheet 切换账户；Vault 区域仅有设备解锁开关，明暗模式用 DropdownPreference 选择，日间沿用 Miuix 默认配色，夜间采用深灰背景与分层灰阶组件。界面支持简体中文（zh-CN）与美式英语（en-US）。动态头像、APNG 边框及脱敏日志收集已实现，当前没有日志查看入口。 |

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
- 界面与操作反馈支持简体中文和美式英语，其他语言回退英文。外观用 Miuix 配色实现，入门指南使用首页提示。
- QR 批准可打开相机拍摄、选择图片或粘贴 Steam 链接。市场批量上架按资产逐件提交，无法精确识别的确认留给用户在待办中心处理。

### 1.3 本期刻意省略的 AVA 模块

以下几块依赖 AVA 自己的后端或桌面平台，本项目既不需要也不应照搬：

- `services/entitlement_store.dart`、`pro_actions.dart`、`paywall_screen.dart`、`play_channel.dart` —— AVA Pro 权益（Ed25519 令牌校验 + Cloudflare worker），本项目无商业化。
- `infra/`（Cloudflare 权益/反馈 worker）、`tool/`（发布脚本）—— 与客户端无关。
- `services/window_service.dart`、`portable_library.dart`、`installer/` —— 桌面端。
- `services/feedback_service.dart`、`update_service.dart`、`image_disk_cache.dart` —— 走 AVA 自有服务或桌面路径，后续 UI 批次再按需重写。

### 1.4 密码登录修复与上游复核

再次克隆并核对 AVA `b53fcd42d1147698554636e4e8fdf910f0009582`（源码位于其 `app/lib/`）。以下项目根据实际调用链核对，不能仅由“存在同名文件”判断已完成移植。

| 项目 | 本次结果 |
|---|---|
| 密码 RSA 时间戳 | 已修复：`GetPasswordRSAPublicKey` 的字段 3 是 uint64，旧 Kotlin 代码通过 `asInt.toLong()` 截断；现在完整传回凭据请求的字段 4。缺失或类型错误的时间戳会停止登录。 |
| HTTP 移动客户端头 | 已修复：此前 `MOBILE_HEADERS` 只有定义、没有应用。现在发送请求时补齐缺失头，保留各端点显式设置的头。 |
| Steam App 批准登录 | 已修复：同时提供验证码和设备/邮件批准时也轮询，验证码输入保持可用；已有账户可自动提交自身 Steam Guard 验证码。 |
| 登录错误提示 | 已修复：密码错误、限流、验证码不匹配使用简体中文和美式英语的提示，不再只显示数字 EResult。 |
| 登录回归覆盖 | 新增真实 HTTP 请求级 MockWebServer 测试，检查 RSA 密文可解密回原始合成密码、64 位时间戳、移动客户端头、SteamID fixed64、Guard 提交、令牌轮询和不完整响应拒绝。 |
| 库清单恢复 | 尚缺：AVA 的 `StoreRecoveryScreen` 可在保有密钥且清单缺失时引导重建；本项目遇到已有账户文件但缺失清单会拒绝打开，没有重建 UI。不能将其当作空库覆盖。 |
| 验证器转移保存失败救援 | 部分实现：已有受保护恢复记录、首页恢复入口及当前页面的复制/重试；如果恢复记录与账户写盘都失败，备用秘密仅在当前页面内存，尚缺 AVA 的全局阻断式 `MoveInRescueScreen`。 |
| 界面入口与翻译 | 同步与调试日志入口在两页精简布局中未暴露；许多业务提示仍为英文。皮肤、PIN 设置等差异来自本项目已明确的产品调整，不属于待补齐功能。 |

这里的登录验证使用合成凭据和本地服务器，不等同于已用真实 Steam 账户完成端到端登录。用户报告的 EResult 5 与截断缺陷相符，但仍需在修复版 APK 上确认其实际账户的结果。

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


2026-11-09 账户功能整合：移除独立账户页，家庭、库存/市场、兑换、导出与移除入口直接归入“我的”，并保留原有导出提醒及危险操作确认。“我的”资料、家庭、库存/市场和售价信息采用 Miuix PullToRefresh，与待办和设备管理保持一致。

验证器登录请求在前台立即刷新，正常每 2 秒轮询；失败后等待 4、8、16、30 秒，成功恢复为 2 秒。展开请求面板会取消旧轮询并创建新轮询，立即刷新并重置退避，离开验证器或进入后台停止轮询。验证码进度按屏幕帧读取经过 Steam 校时偏移的毫秒时间，在每个 30 秒周期内线性减少，周期切换时重置。

本次执行 `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug`，193 项 JVM 单测全部通过。新增 `LoginRequestPollingTest` 覆盖立即刷新、正常间隔、退避上限、成功恢复及取消/重启。模拟器使用独立包和合成账户确认验证器启动；后续连接中断，未完成“我的”页面截图验证。

验证器空状态补充：未添加账户时显示默认圆形头像、“添加账号”和 `XXXXX`，头像昵称进入“我的”；未转移时显示账户资料及 `XXXXX`。扫码叠加提示与最近登录请求卡片分别引导添加账户或转移验证器，缺少在线账户或 shared secret 时禁止扫码审批和登录请求轮询。保留离线 maFile 的本地验证码显示。新增 `AuthenticatorAccessTest` 覆盖四种账户状态，197 项 JVM 单测通过；独立测试包截图确认未登录布局。

### 登录中找回验证器（2026-11-09）

对照用户提供的 Steam Android APK（3.10.12）静态调用链，补齐 AVA 原先没有的 weak-token 迁移分支：密码验证响应字段 6 的 `weak_token` 仅保留在内存；当 Steam 提供设备令牌验证且返回受限凭据时，登录页显示“验证器丢失？”。用户确认后，通过受限 transport 请求 `RemoveAuthenticatorViaChallengeStart`，输入短信码并长按确认，调用 `RemoveAuthenticatorViaChallengeContinue`（`generate_new_token=true`、`version=2`）。

新验证器返回后先去除受限凭据，再立即写入 Vault；网络替换及本地保存处于同一不可取消区间。新密钥保存失败时，ViewModel 持有的恢复副本和全局不可直接关闭的恢复弹窗允许复制 maFile 与重试本地保存，重试不再次调用 Steam。成功保存后可复制新恢复码，再使用新 TOTP 完成原登录会话。最终登录合并采用 Vault 中最新的验证器，避免旧页面快照覆盖新密钥；恢复所得密钥不依赖后续登录是否成功。

MockWebServer 新增 7 项回归：弱凭据迁移到完整登录、无受限凭据和 QR 分离、失败重登清除旧凭据、错误短信码、磁盘失败与纯本地重试、取消保护、未发起挑战拒绝提交。合计 204 项 JVM 单测通过。未使用真实账户进行恢复或转移。

### 完整界面本地化（2026-11-09）

将登录/恢复/转移、账户导入导出、旧库迁移、设备管理、交易、家庭、库存/市场及 WebDAV 的硬编码界面文案移入资源，新增 225 条消息；当前仅保留简体中文（zh-CN）与美式英语（en-US）资源。包括异步操作反馈、危险操作确认、无障碍标签、系统设备验证标题、交易状态、家庭角色和密钥失败原因。业务交易操作改用枚举，避免以翻译后的按钮文本分派操作。核心层继续不依赖 Android；协议诊断由 UI 边界映射为本地化错误，不直接向用户输出枚举名称或异常类名。

Steam 请求语言随应用资源语言更新，简体中文使用 schinese，其余不支持的语种回退 english，使物品、交易和确认内容按对应语言请求。Steam 返回的产品名、设备名、用户留言等仍作为服务器数据展示。

新增资源覆盖、格式参数一致性、界面英文硬编码防回归、Steam 语言映射及简体中文 script 优先级测试，合计 209 项单测通过。使用独立包的空账户登录页截图核对简体中文表单，无真实登录请求。

语言范围收敛：移除繁体中文、德语、西班牙语、法语和俄语资源。Android 应用语言配置仅列出 zh-CN 与 en-US，构建同时过滤依赖中的其他语言资源；默认英文资源保留在 values 目录作为 Android 回退。

夜间配色：页面 #202020、卡片 #2C2C2C、输入框 #3D3D3D，选中状态和主按钮使用浅灰色，统一由 ThemeController 的 darkColors 提供，跟随系统暗色模式同样生效。已在独立包内用虚构输入核对聚焦输入框与启用状态的登录按钮，未提交登录。

二步验证界面：密码认证遇到 Steam Guard 验证时进入独立的 LOGIN_VERIFY 路由，标题为“二步验证”。密码页与验证页共享活跃会话的组合状态，返回时取消轮询并回到密码表单，避免路由切换丢失 weak_token。验证码上方显示手机批准登录的循环示意动画及居中提示；批准仍由 Steam 的真实登录会话结果决定。卡片右上角提供独立蓝色找回入口，原“Steam 令牌验证”标题和“等待批准”操作入口已移除。

使用隔离测试包及本地模拟 RSA/认证响应验证密码登录进入二步验证页、动画与提示、找回确认弹窗及返回后保留用户名；未发送找回短信或使用真实账户。构建、210 项 JVM 单测与 Lint 通过。

找回验证码界面：新增独立 LOGIN_RECOVER 路由，标题为“找回验证器”，与二步验证共用居中手机动画、居中提示及下方验证码/主按钮布局。重新发送为卡片右上角蓝色操作，取消通过返回键回到二步验证；未完成转移时清除短信输入并恢复批准轮询。两条验证路由共享原密码会话，避免丢失临时恢复凭据。已完成的替换与本地保存补救状态仍由原恢复链路保留。

隔离测试包使用本地模拟挑战响应验证独立找回页面、短信输入、重新发送位置与返回导航；截图仅含合成账户。构建、210 项 JVM 单测与 Lint 通过。

添加账号入口现直接进入 LOGIN，删除原 ADD 中间页。maFile 导入使用 Miuix Import 图标，与扫码按钮并列置于登录页右上角，复用原多文件选择及加密导入处理。隔离测试确认直达登录、打开/取消文件选择器及进入独立扫码页；构建与 Lint 通过。

长按确认按钮：WorkPrompt 使用应用内 HoldToConfirmButton 扩展，尺寸、连续圆角和颜色均取自 Miuix 0.9.4 ButtonDefaults。静止时为 disabled Primary，按住时线性从左向右填充正常 Primary，文字按填充区域切换对比色；确认仍由系统长按手势触发，松手/移出取消并重置进度。中文使用“长按操作”短文案，转移确认缩短为“长按转移”，按钮保持单行。隔离本地计数测试验证短按/移出不提交、长按各提交一次，并检查中途填充截图；构建与 Lint 通过。

家庭组成员以独立 Miuix 小卡展示：圆形静态头像、昵称及可用的本地 account_name、右侧角色。公开 miniprofile 查询同时提取昵称与头像，不把公开昵称当作登录用户名；无本地账号信息时隐藏用户名行。刷新保留已有资料字段，失败时可回退为 ID/默认头像。新增头像字段解析回归测试；214 项 JVM 测试、构建和 Lint 通过，隔离合成卡片预览验证长昵称截断、有/无用户名与角色布局。

库存加载：兼容 g_rgAppContextData/g_rgWalletInfo 的空数组表示；识别 HTTP 200 中 g_steamID=false/null 的匿名页面，交由 SteamServices 执行一次会话刷新。HTML 文本请求现在拒绝非 2xx 状态（429 作为限流），不再把 HTTP 错误页交给库存解析。物品 success 只接受 true/1，needauth 触发会话刷新；私密库存、缺失库存页面和物品加载失败各有中英提示。新增 7 项本地响应回归测试，221 项 JVM 测试、构建与 Lint 通过。公开库存页面仅用于比对数据结构；未使用真实账户验证用户此次报错的具体来源。

库存报错的进一步诊断发现公共传输层缺陷：OkHttp enqueue 只异步交付响应头，旧 callback 直接将流式 Response 恢复给 Main 调用者，后续 string()/bytes() 仍可能在 UI 线程读取网络。新增回归在旧实现中复现 HTML/JSON 调用线程读取（2 项失败）。execute 现于 OkHttp 回调线程读取并关闭原响应，再交付内存响应；取消发生在交付竞争中也关闭被丢弃的响应。AppWork 的 Logcat 诊断仅输出异常类型和源代码位置，不记录异常消息中的 URL/凭据。224 项 JVM 测试、构建和 Lint 通过；Android 隔离测试从 Main 发起库存及物品加载，使用约 112 KB 分块响应并在 Main 读取时抛 NetworkOnMainThreadException，结果成功且原响应关闭计数为 2。未访问真实用户账号。
