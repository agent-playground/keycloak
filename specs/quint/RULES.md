# HOTP 一次性驗證規則（G1 草稿）

> 階段：requirements grill（ADR-029 §5）。本文件是**候選規則草稿**，尚未定稿；G1 確認後才進入建模。
> 標記說明：`confirmed`＝有直接程式碼／文件證據；`candidate`＝未確認、會阻擋建模；`assumption`＝由 agent 暫定、G1 可推翻。`confirmed` 只表示規則有證據，**不表示目前程式已滿足**；本次驗證的正是這些不變量是否被違反。
> 驗證對象：Keycloak 的 HOTP 認證路徑。瀏覽器 OTP 表單與 direct grant 最後都呼叫同一個 `OTPCredentialProvider.isValid`。

## ENV（環境假設）

- **ENV-1（confirmed）** 驗證的 realm OTP policy 為 `type=HOTP`、`period=0`、`lookAheadWindow = w ≥ 0`（預設 1）、`initialCounter ≥ 0`；`digits` 與 `algorithm` 取自憑證資料，`w` 取自當前 realm policy。證據：`server-spi/src/main/java/org/keycloak/models/OTPPolicy.java:44-48,61-75`、`model/jpa/src/main/java/org/keycloak/models/jpa/RealmAdapter.java:1005-1017`、`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:104-109`。
- **ENV-2（confirmed）** 每個 HOTP 憑證的持久化狀態包含 `counter`（int，存於 `credentialData` JSON）、secret、digits、algorithm、subtype；模型以一個憑證為單位。證據：`server-spi/src/main/java/org/keycloak/models/credential/dto/OTPCredentialData.java:9,38-44`、`server-spi/src/main/java/org/keycloak/models/credential/OTPCredentialModel.java:31-38,75-92`。
- **ENV-3（confirmed）** 驗證時從 snapshot counter `c_r` 開始，對 `k = 0..w` 依序計算 `HOTP(K, c_r+k)`，第一個符合者為 `matched = c_r+k`；全部不符則失敗。證據：`server-spi/src/main/java/org/keycloak/models/utils/HmacOTP.java:82-93`、`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:109`。
- **ENV-4（confirmed）** 成功時寫入 `counter = matched+1`，並以整筆 `credentialData` 覆蓋持久化值。證據：`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:110-115`、`server-spi/src/main/java/org/keycloak/models/credential/OTPCredentialModel.java:94-101`、`model/jpa/src/main/java/org/keycloak/models/jpa/JpaUserCredentialStore.java:63-76`。
- **ENV-5（confirmed）** 驗證讀取走 `getStoredCredentialById`；在使用者快取啟用且該 session 尚未修改使用者時，這個讀取由快取中的憑證清單提供（每個請求拿到 `shallowClone` 的快照），不保證是最新持久化值。證據：`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:102-104`、`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/UserAdapter.java:349-361`、`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/entities/CachedUser.java:80,156-159`、`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/SubjectCredentialManagerCacheAdapter.java:53-56`。
<!-- grill:Q1 -->
- **ENV-6（assumption；預設採用，G1 可推翻）** 本次驗證的組態為 Keycloak 預設：使用者快取啟用（`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/InfinispanUserCacheProviderFactory.java:91-92`）。因此模型允許「請求持有的 snapshot 落後於持久化 counter」。證據：`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/DefaultLazyLoader.java:64-79`、`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/entities/CachedUser.java:80,156-159`。
<!-- /grill:Q1 -->
- **ENV-7（confirmed）** JPA `CredentialEntity` 雖有 `@Version`（`model/jpa/src/main/java/org/keycloak/models/jpa/entities/CredentialEntity.java:77-79`、`model/jpa/src/main/resources/META-INF/jpa-changelog-26.2.0.xml:33-34`），但 `CredentialModel` 不攜帶 version（`server-spi/src/main/java/org/keycloak/credential/CredentialModel.java:60-71`），且 `updateCredential` 在寫入時才 `em.find` 取得當前 entity（`model/jpa/src/main/java/org/keycloak/models/jpa/JpaUserCredentialStore.java:64`）。因此樂觀鎖只涵蓋「寫入的 `em.find` 到 commit」區間，**不涵蓋**「驗證讀取到寫入」區間。
- **ENV-8（assumption）** 模型只涵蓋單一 realm、單一使用者、單一 HOTP 憑證；多憑證／多使用者彼此獨立，不列入本次。理由：counter 與一次性語意都是以單一憑證為單位。
- **ENV-9（assumption）** TOKEN 以「它所匹配的 counter 值」抽象，模型不計算 HMAC，也不建模雜湊碰撞；同一 token 在模型中等同於唯一的 `matched`。理由：本次要驗證的是併發交錯，不是密碼學映射。
- **ENV-10（assumption）** 每個請求有獨立的 session／snapshot；請求可任意交錯；寫入在驗證決策之後才 commit。證據：`services/src/main/java/org/keycloak/authentication/authenticators/browser/OTPFormAuthenticator.java:112-120`（先 `isValid` 再 `context.success`）。
- **ENV-11（confirmed）** `isCodeReusable` 預設 false，但 HOTP 分支不讀取它；只有 TOTP 分支用它決定是否使用 `SingleUseObjectProvider`。證據：`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:107-127`、`server-spi/src/main/java/org/keycloak/models/OTPPolicy.java:76`。

## INV（不變量）

- **INV-1（confirmed）** 對同一憑證，任一 `matched` counter 值最多只能有一次成功驗證；不得有兩個成功事件匹配同一個 counter。證據：成功時 counter 前進的設計（`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:113-115`）、TOTP 以原子 `putIfAbsent` 保證一次性（同檔 `120-127`）、`DEFAULT_IS_REUSABLE=false`（`server-spi/src/main/java/org/keycloak/models/OTPPolicy.java:76`）。
- **INV-2（confirmed）** 在任何時刻，持久化 counter `p` 必須大於所有曾成功匹配的 counter 值（`p > max(matched_history)`）；否則舊 code 會落在未來的接受窗口內。證據：`server-spi/src/main/java/org/keycloak/models/utils/HmacOTP.java:82-93`（從 `p` 開始掃描）、`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:109-114`。
- **INV-3（confirmed）** 持久化 counter 不得隨時間下降（monotonic non-decreasing）。證據：`server-spi/src/main/java/org/keycloak/models/credential/OTPCredentialModel.java:94-101`、`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:113-114`。程式設計上只前進；本次要驗證 stale 寫入是否破壞它。
- **INV-4（assumption）** 不變量以「每個憑證」為範圍，不是全域；同一使用者的不同 HOTP 憑證不共享 counter。理由：counter 存在各自的 `credentialData`。

## ACT（行為規則）

- **ACT-1（confirmed）** `isValid` 先拒絕空 challenge／空 credentialId，不改變 counter。證據：`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:93-100`。
- **ACT-2（confirmed）** 依 credentialId 讀取憑證、解析 subtype；若為 HOTP，以憑證的 digits/algorithm 與 realm 的 `lookAheadWindow` 建立 validator。證據：`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:102-109`。
- **ACT-3（confirmed）** `validateHOTP` 從 snapshot `c_r` 掃到 `c_r+w`，第一個符合者回傳 `matched+1`；全不符回傳 -1。證據：`server-spi/src/main/java/org/keycloak/models/utils/HmacOTP.java:82-93`。
- **ACT-4（confirmed）** 匹配成功時：`updateCounter(matched+1)`、`updateStoredCredential`、回傳 true；匹配失敗時回傳 false 且不寫入。證據：`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:110-115`。
- **ACT-5（confirmed）** 寫入是整筆 `credentialData` 覆蓋，沒有 compare-and-set、沒有 `WHERE counter = c_r`、也沒有 HOTP 的 single-use marker。證據：`model/jpa/src/main/java/org/keycloak/models/jpa/JpaUserCredentialStore.java:63-76`、`server-spi/src/main/java/org/keycloak/models/credential/OTPCredentialModel.java:94-101`、`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:107-115`（HOTP 分支無 single-use）。
- **ACT-6（confirmed）** 寫入的併發保護只有 `@Version`，且 version 是在寫入端的 `em.find` 才取得；因此「先讀舊 snapshot、後寫」的交錯不被樂觀鎖攔下。證據：`model/jpa/src/main/java/org/keycloak/models/jpa/entities/CredentialEntity.java:77-79`、`model/jpa/src/main/java/org/keycloak/models/jpa/JpaUserCredentialStore.java:64`、`server-spi/src/main/java/org/keycloak/credential/CredentialModel.java:60-71`。
- **ACT-7（confirmed，反例行為）** 存在以下交錯：B 讀到 snapshot `c` → A 讀 `c`、寫 `c+1`、commit → B 以 `c` 驗證成功、寫 `c+1`（B 的 `em.find` 取得 A commit 後的 version）→ B commit。兩次 `isValid` 都回 true，持久化 counter 只前進一次。這是本次要重現的 lost update。證據：ACT-2～ACT-6 的組合；`model/jpa/src/main/java/org/keycloak/models/jpa/JpaUserCredentialStore.java:63-76`。
- **ACT-8（confirmed）** TOTP 分支在 `isCodeReusable=false` 時以 `singleUseObjects().putIfAbsent(credentialId+"."+challengeResponse, lifespan)` 原子地拒絕第二次使用；HOTP 分支沒有對應機制。證據：`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:120-127`。
- **ACT-9（assumption）** 模型把一次 `isValid` 回傳 true 視為「該 counter 已被消耗」，不建模後續 session/token 發放；即使後續流程失敗，counter 已前進，對一次性語意而言仍是消耗。理由：本次缺陷發生在憑證驗證層，且 CVE 的影響是並行登入。
- **ACT-10（confirmed）** HOTP 分支只認 `subType == "hotp"`；`otp`／`totp` 不會進入此分支。證據：`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:107,116`。

## OUT（明確不驗證）

- **OUT-1（confirmed）** TOTP 的一次性與時間窗語義（只作為 HOTP 的對照，不建模其時間前進）。
- **OUT-2（confirmed）** HMAC／RFC 4226 的密碼學正確性、位數截斷、碰撞機率。
- **OUT-3（confirmed）** 密碼驗證、暴力破解鎖定、使用者停用、session/token 發放與授權。
- **OUT-4（confirmed）** 使用者快取的複寫／失效協定與多節點時序；模型只抽象「snapshot 可能過期」。
<!-- grill:Q1 -->
- **OUT-5（confirmed）** 不驗證停用使用者快取的組態，以及 JPA `@Version` 在衝突時的交易中止行為；此組態不在本次模型範圍（`model/jpa/src/main/java/org/keycloak/models/jpa/entities/CredentialEntity.java:77-79`、`model/jpa/src/main/resources/META-INF/jpa-changelog-26.2.0.xml:33-34`）。
<!-- /grill:Q1 -->
- **OUT-6（confirmed）** 憑證建立／註冊、initial counter、secret 產生、管理介面操作。
- **OUT-7（confirmed）** 聯邦使用者儲存、多個 HOTP 憑證、多個使用者。
- **OUT-8（confirmed）** `counter + lookAheadWindow` 的 int 溢位（`server-spi/src/main/java/org/keycloak/models/utils/HmacOTP.java:85`）。
- **OUT-9（confirmed）** `isCodeReusable=true` 對 HOTP 的影響（HOTP 分支不讀取此旗標；此設定對 HOTP 無效果）。
- **OUT-10（confirmed）** `updateCredential` 在 entity 不存在或使用者不符時靜默丟棄寫入（`model/jpa/src/main/java/org/keycloak/models/jpa/JpaUserCredentialStore.java:64-65`），`isValid` 仍回傳 true；此路徑需要併發刪除憑證，未列入本次模型。
