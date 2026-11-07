Verdict: PASS-WITH-NITS

# Issue #5（agent-playground/keycloak，base `main`@`d4d55541`）— Lean 獨立對抗式審查（Phase 3）

一句話結論：三條 headline theorem 建置綠、無後門、敘述未弱化且由可達的具體 witness 非空洞證明，witness 時序不依賴實作走不到的行為（放寬處皆已標成 assumption），報告誠實區分模型層級與程式層級——判定 **PASS-WITH-NITS**，僅有引用行號偏差等不影響結論的瑕疵。

- 審查者：機制端獨立 session（aswf-dev/software_factory#143），未參與撰寫；ADR-029 §5、§7。
- 審查對象：`specs/lean/**`（除本檔外全部唯讀）；對照 `specs/quint/**` 與目標 repo 實作（`services/**`、`server-spi/**`、`model/**`）。
- 語言依 `specs/quint/.user-content/CONTEXT.md` 的 `## Output language (MANDATORY)`：繁體中文。
- 本審查未修改 `REVIEW.md` 以外任何檔案；`lake build` 僅產生 `specs/lean/.lake/`（白名單排除）。

---

## 1. 建置與後門掃描 — 通過（無 blocker）

實測（於 `specs/lean`）：

- `rm -rf .lake/build && lake build`：exit 0，`Build completed successfully (6 jobs)`。工具鏈 `lean-toolchain: leanprover/lean4:v4.35.0` 與本機 Lean 4.35.0 相符；`lake-manifest.json` 零依賴（只用 Lean core，無 Mathlib）。
- `lake env lean Audit.lean`（exit 0，無任何 warning）：
  - `Hotp.inv1_same_matched_counter_accepted_twice` 依賴 `[propext, Classical.choice, Quot.sound]`——全在 allowlist；
  - `Hotp.inv2_counter_not_ahead_of_all_matches` 與 `Hotp.inv3_counter_can_decrease` 依賴 `[propext]`。
  - 無 `sorryAx`、無自定義 axiom。
- 後門掃描（全 `.lean`：`Specs.lean`、`Specs/Model.lean`、`Specs/Proofs.lean`、`Specs/Examples.lean`、`Audit.lean`）：`sorry`／`admit`／`axiom` 宣告／bodyless `opaque`／`native_decide`／`unsafe`／`extern`／`@[implemented_by]`／`set_option` **零命中**。「sorry」一詞僅出現在註解文字（`Specs/Proofs.lean:11`、`Audit.lean:4`）。
- 重跑 skill 閘門 `verify-lean.sh --dir specs/lean --main Specs/Proofs.lean --audit Audit.lean --theorems headline-theorems.txt --baseline metrics-baseline.txt`：`RESULT: PASS`、exit 0；build／direct compile／coverage／guardrails（0 hit）／axioms（3 theorem checked、3/3 expected reported）全綠；metrics 對 baseline 無 regression。`headline-theorems.txt` 三條名稱與 `Audit.lean:10-12` 的 `#print axioms` 一一對應（completeness 通過）。
- 證明只用 kernel `by decide` 檢查具體列表／`Nat` 計算（`Specs/Examples.lean:63-84`、`Specs/Proofs.lean:35-36,53-54,71-73`），組建約 0.5s，無決策膨脹跡象；屬 skill 允許的「小閉合可計算事實」。

结论：**1.1 通過**（severity：info）。

## 2. Statement fidelity（定理對應規則，未被弱化／同義反覆）— 通過

逐條比對 `specs/lean/MODEL-MAP.md:40-42` 的 statement snapshot、`Specs/Proofs.lean:32-33,50-51,67-69` 現行敘述、`specs/lean/RULES.md:40-65`（R1–R3，轉錄自 `specs/quint/RULES.md:25-27` 的 INV-1～INV-3）與 `specs/quint/TRIAGE.md:10-15` 的 G2 判定（`inv1/2/3-default` = real、toLean=yes）：

- **R1／`inv1_same_matched_counter_accepted_twice`**（`∃ s, Reachable defaultConfig s ∧ hasReusedMatchedCounter s = true`）：`hasReusedMatchedCounter`（`Model.lean:176-179`）= 存在兩筆 `successes` 同 `matched` 且不同 request，正是 INV-1（「任一 matched counter 值最多一次成功驗證」，`specs/quint/RULES.md:25`）的否定式。`successes` 在每次 `commit`（= `isValid == true`，ACT-9）追加（`Model.lean:157`），事件語意正確。「不同 request」條件不構成弱化：模型中每個 request 只成功一次（`Phase` 機器 `validated → done` 单向，`Model.lean:150-158`），同 request 不可能產生兩筆成功，與 R1 文字「不得有兩個不同請求以同一 matched 成功」等價。非 `P → P`、非同義反覆。
- **R2／`inv2_counter_not_ahead_of_all_matches`**（`… ∧ counterAheadOfAllMatches s = false`）：定義（`Model.lean:183-184`）為「並非所有成功 matched < 當前 counter」，即 `∃ matched ≥ counter`，正是 INV-2（`p > max(matched_history)`）的反例；`successes` 只增不減（`Model.lean:157`），任一時刻的違反等價於某可達狀態上的違反。對應 G2 `inv2-default` 判定。
- **R3／`inv3_counter_can_decrease`**（`∃ s t, Reachable s ∧ StepRel s t ∧ t.counter < s.counter`）：`StepRel`（`Model.lean:170-171`）是任一單步轉移；模型中唯一能改 `counter` 的動作是 `commit`（`Model.lean:125-158`，`read/validate/fail/reject` 皆不改），而 `commit` 只在 `validated` 階段、即成功驗證——所以「t.counter < s.counter」蕴含該步就是一筆成功驗證寫入，與 R3 文字「不得存在一筆成功的驗證寫入使新值小於舊值」等價。R3 明確不用 Quint 的 ghost `maxCounter`（`specs/lean/RULES.md:65` 已登錄此敘述形式，且兩種形式等價，因為 counter 只由單步寫入改變）。
- 三條都是「G2 反例的模型層級可達性」，與 G2 `toLean=yes` 的對象一致；未把規則改寫成更容易證的形式。

**敘述穩定性**：`Proofs.lean` 現行三條 `theorem` 與 `MODEL-MAP.md:40-42` snapshot、`REPORT.md:131-139` snapshot 三者逐字一致；`.factory/run/stderr.txt`（proofs agent 的 session 推理記錄，例如第 185-187、186 行）顯示其從 statements 檔案帶出的敘述與最終版相同，且意圖記錄為「保留 statements 逐字」。完整位元級驗證受限（見 §「未驗證」），但現有證據一致、無弱化跡象。

結論：**2. 通過**（info；附一項未驗證限制）。

## 3. Non-vacuity（假設可達、witness 非空洞）— 通過

- 每條存在量詞都由**具體 trace** 供給：`traceInv1`（`Examples.lean:25-26`）、`traceInv2`（`Examples.lean:41-42`）、`traceInv3Before` + `commit 1`（`Examples.lean:52-53`、`Proofs.lean:72`），`Reachable`／`StepRel` 的證明就是 `⟨trace, by decide⟩`（`Proofs.lean:35,53,71-72`），`run … = some finalX` 由 kernel `decide` 機驗（`Examples.lean:63-84` 七個 `example`）。
- 手工重推三條 trace 的每一步守衛（`Model.lean:125-158`）與最終狀態（`finalInv1`：counter 2、successes `[(0,1),(1,1)]`；`finalInv2`：counter 1、successes `[(0,1),(1,0)]`；`beforeInv3`：counter 2）皆正確；違規旗標 `hasReusedMatchedCounter finalInv1 = true`、`counterAheadOfAllMatches finalInv2 = false`、`finalInv2.counter < beforeInv3.counter` 均由 `decide` 檢查。假設不存在「永遠為假」：witness 存在即證明可满足。
- `traceInv1` 與 G2 判定的 Quint `traces/inv1-default.itf.json` 步驟逐一相同（read req1@0 → validate req1@1 → read req0@0 → validate req0@1 → commit req0 → commit req1；對照 `specs/quint/REPORT.md:236-241` 的 F1 表）。

結論：**3. 通過**（info）。

## 4. Model fidelity（模型轉移 vs 實作）— 通過（這是最關鍵的一節，逐點查證）

對照目標 repo 原始碼（全部本人於本 session 逐檔核對，非引用對方報告）：

- **讀取路徑**：`OTPCredentialProvider.java:102-104` 以 `getStoredCredentialById` 讀憑證並從回傳的 JSON `credentialData` 反序列化（`OTPCredentialModel.java:75-91`）；快取啟用時由 `UserAdapter.java:349-361` 提供 `CachedUser` 清單（`updated == null` 才走快取，語意正確）、`CachedUser.java:80,156-159`（lazy 快照 + `shallowClone`）、`DefaultLazyLoader.java:62-80`（快照載入後不重讀）。模型 `read r snapshot`（階段 idle→read snapshot）與 `MODEL-MAP.md:18-27` 引用全部屬實。
- **視窗掃描**：`HmacOTP.java:82-93` 從 snapshot counter 掃到 `counter + lookAroundWindow`，**第一個**符合者回傳 `matched+1`；`OTPCredentialProvider.java:109` 以其返回值 `counter` 再 `updateCounter(counter)`（`113`），即寫入 `matched+1`。模型 `validate` 守衛 `snapshot ≤ matched ≤ snapshot + window`（`Model.lean:138-145`）與 `commit` 寫 `matched+1`（`Model.lean:150-155`）忠實對應。ENV-9/A-3 的 token 抽象（`specs/quint/RULES.md:19`）使「第一符合」不需建掃描順序：同一 token 恒匹配同一 counter（無碰撞假設，OUT-2），witness 中 `matched=1` 對 snapshot 0 需先試 0 再中 1、`matched=0` 直接中 0，皆與 first-match 語意相容——不产生實作不存在的 matched 值。
- **無 CAS 整筆覆蓋**：`JpaUserCredentialStore.java:63-76`——寫入時才 `em.find`（`:64`），然後 `entity.setCredentialData(cred.getCredentialData())`（`:75`）整筆覆蓋，無 `WHERE counter = …`；`CredentialEntity.java:77-79` 的 `@Version` 只保護「寫入 em.find 起」的區間（`CredentialModel.java:64-72` 無 version 欄位可攜帶舊版）。模型 `commit` 無前置 `counter == snapshot` 檢查（`Model.lean:150-158`）忠實。
- **失敗／拒絕不寫 counter**：`OTPCredentialProvider.java:94-100,110-112`；模型 `fail`／`reject` 不動 counter（`Model.lean:126-129,146-149`）。
- **時序（本輪最要緊的對抗檢查：witness 是否依賴實作走不到的行為）**：
  - 三條 Lean witness 的**兩個 `read` 都排在第一次 `commit` 之前**（`Examples.lean:26,42,53`）。在實作中这就是「兩個請求的 session 都在任何寫入提交前先取得（同一份落後）快取快照」——快取預熱後兩個 session 各自 `UserAdapter` 讀到同一 `CachedUser` 快照（counter 仍為初始 0），可達性直接成立；**不需要**任何較難辯護的「commit 之後仍讀到舊值」時序。此放寬（`read` 允許任意歷史值在任何時刻發生）已依 ADR-029 標成 assumption：`specs/lean/RULES.md:69`（A-1）、`MODEL-MAP.md:91`、`REPORT.md:100-104` 三處均明示「witness 不使用此路徑」，且我逐 trace 驗證該宣稱**為真**。
  - 與 Quint 對照：G2 選入的 `traces/inv2-default.itf.json`／`inv3-default.itf.json` 用的是「commit 之後才 read 到 snapshot 0」的交錯（`specs/quint/REPORT.md:259-290`）；Lean proofs 把 `read` 前移，改以程式更易達的交錯證明**同一違規**（`MODEL-MAP.md:76-80` 有記錄）。這是朝更保守方向重排，不是換命題。
  - `@Version`：模型把 `em.find` + commit 視為一個會成功的原子步（A-2／OUT-5，`specs/lean/RULES.md:70`、`MODEL-MAP.md:70,89`）。witness 中 B 的寫入交易對 credential row 的首次觸碰就在 A commit 之後（B 的驗證讀取來自快取、不進 JPA persistence context），因此該原子步假設在 witness 時序下不需rollback 分支即成立——與 `JpaUserCredentialStore.java:64` 的實際行為一致；未標記的「其他交易插入時中止」行為不在任何定理裡。
  - 排程／延遲／計時器：本模型無 timer、無相對延遲排程（缺陷是 lost update，不是 timeout），故「模型允許 deadline 前提前觸發」此類錯配不適用；我確認 `Action` 全集只有五種離散步進（`Model.lean:98-109`）。
  - `MODEL-MAP.md:111-113` 與 `REPORT.md:88-91` 明示「程式可達性不是 Lean 的自動結論」；反例的每個 conjunct（read 的 snapshot 值、窗口匹配、`matched+1` 覆蓋）對應到上列真實程式決策，模型自加的抽象欄位 `history`（只在 read 守衛內、`Model.lean:133`）**不出現在任何 headline statement**，`successes` 為 `isValid == true` 事件的 ghost log（ACT-9，`specs/quint/RULES.md:40`），非宣稱的程式欄位。
- **比 Quint 模型更保守處**：Lean `read` 要求 `snapshot ∈ history ∧ snapshot ≤ counter`（`Model.lean:130-137`），严格含於 Quint 的 `INITIAL_COUNTER..counter`（`model.qnt:96-104`；例如 stale 寫入使 counter 回落後，Quint 可讀到「曾可跳過的中間值」、Lean 不可）。模型變窄只會讓反例更難成立；`MODEL-MAP.md:91` 如實記錄。
- **引用抽查**：`MODEL-MAP.md:18-30` 的 12 筆 `file:line` 全部命中實際程式碼（僅一筆行號偏差，見下方 nit-1）；`specs/lean/RULES.md:43-45` 引 `OTPCredentialProvider.java:113-115/120-127`、`OTPPolicy.java:76`（`DEFAULT_IS_REUSABLE=false`）、`InfinispanUserCacheProviderFactory.java:90-93`（快取預設啟用）均屬實。

結論：**4. 通過**（info；無「witness 依賴走不到的行為而未標 assumption」之情事）。

## 5. 反例的意義（模型層級 vs 程式層級；假設登錄）— 通過

- 三條定理證明的是**模型層級** reachability／違反存在；`MODEL-MAP.md:111-113`（not covered）與 `REPORT.md:23-25,88-91` 一致声明程式層級「真實部署必可重現」未被 Lean 證明（OQ-5，`REPORT.md:226-228`）。
- 反例所依賴的三個建模假設（A-1 快取可 stale、A-2 寫入原子步、A-3 token 以 matched 抽象）與範圍參數 A-4（`hotpDefault`：N=2、W=1、initial=0）全部登錄於 `specs/lean/RULES.md:67-72`，並對應到 `specs/quint/RULES.md` 的 ENV-5/6/7/9、OUT-4/5 等已確認／已標 assumption 條款；無未登錄的隱性假設。
- 依判定規則：「反例依賴實作走不到的行為卻沒有標成 assumption」不成立（見 §4）；「把模型層級結論寫成程式層級」不成立（見 §6）。

結論：**5. 通過**（info）。

## 6. 報告誠實性（REPORT.md／MODEL-MAP.md）— 通過

- 無過度宣稱：`REPORT.md:23-25` 「這是模型層級的機器證明」；`REPORT.md:113-123`（§4.3）明示不使用「模型較寬所以真實也會壞」的推論，並逐 conjunct 對應到 `file:line`；`MODEL-MAP.md:100-102` 同樣自我設限。
- G3 簽核證據一致：`G3-SIGNOFF.md:6-8`（philipz、2026-11-07T14:30:12.030Z、approved）＝ `MODEL-MAP.md:122,132` ＝ `REPORT.md:41-43`。
- 對抗審查一項沒有自我背書：`MODEL-MAP.md:121,131` 保持未完成、指向本檔；驗證閘門 checklist（`MODEL-MAP.md:116-125`）其余項目我逐項復核均實（snapshot 相符、witness 為 `by decide`、來源映射屬實、baseline delta 重跑無 regression）。
- `REPORT.md:185-188` 主動披露 `verify-lean.sh` 的四條 printf 顯示層警告（腳本計數器問題，非專案問題；我重跑同樣僅此顯示層警告且 RESULT: PASS）。
- `REPORT.md:243-255`（§10）與 `.factory/run/report.json` 的 `changedPaths`（13 檔，全部在 `specs/lean/**`）與白名單相符；`.gitignore` 只排除 `.lake/`。工作區其他檔案（`specs/lean/RULES.md`、`G3-SIGNOFF.md`、`specs/quint/**`、`src` 對應路徑）我於本 session 開頭至結束以 mtime 核對：proofs 階段未改寫唯讀輸入（14:31–14:36 時間戳屬機制端還原與 proofs 交付範圍；`AGENTS.md` 刪除為機制端預先狀態、非 proofs agent 所為）。
- 小張阻：`specs/lean/RULES.md:6,13-14` 仍是 statements 階段措辭（「尚未證明，待 G3 簽核」「proofs 階段尚未開始」）——唯讀簽核輸入不可改，`REPORT.md:46-49` 已正確解釋；非誠實性問題。

結論：**6. 通過**（info）。

---

## 發現彙總（依嚴重度）

| # | 嚴重度 | 發現 | 證據 |
|---|---|---|---|
| 1 | minor | `MODEL-MAP.md:21` 引用 `OTPCredentialData.java` 的 `39-44`，實際 getter/setter 區間為 `38-44`（`:38 public int getCounter()`）；`9`（counter 欄位）正確。純引用行號偏差，不影響任何定理 | `server-spi/src/main/java/org/keycloak/models/credential/dto/OTPCredentialData.java:38-44` |
| 2 | minor | G2 選入的 `inv2-default`／`inv3-default` ITF trace 使用「commit 後才 read 到 stale snapshot」的交錯，Lean witness 改用「read 全在首次 commit 前」的不同交錯證明同一違規。方向是更保守、且 `MODEL-MAP.md:76-80` 有說明，但未明文標註「與該 ITF trace 步驟序列並非逐字相同（僅 `traceInv1` 相同）」，讀者可能誤以為三條 witness 都與 G2 trace 逐字一致 | `specs/quint/traces/inv2-default.itf.json`、`specs/quint/REPORT.md:259-290` vs `specs/lean/Specs/Examples.lean:41-53` |
| 3 | info | `finalInv1.history = [0,2,2]`（`Examples.lean:31`）含重複值：因兩個 commit 都寫 `matched+1=2`。對 `elem` 守衛無害、未被 headline 引用，但 `MODEL-MAP.md` 的 `history` 說明（「記錄持久化 counter 曾持有的值」）可補一句「允許重複、不影響語意」 | `specs/lean/Specs/Model.lean:155` |
| 4 | info | 「定理敘述與 statements 階段逐字相同」屬自我宣稱：`specs/` 未進 git（無 phase-2 基線可比對位元組），`G3-SIGNOFF.md` 未嵌入敘述全文或雜湊。現有佐證（`MODEL-MAP.md:40-42` snapshot = `Proofs.lean` 現文 = `REPORT.md:131-139`，加上 `.factory/run/stderr.txt` 中 proofs agent 轉錄的 statements 原文）完全一致，無矛盾跡象 | `specs/lean/MODEL-MAP.md:34-36,60-63`；`.factory/run/stderr.txt` |
| 5 | info | `specs/lean/RULES.md:6,13-14` 保留 statements 階段措辭（唯讀輸入，依工廠規則不得改；`REPORT.md:46-49` 已解釋）。下一輪若要交付給人類讀者，可在 MODEL-MAP 頂部加一行「RULES.md 為 G3 歷史快照，其階段措辭已過期」 | `specs/lean/RULES.md:6` |

無 blocker、無 major。

## 必須修（下一輪硬性）

- （無——依判定規則，本輪不構成 FAIL；以上 minor 不影響結論。）

## 可以不修（建議）

1. 修正 `MODEL-MAP.md:21` 的 `OTPCredentialData.java:39-44` → `38-44`（nit-1）。
2. 在 `MODEL-MAP.md:76-80` 補一句：「僅 `traceInv1` 與 `inv1-default` ITF 逐步相同；INV-2／INV-3 的 Lean witness 是將 `read` 前移後的等價交錯」（nit-2）。
3. `MODEL-MAP.md` history 說明補「可含重複值」（nit-3）。
4. 流程建議（非本工作項）：G3 簽核檔嵌入 statements 全文或雜湊，使「敘述未改」可被獨立驗證（nit-4）。
5. 下輪交付文件加註 RULES.md 為 G3 歷史快照（nit-5）。

## 未驗證（做不到的檢查，如實列）

1. **statements 階段檔案的位元級基線**：無 git 追蹤、無快照雜湊，「逐字相同」只能以 MODEL-MAP snapshot、REPORT snapshot 與 session log 轉錄交叉佐證（三者一致）。
2. **G2 判定與 G3 簽核的人類把關過程**：`TRIAGE.md`／`G3-SIGNOFF.md` 為服務端寫入的唯讀證據，只能核对其内容一致性，无法验证 philipz 实际阅读行为。
3. **程式層級排程可達性**（線程／交易交錯在真實部署必然發生）：本就不是 Lean 定理宣稱（OQ-5），需程式層級測試——本審查亦未做（未跑 Keycloak 整合測試；那超出 `specs/lean` 審查範圍且會觸及停手規則外的資源）。
4. **JPA 交易隔離級別細節**（如 MySQL REPEATABLE READ 下 `em.find` 快照可見性對 A-2 的影響）：屬 OUT-5／A-2 已登錄的條件性假設，模型未建、本審查亦未在真實 DB 驗證；witness 時序（B 首次觸碰 row 在 A commit 後）使其不需該分支，但「所有真實時序」不在宣稱內。
5. **N>2、W>1、initialCounter≠0 的參數化版本**（OQ-1）：不在定理宣稱範圍，未驗證。
6. **Quint 工具鏈重跑**：`specs/quint` 屬唯讀對照輸入；我以 ITF trace 檔與 `specs/quint/REPORT.md` 的逐狀態表核對（讀檔），未重跑 `quint run/test`。
7. **Infinispan 快取失效的多節點複製時序**（OUT-4）：未建模亦未驗證。

---

*審查時間：2026-11-07（本 session）。審查者為獨立對抗式 AI 審查（fresh context，未參與撰寫）；本檔為機制端（aswf-dev/software_factory#143）固定步驟產出，判定以第一行 `Verdict:` 為準。*
