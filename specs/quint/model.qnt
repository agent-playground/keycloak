// HOTP 一次性驗證模型（ADR-029 §5 階段②／quint）
//
// 依 specs/quint/RULES.md 建模，RULES.md 是唯讀輸入；本模型不擴大、不修正規則。
// 驗證範圍：單一 realm、單一使用者、單一 HOTP 憑證；使用者快取啟用，
// 驗證讀取可能取得落後於持久化 counter 的快照（ENV-6，grill Q1 選項 a）。
//
// 抽象說明：
//   - TOKEN 以「它所匹配的 counter 值」抽象，不計算 HMAC（ENV-9）。
//   - 驗證讀取只會取得 INITIAL_COUNTER..counter 之間的值；RULES 只允許
//     snapshot 落後於持久化 counter（ENV-6），不允許超前。
//   - 寫入動作把 JPA 的 em.find + commit 視為一個原子步；RULES OUT-5 已明確
//     不驗證 @Version 衝突時的交易中止，因此模型不建立中止分支。任何模型
//     軌跡都可依「寫入動作發生時才 em.find」的順序實現，不會產生不存在的反例。

module hotpOneTime {
  // ── 設定常數 ──────────────────────────────────────────────────────────
  //
  // domain_justification: 程式未限制可同時驗證的請求數；N 是本模型用於模擬的
  // 並行請求數上限，不是程式限制。實例涵蓋 N=1（無並行）與 N=2、N=3（並行）。
  // 證據：services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:87-131
  // （isValid 沒有請求數限制）。
  const N: int

  // domain_justification: RULES ENV-1 宣告 lookAheadWindow >= 0；程式欄位是 int，
  // 未限制上界（server-spi/src/main/java/org/keycloak/models/OTPPolicy.java:42-48,61-75；
  // services/src/main/java/org/keycloak/credential/OTPCredentialProvider.java:104-109）。
  // 實例涵蓋邊界 W=0、預設 W=1、以及 W=2；W>2 不在本輪模擬範圍。
  const W: int

  // domain_justification: RULES ENV-1 宣告 initialCounter >= 0；程式欄位是 int，
  // 未限制上界（server-spi/src/main/java/org/keycloak/models/OTPPolicy.java:42-48,61-75）。
  // 實例涵蓋邊界 0 與非零值 5；initialCounter<0 不在 RULES 的宣告範圍。
  const INITIAL_COUNTER: int

  // ── 型別 ──────────────────────────────────────────────────────────────
  type ReqId = int

  type Phase = Idle | Read | Validated | Failed | Rejected | Done

  type RequestState = {
    phase: Phase,
    snapshot: int,
    matched: int,
  }

  // successes 是 ghost 歷史：每次 isValid 回傳 true（commit 成功）時追加一筆。
  // 以 request 區分事件，避免同一個 matched 值被集合去重。
  type Success = {
    request: ReqId,
    matched: int,
  }

  pure val REQS: Set[ReqId] = if (N <= 0) Set() else 0.to(N - 1)

  // ── 狀態 ──────────────────────────────────────────────────────────────
  //
  // counter：持久化 counter（OTPCredentialData.counter，存於 credentialData JSON）。
  // maxCounter：ghost，記錄 counter 曾經到達的最大值，用於檢查 INV-3。
  // reqs：每個並行請求的本地狀態（snapshot 與 matched）。
  // successes：成功驗證的 ghost 歷史，用於檢查 INV-1/INV-2。
  var counter: int
  var maxCounter: int
  var reqs: ReqId -> RequestState
  var successes: Set[Success]

  // ── 不變量（RULES.md INV） ────────────────────────────────────────────
  //
  // INV-1：任一 matched counter 值最多只有一次成功驗證。
  // 以 request 區分成功事件；同一個 matched 出現在兩個不同 request 即違反。
  val inv1NoReusedMatchedCounter: bool =
    successes.forall(s1 => successes.forall(s2 =>
      (s1.matched == s2.matched) implies (s1.request == s2.request)))

  // INV-2：持久化 counter 必須大於所有曾成功匹配的 counter 值。
  // 「曾成功」以 isValid 回傳 true（commit 成功）為準（ACT-9）。
  val inv2CounterAheadOfAllMatches: bool =
    successes.forall(s => counter > s.matched)

  // INV-3：持久化 counter 不得下降；maxCounter 是歷史最大值。
  val inv3CounterMonotonic: bool =
    counter >= maxCounter

  // ── 行為（RULES.md ACT／ENV） ─────────────────────────────────────────
  //
  // ACT-1：空 challenge／空 credentialId 直接拒絕，不讀取憑證、不改 counter。
  action rejectInvalidInput(r: ReqId): bool = all {
    reqs.get(r).phase == Idle,
    reqs' = reqs.set(r, { ...reqs.get(r), phase: Rejected }),
    counter' = counter,
    maxCounter' = maxCounter,
    successes' = successes,
  }

  // ACT-2／ENV-5／ENV-6：依 credentialId 讀取憑證，取得可能落後的 snapshot。
  // snapshot 的範圍是曾被持久化的值：INITIAL_COUNTER..counter。
  action readSnapshot(r: ReqId, snap: int): bool = all {
    reqs.get(r).phase == Idle,
    INITIAL_COUNTER <= snap,
    snap <= counter,
    reqs' = reqs.set(r, { ...reqs.get(r), phase: Read, snapshot: snap, matched: 0 }),
    counter' = counter,
    maxCounter' = maxCounter,
    successes' = successes,
  }

  // ACT-3／ENV-3：以 snapshot 為起點掃描 snapshot..snapshot+W；模型以「token
  // 所匹配的 matched 值」抽象，因此 matched 落在這個窗口內即代表掃描成功。
  action validateSuccess(r: ReqId, matched: int): bool = all {
    reqs.get(r).phase == Read,
    reqs.get(r).snapshot <= matched,
    matched <= reqs.get(r).snapshot + W,
    reqs' = reqs.set(r, { ...reqs.get(r), phase: Validated, matched: matched }),
    counter' = counter,
    maxCounter' = maxCounter,
    successes' = successes,
  }

  // ACT-3／ACT-4：token 不在窗口中 → 驗證失敗，不寫入 counter。
  action validateFailure(r: ReqId): bool = all {
    reqs.get(r).phase == Read,
    reqs' = reqs.set(r, { ...reqs.get(r), phase: Failed }),
    counter' = counter,
    maxCounter' = maxCounter,
    successes' = successes,
  }

  // ACT-4／ENV-4／ACT-5／ACT-6：成功時以整筆 credentialData 覆蓋，寫入
  // matched+1；沒有 compare-and-set，也沒有「counter 必須等於 snapshot」的條件。
  // 因此 stale 寫入可以把較高的持久化 counter 覆蓋成較低的值。
  action commitSuccess(r: ReqId): bool = {
    val nextCounter = reqs.get(r).matched + 1
    val success: Success = { request: r, matched: reqs.get(r).matched }
    all {
      reqs.get(r).phase == Validated,
      counter' = nextCounter,
      maxCounter' = if (nextCounter > maxCounter) nextCounter else maxCounter,
      successes' = successes.union(Set(success)),
      reqs' = reqs.set(r, { ...reqs.get(r), phase: Done }),
    }
  }

  // ── 非確定性包裝（供 step 使用） ───────────────────────────────────────
  action readSnapshotAny(r: ReqId): bool = {
    nondet snap = INITIAL_COUNTER.to(counter).oneOf()
    readSnapshot(r, snap)
  }

  action validateSuccessAny(r: ReqId): bool = {
    nondet matched = reqs.get(r).snapshot.to(reqs.get(r).snapshot + W).oneOf()
    validateSuccess(r, matched)
  }

  // ── 初始狀態與步進 ────────────────────────────────────────────────────
  action init: bool = all {
    counter' = INITIAL_COUNTER,
    maxCounter' = INITIAL_COUNTER,
    reqs' = REQS.mapBy(_ => { phase: Idle, snapshot: INITIAL_COUNTER, matched: 0 }),
    successes' = Set(),
  }

  action step: bool = {
    nondet r = REQS.oneOf()
    any {
      rejectInvalidInput(r),
      readSnapshotAny(r),
      validateSuccessAny(r),
      validateFailure(r),
      commitSuccess(r),
    }
  }
}
