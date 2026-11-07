# HOTP 一次性驗證：Lean proofs 報告（階段③，proofs）

> 驗證流程：ADR-029 §5（形式化驗證服務），variant a。
> 產出語言：繁體中文（zh-Hant），依 `specs/quint/.user-content/CONTEXT.md` 的
> `## Output language (MANDATORY)`。
> 目標 repo：`agent-playground/keycloak`；驗證基準分支：`main`；
> workspace base commit：`d4d55541da034a55a785c09922fcd62e51cd5bde`。
> 本輪做 **proofs**（G3 簽核後的證明）；**只做本階段**，沒有切換階段。

## 1. 摘要

G3 已簽核 statements 階段的三條 headline theorem（`specs/lean/G3-SIGNOFF.md`：
philipz，2026-11-07T14:30:12.030Z，`approved`）。本輪把三個 `sorry` 換成由
`Specs/Examples.lean` 具體 witness trace 建構的 kernel 檢查證明，定理敘述
**逐字未改**：

- `inv1-default` → **INV-1**：同一 `matched` counter 被兩個不同請求成功驗證。
- `inv2-default` → **INV-2**：持久化 counter 沒有嚴格大於所有曾成功的 `matched`。
- `inv3-default` → **INV-3**：存在一筆可達的成功寫入讓持久化 counter 下降。

`verify-lean.sh` 全綠：build、direct compile、coverage、guardrails（0 hit）、
axiom audit（3/3、僅 allowlist 公理）全部 PASS，`RESULT: PASS`、exit 0。
**這是模型層級的機器證明**：Lean 證明的是 `hotpDefault` 模型內存在可達的反例
狀態／轉移；「真實 Keycloak 部署一定會走到這條交錯」仍屬程式層級的可達性，
**Lean 沒有證明**（見 §4）。

| 項目 | 結果 |
|---|---|
| G3 簽核 | **approved**（philipz，2026-11-07T14:30:12.030Z） |
| `sorry` | **0**（proofs 階段禁止） |
| 新 axiom／bodyless `opaque`／`native_decide`／`unsafe`／`extern` | **0** |
| `lake build` | **PASS**（6 jobs，1.35s） |
| `verify-lean.sh` | **PASS**（exit 0，2.72s） |
| 三條 headline theorem | **已證明**（模型層級） |
| 獨立對抗式審查 | 由機制端執行（見 `specs/lean/REVIEW.md`）；本 agent 未建立、未標成完成 |
| 程式層級「已驗證」 | **未宣稱**；可達性依賴 §4 的假設與人類審查 |

## 2. G3 簽核證據（唯讀輸入）

唯一證據是服務端寫入的 `specs/lean/G3-SIGNOFF.md`，本輪只讀取、未修改：

- 簽核者（actor）：**philipz**
- 時間（at）：**2026-11-07T14:30:12.030Z**
- 結果：**approved**

`specs/lean/RULES.md` 與 `specs/lean/G3-SIGNOFF.md` 都是唯讀還原輸入，本輪
沒有改寫；`RULES.md` 的 sign-off 欄位維持指向 `G3-SIGNOFF.md`。`RULES.md`
仍寫著 statements 階段的文字（「本階段只寫定理敘述」），那是 G3 簽核的歷史
紀錄，proofs 階段不改寫它。

## 3. 證明結果

證明全部在 `specs/lean/Specs/Proofs.lean`；witness 在
`specs/lean/Specs/Examples.lean`（witness trace 與狀態在 proofs 階段未改，
只更新了說明註解）。證明策略：用 witness 的具體 `run` 結果建構存在量詞，
再以 kernel `decide` 檢查具體計算，沒有引入任何公理或可信編譯器捷徑。

| Headline theorem | 證明 | Axioms（`#print axioms`） |
|---|---|---|
| `Hotp.inv1_same_matched_counter_accepted_twice` | `⟨finalInv1, ⟨traceInv1, decide⟩, decide⟩` | `[propext, Classical.choice, Quot.sound]`（allowlist） |
| `Hotp.inv2_counter_not_ahead_of_all_matches` | `⟨finalInv2, ⟨traceInv2, decide⟩, decide⟩` | `[propext]`（allowlist） |
| `Hotp.inv3_counter_can_decrease` | `⟨beforeInv3, finalInv2, ⟨traceInv3Before, decide⟩, ⟨commit 1, decide⟩, decide⟩` | `[propext]`（allowlist） |

Witness trace（與 statements 階段相同）：

- `traceInv1 = [read 1 0, validate 1 1, read 0 0, validate 0 1, commit 0, commit 1]`
  → `finalInv1`：counter 2、`successes = [(0,1), (1,1)]`，
  `hasReusedMatchedCounter = true`。
- `traceInv2 = [read 0 0, read 1 0, validate 0 1, commit 0, validate 1 0, commit 1]`
  → `finalInv2`：counter 1、`successes = [(0,1), (1,0)]`，
  `counterAheadOfAllMatches = false`。
- `traceInv3Before = [read 0 0, read 1 0, validate 0 1, commit 0, validate 1 0]`
  → `beforeInv3`（counter 2）；再套用 `commit 1` → `finalInv2`（counter 1），
  `finalInv2.counter < beforeInv3.counter`。

`Specs.Proofs.lean` 現在 `import Specs.Examples`，因此證明直接使用同一份
machine-checked witness，不需要在 proof 檔重抄 trace。

## 4. 模型忠實度與結論層級（重要）

### 4.1 機器證明了什麼、沒有證明什麼

- **有**：在 `hotpDefault` 模型（單一 HOTP 憑證、兩個並行請求、
  `lookAheadWindow = 1`、`initialCounter = 0`、使用者快取可提供較舊 snapshot）
  中，存在一條可達的 action 交錯，使 (a) 兩個不同請求以同一 `matched` 成功、
  (b) 持久化 counter 未嚴格大於所有成功 `matched`、(c) 一筆可達的 `commit`
  讓 counter 下降。上述是 Lean 對**模型**的機器證明。
- **沒有**：Lean 沒有、也不能證明「Keycloak 的真實執行緒／交易排程一定會
  走到這條交錯」。`Reachable` 是模型內的存在量詞；程式層級的可達性由
  `MODEL-MAP.md` 的來源映射與 witness 時序支撐，仍需人類審查與程式層級測試。
  本報告不把「模型反例存在」推論成「程式一定重現」。
- **沒有**：沒有證明 N>2、W>1、多使用者／多憑證、TOTP、HMAC 正確性、
  `@Version` 衝突中止、int 溢位或任何 liveness／公平性性質（範圍見 §8）。

### 4.2 反例依賴的假設（逐條）

三條反例的 witness 都依賴下列已登錄的建模假設，沒有一條是 agent 自行新增的
規則：

- **A-1（快取 stale；ENV-5／ENV-6／OUT-4）**：`read` 可以取得落後的憑證
  snapshot。witness 把**兩個 `read` 都排在第一次 `commit` 之前**，對應
  `CachedUser.storedCredentials` 第一次載入後留在快取、之後才提交寫入的實際
  順序；witness 不使用「commit 之後才讀到舊 snapshot」這條較難辯護的放寬。
  「真實部署的兩個請求一定這樣排程」**未被 Lean 證明**。
- **A-2（寫入原子步；ENV-7／OUT-5）**：`em.find` + commit 視為一個會成功的
  原子步，不建模 `@Version` 衝突中止。witness 的順序是 B 的 `em.find` 在 A
  commit 之後，取得當前 version；B 的驗證讀取來自快取，未把舊 entity 放進
  persistence context，因此不依賴 version 衝突。**模型沒有證明**其他交易插入
  時的 rollback 行為；那是 OUT-5。
- **A-3（token 抽象；ENV-9／OUT-2）**：token 以「它匹配的 counter 值」表示；
  不計算 HMAC、不建模碰撞。同一 `matched` 值即同一 token。

### 4.3 沒有使用「模型比程式寬」的推論

- `read` 的限制（`snapshot ∈ history` 且 `snapshot ≤ counter`）是**保守化**：
  它只會縮小模型，不會製造不存在的程式行為。
- 模型另外允許 commit 後才讀到 stale snapshot（比 cache invalidation 寬）；
  **本輪三條 witness 都不使用這條路徑**，所以反例不依賴它。
- 每個 witness 的 conjunct（read 的值、`snapshot ≤ matched ≤ snapshot + window`、
  `commit` 寫 `matched + 1`、counter 的整筆覆蓋）都對應 `MODEL-MAP.md` 列出的
  `file:line` 程式決策；沒有把模型自加的抽象欄位（`history`、`successes`）
  放進宣稱「對應真實 trace」的定理。`history` 只限制 `read`，不出現在任何
  headline statement；`successes` 是 `isValid == true` 事件的 ghost log。

## 5. 定理敘述（與 G3 簽核版本逐字相同）

`specs/lean/Specs/Proofs.lean` 的 theorem 宣告未變；proofs 階段只改 proof body
與 import。phase-2 statement snapshot 仍是這份 G3 簽核的版本：

```lean
theorem Hotp.inv1_same_matched_counter_accepted_twice :
    ∃ s : State, Reachable defaultConfig s ∧ hasReusedMatchedCounter s = true

theorem Hotp.inv2_counter_not_ahead_of_all_matches :
    ∃ s : State, Reachable defaultConfig s ∧ counterAheadOfAllMatches s = false

theorem Hotp.inv3_counter_can_decrease :
    ∃ s t : State,
      Reachable defaultConfig s ∧ StepRel defaultConfig s t ∧ t.counter < s.counter
```

`defaultConfig = { window := 1, initialCounter := 0 }`，對應 Quint 的
`hotpDefault`。定理敘述**沒有為了讓證明過關而弱化**；若未來需要改敘述，必須
回到 G3。

## 6. 閘門結果（proofs 階段）

### 6.1 建置

```bash
cd specs/lean
lake build
```

結果：`Build completed successfully (6 jobs).`，exit 0；牆鐘 **1.35s**（本輪
從沒有 `.lake` 的乾淨狀態建置）。沒有 `sorry` 警告。

### 6.2 `verify-lean.sh`（skill 指定的完整閘門）

```bash
bash ~/.dsh/skills/lean-formal-verification/scripts/verify-lean.sh \
  --dir specs/lean --main Specs/Proofs.lean --audit Audit.lean \
  --theorems headline-theorems.txt --baseline metrics-baseline.txt
```

結果：**exit 0、`RESULT: PASS`**，牆鐘 **2.72s**（腳本內計時 3s）。

| gate | 結果 |
|---|---|
| build | **PASS**（`Build completed successfully (6 jobs).`） |
| direct compile | **PASS**（`lake env lean specs/lean/Specs/Proofs.lean` exit 0） |
| coverage | **PASS**（其餘 3 個 `.lean` 檔都可直接編譯） |
| guardrails | **PASS**（5 個 `.lean` 檔、註解／字串剝除後 0 hit：無 `sorry`、`native_decide`、`axiom`、bodyless `opaque`、`unsafe`、`extern`、`@[implemented_by]`） |
| axiom audit | **PASS**（3 theorem checked、3/3 expected reported；僅 `propext`、`Classical.choice`、`Quot.sound`） |
| completeness | **PASS**（`headline-theorems.txt` 三條都在 `Audit.lean` 有 `#print axioms`） |

Axiom audit 原文：

```text
'Hotp.inv1_same_matched_counter_accepted_twice' [propext, Classical.choice, Quot.sound] OK
'Hotp.inv2_counter_not_ahead_of_all_matches' [propext] OK
'Hotp.inv3_counter_can_decrease' [propext] OK
```

`Audit.lean` 的 `#print axioms` 沒有依賴 `sorryAx`。腳本在輸出 metrics 時對
四個未使用的 representation 計數（`List_span`、`takeWhile`、`dropWhile`、
`getLast?`）印出 `printf: invalid number` 警告；這是 `verify-lean.sh` 的顯示
層問題，不影響任何 gate 判定，最終 `RESULT: PASS`、exit 0。

### 6.3 Dashboard（baseline = statements 階段）

`metrics-baseline.txt` 保留 statements 階段的 baseline 作為 phase-2 對照；
proofs 階段重跑後的 delta **全部 ≥ 0，沒有 regression**：

| Metric | Current | Baseline | Delta | 說明 |
|---|---:|---:|---:|---|
| `whole_project.lean_files` | 5 | 5 | 0 | 檔案數不變 |
| `whole_project.total_lines` | 364 | 346 | +18 | 證明與註解 |
| `whole_project.lean_code_lines` | 154 | 147 | +7 | proof body |
| `main.total_lines` | 75 | 61 | +14 | `Proofs.lean` 的證明與說明 |
| `main.lean_code_lines` | 20 | 13 | +7 | proof body |
| `main.proof_api.public` | 3 | 3 | 0 | 三條 headline theorem 不變 |
| 其餘 proof／branching／automation／representation 指標 | 0 | 0 | 0 | 本輪沒有新增這類 token |

沒有指標下降；lines 的增加來自把 `sorry` 換成 4 個 `refine`／`exact`／`decide`
步驟與對應說明，屬預期變化。

## 7. 牆鐘

| 動作 | 牆鐘 |
|---|---:|
| `lake build`（從無 `.lake` 開始） | 1.35s |
| `verify-lean.sh`（含 build、direct、coverage、掃描、audit、metrics） | 2.72s |
| 合計（不含撰寫檔案與閱讀輸入） | 約 4.1s |

## 8. 未決事項

- **OQ-1（規模）**：Lean 只建模 `hotpDefault`（N=2、W=1、initial=0）。N>2、
  W>1 不在本輪定理宣稱；G2 也只選 default trace。
- **OQ-2（快取落後上界）**：`read` 允許任何曾持久化且 ≤ 當前 counter 的
  snapshot，沒有落後上界；witness 只用 snapshot 0，不依賴任意落後。
- **OQ-3（成功時點）**：以 commit／`isValid == true` 為成功事件（ACT-9）；
  `Validated` 中間態不計入 INV-2。
- **OQ-4（`@Version` 中止）**：OUT-5；模型不建 rollback 分支。witness 的
  寫入順序不需要 version 衝突中止，但模型沒有證明程式一定走到這個順序。
- **OQ-5（程式層級可達性）**：Lean 只證明模型層級的 `Reachable`；真實部署的
  執行緒／交易排程是否一定產生 witness 的交錯，仍需程式層級測試或人工排程
  分析確認。

## 9. 重現方式

```bash
cd specs/lean
lake build

bash ~/.dsh/skills/lean-formal-verification/scripts/verify-lean.sh \
  --dir . --main Specs/Proofs.lean --audit Audit.lean \
  --theorems headline-theorems.txt --baseline metrics-baseline.txt
```

## 10. 合規與完成狀態

- 本輪**只做 proofs**：模型與定理敘述未改，三個 `sorry` 已換成證明；沒有為
  下一階段預先產出。
- **沒有**建立或推送任何分支、**沒有** `git push`、**沒有** `gh pr create`、
  **沒有**在 Issue 留言或寫入任何發現、**沒有**自行貼 `stage/done`；這些由
  機制端處理。本輪也沒有建立本地 commit，產出留在工作區。
- `specs/quint/RULES.md`、`TRIAGE.md`、`REPORT.md`、`specs/lean/RULES.md`、
  `specs/lean/G3-SIGNOFF.md` 都是唯讀輸入，本輪未修改。
- 獨立對抗式審查**由機制端執行**（aswf-dev/software_factory#143）；本 agent
  沒有建立 `specs/lean/REVIEW.md`，也沒有把它標成已完成。審查判定 FAIL 時
  本階段交還人類。
- 完成訊號是 Issue 上的 `stage/done`（由機制端在取回產出後貼上）。
- **尚未宣稱程式已驗證**：本輪完成的是模型層級證明與閘門；程式層級結論
  （真實部署可重現此交錯）依賴 §4 的假設與人類審查。

## 11. 本輪變更檔案與 `changedPaths`

`factory-crosscheck` 的 proofs 階段把 `specs/lean/**`（`RULES.md` 與
`G3-SIGNOFF.md` 兩個唯讀輸入除外）整棵樹視為本階段的交付；因此
`.factory/run/report.json` 的 `changedPaths` 列出全部 13 個檔案，包含
statements 階段建立、本輪未改的鷹架（`lakefile.toml`、`lean-toolchain`、
`lake-manifest.json`、`.gitignore`、`Specs.lean`、`Specs/Model.lean`、
`headline-theorems.txt`、`metrics-baseline.txt`），以免被判
`diff-not-reported`。`RULES.md` 與 `G3-SIGNOFF.md` 是唯讀還原輸入，不列入。

實際內容有編輯的 5 個檔案：

| 檔案 | 變更 |
|---|---|
| `specs/lean/Specs/Proofs.lean` | 三個 `sorry` → kernel 證明；import `Specs.Examples`；更新階段註解 |
| `specs/lean/Specs/Examples.lean` | 只更新階段說明註解；witness trace／狀態不變 |
| `specs/lean/Audit.lean` | 只更新階段說明註解；`#print axioms` 三行不變 |
| `specs/lean/MODEL-MAP.md` | proof status、statement stability、G3 簽核者／時間、閘門 checklist、re-sync log |
| `specs/lean/REPORT.md` | 改寫為 proofs 階段報告（本檔） |

其餘 8 個檔案是本輪未修改的 statements 階段鷹架／輸入，內容逐字沿用；
`changedPaths` 仍如實列出，因為它們在 `specs/lean/**` 交付範圍內。
