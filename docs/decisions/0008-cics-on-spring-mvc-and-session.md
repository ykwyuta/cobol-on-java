# ADR-0008: CICS タスクを同期 Spring MVC 要求と Spring Session の疑似会話へ写像する

| 項目 | 内容 |
| --- | --- |
| 状態 | 採用 |
| 日付 | 2026-09-09 |
| 関連要件 | FR-160〜165, NFR-022, NFR-023, NFR-034 |
| 関連設計 | [設計 77](../design/77-spring-cics-db2.md) |

## 文脈

CICS の疑似会話型処理では、要求ごとにタスクを開始し、`RETURN TRANSID` で次要求に必要な
COMMAREA またはチャネル・コンテナだけを残す。稼働中のタスク、COBOL の WORKING-STORAGE、
Db2 のカーソルやコネクションを HTTP セッションへ持ち越すものではない。

Spring JDBC の通常のトランザクション資源は実行スレッドへ束縛される。非同期実行へ安易に
切り替えると CICS タスクと Db2 作業単位の境界がずれる。

## 決定

CICS オンライン入口は Spring Boot 4.1 の Servlet / Spring MVC による同期要求とする。
TRANSID は URL や要求項目から受け取り、不変の `CicsTransactionRegistry` で許可済みの
`ProgramId` へ解決する。要求スレッド上で一つの `CicsTask` と `CobolSession` を生成し、
終了まで同じスレッドで実行する。タスク内で `@Async` や並列ストリームへ処理を逃がさない。

コマンドは次のように扱う。

- `LINK` は同じタスク、セッション、UOW で子プログラムを呼び、呼び元へ戻る。
- `XCTL` は同じタスクと UOW のまま現在プログラムを置換し、元へ戻らない。
- `RETURN` はタスクを終了する。`TRANSID` 指定時は次要求の会話状態を保存する。
- 正常終了は UOW を commit、未処理の ABEND は rollback する。
- `SYNCPOINT` は現在の UOW を完了し、後続の資源アクセス時に次の UOW を遅延開始する。

疑似会話の識別とライフサイクルには Spring Session を使う。保存する `ConversationEnvelope` は、
会話 ID、次 TRANSID、COMMAREA の不変バイト列、チャネル・コンテナ、版番号、有効期限だけからなる。
生きた `CobolSession`、`Storage` / `DataView`、プログラムインスタンス、JDBC 資源は保存しない。
同一会話への並行要求は、版番号と lease によって一つだけを受理し、二重送信を検出する。

Spring Session JDBC の session ID に結び付けた専用の版管理付き会話表を、業務 Db2 と同じ
DataSource / transaction manager / 現在の UOW で明示更新できる構成だけを `STRICT` 会話整合性
プロファイルとする。標準の `JdbcIndexedSessionRepository` は既定で `REQUIRES_NEW` を使うため、
同じ DataSource というだけでは `STRICT` としない。Redis や別 DataSource の Session は Db2 commit と
原子的にはならないため `NON_ATOMIC` プロファイルと明示し、版番号・冪等キー・再送方針を要求する。
XA を構成していない状態を原子的と表現しない。

BMS は中立なフィールドモデルへ変換する。Spring MVC アダプタは JSON を、任意の Thymeleaf adapter は
JavaScript / CSS と組み合わせた 3270 互換 HTML view を提供するが、3270 画面プロトコルを中核モデルへ
持ち込まない。詳細な表示・入力判断は [ADR-0010](0010-bms-thymeleaf-terminal-ui.md) に従う。

## 影響

- Spring MVC、Spring Session、graceful shutdown、Actuator / Micrometer を標準的に利用できる。
- 長時間処理は Servlet スレッドを占有するため、同時実行上限とタイムアウトを明示する必要がある。
- WebFlux を選択肢から排除するものではないが、リアクティブ対応には UOW とセッションの別アダプタ、
  別 ADR が必要になる。
- Session 保存先によって会話状態と業務更新の整合性保証が異なる。

## 実装メモ（2026-09-10）

中立第1増分として`cobol-cics`へTRANSID registry、入力上限、CICS command/control、同一sessionの
LINK gateway、版・owner・期限・冪等key・期限付きleaseを持つ会話portを追加した。会話を使うtaskは
`load`結果だけで実行せず、COBOL起動前に期待版を`claim`する。reference実装は単一JVM用であり、
Spring Sessionや業務Db2との原子性を表さない。中立`CicsTaskCoordinator`はclaimからprogram、会話変更、
UOW、abort、closeを順序付けるが、原子確定は`CicsTaskBoundary` adapterの責務とする。commit結果が
`UNKNOWN`ならleaseを解放せず自動再実行しない。Spring adapterが実装されるまでは本ADR全体を
実装済みとは判定しない。

## 却下した案

### WebFlux を既定入口とする

同期 COBOL 実行と JDBC を bounded elastic へ委譲するとスレッド境界と取消しの扱いが複雑になり、
現段階で得られる利点が小さいため採用しない。

### `CobolSession` 全体を HTTP セッションへ保存する

クラス更新との互換性、同時要求、直列化、安全な JDBC 資源解放を成立させられない。CICS の
疑似会話モデルとも異なるため採用しない。

### すべての Session ストアを Db2 更新と原子的に扱う

Redis または別データベースを XA なしで同一 commit に含めることはできない。保証を構成能力として
明示する。
