# HOTP 一次性驗證：Quint 模型報告（階段②）

> 驗證流程：ADR-029 §5（形式化驗證服務），variant a。
> 產出語言：繁體中文（zh-Hant），依本階段的 output language 規範。
> 目標 repo：`agent-playground/keycloak`；驗證基準分支：`main`；
> workspace base commit：`d4d55541da034a55a785c09922fcd62e51cd5bde`。
> 唯讀輸入：`specs/quint/RULES.md`（G1 已確認）。本報告不修改、不擴大規則；
> 反例一律標為**候選發現**，不是已確認缺陷，交由 G2 分類。

## 1. 摘要

本輪以 Quint 建立 Keycloak HOTP 驗證路徑的並行模型，涵蓋 RULES.md 的 ENV、INV、ACT
與 OUT 範圍。模型的核心狀態是：單一 HOTP 憑證的持久化 `counter`、每個並行請求持有的
`snapshot`（可能落後）、驗證匹配到的 `matched` counter、以及成功驗證的 ghost 歷史
`successes`。寫入動作把 JPA 的 `em.find` + commit 視為一個原子步，並且**沒有**
compare-and-set／`WHERE counter = snapshot` 條件，對應 ACT-5／ACT-6。

結果：

| 檢查 | 結果 |
|---|---|
| `quint typecheck`（model／instances／test） | 全部通過 |
| `quint test`（4 個測試模組） | 19 個 run test 全數通過 |
| `quint run --witnesses`（default 實例） | 7 個 witness 全部 > 0 條 trace |
| INV-1（同一 matched counter 只能成功一次） | **找到反例**（default／windowZero／three） |
| INV-2（持久化 counter 大於所有成功匹配） | **找到反例**（default／three） |
| INV-3（持久化 counter 不得下降） | **找到反例**（default／three） |
| 對照實例 `hotpSingleRequest`（N=1） | 三個不變量在取樣中均無反例 |

三個不變量的反例都已在 `specs/quint/traces/` 留下 ITF trace。它們共同指向同一個機制：
驗證讀取與 counter 寫入不是原子操作，且 stale 寫入沒有 compare-and-set；G2 可據此
分類為 lost update 的不同面向。本輪**沒有**執行 `quint verify`（ADR-029 §7）。

## 2. 規則 → 模型元素／檢查對照

### 2.1 ENV（環境假設）

| 規則 | 模型元素 | 對應檢查／說明 |
|---|---|---|
| ENV-1 | `W`、`INITIAL_COUNTER` 常數；HOTP-only scope | `env1PolicyDomainTest`（N>0、W≥0、initial≥0）；`env3Act3*` 檢查窗口 |
| ENV-2 | `counter`（持久化）、`reqs[r].snapshot/matched` | `env4Act4CommitWritesNextCounterTest` |
| ENV-3 | `validateSuccess(r, matched)` 的 `snapshot ≤ matched ≤ snapshot+W` | `env3Act3WindowEndAcceptedTest`、`env3Act3BeyondWindowRejectedTest`、`zeroWindow*` |
| ENV-4 | `commitSuccess` 寫入 `matched+1` | `env4Act4CommitWritesNextCounterTest` |
| ENV-5／ENV-6 | `readSnapshot(r, snap)` 允許 `snap ∈ INITIAL_COUNTER..counter` | `env5StaleSnapshotHeldTest` |
| ENV-7 | 寫入以原子 `commitSuccess` 表示；不建 version 狀態（OUT-5） | `act5Act6Act7LostUpdateTest` 證明 stale 讀取後的寫入仍成功 |
| ENV-8 | 單一 realm／使用者／憑證；`reqs` 只是一個憑證上的請求 | 結構性 scope；多憑證列 OUT-7 |
| ENV-9 | token 以 `matched` counter 值抽象 | `env9SameTokenValueAcrossSnapshotsTest` |
| ENV-10 | 每個請求獨立 `snapshot`；`reqs` 為 per-request 記錄 | `env5StaleSnapshotHeldTest`、`act5Act6Act7LostUpdateTest` |
| ENV-11 | 模型沒有 `isCodeReusable` 旗標；HOTP 分支不讀它 | 結構性 scope；此旗標對 HOTP 無效果（OUT-9） |

### 2.2 INV（不變量）

| 規則 | 模型定義 | 對應檢查 |
|---|---|---|
| INV-1 | `inv1NoReusedMatchedCounter`：`successes` 中同一 `matched` 不得對應兩個不同 request | `quint run --invariant` 找到反例；`act5Act6Act7LostUpdateTest` 正面重現同一 token 兩次成功 |
| INV-2 | `inv2CounterAheadOfAllMatches`：`successes.forall(s => counter > s.matched)` | `quint run --invariant` 找到反例；`act5Act6StaleWriteRewritesHigherCounterTest` |
| INV-3 | `inv3CounterMonotonic`：`counter >= maxCounter`（`maxCounter` 是歷史最大值 ghost） | `quint run --invariant` 找到反例；`act5Act6StaleWriteRewritesHigherCounterTest` |
| INV-4 | 不變量以單一憑證為範圍；`successes`／`counter` 都是單一憑證 | 結構性 scope；多憑證列 OUT-7 |

`INV-2` 的「曾成功匹配」以 ACT-9 的「`isValid` 回傳 true」為事件時點，也就是
`commitSuccess` 成功時；尚未 commit 的 `Validated` 中間態不計入成功歷史。這是本模型
對 ACT-9 的建模解釋，列於未決事項 U4。

### 2.3 ACT（行為規則）

| 規則 | 模型元素 | 對應檢查 |
|---|---|---|
| ACT-1 | `rejectInvalidInput`：Idle → Rejected，不讀憑證、不改 counter | `act1RejectInvalidInputTest` |
| ACT-2 | `readSnapshot` 依 credentialId 的單一憑證讀取；snapshot 限於 `INITIAL_COUNTER..counter` | `act2ReadSnapshotDomainTest`、`act2ReadSnapshotBelowInitialRejectedTest`、`act2ReadSnapshotAboveCounterRejectedTest` |
| ACT-3 | `validateSuccess` 的窗口守衛；`validateFailure` 表示 token 不在窗口 | `env3Act3WindowEndAcceptedTest`、`env3Act3BeyondWindowRejectedTest`、`zeroWindowRejectsNextCounterTest` |
| ACT-4 | 成功 → `commitSuccess` 寫入 `matched+1`；失敗 → `validateFailure` 不寫入 | `env4Act4CommitWritesNextCounterTest`、`act4FailureDoesNotWriteTest`、`act4FailedRequestCannotCommitTest` |
| ACT-5 | `commitSuccess` 無 CAS、無 `counter == snapshot` 條件，整筆覆蓋 | `act5Act6Act7LostUpdateTest`、`act5Act6StaleWriteRewritesHigherCounterTest` |
| ACT-6 | 寫入動作代表寫入端 `em.find` + commit；stale 讀取到寫入之間的版本不被檢查 | 同上；ENV-7／OUT-5 的建模說明見 §3 |
| ACT-7 | 反例交錯：B 讀舊 snapshot → A 成功 commit → B 以同一 token 成功 commit | `act5Act6Act7LostUpdateTest`；反例 trace F1 |
| ACT-8 | HOTP 分支沒有 TOTP 的 `putIfAbsent` single-use marker；同一 token 可再次成功 | `act5Act6Act7LostUpdateTest`、`zeroWindowLostUpdateTest`；TOTP 對照列 OUT-1 |
| ACT-9 | 成功事件在 `commitSuccess` 時寫入 `successes`；`isValid == true` 即視為消耗 | `env4Act4CommitWritesNextCounterTest`、`act5Act6Act7LostUpdateTest` |
| ACT-10 | 模型只建 HOTP 分支（`subType == "hotp"`）；`otp`／`totp` 不在模型 | 結構性 scope；TOTP 列 OUT-1 |

### 2.4 OUT（明確不驗證）

OUT-1～OUT-10 全部不在模型的行為範圍：TOTP 時間窗與 single-use（OUT-1、OUT-9）、
HMAC／位數（OUT-2）、密碼與 session/token（OUT-3）、快取複寫協定（OUT-4）、
停用快取與 `@Version` 衝突中止（OUT-5）、憑證註冊與 initial counter 產生（OUT-6）、
多使用者／多憑證／聯邦儲存（OUT-7）、`counter+W` 溢位（OUT-8）、
`updateCredential` 靜默丟棄（OUT-10）。這些不是本輪的驗證結論，也不應從本模型推論。

## 3. 模型抽象與限制

1. **單一憑證**：`counter`、`reqs`、`successes` 都屬於同一個 HOTP 憑證（ENV-2、ENV-8）。
   多憑證彼此獨立，不在本輪。
2. **Token 抽象**：token 以其匹配的 `matched` counter 值表示；不計算 HMAC、不建模碰撞
   （ENV-9、OUT-2）。因此「同一 token 被兩個請求使用」在模型中就是兩個請求選到同一個
   `matched` 值。因不建模碰撞，窗口內每個 counter 最多對應一個 token，所以不需要建模
   ACT-3「第一個符合者」的掃描順序；`matched` 值本身已決定驗證結果。
3. **Snapshot 只會落後**：`readSnapshot` 允許 `INITIAL_COUNTER..counter` 的任何值，代表
   使用者快取可能回傳較舊的持久化 counter（ENV-5／ENV-6）。模型不允許 snapshot 超前
   目前 counter；RULES 只宣告「落後」，快取複寫／失效協定列 OUT-4。
4. **成功事件時點**：`successes` 在 `commitSuccess` 時追加，對應 `isValid` 回傳 true
   （ACT-9）。`Validated` 但尚未 commit 的請求不算「曾成功匹配」；否則任何單一請求在
   匹配與寫入之間都會讓 INV-2 在中間態失敗，與本缺陷的並行語意無關。
5. **寫入原子化、沒有 version 中止**：`commitSuccess` 把 `em.find` 與 commit 視為一個
   原子步，且不建 `@Version` 衝突中止分支（OUT-5）。這不會製造不存在的反例：任何模型
   軌跡都可以把每個寫入的 `em.find` 安排在該次 commit 前、且在所有較早 commit 之後，
   因此不會發生版本衝突；stale 讀取發生在更早的 `readSnapshot`，不受寫入端 version 保護。
   若要把「`em.find` 到 commit 之間被其他交易插入」的中止行為納入，需新增版本狀態與
   中止分支，這超出 RULES 的 OUT-5。
6. **W／N 是模擬規模，不是程式限制**：程式沒有對 `lookAheadWindow` 或並行請求數設上界
   （`OTPPolicy.java:42-48,61-75`、`OTPCredentialProvider.java:87-131`）。本輪以
   W=0、1、2 與 N=1、2、3 取樣；更大的值需另一個規模決策（U1、U2）。
7. **`quint run` 是取樣不是證明**：沒有反例只表示在給定的 seed／max-steps／max-samples
   內沒取樣到；本報告不把「no violation」寫成「已證明」。本輪沒有 `quint verify`。
8. **隨機步進可能提早結束**：`step` 先均勻選一個 `ReqId`，再嘗試該請求的五種動作；若選到
   `Done`／`Failed`／`Rejected` 的請求，該步會停用，即使其他請求仍可動作。這只會讓取樣
   軌跡較短，不會把不存在的行為變成反例；已找到的反例都是完整、可實現的動作序列。

## 4. 實例與常數

`instances.qnt` 的每個模組都以具體常數 import `model.qnt`。`test.qnt` 的測試模組也以
相同數值直接 import `model.qnt`；原因是 Quint 的參數化模組再 `export` 給下游時，常數
綁定不會一起帶過去（實測會回報 `Uninitialized const`），所以測試模組各自直接綁定。
兩處數值一致，理由如下。

| 實例模組 | N | W | INITIAL_COUNTER | 理由 |
|---|---:|---:|---:|---|
| `hotpDefault` | 2 | 1 | 0 | Keycloak 預設 `lookAheadWindow=1`、`initialCounter=0`；兩個並行請求已可重現 ACT-7。三個不變量都在此實例找到反例。 |
| `hotpWindowZero` | 2 | 0 | 0 | 邊界 W=0：窗口只有 snapshot 本身。仍可重現 INV-1 的同一 matched counter 重用；INV-2／INV-3 在此取樣中無反例。 |
| `hotpThreeRequests` | 3 | 2 | 5 | N=3 與非零 initialCounter；W=2 覆蓋較寬窗口。可重現 stale 寫入把已前進的 counter 覆寫回較低值；三個不變量都有反例。 |
| `hotpSingleRequest` | 1 | 1 | 0 | 邊界 N=1：沒有並行交錯，作為對照；三個不變量在取樣中均無反例。 |

常數的 `domain_justification` 已逐項寫在 `model.qnt` 的常數上方；程式未限制的值（N、W、
initialCounter 的上界）都以「模擬規模」而非 `assume` 縮小，並在未決事項標明未覆蓋範圍。

## 5. 執行命令與牆鐘

以下命令都在 `specs/quint/` 下執行；牆鐘是外部 `date +%s%N` 量測的實際耗時（含
`npx --no-install` 啟動），非 Quint 內部計時。`--max-samples` 在沒有反例的確認跑使用
10000；有反例的跑通常在前數十毫秒就停止。

### 5.1 型別檢查與測試

| 命令 | 結果 | 牆鐘 |
|---|---|---:|
| `npx --no-install quint typecheck model.qnt` | 通過 | 0.96s |
| `npx --no-install quint typecheck instances.qnt` | 通過 | 0.90s |
| `npx --no-install quint typecheck test.qnt` | 通過 | 1.14s |
| `npx --no-install quint test test.qnt --main hotpDefaultTests --match '.*Test'` | 13 passing | 1.47s |
| `npx --no-install quint test test.qnt --main hotpWindowZeroTests --match '.*Test'` | 3 passing | 1.24s |
| `npx --no-install quint test test.qnt --main hotpThreeTests --match '.*Test'` | 1 passing | 1.21s |
| `npx --no-install quint test test.qnt --main hotpSingleRequestTests --match '.*Test'` | 2 passing | 1.21s |

### 5.2 Witness（default 實例；`--max-steps 12 --max-samples 10000 --seed 0x3`）

命令：

```bash
npx --no-install quint run test.qnt --main hotpDefaultWitnesses \
  --witnesses witnessAnyRead witnessAnyRejected witnessAnyFailure \
    witnessAnyCommit witnessStaleSnapshotHeld witnessCounterAdvanced witnessWindowEndMatched \
  --max-steps 12 --max-samples 10000 --seed 0x3 --verbosity 1
```

結果：`[ok] No violation found`；7 個 witness 全部 > 0 條 trace（任何一個為 0 才代表
目標狀態在此取樣中不可達）。牆鐘 **2.17s**。

| Witness | 取樣命中 |
|---|---:|
| `witnessAnyRead` | 7416 / 10000 |
| `witnessAnyRejected` | 7538 / 10000 |
| `witnessAnyFailure` | 4359 / 10000 |
| `witnessAnyCommit` | 4273 / 10000 |
| `witnessStaleSnapshotHeld` | 646 / 10000 |
| `witnessCounterAdvanced` | 4273 / 10000 |
| `witnessWindowEndMatched` | 2225 / 10000 |

### 5.3 不變量矩陣（`--max-steps 12 --max-samples 10000 --seed 0x1 --verbosity 1`）

命令模板：

```bash
npx --no-install quint run instances.qnt --main <instance> \
  --invariant <invariant> --max-steps 12 --max-samples 10000 --seed 0x1 --verbosity 1
```

| 實例 | 不變量 | 結果 | 牆鐘 |
|---|---|---|---:|
| `hotpDefault` | `inv1NoReusedMatchedCounter` | violation | 1.00s |
| `hotpDefault` | `inv2CounterAheadOfAllMatches` | violation | 0.92s |
| `hotpDefault` | `inv3CounterMonotonic` | violation | 0.92s |
| `hotpWindowZero` | `inv1NoReusedMatchedCounter` | violation | 0.90s |
| `hotpWindowZero` | `inv2CounterAheadOfAllMatches` | no violation | 1.62s |
| `hotpWindowZero` | `inv3CounterMonotonic` | no violation | 1.64s |
| `hotpThreeRequests` | `inv1NoReusedMatchedCounter` | violation | 0.91s |
| `hotpThreeRequests` | `inv2CounterAheadOfAllMatches` | violation | 0.93s |
| `hotpThreeRequests` | `inv3CounterMonotonic` | violation | 0.92s |
| `hotpSingleRequest` | `inv1NoReusedMatchedCounter` | no violation | 1.45s |
| `hotpSingleRequest` | `inv2CounterAheadOfAllMatches` | no violation | 1.46s |
| `hotpSingleRequest` | `inv3CounterMonotonic` | no violation | 1.46s |

### 5.4 反例 trace 產生

命令模板（`{seq}` 由 Quint 填為 trace 序號；此處皆為 `0`）：

```bash
npx --no-install quint run instances.qnt --main <instance> \
  --invariant <invariant> --max-steps 12 --max-samples 1000 --seed <seed> \
  --out-itf "<temp>/<name>-{seq}.itf.json" --n-traces 1 --verbosity 1
```

| 產出檔（`traces/`） | 實例 | 不變量 | seed | 牆鐘 |
|---|---|---|---|---:|
| `inv1-default.itf.json` | `hotpDefault` | `inv1NoReusedMatchedCounter` | `0x1` | 0.99s |
| `inv1-window-zero.itf.json` | `hotpWindowZero` | `inv1NoReusedMatchedCounter` | `0x4` | 0.90s |
| `inv1-three-requests.itf.json` | `hotpThreeRequests` | `inv1NoReusedMatchedCounter` | `0x5` | 0.91s |
| `inv2-default.itf.json` | `hotpDefault` | `inv2CounterAheadOfAllMatches` | `0x2` | 0.90s |
| `inv2-three-requests.itf.json` | `hotpThreeRequests` | `inv2CounterAheadOfAllMatches` | `0x6` | 0.92s |
| `inv3-default.itf.json` | `hotpDefault` | `inv3CounterMonotonic` | `0x3` | 0.92s |
| `inv3-three-requests.itf.json` | `hotpThreeRequests` | `inv3CounterMonotonic` | `0x7` | 0.90s |

所有 trace 都是 `quint run --out-itf` 產生的 ITF；沒有反例的檢查沒有建立 trace 檔。

## 6. 反例清單（全部為候選發現）

> 以下每一條都是**候選發現**，不是已確認缺陷。分類、嚴重度與是否為真陽性由 G2 決定。
> 這些 trace 顯示的是模型在 RULES.md 下的可執行行為；模型沒有為了讓反例消失而修改。

### F1（INV-1）：同一 `matched` counter 被兩次成功驗證

**候選發現 F1**：兩個並行請求可以持有同一個落後 snapshot，對同一個 `matched` counter
各自驗證成功，且各自寫入 `matched+1`；持久化 counter 只前進一次。

代表 trace：`traces/inv1-default.itf.json`（`hotpDefault`：N=2、W=1、initial=0）。

| 步驟 | counter | maxCounter | req0 | req1 | successes |
|---:|---:|---:|---|---|---|
| 0 | 0 | 0 | Idle | Idle | ∅ |
| 1 | 0 | 0 | Idle | Read(snapshot=0) | ∅ |
| 2 | 0 | 0 | Idle | Validated(matched=1) | ∅ |
| 3 | 0 | 0 | Read(snapshot=0) | Validated(matched=1) | ∅ |
| 4 | 0 | 0 | Validated(matched=1) | Validated(matched=1) | ∅ |
| 5 | 2 | 2 | Done | Validated(matched=1) | {req0→1} |
| 6 | 2 | 2 | Done | Done | {req0→1, req1→1} |

最後狀態有兩個成功事件匹配 `matched=1`，但持久化 counter 只從 0 到 2（只前進一次）。
同一現象也在：

- `traces/inv1-window-zero.itf.json`（W=0；兩個請求都匹配 0，counter 只到 1）；
- `traces/inv1-three-requests.itf.json`（N=3、W=2、initial=5；req0 與 req2 都匹配 7，
  counter 只到 8）。

對應 RULES：INV-1、ACT-5、ACT-6、ACT-7、ACT-8（HOTP 沒有 single-use marker）。

### F2（INV-2）：持久化 counter 未大於所有曾成功匹配的 counter

**候選發現 F2**：stale 寫入可以把已經前進的持久化 counter 覆寫成較低的值，使
`counter > max(matched_history)` 不成立；舊 code 可能重新落回接受窗口。

代表 trace：`traces/inv2-default.itf.json`（`hotpDefault`）。

| 步驟 | counter | maxCounter | req0 | req1 | successes |
|---:|---:|---:|---|---|---|
| 0 | 0 | 0 | Idle | Idle | ∅ |
| 1 | 0 | 0 | Read(snapshot=0) | Idle | ∅ |
| 2 | 0 | 0 | Validated(matched=1) | Idle | ∅ |
| 3 | 2 | 2 | Done | Idle | {req0→1} |
| 4 | 2 | 2 | Done | Read(snapshot=0) | {req0→1} |
| 5 | 2 | 2 | Done | Validated(matched=0) | {req0→1} |
| 6 | 1 | 2 | Done | Done | {req1→0, req0→1} |

最後 `counter=1`，但成功匹配過的最大值是 1，故 `counter > max(matched)` 為 false。
`traces/inv2-three-requests.itf.json` 是 N=3、W=2、initial=5 的另一個實例：req2 先匹配 7
把 counter 推到 8，req0 再以 stale snapshot 5 匹配 6 把 counter 寫回 7。

### F3（INV-3）：持久化 counter 下降

**候選發現 F3**：stale 寫入讓持久化 counter 從較高值下降到較低值，違反單調性。

代表 trace：`traces/inv3-default.itf.json`（`hotpDefault`）。

| 步驟 | counter | maxCounter | req0 | req1 | successes |
|---:|---:|---:|---|---|---|
| 0 | 0 | 0 | Idle | Idle | ∅ |
| 1 | 0 | 0 | Idle | Read(snapshot=0) | ∅ |
| 2 | 0 | 0 | Idle | Validated(matched=1) | ∅ |
| 3 | 2 | 2 | Idle | Done | {req1→1} |
| 4 | 2 | 2 | Read(snapshot=0) | Done | {req1→1} |
| 5 | 2 | 2 | Validated(matched=0) | Done | {req1→1} |
| 6 | 1 | 2 | Done | Done | {req0→0, req1→1} |

counter 從 2 降到 1；`maxCounter=2` 記錄了歷史最大值。`traces/inv3-three-requests.itf.json`
是 N=3、W=2、initial=5 的版本：counter 從 8 降到 7。

### 6.1 重疊與解讀

- F2 與 F3 的 default trace 都是「先寫高、再被 stale 寫低」的交錯（seed 不同，請求順序略異）；
  F2／F3 是同一機制的兩個不變量面向。
- F1 與 F2／F3 的差別：F1 是**同一個 matched counter 被重用**；F2／F3 即使兩個請求
  匹配不同 counter（例如 F2 default 的 1 與 0），stale 寫入仍可讓 counter 倒退。
- 三者都依賴「請求在較早時間取得 stale snapshot，之後才寫入」；這是 ENV-5／ENV-6 的
  預設快取組態，不是模型額外加入的假設。

## 7. 未決事項

- **U1（W 的上界）**：RULES ENV-1 只要求 `lookAheadWindow ≥ 0`，程式也未限制上界
  （`OTPPolicy.java:42-48,61-75`）。本輪取樣只涵蓋 W=0、1、2；更大的窗口要由 G1／人類
  決定模擬上限，或改用 `quint verify` 的有限界（本輪未執行 verify）。
- **U2（並行請求數 N）**：程式未限制同時驗證的請求數；本輪涵蓋 N=1、2、3。N>3 的
  交錯未取樣；若需要更大 N，需決定規模與牆鐘預算。
- **U3（快取落後上界）**：ENV-6／OUT-4 只抽象「snapshot 可能落後」，沒有給出落後上界。
  模型允許 `INITIAL_COUNTER..counter` 的任一 snapshot（最寬的落後語義）。若實際快取有
  較小的落後上界，可縮小狀態空間；這不影響已找到的反例。
- **U4（INV-2 的成功時點）**：本模型依 ACT-9 把「曾成功匹配」定義為 `isValid` 回傳 true
  （`commitSuccess`）的成功事件；尚未 commit 的 `Validated` 中間態不計入。若 G1 要改成
  「`validateHOTP` 匹配即算」，INV-2 的檢查時點與中間態語義需要重新確認。
- **U5（`@Version` 衝突中止）**：OUT-5 已排除 `em.find` 到 commit 之間被其他交易插入的
  中止行為；本模型把寫入視為原子步。若要把中止納入，需新增 version 狀態、`writeFind`／
  `commit` 兩個階段與 rollback 分支，屬於另一個模型範圍。
- **U6（TOTP 對照）**：ACT-8 的 TOTP `putIfAbsent` 行為只作為 HOTP 的對照，未建模
  （OUT-1）。本輪沒有用模型證明 TOTP 分支的一次性；那是另一個範圍。

## 8. 重現方式

在 `specs/quint/` 下：

```bash
npx --no-install quint typecheck model.qnt
npx --no-install quint typecheck instances.qnt
npx --no-install quint typecheck test.qnt

npx --no-install quint test test.qnt --main hotpDefaultTests --match '.*Test'
npx --no-install quint test test.qnt --main hotpWindowZeroTests --match '.*Test'
npx --no-install quint test test.qnt --main hotpThreeTests --match '.*Test'
npx --no-install quint test test.qnt --main hotpSingleRequestTests --match '.*Test'

npx --no-install quint run instances.qnt --main hotpDefault \
  --invariant inv1NoReusedMatchedCounter --max-steps 12 --max-samples 10000 --seed 0x1

npx --no-install quint run instances.qnt --main hotpDefault \
  --invariant inv2CounterAheadOfAllMatches --max-steps 12 --max-samples 10000 --seed 0x1

npx --no-install quint run instances.qnt --main hotpDefault \
  --invariant inv3CounterMonotonic --max-steps 12 --max-samples 10000 --seed 0x1

# 反例 trace（輸出的 {seq} 為 0；可重新命名為 traces/ 下的檔名）
npx --no-install quint run instances.qnt --main hotpDefault \
  --invariant inv1NoReusedMatchedCounter --max-steps 12 --max-samples 1000 --seed 0x1 \
  --out-itf "traces/inv1-default-{seq}.itf.json" --n-traces 1
```

`npx --no-install` 使用 workspace 既有安裝，不新增依賴。沒有執行 `quint verify`。
