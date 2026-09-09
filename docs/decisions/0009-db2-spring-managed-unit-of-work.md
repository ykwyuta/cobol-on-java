# ADR-0009: Db2 と CICS の UOW を Spring のプログラム的トランザクション管理へ写像する

| 項目 | 内容 |
| --- | --- |
| 状態 | 採用 |
| 日付 | 2026-09-09 |
| 関連要件 | FR-150〜156, FR-160, FR-165, NFR-034 |
| 関連設計 | [設計 77](../design/77-spring-cics-db2.md) |
| 後続決定 | `WITH HOLD` に関する決定は [ADR-0012](0012-db2-driver-managed-uow-for-required-hold-cursors.md)で置換 |

> **適用範囲:** 本 ADR の Spring 管理 UOW は `SPRING_MANAGED` profile に適用する。
> `WITH HOLD` 必須 task は ADR-0012 の `DB2_DRIVER_MANAGED_HOLD` profile を使用する。

## 文脈

CICS タスクでは、COBOL の `COMMIT` / `ROLLBACK` や CICS `SYNCPOINT` が一要求の途中に複数回
現れ得る。メソッド全体を囲む `@Transactional` だけでは、この明示境界を正しく表現できない。
また、COBOL ランタイムが物理 JDBC Connection を保持して直接 commit すると、Spring が管理する
Java 側の更新、接続プール、同期コールバックと不整合になる。

カーソル `WITH HOLD` は commit 後も開く意味を持つ一方、一般的な Spring ローカル
トランザクションは完了時にコネクションをプールへ返す。JDBC ドライバが holdability を持つことだけでは、
フレームワーク境界を越えたカーソル保持は保証されない。

## 決定

Spring アダプタは `PlatformTransactionManager` のプログラム的 API で CICS の UOW を制御する。
タスクの最初の資源アクセスで `PROPAGATION_REQUIRES_NEW` の UOW を遅延開始し、同じ要求スレッド上の
JDBC と参加対象 Java サービスを同じ Spring トランザクションへ参加させる。

初期 local profile は Db2 と同じ DataSource の `JdbcTransactionManager` または
`DataSourceTransactionManager` に限定する。JTA、JPA、独自 manager は同じ interface だけでは許可せず、
資源参加、suspend / resume、複数 syncpoint、timeout の adapter contract test 合格を要求する。
JPA persistence context を COBOL の明示 syncpoint 前後にまたいで保持する構成は初期非対応とする。

COBOL の `COMMIT`、`ROLLBACK`、CICS `SYNCPOINT` は `PlatformTransactionManager` に対する
commit / rollback へ写像する。`Connection.commit()` / `rollback()` を直接呼ばない。境界の後は
次の資源アクセスまで新しい UOW を開始しない。正常な CICS タスク終了は commit、未処理 ABEND は
rollback とする。SQLCODE、CICS RESP と rollback-only の関係は診断分類表で定義し、単に負の
SQLCODE で一律 rollback しない。

JDBC 処理は Spring がトランザクションへ同期する `JdbcOperations` と `DataSourceUtils` を使う。
`DataSource.getConnection()` で独立した接続を取得しない。接続プールの種類は Spring Boot の
DataSource 自動構成に委ね、中核 API へ漏らさない。

通常カーソルはタスク内の `CobolSession` に登録し、UOW 終了時に確実に閉じる。`WITH HOLD` の
未分類時は `REJECT_UNVERIFIED` とする。spool は native cursor の lock、可視性、I/O、fetch error の
時点を変えるため既定にしない。SQL inventory で cursor ごとに、意味差を承認した bounded
`PORTABLE_SPOOL`、Spring の資源解放との互換性を実 Db2 crash 試験で確認した `DB2_NATIVE_HOLD`、
非対応のいずれかを明示する。更新可能、scroll sensitivity、LOB locator 等も選択条件へ含める。

> この `DB2_NATIVE_HOLD` 決定は ADR-0012 により置換された。現在は cursor 単位で Spring UOW へ
> 組み込まず、`DB2_DRIVER_MANAGED_HOLD` を task 全体へ適用する。

CICS 外の Java / バッチ呼び出しは、`CICS_TASK`、`EXPLICIT`、`HOST_MANAGED` の完了方針を入口で
必ず選ぶ。既存の外側トランザクションへ暗黙参加するかどうかをランタイムが推測しない。

一つの local UOW で保証する durable resource は、一つの Db2 DataSource と、その同じ接続で更新する
会話表までとする。CICS file / queue 等の別 durable resource を同じ同期点へ含める場合は、検証済み
JTA/XA adapter、または保証が異なる outbox 等の方針を明示する。複数資源を local transaction だけで
原子的と表現しない。

## 影響

- COBOL と Spring Java コードの Db2 更新を一つの Spring 管理 UOW にできる。
- 一要求内の複数 `SYNCPOINT` を表現できるが、宣言的な `@Transactional` だけより実装が複雑になる。
- `WITH HOLD` は配備前 inventory と cursor 単位の方針選択を要求する。spool を選ぶ場合は結果量上限、
  意味差、spool 容量、機密データ消去を設計・監視する必要がある。
- JTA が必要な複数資源構成でも中立 `UnitOfWorkPort` は維持し、Spring 側の manager を交換できる。

## 却下した案

### MVC controller または COBOL 呼び出し全体に `@Transactional` を付ける

一要求内の明示 commit / rollback 後に別 UOW を開始する意味を表せないため採用しない。

### COBOL ランタイムが JDBC Connection を所有する

Spring 管理の Java サービスと同じ UOW へ参加できず、プール返却や例外時解放にも抜け道ができるため
採用しない。

### `WITH HOLD` はドライバ設定だけで常に維持する

トランザクション完了時の Spring 側の Connection 解放を考慮しておらず、プールへ返した Connection の
ResultSet を保持する設計は安全でない。移植可能な既定方式を用意し、ネイティブ方式は検証済み構成に限る。
