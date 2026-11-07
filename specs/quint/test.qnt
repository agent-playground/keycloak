// HOTP 模型的規則情境 witness 與檢查（ADR-029 §5 階段②／quint）
//
// 本檔只放 run 測試與 val witness，不放模型邏輯。
// witness 一律正面描述「規則要保護的情境確實發生」，且不在 init 成立；
// 反例本身由 REPORT.md 記錄的 quint run --invariant 產生，不把 witness
// 寫成不變量的否定。
//
// 註：`fail()` 會斷言動作被停用，但不能在其後接續步驟；因此每個負向檢查
// 都是一個以 fail() 結尾的獨立 run。

// ── 預設實例：N=2, W=1, INITIAL_COUNTER=0 ──────────────────────────────
module hotpDefaultWitnesses {
  import hotpOneTime(N = 2, W = 1, INITIAL_COUNTER = 0).* from "./model"

  // 有請求真的讀了 snapshot（排除只被 ACT-1 拒絕、從未讀取憑證的請求）。
  val witnessAnyRead: bool =
    REQS.exists(r => reqs.get(r).phase != Idle and reqs.get(r).phase != Rejected)

  // 有請求被拒絕（ACT-1）。
  val witnessAnyRejected: bool =
    REQS.exists(r => reqs.get(r).phase == Rejected)

  // 有請求驗證失敗（ACT-3／ACT-4 的失敗分支）。
  val witnessAnyFailure: bool =
    REQS.exists(r => reqs.get(r).phase == Failed)

  // 有成功驗證被 commit（ACT-4／ACT-9）。
  val witnessAnyCommit: bool =
    successes.size() > 0

  // 有請求持有落後於持久化 counter 的 snapshot（ENV-5／ENV-6）。
  val witnessStaleSnapshotHeld: bool =
    REQS.exists(r => reqs.get(r).phase == Read and reqs.get(r).snapshot < counter)

  // 持久化 counter 已經前進（ENV-4）。
  val witnessCounterAdvanced: bool =
    counter > INITIAL_COUNTER

  // 有成功事件落在窗口末端 snapshot+W（ENV-3／ACT-3）。
  val witnessWindowEndMatched: bool =
    successes.exists(s => s.matched == INITIAL_COUNTER + W)
}

module hotpDefaultTests {
  import hotpOneTime(N = 2, W = 1, INITIAL_COUNTER = 0).* from "./model"

  // ENV-1：模型常數落在規則宣告的 domain；N>0 是模型可執行的前提。
  run env1PolicyDomainTest =
    all {
      N > 0,
      W >= 0,
      INITIAL_COUNTER >= 0,
    }

  // ACT-1：空 challenge／空 credentialId 被拒絕，counter 不變。
  run act1RejectInvalidInputTest =
    init
      .then(rejectInvalidInput(0))
      .expect(all {
        reqs.get(0).phase == Rejected,
        counter == INITIAL_COUNTER,
        successes.size() == 0,
      })

  // ACT-2：驗證讀取接受曾被持久化的 snapshot（INITIAL_COUNTER..counter）。
  run act2ReadSnapshotDomainTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER))
      .expect(all {
        reqs.get(0).phase == Read,
        reqs.get(0).snapshot == INITIAL_COUNTER,
      })

  // ACT-2／ENV-6：低於 INITIAL_COUNTER 的值不是曾被持久化的 snapshot。
  run act2ReadSnapshotBelowInitialRejectedTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER - 1).fail())

  // ACT-2／ENV-6：高於目前持久化 counter 的值不是「落後」的快照。
  run act2ReadSnapshotAboveCounterRejectedTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER + 1).fail())

  // ENV-3／ACT-3：窗口末端 snapshot+W 可以匹配。
  run env3Act3WindowEndAcceptedTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER))
      .then(validateSuccess(0, INITIAL_COUNTER + W))
      .expect(all {
        reqs.get(0).phase == Validated,
        reqs.get(0).matched == INITIAL_COUNTER + W,
      })

  // ENV-3／ACT-3：超出 snapshot+W 的 counter 不可匹配。
  run env3Act3BeyondWindowRejectedTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER))
      .then(validateSuccess(0, INITIAL_COUNTER + W + 1).fail())

  // ENV-4／ACT-4：成功驗證後寫入 matched+1，並留下成功事件。
  run env4Act4CommitWritesNextCounterTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER))
      .then(validateSuccess(0, INITIAL_COUNTER + W))
      .then(commitSuccess(0))
      .expect(all {
        counter == INITIAL_COUNTER + W + 1,
        maxCounter == INITIAL_COUNTER + W + 1,
        reqs.get(0).phase == Done,
        successes.contains({ request: 0, matched: INITIAL_COUNTER + W }),
      })

  // ACT-4：驗證失敗不寫入 counter，也不留下成功事件。
  run act4FailureDoesNotWriteTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER))
      .then(validateFailure(0))
      .expect(all {
        reqs.get(0).phase == Failed,
        counter == INITIAL_COUNTER,
        successes.size() == 0,
      })

  // ACT-4：驗證失敗的請求不能再 commit。
  run act4FailedRequestCannotCommitTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER))
      .then(validateFailure(0))
      .then(commitSuccess(0).fail())

  // ENV-5／ENV-6：請求可以持有比持久化 counter 更舊的 snapshot。
  run env5StaleSnapshotHeldTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER))
      .then(readSnapshot(1, INITIAL_COUNTER))
      .then(validateSuccess(0, INITIAL_COUNTER))
      .then(commitSuccess(0))
      .expect(all {
        counter == INITIAL_COUNTER + 1,
        reqs.get(1).phase == Read,
        reqs.get(1).snapshot == INITIAL_COUNTER,
        reqs.get(1).snapshot < counter,
      })

  // ACT-5／ACT-6／ACT-7：B 先讀舊 snapshot，A 成功並 commit，B 再用同一個
  // matched counter 成功並覆寫；兩次 isValid 都回 true，counter 只前進一次。
  run act5Act6Act7LostUpdateTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER))
      .then(readSnapshot(1, INITIAL_COUNTER))
      .then(validateSuccess(0, INITIAL_COUNTER))
      .then(commitSuccess(0))
      .then(validateSuccess(1, INITIAL_COUNTER))
      .then(commitSuccess(1))
      .expect(all {
        counter == INITIAL_COUNTER + 1,
        reqs.get(0).phase == Done,
        reqs.get(1).phase == Done,
        successes.size() == 2,
        successes.contains({ request: 0, matched: INITIAL_COUNTER }),
        successes.contains({ request: 1, matched: INITIAL_COUNTER }),
      })

  // ENV-9：同一個 token（以 matched counter 值表示）可以被兩個不同 snapshot
  // 的請求各自匹配；模型不計算 HMAC，也不建模碰撞。
  run env9SameTokenValueAcrossSnapshotsTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER))
      .then(validateSuccess(0, INITIAL_COUNTER + 1))
      .then(commitSuccess(0))
      .then(readSnapshot(1, INITIAL_COUNTER + 1))
      .then(validateSuccess(1, INITIAL_COUNTER + 1))
      .then(commitSuccess(1))
      .expect(all {
        counter == INITIAL_COUNTER + 2,
        successes.size() == 2,
        successes.contains({ request: 0, matched: INITIAL_COUNTER + 1 }),
        successes.contains({ request: 1, matched: INITIAL_COUNTER + 1 }),
      })
}

// ── 邊界實例：N=2, W=0, INITIAL_COUNTER=0 ──────────────────────────────
module hotpWindowZeroTests {
  import hotpOneTime(N = 2, W = 0, INITIAL_COUNTER = 0).* from "./model"

  // W=0 時窗口只有 snapshot 本身。
  run zeroWindowAcceptsSnapshotTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER))
      .then(validateSuccess(0, INITIAL_COUNTER))
      .then(commitSuccess(0))
      .expect(all {
        counter == INITIAL_COUNTER + 1,
        successes.size() == 1,
        successes.contains({ request: 0, matched: INITIAL_COUNTER }),
      })

  // W=0 時下一個 counter 必須被拒絕。
  run zeroWindowRejectsNextCounterTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER))
      .then(validateSuccess(0, INITIAL_COUNTER + 1).fail())

  // W=0 下仍可重現同一個 matched counter 的 lost update。
  run zeroWindowLostUpdateTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER))
      .then(readSnapshot(1, INITIAL_COUNTER))
      .then(validateSuccess(0, INITIAL_COUNTER))
      .then(commitSuccess(0))
      .then(validateSuccess(1, INITIAL_COUNTER))
      .then(commitSuccess(1))
      .expect(all {
        counter == INITIAL_COUNTER + 1,
        successes.size() == 2,
        successes.contains({ request: 0, matched: INITIAL_COUNTER }),
        successes.contains({ request: 1, matched: INITIAL_COUNTER }),
      })
}

// ── 三請求實例：N=3, W=2, INITIAL_COUNTER=5 ────────────────────────────
module hotpThreeTests {
  import hotpOneTime(N = 3, W = 2, INITIAL_COUNTER = 5).* from "./model"

  // ACT-5／ACT-6：stale 寫入沒有 CAS；B 持有 snapshot 5，A、C 已經把
  // counter 推到 7，B 仍可把持久化 counter 覆寫回 6（低於歷史最大值）。
  run act5Act6StaleWriteRewritesHigherCounterTest =
    init
      .then(readSnapshot(0, 5))
      .then(readSnapshot(1, 5))
      .then(validateSuccess(1, 5))
      .then(commitSuccess(1))
      .then(readSnapshot(2, 6))
      .then(validateSuccess(2, 6))
      .then(commitSuccess(2))
      .then(validateSuccess(0, 5))
      .then(commitSuccess(0))
      .expect(all {
        counter == 6,
        maxCounter == 7,
        successes.size() == 3,
        successes.contains({ request: 0, matched: 5 }),
        successes.contains({ request: 1, matched: 5 }),
        successes.contains({ request: 2, matched: 6 }),
      })
}

// ── 對照實例：N=1, W=1, INITIAL_COUNTER=0 ──────────────────────────────
module hotpSingleRequestTests {
  import hotpOneTime(N = 1, W = 1, INITIAL_COUNTER = 0).* from "./model"

  // 沒有並行交錯時，單一請求只能消耗一次。
  run singleRequestConsumesOnceTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER))
      .then(validateSuccess(0, INITIAL_COUNTER))
      .then(commitSuccess(0))
      .expect(all {
        counter == INITIAL_COUNTER + 1,
        successes.size() == 1,
        reqs.get(0).phase == Done,
      })

  // 同一個請求不能再 commit 第二次。
  run singleRequestCannotCommitTwiceTest =
    init
      .then(readSnapshot(0, INITIAL_COUNTER))
      .then(validateSuccess(0, INITIAL_COUNTER))
      .then(commitSuccess(0))
      .then(commitSuccess(0).fail())
}
