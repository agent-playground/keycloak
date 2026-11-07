# G2 反例分類（TRIAGE）

每一個反例的判定：真缺陷（real）／模型假象（artifact）／設計取捨（accepted），是否送 Lean，是否涉及資安。

- 判定者（actor）：philipz
- 時間（at）：2026-11-07T14:06:51.814Z

| id | verdict | toLean | security |
|---|---|---|---|
| inv1-default | real | yes | no |
| inv1-three-requests | real | no | no |
| inv1-window-zero | real | no | no |
| inv2-default | real | yes | no |
| inv2-three-requests | real | no | no |
| inv3-default | real | yes | no |
| inv3-three-requests | real | no | no |
