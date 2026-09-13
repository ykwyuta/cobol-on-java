# アーキテクチャ決定記録 (ADR)

このディレクトリには、複数の実装案があり、あとから覆すと公開 API や実行時の互換性へ
影響する判断を残す。未検証の挙動を解消条件つきで残す
[`provisional.md`](provisional.md) とは役割が異なる。

| ADR | 状態 | 決定 |
| --- | --- | --- |
| [ADR-0001](0001-unified-program-resolution.md) | 採用 | COBOL と Java の呼び先を明示的なプログラムカタログで解決する |
| [ADR-0002](0002-byte-oriented-call-contract.md) | 採用 | 呼び出し ABI は `DataView`、Java 向け API は生成した型付きビューを使う |
| [ADR-0003](0003-execution-unit-and-lifecycle.md) | 採用 | Java からの連続呼び出しはスレッド非共有の `CobolSession` を実行単位とする |
| [ADR-0004](0004-call-failure-and-control-flow.md) | 採用 | COBOL の制御終了と解決失敗と実行失敗を別の結果・例外として扱う |
| [ADR-0005](0005-junit-adapter-module.md) | 採用 | JUnit 連携を製品ランタイムから分離した `cobol-junit` に置く |
| [ADR-0006](0006-program-and-section-test-seams.md) | 採用 | サブルーチンはカタログ上書き、SECTION は明示的な PERFORM 境界で差し替える |
| [ADR-0007](0007-framework-neutral-subsystem-ports.md) | 採用 | CICS / Db2 の中核をフレームワーク非依存ポートとして分離する |
| [ADR-0008](0008-cics-on-spring-mvc-and-session.md) | 採用 | CICS タスクを同期 Spring MVC 要求と Spring Session の疑似会話へ写像する |
| [ADR-0009](0009-db2-spring-managed-unit-of-work.md) | 採用 | Db2 と CICS の UOW を Spring のプログラム的トランザクション管理へ写像する |
| [ADR-0010](0010-bms-thymeleaf-terminal-ui.md) | 採用 | BMS 画面を中立モデルから Thymeleaf、JavaScript、CSS で再現する |
| [ADR-0011](0011-separate-procedural-and-oo-java-interop.md) | 採用 | 手続き型 CALL と OO COBOL の Java 連携を別の契約として設計する |
| [ADR-0012](0012-db2-driver-managed-uow-for-required-hold-cursors.md) | 採用 | `WITH HOLD` 必須タスクは Db2 ドライバ管理 UOW で実行する |

ADR の状態は `提案`、`採用`、`廃止`、`置換` のいずれかとする。決定を変える場合は過去の
記録を書き換えず、新しい ADR から置き換える ADR を参照する。
