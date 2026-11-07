# Keycloak HOTP 一次性驗證：程式碼摘要（grill 階段①）

> 階段：requirements grill（ADR-029 §5）。本文件是**唯讀分析**，不定稿規則、不寫模型、不改程式碼。
> 語言：繁體中文（zh-Hant）。`confirmed` 只表示規則有直接程式碼證據，不表示目前程式已滿足；本次驗證的正是這些不變量是否被違反。

## 分析範圍

- **Repo**：`agent-playground/keycloak`
- **驗證基準分支**：`main`；本次 workspace 的 base commit：`d4d55541da034a55a785c09922fcd62e51cd5bde`
- **Issue 宣告的 scope**：
  - `services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java`
  - `services/src/main/java/org/keycloak/models/utils/HmacOTP.java`（**此路徑不存在**；repo 內唯一同名檔為 `server-spi/src/main/java/org/keycloak/models/utils/HmacOTP.java`，本分析以實際檔案為準，見「其他觀察 A-1」）
  - `services/src/main/java/org/keycloak/authentication/authenticators/browser/OTPFormAuthenticator.java`
- **為確認呼叫鏈與併發語義而另外讀取的檔案（皆未修改）**：
  - 入口與憑證管理：`services/src/main/java/org/keycloak/authentication/authenticators/directgrant/ValidateOTP.java`、`model/storage/src/main/java/org/keycloak/credential/UserCredentialManager.java`、`server-spi/src/main/java/org/keycloak/models/SubjectCredentialManager.java`、`server-spi/src/main/java/org/keycloak/credential/CredentialProvider.java`、`server-spi/src/main/java/org/keycloak/models/UserCredentialModel.java`
  - OTP 模型與政策：`server-spi/src/main/java/org/keycloak/models/credential/OTPCredentialModel.java`、`server-spi/src/main/java/org/keycloak/models/credential/dto/OTPCredentialData.java`、`server-spi/src/main/java/org/keycloak/models/OTPPolicy.java`、`model/jpa/src/main/java/org/keycloak/models/jpa/RealmAdapter.java`
  - 持久化與樂觀鎖：`model/jpa/src/main/java/org/keycloak/models/jpa/JpaUserCredentialStore.java`、`model/jpa/src/main/java/org/keycloak/models/jpa/entities/CredentialEntity.java`、`model/jpa/src/main/java/org/keycloak/models/jpa/JpaUserProvider.java`、`model/jpa/src/main/resources/META-INF/jpa-changelog-26.2.0.xml`、`server-spi/src/main/java/org/keycloak/credential/CredentialModel.java`
  - 使用者快取：`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/UserAdapter.java`、`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/entities/CachedUser.java`、`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/SubjectCredentialManagerCacheAdapter.java`、`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/InfinispanUserCacheProviderFactory.java`、`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/DefaultLazyLoader.java`
  - 交易／例外轉換：`server-spi-private/src/main/java/org/keycloak/connections/jpa/support/EntityManagerProxy.java`、`services/src/main/java/org/keycloak/transaction/JtaTransactionWrapper.java`
  - 既有測試：`testsuite/integration-arquillian/tests/base/src/test/java/org/keycloak/testsuite/forms/LoginHotpTest.java`
- **既有測試覆蓋**：`LoginHotpTest` 只有循序的成功／失敗案例（`loginWithHotpFailure`、`loginWithHotpMissing`、`loginWithHotpSuccess`、`loginWithHotpInvalidPassword`；`testsuite/integration-arquillian/tests/base/src/test/java/org/keycloak/testsuite/forms/LoginHotpTest.java:96,110,134,148`），**沒有**併發／同一 code 重複使用的測試。
- **結論證據格式**：以下每個結論都附 `file:line`；完整規則見 `specs/quint/RULES.md`。

## 元件與狀態

| 元件 | 責任 | 證據 |
|---|---|---|
| `OTPFormAuthenticator` | 瀏覽器 OTP 表單入口；讀取 `otp` 與 `selectedCredentialId`，呼叫 `credentialManager().isValid(...)`，成功後 `context.success(...)` | `services/src/main/java/org/keycloak/authentication/authenticators/browser/OTPFormAuthenticator.java:74-121` |
| `ValidateOTP` | direct grant／token endpoint 入口；使用預設 OTP 憑證，呼叫同一個 `OTPCredentialProvider.isValid` | `services/src/main/java/org/keycloak/authentication/authenticators/directgrant/ValidateOTP.java:60-90` |
| `UserCredentialManager` / `SubjectCredentialManager` | 將 `CredentialInput` 分派給 credential provider；`updateStoredCredential` 轉到 local/federated store | `model/storage/src/main/java/org/keycloak/credential/UserCredentialManager.java:71-93,130-134,299-312,327-334` |
| `OTPCredentialProvider` | HOTP 驗證、counter 更新；TOTP 走另一分支 | `services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:87-131` |
| `HmacOTP` | RFC 4226 HOTP 產生與 `lookAheadWindow` 掃描 | `server-spi/src/main/java/org/keycloak/models/utils/HmacOTP.java:60-93,110-142` |
| `OTPCredentialModel` / `OTPCredentialData` | `counter` 的記憶體模型與 `credentialData` JSON 序列化 | `server-spi/src/main/java/org/keycloak/models/credential/OTPCredentialModel.java:31-38,94-101`、`server-spi/src/main/java/org/keycloak/models/credential/dto/OTPCredentialData.java:9,38-44` |
| `UserAdapter` / `CachedUser` | 使用者快取；憑證清單由快取提供，回傳 `shallowClone` 快照 | `model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/UserAdapter.java:349-361`、`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/entities/CachedUser.java:80,156-159` |
| `JpaUserCredentialStore` / `CredentialEntity` | 寫入時 `em.find` 當前 entity、整筆覆蓋 `credentialData`；`@Version` 樂觀鎖 | `model/jpa/src/main/java/org/keycloak/models/jpa/JpaUserCredentialStore.java:63-76`、`model/jpa/src/main/java/org/keycloak/models/jpa/entities/CredentialEntity.java:77-79` |

### 狀態變數（建模抽象）

- `counter`（persistent，int）：憑證的持久化 counter，存在 `credentialData` JSON（`OTPCredentialData.java:9,38-44`；`OTPCredentialModel.java:94-101`）。
- `snapshot`（per-request，int）：驗證當下讀到的 counter。預設快取組態下可能落後於 `counter`（`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/UserAdapter.java:355-361`；`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/entities/CachedUser.java:156-159`）。
- `matched`（per-request，int）：token 在 `snapshot..snapshot+w` 中匹配到的 counter；成功時 `matched+1` 寫回（`HmacOTP.java:82-93`）。
- `used`（model ghost，set of int）：歷史成功匹配的 counter 值，用於 INV-1／INV-2。
- `version`（persistent，int）：JPA `CredentialEntity` 的樂觀鎖版本；在寫入端 `em.find` 時取得（`CredentialEntity.java:77-79`；`JpaUserCredentialStore.java:64`）。
- `cacheEnabled`（ENV）：預設 true；決定 `snapshot` 是否可能落後（`InfinispanUserCacheProviderFactory.java:91-92`）。

### 關鍵流程（文字簡圖）

```
[Browser OTP form / direct grant]
        │  otp, credentialId
        ▼
OTPFormAuthenticator.validateOTP / ValidateOTP.authenticate
        │  UserCredentialModel(credentialId, "otp", otp)
        ▼
UserCredentialManager.isValid  ──►  OTPCredentialProvider.isValid
                                         │
                     getStoredCredentialById(credentialId)
                     ├─ 快取命中且有 snapshot → 回傳舊 counter c_r（可能落後）
                     └─ 快取未命中/已更新 → JPA em.find 當前 counter
                                         │
                     HmacOTP.validateHOTP(secret, c_r, w)
                     ├─ 無匹配 → return false（不改 counter）
                     └─ 匹配 v = c_r+k → updateCounter(v+1)
                                         │
                     updateStoredCredential(otpCredentialModel)
                                         │
                     JpaUserCredentialStore.updateCredential
                     ├─ em.find(CredentialEntity)（此時才取得 version）
                     ├─ entity.setCredentialData(整筆 JSON)
                     └─ commit：UPDATE ... WHERE VERSION = 寫入端讀到的 version
```

### 為什麼 `@Version` 沒有擋下這個競態

`CredentialModel` 沒有攜帶 entity version（`server-spi/src/main/java/org/keycloak/credential/CredentialModel.java:60-71`）。`JpaUserCredentialStore.updateCredential` 是在**寫入當下**才 `em.find(CredentialEntity.class, cred.getId())`（`JpaUserCredentialStore.java:64`），所以樂觀鎖比較的是「寫入端 `em.find` 時的 version」與「commit 時的 version」，不是「驗證讀取時的 version」與「commit 時的 version」。

因此存在以下可行交錯（ACT-7）：

1. B 先讀到快取 snapshot `c`（尚未寫入）。
2. A 讀 `c`、驗證成功、寫 `c+1`、commit（version V→V+1）。
3. B 以 snapshot `c` 驗證成功，執行 `updateStoredCredential`；此時 `em.find` 讀到 **V+1**，所以 B 的寫入被視為基於最新 version。
4. B 把整筆 `credentialData` 覆寫成 `c+1` 並 commit（version V+1→V+2）。

結果：A、B 都回傳 true，但持久化 counter 只從 `c` 前進到 `c+1`；同一個 counter `c` 被成功使用兩次。這正是 INV-1／INV-2 的反例。

> 若 B 的 `em.find` 發生在 A commit **之前**，B 會拿到 version V，commit 時樂觀鎖會讓 B 失敗；因此反例需要「B 讀取 → A commit → B 寫入」這個交錯。使用者快取讓 B 的 snapshot 可以在很早之前取得，擴大了這個窗口。

## 候選規則

> 完整、可發布的草稿見 `specs/quint/RULES.md`；以下為同一組規則的索引與證據摘要。

### ENV（環境假設）

- **ENV-1（confirmed）** realm policy：`type=HOTP`、`period=0`、`w ≥ 0`（預設 1）、`initialCounter ≥ 0`；digits/algorithm 來自憑證，`w` 來自當前 realm policy。證據：`server-spi/src/main/java/org/keycloak/models/OTPPolicy.java:44-48,61-75`、`model/jpa/src/main/java/org/keycloak/models/jpa/RealmAdapter.java:1005-1017`、`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:104-109`。
- **ENV-2（confirmed）** 每個 HOTP 憑證的持久化狀態包含 `counter`、secret、digits、algorithm、subtype。證據：`OTPCredentialData.java:9,38-44`、`OTPCredentialModel.java:31-38,75-92`。
- **ENV-3（confirmed）** 從 snapshot `c_r` 掃 `k=0..w`，第一個符合者為 `matched=c_r+k`。證據：`HmacOTP.java:82-93`、`OTPCredentialProvider.java:109`。
- **ENV-4（confirmed）** 成功時寫入 `matched+1`，整筆 `credentialData` 覆蓋。證據：`OTPCredentialProvider.java:110-115`、`OTPCredentialModel.java:94-101`、`JpaUserCredentialStore.java:63-76`。
- **ENV-5（confirmed）** 驗證讀取可能由使用者快取提供，回傳 `shallowClone` 快照。證據：`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:102-104`、`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/UserAdapter.java:349-361`、`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/entities/CachedUser.java:80,156-159`、`model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/SubjectCredentialManagerCacheAdapter.java:53-56`。
- **ENV-6（assumption；Q1）** 預設使用者快取啟用；模型允許 snapshot 落後。證據：`InfinispanUserCacheProviderFactory.java:91-92`、`DefaultLazyLoader.java:64-79`。
- **ENV-7（confirmed）** `@Version` 只在寫入端 `em.find` 到 commit 之間有效；`CredentialModel` 不攜帶 version。證據：`CredentialEntity.java:77-79`、`jpa-changelog-26.2.0.xml:33-34`、`CredentialModel.java:60-71`、`JpaUserCredentialStore.java:64`。
- **ENV-8（assumption）** 單一 realm／使用者／HOTP 憑證；多憑證彼此獨立。
- **ENV-9（assumption）** token 以 `matched` counter 抽象，不建模 HMAC 碰撞。
- **ENV-10（assumption）** 每請求獨立 session/snapshot；寫入在驗證決策後 commit。證據：`OTPFormAuthenticator.java:112-120`。
- **ENV-11（confirmed）** `isCodeReusable` 預設 false，但 HOTP 分支不讀取它。證據：`OTPCredentialProvider.java:107-127`、`OTPPolicy.java:76`。

### INV（不變量）

- **INV-1（confirmed）** 同一憑證的任一 `matched` counter 最多一次成功驗證。證據：`OTPCredentialProvider.java:113-115,120-127`、`OTPPolicy.java:76`。
- **INV-2（confirmed）** 持久化 counter `p` 永遠大於所有曾成功匹配的 counter。證據：`HmacOTP.java:82-93`、`OTPCredentialProvider.java:109-114`。
- **INV-3（confirmed）** 持久化 counter 不得下降。證據：`OTPCredentialModel.java:94-101`、`OTPCredentialProvider.java:113-114`。
- **INV-4（assumption）** 不變量以每個憑證為範圍，不是全域。

### ACT（行為規則）

- **ACT-1（confirmed）** 空 challenge／空 credentialId 直接拒絕，不改 counter。證據：`OTPCredentialProvider.java:93-100`。
- **ACT-2（confirmed）** 依 credentialId 讀憑證；HOTP 以憑證 digits/algorithm + realm `w` 建 validator。證據：`OTPCredentialProvider.java:102-109`。
- **ACT-3（confirmed）** `validateHOTP` 掃 `c_r..c_r+w`，第一個匹配回 `matched+1`。證據：`HmacOTP.java:82-93`。
- **ACT-4（confirmed）** 成功：`updateCounter(matched+1)` + `updateStoredCredential` + true；失敗：false 且不寫。證據：`OTPCredentialProvider.java:110-115`。
- **ACT-5（confirmed）** 整筆 `credentialData` 覆蓋；沒有 CAS、沒有 `WHERE counter = c_r`、HOTP 沒有 single-use marker。證據：`JpaUserCredentialStore.java:63-76`、`OTPCredentialModel.java:94-101`、`OTPCredentialProvider.java:107-115`。
- **ACT-6（confirmed）** 樂觀鎖 version 在寫入端才取得；「先讀舊 snapshot、後寫」不被攔。證據：`CredentialEntity.java:77-79`、`JpaUserCredentialStore.java:64`、`CredentialModel.java:60-71`。
- **ACT-7（confirmed，反例）** B 讀 `c` → A 寫 `c+1`/commit → B 寫 `c+1`/commit；兩次 true，counter 只前進一次。證據：ACT-2～ACT-6 組合；`JpaUserCredentialStore.java:63-76`。
- **ACT-8（confirmed）** TOTP 以 `singleUseObjects().putIfAbsent(...)` 原子拒絕第二次；HOTP 無對應機制。證據：`OTPCredentialProvider.java:120-127`。
- **ACT-9（assumption）** `isValid == true` 即視為 counter 被消耗，不建模後續 session/token 發放。
- **ACT-10（confirmed）** 只有 `subType == "hotp"` 進入 HOTP 分支。證據：`OTPCredentialProvider.java:107,116`。

### OUT（明確不驗證）

- **OUT-1（confirmed）** TOTP 一次性與時間窗語義（僅作對照）。
- **OUT-2（confirmed）** HMAC／RFC 4226 密碼學正確性、位數截斷、碰撞。
- **OUT-3（confirmed）** 密碼驗證、暴力破解、使用者停用、session/token 發放與授權。
- **OUT-4（confirmed）** 快取複寫／失效協定與多節點時序；只抽象「snapshot 可能過期」。
- **OUT-5（confirmed；Q1）** 停用快取的組態與 `@Version` 衝突交易中止；不在本次模型範圍。
- **OUT-6（confirmed）** 憑證建立／註冊、initial counter、secret 產生、管理介面。
- **OUT-7（confirmed）** 聯邦使用者儲存、多個 HOTP 憑證、多個使用者。
- **OUT-8（confirmed）** `counter + w` 的 int 溢位（`HmacOTP.java:85`）。
- **OUT-9（confirmed）** `isCodeReusable=true` 對 HOTP 無效果。
- **OUT-10（confirmed）** `updateCredential` 在 entity 不存在／使用者不符時靜默丟棄寫入，`isValid` 仍 true（需要併發刪除憑證，未列入模型）。證據：`JpaUserCredentialStore.java:64-65`。

## 未確認問題

### Q1（阻擋建模）

本次模型要驗證哪一種 HOTP 讀取／寫入組態？

**建議：選 a（預設部署，使用者快取啟用）。** 理由：這是 Keycloak 的預設（`InfinispanUserCacheProviderFactory.java:91-92`），也是 CVE 描述的實際部署；在這個組態下，驗證可能讀到過期快照，而寫入端的 `em.find` 會取得最新 version，因此會出現 ACT-7 的 lost update。若只驗無快取組態（選 c），依程式碼推論 `@Version` 會讓後到交易失敗，模型很可能沒有反例，與本次要重現的缺陷不同。

選項與「選了它，RULES.md 要怎麼寫」的完整文字見 `specs/quint/GRILL-OPTIONS.json`（questionIndex 1）。可選：

- **a（建議）** 預設部署：使用者快取啟用，允許 snapshot 落後；無快取組態列 OUT。
- **b** 兩種組態都建模：`cacheEnabled ∈ {true,false}`；`false` 時把 `@Version` 衝突建模為交易中止。
- **c** 只建模無快取組態：驗證與寫入共用 JPA persistence context；依推論應無反例（與 CVE 的預設部署不同）。

## RULES.md 草稿

> 以下與 `specs/quint/RULES.md` 的內容一致；`<!-- grill:Q1 -->` 標記的是 Q1 會替換的段落。

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

## 其他觀察（不阻擋建模、不列入 openQuestions）

- **A-1（路徑更正）** Issue 宣告的 `services/src/main/java/org/keycloak/models/utils/HmacOTP.java` 不存在；`find . -name HmacOTP.java` 只找到 `server-spi/src/main/java/org/keycloak/models/utils/HmacOTP.java`。本分析以實際檔案為準；這是 Issue 文字的路徑筆誤，不明顯改變語義，因此列為 assumption 而非阻擋問題。
- **A-2（既有測試缺口）** `LoginHotpTest` 只有循序案例（`testsuite/integration-arquillian/tests/base/src/test/java/org/keycloak/testsuite/forms/LoginHotpTest.java:96,110,134,148`），沒有併發測試；這不影響 grill 階段，但下游建模需要自行產生反例。
- **A-3（空憑證 id 的 NPE）** `OTPCredentialProvider.isValid` 只檢查 credentialId 非空，未檢查 `getStoredCredentialById` 回傳 null；若 id 不存在，`OTPCredentialModel.createFromCredentialModel(null)` 會 NPE（`OTPCredentialProvider.java:102-103`、`OTPCredentialModel.java:75-78`）。這是健壯性問題，與本次一次性語意無直接關係，不列入模型。
- **A-4（direct grant 不在 Issue scope）** `ValidateOTP` 是 token endpoint 的 OTP 入口（`ValidateOTP.java:60-90`），也走同一個 `OTPCredentialProvider.isValid`；Issue scope 只列 browser 的 `OTPFormAuthenticator`，但模型以 provider 為核心，兩個入口共用同一組 ACT/INV，因此不影響規則。
- **A-5（HmacOTP 不在 Issue scope 路徑）** 除了 A-1 的路徑筆誤，`HmacOTP` 位於 `server-spi` 模組，不在 Issue 列的 `services` 路徑下；本次只讀不寫，無白名單問題。
