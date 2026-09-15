# STRICT の置き場の実Db2試験と SSE のブラウザ試験 (2026-09-15)

## 結論

- 会話ストア、端末の登録、START / TD の置き場、SPRING_MANAGED / DB2_DRIVER_MANAGED_HOLD の STRICT の task 境界
  (暫定判断 P-143・P-144、設計 83) の試験を、H2 と同じ内容のまま Docker Compose 上の Db2 Community 12.1.5.0 で流し、
  27 件すべて通った。
- 端末へ出す task の画面を SSE で知らせてブラウザが読み直すこと (設計 83 §7) を、Playwright で実際の Edge を動かして
  確かめ、2 件とも通った。
- ブラウザの試験で、開いている SSE の接続が application の停止を最長 30 秒 (graceful shutdown の期限) 止める不具合が
  見つかった。controller が開いている SSE を覚え、web server の graceful shutdown より先に閉じるよう直した。

## 検証環境

| 項目 | 値 |
| --- | --- |
| Db2 server | Community Edition 12.1.5.0 (`icr.io/db2_community/db2:12.1.5.0`、`infra/db2/compose.yaml`) |
| JDBC driver | IBM JCC 12.1.4.0 (test scope) |
| database | `COBOLDB`、試験ごとに使い捨ての schema (`CJT` で始まる名前) |
| lock timeout | 試験の connection は `SET CURRENT LOCK TIMEOUT 10` |
| ブラウザ | 導入済みの Microsoft Edge (Playwright 1.59.0、channel `msedge`、headless)。ブラウザは取得していない |
| server | Spring Boot 4.1.1 の組み込み Tomcat (random port)、Spring Security の form login |

## 実Db2試験 (`Db2StrictStoresIntegrationTest`)

H2 の試験 class を継承し、database だけを Db2 に替えた `@Nested` class で流した。assertion は H2 と同一である。

| class | 件数 | 確かめたこと |
| --- | --- | --- |
| ConversationStore | 5 | DDL、会話の条件つき更新、重複作成 (SQLCODE -803 → `DuplicateKeyException`)、冪等キーの予約と記録、8 本同時の claim で 1 件だけ |
| TerminalRegistry | 5 | 2 つの置き場からの端末登録、8 本同時の lease で 1 件だけ、固定の端末名、画面の版と BLOB |
| Starts | 5 | 2 つの dispatcher で満了した START を 1 度だけ、TERMIDERR、端末を待つ START、RETRIEVE WAIT の取り出し |
| TransientData | 6 | 4 本同時の読み出しで各 record を 1 度だけ、trigger の状態遷移、TERMINAL の trigger |
| SpringManagedBoundary | 3 | 業務の表と会話を同じ UOW で commit、会話を保存できなければ業務も rollback、SYNCPOINT |
| DriverManagedBoundary | 3 | JCC の native lease 上で業務の表と会話を同じ UOW で commit、別 connection から commit 前が見えない、SYNCPOINT 後も同じ lease |

終了後、`SYSCAT.SCHEMATA` と `SYSCAT.TABLES` に `CJT` の schema / 表が残っていないことを確かめた。

Db2 の接続情報は起動中のコンテナの設定から試験の環境変数へ渡し、画面や記録には出していない。

## ブラウザ試験 (`CicsBrowserSseBrowserTest`)

| 試験 | 確かめたこと |
| --- | --- |
| 端末へ出す task の画面 | form login → transaction 開始 → terminal.js が SSE を開く → 試験が端末へ画面を置く → 利用者の操作なしに `/cics/terminal` へ移り、置いた文字が出る |
| 自分の送信の応答 | Enter の応答を受けたあと 3 秒 (見回り 3 回分) 待っても読み直さない |

## 見つかった不具合

最初の実行で、試験は通ったが試験の JVM が停止に 30 秒以上かかった。thread dump では
`SpringApplicationShutdownHook` が `DefaultLifecycleProcessor` の停止を待ち、Tomcat の graceful shutdown が
開いている SSE (期限 5 分) の終わりを待っていた。本番でも、画面を開いている利用者がいる限り停止が遅れる。

`CicsBrowserController` を `SmartLifecycle` にし、既定の phase (web server の graceful shutdown より先) で開いている SSE を
すべて閉じるようにした。直したあとは、ブラウザ試験と MockMvc の試験を合わせた実行が 19 秒で終わった。

## 未検証

- Chrome / Firefox / Safari、および実際の利用者端末の proxy を挟んだ SSE
- 別プロセスの複数 JVM (試験は 1 つの JVM の中の 2 つの置き場で見立てた)
- Db2 の lock timeout / deadlock (-911 / -913) が起きたときの分類と再試行
- Spring Session JDBC を使った複数 JVM の HTTP session と、SSE の接続を持つ JVM の停止
- 負荷のもとでの dispatcher の間隔、SSE の接続数と DB の読み出しの量
