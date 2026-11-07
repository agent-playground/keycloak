# Model map — Lean proofs（HOTP 一次性驗證）

> 本檔是 Lean 階段的 claim ledger。沒有記在這裡的主張，就不算涵蓋。
> 階段：**proofs**（ADR-029 §5）。G3 已於 2026-11-07T14:30:12.030Z 由
> philipz 簽核（`specs/lean/G3-SIGNOFF.md`，approval）；三條 headline theorem
> 已用 `Specs/Examples.lean` 的具體 witness 證明，`verify-lean.sh` 全綠。
> **定理敘述與 statements 階段逐字相同，未弱化。**

- **Verified at commit**：`d4d55541da034a55a785c09922fcd62e51cd5bde`（workspace base；`main`；2026-11-07）
- **Rules of record**：`specs/quint/RULES.md`（G1，confirmed）；`specs/quint/TRIAGE.md`（G2，philipz，2026-11-07T14:06:51.814Z）；本目錄 `RULES.md` 是轉錄
- **Toolchain**：Lean `v4.35.0`（`lean-toolchain`）；Mathlib：**no**（只用 Lean core）
- **本輪 G2 選入 Lean 的反例**：`inv1-default`、`inv2-default`、`inv3-default`（`TRIAGE.md` 的 `toLean=yes`）

## Verified sources（模型聲稱描述的程式碼）

| File | Lines | What is modeled |
|---|---|---|
| `services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java` | `87-131`（HOTP 分支 `107-115`） | `isValid` 依 subtype 分派；HOTP 讀取、視窗驗證、`updateCounter`、`updateStoredCredential`；TOTP 分支不建模 |
| `server-spi/src/main/java/org/keycloak/models/utils/HmacOTP.java` | `82-93` | 從 snapshot `c` 掃到 `c + lookAheadWindow`，第一個符合者回傳 `matched + 1` |
| `server-spi/src/main/java/org/keycloak/models/credential/OTPCredentialModel.java` | `94-101` | `updateCounter` 把 `counter` 寫進 `credentialData` 並序列化整筆資料 |
| `server-spi/src/main/java/org/keycloak/models/credential/dto/OTPCredentialData.java` | `9`、`39-44` | 持久化 counter 欄位與其 JSON 映射 |
| `model/jpa/src/main/java/org/keycloak/models/jpa/JpaUserCredentialStore.java` | `63-76` | `updateCredential` 在寫入時才 `em.find`，然後以傳入的 `credentialData` 整筆覆蓋；沒有 CAS |
| `model/jpa/src/main/java/org/keycloak/models/jpa/entities/CredentialEntity.java` | `77-79` | `@Version` 只從寫入端 `em.find` 取得，不覆蓋「驗證讀取到寫入」的區間（ENV-7） |
| `model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/UserAdapter.java` | `349-361` | 使用者快取啟用時，`getStoredCredentialById` 由 `CachedUser` 的憑證清單提供 |
| `model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/entities/CachedUser.java` | `80`、`156-159` | `storedCredentials` 是 lazy-loaded 快照；回傳前 `shallowClone` |
| `model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/DefaultLazyLoader.java` | `63-79` | 快照第一次載入後留在 cache entity，不會因持久化 counter 改變自動重讀 |
| `model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/SubjectCredentialManagerCacheAdapter.java` | `53-56` | 寫入時 invalidate cache；已持有舊 `CachedUser` 的請求仍可用舊快照 |
| `services/src/main/java/org/keycloak/authentication/authenticators/browser/OTPFormAuthenticator.java` | `112-120` | 先 `isValid` 再 `context.success`；模型把 `isValid == true` 當作成功事件 |
| `server-spi/src/main/java/org/keycloak/models/OTPPolicy.java` | `44-48`、`61-75`、`102-134` | `initialCounter`、`lookAheadWindow`、`isCodeReusable` 的來源與預設 |
| `model/jpa/src/main/java/org/keycloak/models/jpa/RealmAdapter.java` | `1005-1017` | realm OTP policy 的讀取 |

## Rule coverage — headline statements

Statement snapshot 與 `Specs/Proofs.lean` 逐字相同（proofs 階段只把 proof body
由 `sorry` 換成證明，並把 import 改為 `Specs.Examples`；定理名稱、量詞、假設與
結論均未改動）。完整名稱在 `Hotp` namespace。

| Rule | Headline theorem | Statement snapshot（phase 2） | Model definition | Source file:line | 為何這個敘述涵蓋規則 |
|---|---|---|---|---|---|
| R1／INV-1 | `Hotp.inv1_same_matched_counter_accepted_twice` | `∃ s : State, Reachable defaultConfig s ∧ hasReusedMatchedCounter s = true` | `Reachable`, `hasReusedMatchedCounter`, `step`, `run` | `OTPCredentialProvider.java:102-115`；`HmacOTP.java:82-93`；`JpaUserCredentialStore.java:63-76` | `hasReusedMatchedCounter` 為 true 表示兩個不同 request 的成功事件有相同 `matched`；`Reachable` 表示存在合法交錯。兩者同時成立就是 INV-1 的反例 |
| R2／INV-2 | `Hotp.inv2_counter_not_ahead_of_all_matches` | `∃ s : State, Reachable defaultConfig s ∧ counterAheadOfAllMatches s = false` | `Reachable`, `counterAheadOfAllMatches`, `step`, `run` | `OTPCredentialProvider.java:109-115`；`HmacOTP.java:82-93` | `counterAheadOfAllMatches` 檢查每個成功 `matched` 都嚴格小於 `counter`；`= false` 就是存在 `counter ≤ matched`，即 INV-2 的反例 |
| R3／INV-3 | `Hotp.inv3_counter_can_decrease` | `∃ s t : State, Reachable defaultConfig s ∧ StepRel defaultConfig s t ∧ t.counter < s.counter` | `Reachable`, `StepRel`, `step` | `OTPCredentialProvider.java:113-114`；`OTPCredentialModel.java:94-101`；`JpaUserCredentialStore.java:63-76` | `StepRel` 是一筆模型轉移；`t.counter < s.counter` 表示該筆成功寫入讓持久化 counter 下降，直接否定單調性。敘述不引入 ghost `maxCounter` |

Helper definitions（不是 headline theorem）：`Specs.Model` 的 `Config`、`Phase`、
`Success`、`State`、`getPhase`、`setPhase`、`initialState`、`Action`、`step`、
`run`、`Reachable`、`StepRel`、`hasReusedMatchedCounter`、`counterAheadOfAllMatches`。

## Proof status（proofs 階段）

三條定理都由 `Specs/Examples.lean` 的具體 witness trace 直接建構存在量詞，
再以 kernel `decide` 檢查具體計算；沒有 `sorry`、沒有新 axiom、沒有
`native_decide`。`#print axioms` 只印出 allowlist 內的公理。

| Headline theorem | 證明方式 | Witness | Axioms（`Audit.lean`） |
|---|---|---|---|
| `Hotp.inv1_same_matched_counter_accepted_twice` | `⟨finalInv1, ⟨traceInv1, decide⟩, decide⟩` | `traceInv1` | `[propext, Classical.choice, Quot.sound]`（allowlist） |
| `Hotp.inv2_counter_not_ahead_of_all_matches` | `⟨finalInv2, ⟨traceInv2, decide⟩, decide⟩` | `traceInv2` | `[propext]`（allowlist） |
| `Hotp.inv3_counter_can_decrease` | `⟨beforeInv3, finalInv2, ⟨traceInv3Before, decide⟩, ⟨commit 1, decide⟩, decide⟩` | `traceInv3Before` + `commit 1` | `[propext]`（allowlist） |

**Statement stability**：`Proofs.lean` 的三個 `theorem` 宣告（名稱、binder、
`Reachable`／`StepRel`／`hasReusedMatchedCounter`／`counterAheadOfAllMatches`
的結論）與 statements 階段完全相同；phase-2 snapshot 仍是這份 G3 簽核的版本。
未來若需要改敘述，必須回 G3，不得為了證明而弱化。

## Non-vacuity witnesses（`Specs/Examples.lean`）

每一條 witness 都是 `run defaultConfig (initialState defaultConfig) trace` 的
具體計算，並用 `by decide` 檢查；不是 hand-waving。

| Theorem | Witness trace | 具體結果（machine-checked） | Satisfiability note |
|---|---|---|---|
| R1 | `traceInv1 = [read 1 0, validate 1 1, read 0 0, validate 0 1, commit 0, commit 1]` | `run … traceInv1 = some finalInv1`；`finalInv1.counter = 2`；`successes = [(req0,1), (req1,1)]`；`hasReusedMatchedCounter finalInv1 = true` | 兩個請求都在 0 時讀到 snapshot 0（初始持久化值），都在窗口 `0..1` 內匹配 1；兩個 commit 都是合法轉移 |
| R2 | `traceInv2 = [read 0 0, read 1 0, validate 0 1, commit 0, validate 1 0, commit 1]` | `run … traceInv2 = some finalInv2`；`finalInv2.counter = 1`；`successes = [(req0,1), (req1,0)]`；`counterAheadOfAllMatches finalInv2 = false` | 兩個 snapshot 都在任何 commit 之前載入；req1 的 `matched = 0` 在 `0..1` 窗口內；最後一筆 commit 把 counter 從 2 寫回 1 |
| R3 | `traceInv3Before = [read 0 0, read 1 0, validate 0 1, commit 0, validate 1 0]`，再套用 `commit 1` | `run … traceInv3Before = some beforeInv3`（`counter = 2`）；`step defaultConfig beforeInv3 (commit 1) = some finalInv2`（`counter = 1`）；`finalInv2.counter < beforeInv3.counter` | 與 R2 相同交錯；`beforeInv3 → finalInv2` 是一筆可達的 `StepRel`，counter 由 2 降為 1 |

Witness trace 的時序刻意安排成「兩個請求都先載入 snapshot，之後才發生第一次
commit」。這是快取 stale 的忠實順序：`CachedUser` 的 `storedCredentials` 在
第一次讀取時載入，之後即使持久化 counter 已前進，持有舊 `CachedUser` 的請求
仍看到舊值（`CachedUser.java:80,156-159`、`DefaultLazyLoader.java:63-79`）。
Lean witness 不依賴「commit 之後才讀到舊 snapshot」這種較難辯護的順序。

## Abstraction ledger — 丟掉的細節與為何不影響（或被標為條件）

| 丟掉的細節 | 出處 | 安全論證 |
|---|---|---|
| HMAC／RFC 4226、位數、演算法、碰撞 | `OUT-2`、`ENV-9` | token 以「它匹配的 counter 值」抽象；本次要驗證的是交錯與 lost update，不是密碼學映射。同一 matched 值即同一 token；不建模碰撞只會讓「同一 token」更嚴格 |
| 單一 realm／使用者／憑證 | `ENV-8`、`INV-4`、`OUT-7` | counter 與一次性語意都以單一憑證為單位；多憑證彼此獨立，不影響本 witness |
| 只有兩個請求、`window = 1`、`initialCounter = 0` | `ENV-1`、`ENV-8`；`hotpDefault` | G2 選的是 `hotpDefault` 的 default trace；本輪定理只宣稱這個組態。N>2、W>1 是 OQ-1，不在宣稱範圍 |
| 寫入的 `em.find` 與 commit 視為一個原子步 | `ENV-7`、`OUT-5` | 模型不建 `@Version` 衝突中止分支。witness 的寫入順序是：B 的 `em.find` 在 A commit 之後，取得 A commit 後的當前 version，因此不會發生 version 衝突；且 B 的驗證讀取來自快取，未把 entity 放進 B 的 persistence context。這是 **conditional** 的建模假設：若實際交易在 `em.find` 前已載入舊 entity，衝突中止路徑不在本模型內 |
| 快取複寫／失效時序、多節點 | `OUT-4` | 模型只抽象「請求持有 stale snapshot」；witness 讓兩個 snapshot 都在第一次 commit 前載入，不需要跨節點時序假設 |
| `read` 只允許 `snapshot ∈ history` 且 `snapshot ≤ counter` | `ENV-5`、`ENV-6`；保守化 | 真實 cache 快照必須是曾經持久化的值；此限制只會縮小模型，不會把不存在的行為變成 witness。witness 只用 snapshot 0，這是初始持久化值。模型另外允許 commit 之後才讀到 stale snapshot（比 cache invalidation 寬）；witness 不使用此路徑，兩個 `read` 都在第一次 commit 前 |
| 每個 request 只呼叫一次 `isValid`；重試視為新 request | `OTPFormAuthenticator.java:112-120` | 失敗後重試是另一個獨立的 `isValid`，有自己的 snapshot；本輪的兩個並行 request 已涵蓋交錯，重試不改變 counter 反例 |
| `successes` ghost history | `ACT-9` | 每次 `commit` 追加一筆，對應 `isValid` 回傳 true；不是程式欄位，但記錄的是真實回傳值。R1／R2 的敘述用它表達「曾成功」 |
| `history` ghost 欄位 | `ENV-5`、`ENV-6` | 記錄持久化 counter 曾持有的值，用來限制 `read` 的 snapshot 來源；**不出現在任何 headline statement**，也不宣稱是程式欄位 |
| `int` 溢位 | `OUT-8` | 模型用 `Nat`；不宣稱 `counter + window` 的溢位行為 |
| TOTP 分支、`isCodeReusable` | `OUT-1`、`OUT-9` | HOTP 分支不讀 `isCodeReusable`；TOTP 的 `putIfAbsent` 只作為對照，不在模型內 |
| 失敗／拒絕路徑的完整輸入驗證 | `ACT-1`、`ACT-3` | 模型有 `reject`、`fail` 轉移且不寫 counter；witness 不使用它們。它們的存在只讓狀態機完整，不製造 counter 反例 |
| 認證成功後的 session／token 發放 | `OUT-3`、`ACT-9` | 模型把 `isValid == true` 視為消耗；即使後續流程失敗，counter 已前進的語意不變 |

**沒有的推論**：本報告不會說「模型比程式寬，所以模型反例在程式一定發生」。每個
witness 都列出它實際使用的動作與值；R1／R2／R3 的 witness 只用到程式確實會做的
讀取、視窗匹配與無條件覆蓋寫入。條件性的 `@Version` 假設已在上表標明。

## Explicitly not covered

- `RULES.md` OUT-1～OUT-10 全部不在本輪 Lean 宣稱內，特別是：TOTP、HMAC、
  多使用者／多憑證、快取複寫協定、停用快取組態、`@Version` 衝突中止、
  憑證註冊、int 溢位、`updateCredential` 靜默丟棄。
- N>2 或 W>1 的參數化定理（OQ-1）。
- 任何 liveness／公平性／排程性質；模型只做 safety 的 reachability 反例。
- 程式層級的「真實 Keycloak 部署一定可重現」：Lean 證明的是**模型層級**的
  reachability；程式可達性依賴 MODEL-MAP 的來源映射與 witness 時序，不是 Lean
  的自動結論。

## Verification gate checklist（proofs 階段才要求全綠）

- [x] `RULES.md` 有 confirmed-by、日期、G1／G2 出處與 acceptance
- [x] 每個 modeled decision 映射到 `file:line`；abstraction ledger 完整
- [x] 三條 headline theorem 的 statement snapshot 已記錄，且與 `Specs/Proofs.lean` 相同
- [x] 每條 headline theorem 都有非空洞 witness（`Specs/Examples.lean`，`by decide`）
- [ ] 獨立對抗式審查：**由機制端獨立審查（見 `specs/lean/REVIEW.md`）**，本 agent 不建立、不標成已完成
- [x] Owner 讀過 top-level statements 並簽核：`specs/lean/G3-SIGNOFF.md`，philipz，2026-11-07T14:30:12.030Z，結果 `approved`
- [x] `verify-lean.sh` 全綠：build／direct compile／coverage／guardrails／axioms 全 PASS，0 `sorry`，axiom 僅 allowlist（見 `REPORT.md` 的閘門結果）
- [x] Dashboard baseline：`metrics-baseline.txt`（statements 階段，作為 phase-2 對照）；proofs 階段重跑後所有 delta ≥ 0，無 regression（+18 行、+7 程式行，全部來自證明與註解；見 `REPORT.md` §6.4）
- [x] Bounded port audit：guards／order／outcomes／boundaries／dropped details 已逐條列在上表與來源表

## Review log

| Date | Reviewer | Kind | Findings | Resolution |
|---|---|---|---|---|
| 2026-11-07 | 機制端（aswf-dev/software_factory#143） | adversarial AI（獨立 session） | 待審查 | 由機制端獨立審查（見 `specs/lean/REVIEW.md`）；不在此標成已完成 |
| 2026-11-07T14:30:12.030Z | philipz | human（G3） | 讀過 statements 階段的 headline theorems，判定 approved | 簽核證據：`specs/lean/G3-SIGNOFF.md`（服務端寫入，唯讀）；proofs 階段未改敘述 |

## Re-sync log

| Date | Commit | Sources changed | Rule affected | Theorems touched | Notes |
|---|---|---|---|---|---|
| 2026-11-07 | `d4d55541` | 初次 statements | R1、R2、R3 | 三條 headline theorem | 由 G2 TRIAGE 的 `toLean=yes` 轉錄；G3 於 2026-11-07T14:30:12.030Z approved |
| 2026-11-07 | `d4d55541` | 無程式碼變更；proofs 階段 | R1、R2、R3 | 三條 headline theorem | 只把 `sorry` 換成由 witness 建構的證明；定理敘述逐字不變 |
