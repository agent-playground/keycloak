# Lean 驗證規則（G1／G2 轉錄）

> 本檔是 `specs/lean/` 的規則登錄，不是新的訪談。內容**逐條轉錄**自
> `specs/quint/RULES.md`（G1）與 `specs/quint/TRIAGE.md`（G2），
> 並以 `specs/quint/.user-content/CONTEXT.md` 的 grill Q1 為組態依據。
> 本階段只寫定理敘述（statements）；尚未證明，待 G3 簽核。

- **驗證對象（entry point）**：`services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java` 的 `isValid`（`87-131`），HOTP 分支 `107-115`。
- **Owner／G2 判定者**：philipz
- **Confirmed by**：philipz，2026-11-07T14:06:51.814Z，出處 `specs/quint/TRIAGE.md`（G2）
- **規則出處**：`specs/quint/RULES.md`（G1 已確認；本檔只轉錄，不重新訪談、不改規則）
- **狀態**：`confirmed`（G2 已把 `inv1-default`、`inv2-default`、`inv3-default` 判為 `real` 且 `toLean=yes`）
- **Acceptance（本階段）**：三條 headline theorem 敘述完整、`lake build` 通過、非空洞 witness 可執行、`MODEL-MAP.md` 有 statement snapshot 與出處；`RULES.md` 不再有未決的規則文字。
- **Acceptance（proofs 階段，尚未開始）**：`G3-SIGNOFF.md` 存在且 approved；零 `sorry`、零新 axiom／bodyless opaque／`native_decide`／`unsafe`／`extern`；`verify-lean.sh` 全綠；定理敘述不得弱化。
- **G3 sign-off**：見 `specs/lean/G3-SIGNOFF.md`（服務端於 G3 簽核後寫入）

## Scope

**In scope**

- 單一 realm、單一使用者、單一 HOTP 憑證（`RULES.md` ENV-2、ENV-8、INV-4）。
- Keycloak 預設部署：使用者快取啟用，驗證讀取可能拿到落後的憑證快照（`RULES.md` ENV-5、ENV-6；grill Q1 選項 a）。
- `lookAheadWindow = 1`、`initialCounter = 0`、兩個並行請求（`specs/quint/instances.qnt` 的 `hotpDefault`）。
- 非原子「驗證讀取 → counter 寫入」造成的 lost update，以及它對 INV-1／INV-2／INV-3 的影響。

**Out of scope（明確）**

- TOTP 的時間窗與 `putIfAbsent` single-use（`RULES.md` OUT-1、OUT-9）。
- HMAC／RFC 4226 的密碼學正確性、位數截斷、碰撞（OUT-2）。
- 密碼驗證、暴力破解、使用者停用、session／token 發放與授權（OUT-3）。
- 快取複寫／失效協定、多節點時序（OUT-4）。
- 停用快取的組態，以及 JPA `@Version` 衝突時的交易中止行為（OUT-5）。
- 憑證註冊、initial counter、secret 產生、管理介面（OUT-6）。
- 聯邦使用者儲存、多個 HOTP 憑證、多個使用者（OUT-7）。
- `counter + lookAheadWindow` 的 int 溢位（OUT-8）。
- `updateCredential` 在 entity 不存在或使用者不符時靜默丟棄寫入（OUT-10）。

## Rules（G2 選入 Lean 的三條）

### R1 — INV-1：同一 matched counter 只能成功一次

- **Statement**：對同一憑證，任一 `matched` counter 值最多只能有一次成功驗證（`isValid == true`）；不得有兩個不同請求以同一個 `matched` 成功。
- **Source of truth**：`specs/quint/RULES.md` INV-1；證據 `services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:113-115`（成功時 counter 前進）、同檔 `120-127`（TOTP 有 `putIfAbsent`，HOTP 沒有）、`server-spi/src/main/java/org/keycloak/models/OTPPolicy.java:76`（`DEFAULT_IS_REUSABLE=false`）。
- **G2 出處**：`specs/quint/TRIAGE.md`，`inv1-default`（verdict `real`、`toLean=yes`）。
- **Positive example（不得違反）**：單一請求以 snapshot 0 匹配 counter 1、寫入 counter 2；之後另一個請求以 snapshot 2 只能匹配 2 或 3，不能再次成功匹配 1。
- **Negative example（違反）**：兩個請求都先讀到 snapshot 0，都匹配 counter 1，先後 commit；兩次 `isValid` 都回 true，但持久化 counter 只前進一次（`specs/quint/traces/inv1-default.itf.json`）。
- **Boundaries**：`w = 0` 時窗口只有 snapshot 本身；`w ≥ 1` 時同一 stale snapshot 可匹配多個 counter。`initialCounter ≥ 0`。空 challenge／空 credentialId 直接拒絕，不消耗 counter（ACT-1）。

### R2 — INV-2：持久化 counter 必須大於所有曾成功的 matched

- **Statement**：在任何時刻，持久化 counter `p` 必須嚴格大於所有曾成功匹配的 counter 值（`p > max(matched_history)`）；否則舊 code 會落在未來的接受窗口內。
- **Source of truth**：`specs/quint/RULES.md` INV-2；證據 `server-spi/src/main/java/org/keycloak/models/utils/HmacOTP.java:82-93`（從持久化 counter 開始掃描）、`OTPCredentialProvider.java:109-114`（成功才寫入 `matched+1`）。
- **G2 出處**：`specs/quint/TRIAGE.md`，`inv2-default`（verdict `real`、`toLean=yes`）。
- **Positive example（不得違反）**：counter 2 且成功歷史只有 matched 0、1；`2 > 1`。
- **Negative example（違反）**：請求 A 以 matched 1 commit 後 counter 為 2；請求 B 仍持有 snapshot 0，匹配 counter 0 後 commit，把 counter 寫回 1；此時成功歷史含 matched 1，而 `1 > 1` 不成立（`specs/quint/traces/inv2-default.itf.json`）。
- **Boundaries**：「曾成功」以 `isValid` 回傳 true／commit 成功為事件時點（ACT-9）；`Validated` 但尚未 commit 的中間態不計入。成功歷史是 ghost log，不是程式欄位。

### R3 — INV-3：持久化 counter 不得下降

- **Statement**：持久化 counter 隨時間單調不減；不得存在一筆成功的驗證寫入，使新的持久化 counter 小於寫入前的值。
- **Source of truth**：`specs/quint/RULES.md` INV-3；證據 `OTPCredentialProvider.java:113-114`（只寫 `matched+1`）、`server-spi/src/main/java/org/keycloak/models/credential/OTPCredentialModel.java:94-101`（整筆 `credentialData` 覆蓋）、`model/jpa/src/main/java/org/keycloak/models/jpa/JpaUserCredentialStore.java:63-76`（`em.find` 後直接覆蓋，無 compare-and-set）。
- **G2 出處**：`specs/quint/TRIAGE.md`，`inv3-default`（verdict `real`、`toLean=yes`）。
- **Positive example（不得違反）**：counter 2 的成功寫入只能寫 3 或更高（在程式允許的窗口中），不能寫回 0／1。
- **Negative example（違反）**：counter 已到 2，B 以 stale snapshot 0 匹配 0 後 commit，counter 從 2 降為 1（`specs/quint/traces/inv3-default.itf.json`）。
- **Boundaries**：counter 是 `int`，但本模型用 `Nat` 且不建模溢位（OUT-8）。R3 的 Lean 敘述以「一個可達的 `step` 使 counter 下降」表示，不引入 ghost `maxCounter`。

## Model assumptions（建模假設，非新規則）

- **A-1（assumption；對應 ENV-5／ENV-6／OUT-4）**：使用者快取可能提供落後於持久化 counter 的憑證快照。模型把 `read` 允許在任意時間點發生；這比「快取在寫入後立即失效、之後的請求一定讀到新值」**寬**。**本輪 witness 不依賴這個放寬**：兩個 `read` 都排在第一次 `commit` 之前，對應「請求先持有舊 `CachedUser` 快照，之後才完成驗證」的實際順序。
- **A-2（assumption；對應 ENV-7／OUT-5）**：`updateStoredCredential` 的 `em.find` + commit 視為一個會成功的原子步；不建模 `@Version` 衝突中止。witness 的寫入順序是 B 的 `em.find` 在 A commit 之後，取得當前 version，因此不需要衝突中止；但模型**沒有證明**其他交易插入時的 rollback 行為。
- **A-3（assumption；對應 ENV-9／OUT-2）**：token 以「它匹配的 counter 值」抽象；不計算 HMAC、不建模碰撞。同一 `matched` 值即同一 token。
- **A-4（scope；對應 ENV-1／ENV-8）**：只建模 `hotpDefault`（N=2、W=1、initial=0、單一憑證）；N>2、W>1 不在本輪定理宣稱。

## Grilling log（轉錄，不新增問題）

| # | 問題 | Owner 回答 | 日期／出處 |
|---|---|---|---|
| Q1 | 模型要驗證哪一種 HOTP 讀取／寫入組態？ | a：預設部署，使用者快取啟用；驗證可能讀到過期快照；無快取組態列 OUT | `specs/quint/.user-content/CONTEXT.md` Round 1 |
| Q2 | 哪些反例送 Lean？ | `inv1-default`、`inv2-default`、`inv3-default`；其餘 `toLean=no` | `specs/quint/TRIAGE.md`，philipz，2026-11-07T14:06:51.814Z |

## Open questions（statements 階段已知的範圍限制；不是規則變更）

| # | 問題 | 狀態 | 處理 |
|---|---|---|---|
| OQ-1 | Lean 只建模 `hotpDefault`（N=2、W=1、initial=0）；N>2、W>1 未進 Lean | open | G2 只選 default trace；更大的規模是另一個工作項，不在本輪定理宣稱 |
| OQ-2 | 快取落後沒有上界；模型要求 snapshot 是曾持久化且 ≤ 當前 counter 的值 | open | 這是保守方向；witness 只用 snapshot 0（初始值），不依賴任意落後 |
| OQ-3 | 「曾成功」的時點是 commit（ACT-9），不是 `Validated` 中間態 | open | 依 G1 ACT-9；若 G1 改變語義，需重寫 R2 敘述並回 G3 |
| OQ-4 | `@Version` 在 `em.find` 到 commit 之間被其他交易插入時的中止未建模（OUT-5） | open | witness 的寫入順序是 B 的 `em.find` 在 A commit 之後，取得當前 version；不依賴衝突中止 |
| OQ-5 | TOTP 的 `putIfAbsent` 對照未建模（OUT-1、OUT-9） | closed for this round | 不在本輪宣稱 |
| OQ-6 | `int` 溢位未建模（OUT-8） | closed for this round | 模型用 `Nat`；不宣稱溢位行為 |

## Change policy

程式碼變更導致定理不成立時：**不得為了讓證明過關而弱化已確認的定理**。停止、回報哪一條規則／敘述改變，取得 owner 重新確認；需要改敘述就回到 G3。
