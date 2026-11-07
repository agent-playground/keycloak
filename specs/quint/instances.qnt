// HOTP 模型的具體實例（ADR-029 §5 階段②／quint）
//
// 每個模組固定 model.qnt 的常數。常數選擇理由見 REPORT.md「實例與常數」。
// 這些是模擬規模的具體值，不是從程式碼推導出的限制。

// 預設部署：Keycloak lookAheadWindow 預設 1、initialCounter 0；
// 兩個並行請求足以重現 ACT-7 的 lost update（INV-1）。
module hotpDefault {
  import hotpOneTime(N = 2, W = 1, INITIAL_COUNTER = 0).* from "./model"
}

// 邊界：lookAheadWindow = 0（窗口只有 snapshot 本身）。
module hotpWindowZero {
  import hotpOneTime(N = 2, W = 0, INITIAL_COUNTER = 0).* from "./model"
}

// 三個並行請求 + 非零 initialCounter + 較寬窗口：
// 可重現 stale 寫入把已經前進的 counter 覆蓋回較低值（INV-2、INV-3）。
module hotpThreeRequests {
  import hotpOneTime(N = 3, W = 2, INITIAL_COUNTER = 5).* from "./model"
}

// 邊界：只有一個請求，沒有並行交錯；作為對照實例。
module hotpSingleRequest {
  import hotpOneTime(N = 1, W = 1, INITIAL_COUNTER = 0).* from "./model"
}
