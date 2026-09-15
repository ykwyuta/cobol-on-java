# 設計文書 77: Spring Boot 4.1 による CICS / Db2 連携

| 項目 | 内容 |
| --- | --- |
| 対応要件 | FR-150〜156, FR-160〜167, NFR-032, NFR-034〜036 |
| 関連 ADR | [ADR-0007](../decisions/0007-framework-neutral-subsystem-ports.md), [ADR-0008](../decisions/0008-cics-on-spring-mvc-and-session.md), [ADR-0009](../decisions/0009-db2-spring-managed-unit-of-work.md), [ADR-0010](../decisions/0010-bms-thymeleaf-terminal-ui.md), [ADR-0012](../decisions/0012-db2-driver-managed-uow-for-required-hold-cursors.md) |
| 関連レビュー | [敵対的レビュー](../reviews/2026-09-09-interop-adversarial-review.md) |
| ステータス | 敵対的レビュー済み。中立コアと`SPRING_MANAGED` UOW adapterの第1増分をexperimentalとして実装。P0 gate未合格 |
| 基準環境 | Java 21、Spring Boot 4.1.x、Spring Framework 7.0.x |

## 1. 目的と範囲

翻訳済み COBOL を Spring Boot 4.1 アプリケーションの一部として動作させ、次を成立させる。

- CICS トランザクションを Spring MVC から同期実行する。
- CICS のプログラム制御、EIB、BMS、疑似会話、同期点を Java 上の中立モデルで再現する。
- BMS 画面を Thymeleaf、JavaScript、CSS で、配置と主要端末属性を含め可能な限り忠実に再現する。
- 通常の `EXEC SQL` は Spring Boot の DataSource、コネクションプール、Spring Framework の
  トランザクション資源同期を利用する。
- `WITH HOLD` 必須 task は Db2 JDBC driver を直接管理する専用 profile で、同じ connection を
  task 内の複数 commit 間に維持する。
- COBOL、CICS、Db2 と参加可能な Java サービスを、選択した profile の同じ作業単位 (UOW) で実行する。
- Spring Boot の更新および別フレームワークへの移行時に、COBOL の再翻訳と中核意味論の変更を避ける。

本設計を基準として段階実装を開始した。IBM CICS 製品そのもの、3270 データストリームと端末通信、Db2 の
アクセスパスやロック性能を再現するものでもない。Web UI は画面セルと操作意味論の互換を目標とし、
実端末との pixel 単位の同一性を保証しない。

### 1.1 実装状況（2026-09-11）

`cobol-cics`を追加し、許可リスト型`CicsTransactionRegistry`、COMMAREA / containerのcopy分離と
入力上限、`LINK` / `XCTL` / `RETURN` / `SYNCPOINT`のcommand/control、同一thread・同一
`CobolSession`でLINKする`DefaultCicsGateway`を実装した。疑似会話はversion / owner / expiry /
idempotency keyを持つ不変envelopeとし、COBOL起動前の排他claim、期限付きleaseによるCAS
save / complete / releaseをport契約へ追加した。単一JVM用reference storeでは同一版への並行claimが
一件だけ成功することを試験している。

`CicsTaskCoordinator`は、TRANSIDと入力上限の検査、会話claim、program port実行、次TRANSIDの再検査、
会話mutationとUOWの確定、異常時abort、closeを一要求上で順序付ける。会話変更と業務UOWの実際の
原子性・公開順序は`CicsTaskBoundary`へ委譲し、Spring型やDb2型を中立coordinatorへ持ち込まない。
commit失敗は`NOT_COMMITTED`と`UNKNOWN`を区別し、`UNKNOWN`では危険な自動rollback、lease解放、
program再実行を行わない。開始前、program失敗、commit失敗、close失敗の各cleanup traceをfakeで固定した。

`CobolCicsTaskProgram`はtaskごとに一つの`CobolSession`と型付き`RuntimeServices`を生成し、初期programを
起動して`XCTL`をloopで反復する。`LINK`は同じsessionの通常呼出し、`XCTL` / `RETURN`はsessionを
失敗状態にしない内部control transferとして外側のtask結果へ戻す。公開実行境界でも入力上限とtask / 定義の
TRANSID一致を再検査する。

コンパイラはisland token化した`EXEC CICS`の初期subsetを中立runtime操作へ変換する。対象は静的な
`PROGRAM('...')` / `TRANSID('...')`、単純データ名`COMMAREA`、正の数値`LENGTH`を持つ`LINK`、`XCTL`、
`RETURN`、`SYNCPOINT [ROLLBACK]`、および静的`ABCODE` / `CANCEL` / `NODUMP`の`ABEND`である。
abend exit向けに4byte英数字領域を受取側とする`ASSIGN ABCODE`も扱う。
`RETURN TRANSID(...) IMMEDIATE`は`TaskCompletion.immediate`と`CicsTaskReply.immediateNext`へ残す。
次taskを端末入力なしで始めるのはtransport adapterの責務であり、coordinatorは同じ要求の中で次のprogramを
起動しない。TRANSIDの無いIMMEDIATEとRETURN以外のIMMEDIATEは翻訳時に拒否する。
未知option、重複option、範囲外LENGTH、未定義COMMAREA、不正TRANSID / ABCODEはfail-closedで
翻訳を拒否する。生成した3 programを通すLINK→XCTL→SYNCPOINT→RETURNの結合試験で、session共有、
COMMAREA copy-back、次TRANSIDを固定した。生成ABENDは検証済みcode、task ID、CANCEL、dump方針を持つ
`CicsAbend`となり、同じ原因のまま`CicsTaskBoundary.abort`へ渡る。

このCICS増分は中立構造契約である。Spring MVC / Session adapter、lease更新、
STRICT会話表とNON_ATOMIC outcome journal、動的CICS option、condition handlerの拡張、
channel / container、実CICS比較は未実装である。

BMSは翻訳時の部分を先に実装した（2026-09-14）。`cobol-cics`の`BmsParser`がBMSマクロ
（DFHMSD / DFHMDI / DFHMDF）をThymeleaf、HTML、DOMに依存しない`BmsModel`へ読み、座標、
画面サイズ、基本・拡張属性、INITIAL、PICIN / PICOUT、JUSTIFY、OCCURSを保持する。未知のoperandと値、
`GRPNAME`、`POS=数値`、LANG=COBOL以外、TIOAPFXの省略は行番号つきで断る。`cobol-compiler`の
`BmsCopyBookResolver`は`COPY mapset`が引かれたときに`BmsSymbolicMapWriter`で記号マップ写し句を
その場で作り、生成物を置き場へ書き出さない。写し句の形はBank-of-Zに同梱された組立て済み
記号マップ写し句3本を外の基準にした（暫定判断P-112）。`DFHAID`はIBM提供写し句の原文を参照せず、
3270データストリームの公開AID値から16進定数で作る（`CicsSystemCopybooks`）。
`SEND MAP` / `RECEIVE MAP`、物理マップと画面snapshot、`BmsInputDecoder`、`DFHBMSCA`、
Thymeleaf renderer と JavaScript の端末操作は第 1 増分を `cobol-spring-boot-4-bms-thymeleaf` に実装した
(設計 81)。renderer spike で方式を決め、HTTP 入口は認証・CSRF・Session の adapter と一緒に入れる。
EIBは初期subsetとして`EIBTRNID`、`EIBCALEN`、`EIBFN`、`EIBRCODE`、`EIBRESP`、`EIBRESP2`を
実装済みである。`EIBTIME`、`EIBDATE`、`EIBTASKN`、`EIBTRMID`、`EIBCPOSN`、`EIBAID`も公開位置の
読み取り専用項目として公開した。`EIBTASKN`は`CicsTaskContext.taskNumber`、`EIBDATE` / `EIBTIME`は
task開始時刻を`CicsTaskContext.hostZone`の地方時にしたPL4で置き、出どころの無いfieldはbinary zeroの
ままにする（暫定判断P-113）。端末入力を持つまで`EIBAID` / `EIBCPOSN` / `EIBTRMID`は設定しない。
`RESP` / `RESP2`は静的な単純データ名と4byte binary受取項目に限定し、`NOHANDLE`はcommand単位の
既定処理抑止として実装済みであり、
標準gatewayはLINK / XCTL対象未登録を`PGMIDERR(27), RESP2=1`として返す。
`HANDLE CONDITION` / `IGNORE CONDITION`は初期subsetとして`PGMIDERR`とgeneralized `ERROR`を扱い、handler tableを
CICS LINK levelごとに分離する。handler段落への移動は既存のCOBOL段落state machineへ返す制御結果であり、
Java例外に変換しない。handlerを省略したHANDLEは既定処置へ戻し、RESP / NOHANDLEは登録済みhandlerも
そのcommandだけ迂回する。一つのcommandには最大16 conditionを列挙でき、重複は翻訳時に拒否する。
`HANDLE ABEND`はCOBOL `LABEL`、`CANCEL`（option省略時の既定）、`RESET`を実装し、明示ABEND時に
現在levelから上位levelへ最初の有効なexitを選ぶ。選択時にexitを無効化して再入を防止し、RESETで
再有効化する。`ABEND CANCEL`は全levelのexitを無効化してhandlerへ移さず、構造化異常をtask境界へ渡す。
`PUSH HANDLE`は現在のLINK levelのcondition tableとabend exitをLIFO stackへ退避したうえで現在状態を空にして効果を
一時停止し、`POP HANDLE`は最後の退避値を復元する。退避stackもLINK levelごとに分離し、対応するPUSHの
ないPOPは実行時に拒否する。
完了した対応commandは2byte `EIBFN`を更新する。6byte `EIBRCODE`は現在、プログラム制御群の
`PGMIDERR`を`01 00 00 00 00 00`へ変換し、NORMAL時は全zeroとする。サービス群で値が異なるため、
未分類RESPをEIBRESPの下位byteで代用せずfail-closedにする。ABENDコード自体はEIBRCODEではなく
`ASSIGN ABCODE`の対象である。初期subsetの`ASSIGN ABCODE(data-area)`は4byte英数字受取領域だけを
許可し、現在codeを右側space paddingして返す。abend未発生時はspace 4byte、完了時のEIBFNは`02 08`である。
`HANDLE ABEND PROGRAM`、暗黙的なJava例外・CICS内部異常からabend exitへの変換は未実装である。
`load`は観測用でありtask実行には必ず`claim`を使う。in-memory storeを本番・cluster構成に使わない。

`cobol-db2`を追加し、二つの`Db2ExecutionProfile`、task-scoped `UnitOfWorkPort`、中立`SqlPlan` /
`SqlBindings` / `SqlOutcome`、`CursorHoldStrategy`、SQLCA field別fidelity行列を実装した。
`Db2TaskRuntime`はtask開始時のprofile固定、最初のSQLまでのUOW遅延開始、明示commit / rollback後の
遅延再開、同期thread所有、終了時rollbackとport closeを強制する。native profileではopaqueな
`ResourceLeaseId`をcommit間でpinし、leaseが変われば拒否する。静的inventoryと動的OPENの双方で、
未承認またはprofile不一致の`WITH HOLD`をUOW開始前に拒否する。

`cobol-spring-boot-4-autoconfigure`の第1増分はSpring Boot 4.1.1を基準に、同一`DataSource`の
`JdbcTransactionManager` / `DataSourceTransactionManager`を中立`UnitOfWorkPort`へ接続する。
UOWはprototype beanでtaskごとに生成し、`PROPAGATION_REQUIRES_NEW`、timeout、read-only、
commit / rollback / cleanup、thread所有を写像する。異なるDataSource、未知のtransaction manager、
`DB2_DRIVER_MANAGED_HOLD`との混在は開始前に拒否する。H2によるadapter試験でJDBC資源参加と
外側transactionのsuspend / resumeを確認したが、これは実Db2の適合性証拠ではない。

次の増分で、固定長文字、COMP-3、数字DISPLAY、BINARY、2byte null indicatorを表す中立host variable
descriptor / codecと、Spring transaction-bound `Connection`を使うSQL executorを追加した。
`PreparedStatement`の値binding、transaction timeout、`INSERT` / `UPDATE` / `DELETE`、単一行`SELECT`、
該当なし`+100`、複数行`-811`、全出力検証後の一括storage反映、値非包含のJDBC exception / warning
chain採取を実装した。UOWとexecutorの`DataSource` identityおよびthread所有もSQL取得前に検査する。

非`WITH HOLD`のforward-only / insensitive / read-only cursorについて、Spring adapter内に
COBOL session identityとcursor名をキーとするtask-scoped registryを追加した。`OPEN`はSpringの
transaction-bound connection上にPreparedStatement / ResultSetを保持し、`FETCH`は出力を全項目検証してから
storageへ反映し、終端を`+100`として値を変更しない。明示`CLOSE`に加え、commit / rollback / task close前に
UOW登録資源を逆順で閉じる。資源close失敗時はcommitせずrollbackし、主障害とsuppressed causeを保持する。
Spring経路の`WITH HOLD`はOPEN前に拒否し、native profileとの境界を維持する。sessionだけをtask/UOWより
先に単独closeした場合の即時cursor closeとCOBOL `CANCEL`連動は未実装であり、通常のtask coordinator経路では
Db2TaskRuntimeの完了処理を先に行う。

SQLコプロセッサの初期subsetを実装した（2026-09-15、暫定判断P-120 / P-121）。`EXEC SQL INCLUDE`は前処理で
COPYとして取り込み、SQLCAは公開の宣言から作る。`SqlBlockParser`はSELECT INTO / INSERT / UPDATE /
DELETE / DECLARE CURSOR / OPEN / FETCH / CLOSE / COMMIT / ROLLBACKを文の種類とhost variableに分け、
`?`に置き換えたSQLを`SqlPlan`として`Db2RuntimeOps`へ渡す。host variableの形（固定長文字、COMP-3、
ゾーン10進、2進）は翻訳時に決め、実行結果はSQLCAのSQLCODE / SQLSTATE / SQLERRD(3)へ書く。
`DECLARE TABLE`は実行時の効果を持たない宣言として受ける。動的SQL、WHENEVER、positioned UPDATE、
VARCHAR group、日付・時刻・LOB、Db2固有diagnostic mapper、scroll / sensitive / update / LOB cursorは
未実装である。
`cobol-db2-jdbc`にはSpringへ依存しない専用provider / lease契約とdriver-managed UOW adapterを追加した。
同じ物理connectionを複数commit間で保持し、`autoCommit=false`、read-only、
`HOLD_CURSORS_OVER_COMMIT`を検証する。commitでは非hold資源だけ、rollback / task closeでは全資源を閉じ、
task終了時に取得時属性へresetできた場合だけleaseを`REUSABLE`で返す。commit、rollback、resource close、
resetの失敗時は`DISCARD`とする。Db2 Community 12.1.5.0とIBM JCC 12.1.4.0による適合性試験で、
同一JDBC `Connection` object、`HOLD_CURSORS_OVER_COMMIT`、commit後FETCH、rollback時cursor close、
task終了時connection closeを確認した。コンテナ再起動後にも同じ試験を通した。
native SQL executorは同じlease上でDML、単一行SELECT、forward-only / read-only cursorを実行し、
task timeoutをstatement timeoutへ写像する。SQLSTATE class `08`とJDBC resource close失敗ではleaseを
再利用せず破棄する。中立`SqlPlan`とCOBOL host variableを通る実Db2試験で、
INSERT / SELECTと`OPEN WITH HOLD` / commit後FETCH / CLOSEを確認した。
databaseがUOWを暗黙rollbackしたかの判定、接続断・プロセス停止、`-911` / `-913`、SQLWARN各fieldは
未保証である。この限定試験だけでDb2適合性全体を保証しない。

## 2. 設計原則

1. **COBOL の意味論を Spring 型で表さない。** 生成コードは CICS / SQL の中立 API だけを見る。
2. **一つの CICS タスクは一つの同期要求で実行する。** スレッドに束縛された JDBC UOW を途中で
   非同期処理へ移さない。
3. **UOW の所有者を task 開始時に一つだけ選ぶ。** 通常は Spring が所有する。`WITH HOLD` 必須 task
   だけは Db2 adapter が task-scoped connection lease を所有し、Spring 管理 JDBC と混在させない。
4. **疑似会話ではデータだけを持ち越す。** 実行中セッション、WORKING-STORAGE、カーソルは持ち越さない。
5. **保証の差を構成能力として表す。** Session 保存先、JTA の有無、holdable cursor の可否を
   暗黙に同等扱いしない。
6. **交換可能性を過大表示しない。** 中立 port が直接交換できるのは同期 imperative host である。
   reactive / distributed execution は別の task coordinator と ADR を必要とする。

## 3. 論理アーキテクチャ

```text
Browser (Thymeleaf HTML + JavaScript + CSS) / JSON client
       │
       ▼
Spring MVC CicsEndpoint
       │  request/response 変換、認証、入力上限
       ▼
CicsTaskCoordinator ──────── ConversationStorePort
       │                              ▲
       │                              └─ Spring Session adapter
       ├──────── UnitOfWorkPort
       │               ▲
       │               ├─ SPRING_MANAGED ─▶ PlatformTransactionManager
       │               └─ DB2_DRIVER_MANAGED_HOLD ─▶ task-scoped ConnectionLease
       ▼
CobolSession / ProgramCatalog
       │
       ├─ EXEC CICS ─▶ CicsGateway ─▶ file / queue / terminal ports
       │
       └─ EXEC SQL  ─▶ SqlExecutorPort ─┬─▶ Spring JDBC / DataSource ─▶ Db2
                                        └─▶ IBM JDBC / native provider ─▶ Db2
```

### 3.1 モジュール

| モジュール | 責務 | 主な依存 |
| --- | --- | --- |
| `cobol-cics` | CICS コマンド、EIB、BMS、会話 envelope、タスク協調、各資源ポート | `cobol-runtime` |
| `cobol-db2` | SQL 計画、ホスト変数、SQLCA、カーソル、Db2 診断、SQL 実行ポート | `cobol-runtime` |
| `cobol-spring-boot-4-autoconfigure` | MVC、Session、Spring JDBC、Db2 native connection provider、トランザクション、Actuator のポート実装と自動構成 | 上記、Spring Boot 4 |
| `cobol-spring-boot-4-bms-thymeleaf` | BMS の Thymeleaf view、terminal JavaScript、CSS theme | `cobol-cics`、Spring Boot 4、Thymeleaf |
| `cobol-spring-boot-4-starter` | 利用者向け依存関係と標準設定メタデータ | autoconfigure |
| `cobol-subsystem-testkit` | ポート契約試験、CICS / Db2 fake、適合性シナリオ | 中立 API、test scope |

Spring Boot アダプタは `cobol-compiler` に依存しない。コンパイラは `EXEC CICS` と `EXEC SQL` を
中立な命令または計画へ翻訳し、Spring が存在しない単体試験でも fake ポートに対して実行できる。
標準の browser UI 構成は base starter と BMS Thymeleaf adapter を組み合わせ、JSON-only または独自 UI の
構成では BMS Thymeleaf adapter を依存関係から外せるようにする。

### 3.2 公開境界

公開 API の概念形を以下に示す。正確な Java シグネチャは実装開始時に API レビューで確定する。

```java
interface CicsGateway {
    CicsResponse execute(CicsCommand command, CicsTaskContext task);
}

interface ConversationStorePort {
    Optional<ConversationEnvelope> load(ConversationId id);
    SaveResult save(ConversationEnvelope expected, ConversationEnvelope next);
    void complete(ConversationEnvelope expected);
}

interface CicsTaskBoundary extends SyncpointPort, AutoCloseable {
    void commit(ConversationMutation conversation, Instant now);
    void abort(Optional<ConversationLease> lease, Throwable failure, Instant now);
}

interface SqlExecutorPort {
    SqlOutcome execute(SqlPlan plan, SqlBindings bindings, CobolSession session);
}

interface UnitOfWorkPort {
    UnitOfWork begin(UnitOfWorkOptions options);
    void commit(UnitOfWork unit);
    void rollback(UnitOfWork unit, RollbackReason reason);
}
```

`CicsTransactionDefinition` と Java / batch entry definition は `Db2ExecutionProfile` を持つ。
値は `SPRING_MANAGED` または `DB2_DRIVER_MANAGED_HOLD` であり、task 開始時に固定する。中立 API へ
`Connection` を公開せず、二 profile は同じ `UnitOfWorkPort` / `SqlExecutorPort` 契約を別 adapter で実装する。

中立 API には SQLException、ResultSet、HttpServletRequest、HttpSession、TransactionStatus を出さない。
アダプタ固有例外は `SqlFailure`、`CicsFailure`、`ConversationConflict` へ変換し、元の診断は
マスク可能な構造化属性として保持する。

## 4. CICS オンライン実行

### 4.1 Spring Boot 4.1 の利用箇所

| Spring Boot / Framework 機能 | 用途 | 中立境界 |
| --- | --- | --- |
| Spring MVC annotated controller | TRANSID ごとの同期 HTTP 入口 | `CicsRequest` / `CicsReply` |
| Thymeleaf view resolver | `BmsScreenModel` からのサーバサイド HTML 描画 | `BmsViewPort` |
| version 付き静的 resource | 端末操作 JavaScript と BMS theme CSS | `BmsTerminalProfile` |
| Spring Session | 疑似会話の識別・期限と会話ストアのライフサイクル | `ConversationStorePort` |
| DataSource 自動構成 | `SPRING_MANAGED` 用 Db2 DataSource と pool の生成・外部化 | `SqlExecutorPort` |
| `PlatformTransactionManager` | `SPRING_MANAGED` task / SQL / Java サービスの UOW | `UnitOfWorkPort` |
| IBM JDBC DataSource / dedicated provider | `DB2_DRIVER_MANAGED_HOLD` の task-scoped connection lease | `UnitOfWorkPort` / `SqlExecutorPort` |
| Actuator health | DataSource、会話ストア、受付状態の health/readiness | `SubsystemHealth` |
| Micrometer Observation / metrics | タスク、プログラム、SQL、応答コード、遅延の計測 | `ObservabilityPort` |
| graceful shutdown | 新規タスクを止め、実行中タスクの終了を待つ | `TaskAdmissionPort` |

`SPRING_MANAGED` では Spring Boot 4.1 の `spring.datasource.connection-fetch=lazy` を標準推奨値とする。UOW が開始しても
最初の SQL まで物理接続取得を遅らせ、SQL を実行しない CICS タスクで pool を消費しない。
アプリケーション側の明示設定を自動構成が上書きしてはならない。

### 4.2 HTTP 入口と TRANSID 解決

標準入口は `POST /cics/{transid}` とし、必要なら利用者が別 controller から同じ
`CicsTaskLauncher` を呼べる。TRANSID は正規化後に不変の `CicsTransactionRegistry` へ照会する。
クラス名や任意 Bean 名を要求値から組み立てない。

入口で次を検査する。

- 認証済み principal が TRANSID を実行できること。
- COMMAREA、各 container、container 合計、BMS field、HTTP body が設定上限以内であること。
- 会話 ID と冪等キーの形式、期限、所有者が正しいこと。
- content type と文字コードが許可済みであること。

ブラウザ入口は Thymeleaf HTML を既定とし、同じ launcher に対する JSON API も content negotiation
または別 endpoint で提供できる。COMMAREA / container は JSON API では base64 または生成した
型付き DTO で受ける。いずれも中立層へ渡す前に必ずバイト列へ確定し、Thymeleaf、DOM、Jackson の型を
持ち込まない。GET は画面 shell または副作用のない初期表示だけに使い、CICS task の実行は CSRF 保護した
POST とする。

### 4.3 タスクのライフサイクル

```text
要求受付
  │
  ├─ readiness / admission / 認証 / 上限検査
  ├─ ConversationEnvelope を版番号つきで取得・lease
  ├─ CicsTask + 新しい CobolSession を生成
  ├─ 最初の更新資源アクセスで UOW を遅延開始
  ├─ TRANSID の初期プログラムを実行
  │     LINK        同じ task/session/UOW で呼び戻る
  │     XCTL        同じ task/session/UOW で置換する
  │     SYNCPOINT   現 UOW を完了、次は遅延開始
  │     RETURN      タスク終了情報を返す
  ├─ 次会話を確定し、STRICT では同じ UOW 内で compare-and-set 保存
  ├─ 正常終了なら commit、未処理 ABEND なら rollback
  ├─ NON_ATOMIC では業務 commit 後に会話を保存し、結果不明を回復対象として記録
  └─ session、cursor、temporary storage、lease を必ず解放
```

HTTP クライアント切断は COBOL の安全な即時停止と同義ではない。実行中処理へ割り込みを注入せず、
サーバ側タイムアウトと取消し点で協調的に終了させる。結果が不明なクライアントには同じ冪等キーで
結果照会または再送できるようにする。

実装 (暫定判断 P-142): coordinator が (owner、冪等キー) を予約し、commit した task の `CicsTaskReply` を task 境界の
commit の中で `CicsOutcomeStorePort` に記録する。同じ内容の再送には task を動かさず記録した応答を返し、動いている最中か
内容の違う再送は `IdempotencyConflictException` (JSON の入口では 409) にする。

### 4.4 CICS コマンドの写像

| CICS 概念 | Java 上の設計 |
| --- | --- |
| `LINK` | `ProgramCatalog` を同一 `CobolSession` で呼ぶ。COMMAREA は長さ検査後にcopy-in / copy-outし変更を呼出元へ戻す |
| `XCTL` | 内部制御結果 `TransferControl` をtask program executorへ返し、呼出スタックを増やさず次programへ移る |
| `RETURN` | `TaskCompletion`。`TRANSID` / COMMAREA / channel があれば次 envelope を生成する |
| `LOAD` / `RELEASE` | immutable な program catalog の lease。任意 class loading は許可しない |
| `GETMAIN` / `FREEMAIN` | task-local storage arena。タスク終了で一括解放、use-after-free を診断する |
| `HANDLE CONDITION` / `RESP` | command outcome と COBOL 制御フローで処理し、Java exception と混同しない |
| `ABEND` | `CicsAbend` と rollback reason。コードと任意の安全な診断を保持する |
| `SYNCPOINT` | `UnitOfWorkPort` の commit / rollback と cursor policy を実行する |
| file / queue | `CicsFilePort`、`TemporaryStoragePort` 等へ委譲する |

`XCTL` や `RETURN` は内部ではstack unwind専用signalを使うが、session failureや通常のJava障害として
利用者コードへ漏らさず、task program executorでCOBOL制御結果へ変換する。

### 4.5 EIB と BMS Web UI

EIB は task ごとの固定レイアウト storage として生成する。サーバ時計とタイムゾーンは `ClockPort`、
利用者は `IdentityPort`、端末情報は `TerminalPort` から供給し、テストで固定できるようにする。
`EIBRESP` / `EIBRESP2` は CICS command outcome から設定し、HTTP status を直接格納しない。

初期実装はIBM DFHEIBLK互換の85byte領域を`CicsExecution`がtaskごとに一つ持つ。生成COBOLには
`EIBTRNID` (`PIC X(4)`、offset 0x08)、`EIBCALEN` (`PIC S9(4) COMP`、offset 0x18)、
`EIBRESP` / `EIBRESP2` (`PIC S9(8) COMP`、offset 0x4c / 0x50)を暗黙項目として公開する。
これらはCOBOLから読み取り専用であり、受取側に指定した文は翻訳時に拒否する。command outcomeは
制御結果を解釈する前にRESP / RESP2へ反映する。未対応fieldはbinary zeroとし、AID、日時、端末、
task番号は対応するportとhost比較vectorを得るまで推測値を設定しない。

`RESP(name)`はcommandの既定例外処理をその一回だけ抑止し、command outcomeをEIBへ設定したあと、
EIBRESPから指定された4byte binary項目へ転記する。`RESP2(name)`はRESPと同時指定の場合だけ許し、
同様にEIBRESP2から転記する。RESPもNOHANDLEもない既存runtime APIは非normal outcomeで従来どおり失敗する。
`NOHANDLE`も同じ一回限りの抑止を行うが受取項目への転記はせず、プログラムはEIBRESP / EIBRESP2を
検査する。`RESP`は`NOHANDLE`を暗黙に含むため、両optionは同じruntimeフラグへ正規化する。
`DFHRESP(condition-name)`はCICS translator組込み構文として扱い、初期subsetでは`NORMAL(0)`と
`PGMIDERR(27)`を翻訳時の数値定数へ置換する。結果を生成できないcondition名は推測値へ変換せず拒否する。
初期condition mappingは、標準gatewayが安全に識別できるLINK / XCTL対象そのものの未登録である。
XCTLは移送元programを失う前にsession固定catalogの`ProgramResolver.isResolvable`でprobeする。
明示catalogとlegacy class resolverはfactory生成、class初期化、WORKING-STORAGE割当を行わずに判定する。
独自resolverの互換defaultは`resolve`を呼ぶため、副作用なしのprobeが必要なadapterは必ずoverrideする。
LINK先へ制御が入ったあとの未解決CALLや業務例外、および登録済みXCTL targetの生成・実行障害は
PGMIDERRへ丸めず、その原因を維持する。

`HANDLE CONDITION PGMIDERR(paragraph)`は現在のCICS LINK levelに登録programと段落番号を記録する。
LINKで作る新しいlevelは別tableであり、呼出元のhandlerをLINK先へ継承しない。condition発生時はEIBを
先に更新し、commandにRESP / NOHANDLEがあれば次の文へ、なければIGNORE、handler段落、既定異常処置の
順に決める。個別conditionの登録がなければgeneralized `ERROR`へfallbackする。
`HANDLE CONDITION PGMIDERR`のように個別handlerを省略した状態は単なる登録なしと区別し、
`ERROR` handlerがあってもCICS既定処置を強制する。初期parserは一commandにつき最大16件を空白区切りで
列挙できる。ただし現在分類できるconditionは`PGMIDERR`と`ERROR`に限り、重複、空リスト、IGNOREのlabel、
区切りのないoptionをfail-closedで拒否する。
`PUSH HANDLE` / `POP HANDLE`は同じLINK level内でcondition tableとabend exitをLIFO退避・復元し、PUSHからPOPまで
退避した処置の効果を停止する。
LINK時は退避stackも空の新規levelへ切り替えるため、呼出元の退避値をLINK先からPOPできない。
対応するPUSHのないPOPは、未分類のCICS condition値を推測せず
`CicsTaskStateException`でfail-closedにする。
`HANDLE ABEND LABEL(paragraph)`はowner programと段落番号をlevelへ一件登録し、`CANCEL`は無効化、
`RESET`は再有効化する。明示ABENDを受けると現在levelから上位へ検索し、選択exitを実行前に無効化する。
同じprogramまたは上位LINK levelのLABELへ既存のowner付き段落移送を使って戻す。`ABEND CANCEL`は
全levelを迂回する。別programを起動してCOMMAREAを渡す`HANDLE ABEND PROGRAM`と、明示ABEND以外の
runtime障害をCICS abend codeへ分類する処理は、実CICS比較vectorを追加するまでfail-closedで拒否する。
明示ABENDを捕捉するとtask-localな現在codeをhandler移送前に記録する。`ASSIGN ABCODE`はそのcodeを
4文字領域へ返し、まだabendがなければspaceを返す。再ABEND時は新しいcodeで置き換える。
通常のCOBOL `CALL`は新しいCICS LINK levelを作らないためtableを共有する。別の生成programでconditionが
発生した場合、登録ownerと段落番号を持つ`ProgramTargetTransfer`をCALL境界だけで伝播する。各境界では
呼び終えたprogram frameを正常なsubsystem制御として外し、ownerが一致するprogramで段落番号を既存の
state machineへ返す。これにより中間CALLを重ねても登録元handlerへ戻り、別programの同じ段落番号を
誤って実行しない。生成classが依存するtransfer型はruntime中立型とし、CICS未使用classへCICS依存を加えない。

BMS マクロは翻訳時に `BmsMapDefinition` へ変換する。実行時の `BmsScreenModel` は mapset / map、
端末 profile、画面サイズ、field、literal、cursor、send option を持つ。画面表示技術を交換しても
COBOL の symbolic map storage と field semantics は変えない。

#### 4.5.1 BMS 中立画面モデル

`BmsScreenModel` には少なくとも次を保持する。

| 分類 | 主な情報 |
| --- | --- |
| 画面 | rows、columns、alternate size、erase / alarm / keyboard restore、map version |
| field 配置 | field ID、attribute cell と data の row / column、cell length、折返し segment、重なり診断 |
| 基本属性 | ASKIP / PROT / UNPROT、NUM、BRT / NORM / DRK、IC、FSET / MDT |
| 拡張属性 | COLOR、HILIGHT、OUTLINE、VALIDN、PS、SOSI 等の対応値と未対応値診断 |
| データ | INITIAL、output value、logical / encoded byte length、PICIN / PICOUT、OCCURS / GRPNAME |
| 実行状態 | initial cursor、現在 cursor、modified flag、AID、MAPONLY / DATAONLY / FRSET 等 |

未対応属性を無視せず、map compile report と runtime capability report に出す。端末 profile は最低限
24x80、32x80、43x80、27x132 を表現できるようにし、map と profile のサイズ不一致を診断する。

#### 4.5.2 Thymeleaf レンダリング

`cobol-spring-boot-4-bms-thymeleaf` は、一つの共通 template と再利用可能 fragment で
`BmsScreenView` を semantic HTML へ変換する。map ごとに template を生成しないが、利用者は
mapset / map 単位で header、footer、help、theme fragment を上書きできる。上書きしても field ID、
座標、AID、会話版の契約を変更してはならない。

ただし通常の HTML input だけで cell 単位 cursor、overwrite、画面端をまたぐ field、DBCS / IME を
満たせるとは仮定しない。実装前に representative な難画面で time-boxed spike を行い、native input
overlay、per-cell DOM + 隠れた native input、visual canvas + semantic form の hybrid を比較する。
Canvas-only は採用しないが、accessibility と form の正本を DOM に残す hybrid visual layer は許容する。
以下の CSS / DOM 設計は spike の exit criteria を満たした方式で具体化する。

CSS は `--bms-columns`、`--bms-rows`、`--bms-cell-width`、`--bms-cell-height` を持つ固定セル grid とし、
field を BMS の行・桁へ配置する。viewport が狭い場合も field の reflow や順序変更を行わず、等比縮小、
水平・垂直 scroll、全画面表示を選べるようにする。font は配布条件を確認した monospace を優先し、
読み込み前後で cell 寸法が変わらないよう fallback と測定検査を持つ。

属性は列挙済み CSS class だけへ写像する。BRT / NORM / DRK、extended COLOR、REVERSE、UNDERLINE、
BLINK、OUTLINE を theme token で表す。BLINK は `prefers-reduced-motion` 時に静的な強調へ置き換え、
色だけに依存しない focus / validation 表示を加える。DRK 入力は password 相当として画面・DOM・ログへ
再表示せず、単なる黒文字 CSS を機密保護として使わない。

通常 field は label、output、input の semantic element とし、画面端で折り返す field は複数の
visual segment と一つの logical field state に分ける。DOM 順序は座標順とし、`aria-label`、field 名、
現在位置を付与する。視覚互換モードとアクセシブル表示・on-screen AID key は同じ入力モデルを使う。

Thymeleaf では escape される `th:text` / `th:value` / `th:attr` を使い、BMS の literal や field value を
`th:utext`、生の style、任意 class、実行可能 script へ展開しない。動的状態は HTML data attribute または
安全に直列化した `application/json` として渡し、動作コードは CSP 対応の外部 JavaScript module とする。

#### 4.5.3 JavaScript terminal state machine

JavaScript は表示を作り直す任意業務ロジックではなく、次の 3270 操作意味論を持つ共通 state machine
とする。

- protected field を focus / 編集対象から外し、ASKIP で次の unprotected field へ移動する。
- IC と server 指定 cursor を初期位置へ反映し、Tab / Backtab、矢印、Home を cell 単位で扱う。
- field length、NUM、insert / overwrite、erase EOF / erase input を端末 profile に従って処理する。
- 最初の利用者変更で MDT を立て、FSET field は未変更でも送信対象にする。FRSET 等で reset する。
- Enter、Clear、PA1〜PA3、PF1〜PF24 を AID へ写像し、cursor position と modified field だけを送る。
- AID 送信後は keyboard を lock して二重送信を防ぎ、正常応答または明示的な再試行時だけ解除する。
- ブラウザが予約する function key は terminal focus 中だけ可能な範囲で抑止し、必ず on-screen key と
  configurable key binding を代替として用意する。

IME、全角文字、結合文字は JavaScript の UTF-16 length で判定しない。client は grapheme と表示 cell
数を補助検査し、server が対象 code page へ encode した byte length を最終判定する。DBCS の SO / SI、
全角 cell 幅、field 境界の扱いは `TerminalEncodingProfile` と共通 test vector で定義する。

#### 4.5.4 RECEIVE と信頼境界

ブラウザは `fieldId`、value、MDT、AID、cursor position、map version、single-use screen nonce、
conversation version、冪等キーを POST する。サーバの `BmsInputDecoder` は client の hidden /
disabled 状態を信用せず、会話ストアの `BmsScreenSnapshot` と versioned map definition から復元した
`BmsScreenModel` に対して次を再検証する。screen snapshot は CICS `RETURN` と同じ会話保存境界で
確定し、疑似会話をまたぐ動的属性上書きを失わない。

- map / conversation の版、single-use screen nonce、認証主体、CSRF token、冪等キー。
- field の存在、unprotected / protected、最大 cell / byte length、NUM / VALIDN、文字コード。
- MDT / FSET と送信対象、重複 field、未知属性、cursor 範囲、許可 AID。

検証後にだけ symbolic input map の length / flag / data subfield、EIBAID、cursor position を構築する。
不正要求は CICS program を起動せず transport error として監査する。入力値そのものは通常ログへ出さない。

#### 4.5.5 互換性レベル

| レベル | 用途 | 保証 |
| --- | --- | --- |
| `DATA_ONLY` | API、テスト、独自 UI | BMS field 入出力と属性モデルのみ |
| `WEB_3270` | 既定ブラウザ UI | cell 配置、主要属性、cursor、MDT、AID、keyboard workflow |
| `WEB_3270_STRICT` | 移行受入試験 | renderer spike 合格後だけ有効。対応 browser / font / terminal profile を固定し、golden image と操作 trace を比較 |

`WEB_3270_STRICT` でも TN3270 データストリーム、端末通信 timing、物理 keyboard の完全一致は保証しない。

### 4.6 疑似会話と一貫性

`ConversationEnvelope` は少なくとも次を含む。

| 項目 | 意味 |
| --- | --- |
| `conversationId` | 推測困難な不透明 ID |
| `version` | compare-and-set 用単調増加版 |
| `owner` | tenant / principal の結合情報。生の個人情報は避ける |
| `nextTransid` | `RETURN TRANSID` が指定した次トランザクション |
| `commarea` | コピー済み不変バイト列と論理長 |
| `channels` | channel / container 名とコピー済み不変バイト列 |
| `screenState` | map ID / 定義版、terminal profile、動的 field 属性、cursor、MDT を持つ中立 `BmsScreenSnapshot`。HTML / DOM は含めない |
| `expiresAt` | 期限 |
| `lastOutcome` | 冪等再送で返す完了識別子または安全な結果要約 |

同一ブラウザ session と CICS conversation は 1 対 1 と仮定しない。複数タブに備えて会話 ID を
明示し、版不一致は HTTP 409 相当の `ConversationConflict` とする。

Spring Session の標準 JDBC repository は JDBC 操作を既定で `REQUIRES_NEW` にするため、同じ
DataSource を指定しただけでは業務 UOW と原子的にならない。本設計の `STRICT` adapter は Spring Session
の session ID と期限へ結び付けた専用 `COBOL_CONVERSATION` 表を `JdbcOperations` で版条件付き更新し、
task coordinator が業務 commit より前に明示保存する。HTTP session filter による応答後の保存へ
原子性を依存させない。

Redis 等の `NON_ATOMIC` adapter は、版番号を含む `ConversationEnvelope` を Spring Session attribute
として明示保存できる。ただし業務 commit とは別の完了であり、片方だけ成功した状態を検知・回復する
outcome journal を設ける。いずれの adapter も Spring Session の ID 解決、期限、logout 時の無効化へ従う。

outcome journal は名称だけで済ませず、次の状態機械を契約とする。

```text
Db2 UOW 内: business update + next envelope + COMMITTED_PENDING_SESSION を同じ idempotency key で記録
       │ commit
       ▼
Spring Session へ conversation version を CAS publish
       │ success
       ▼
Db2 journal を PUBLISHED に更新
```

crash / timeout / 応答喪失後の再送は journal を先に照会し、`COMMITTED_PENDING_SESSION` なら business
program を再実行せず publish を再開する。同じ screen nonce は同じ idempotency outcome にだけ再利用でき、
別 key では拒否する。Session expiry / logout、期限切れ journal、孤児 conversation の削除順と retention を
adapter contract test に含める。

整合性プロファイルを次のように定義する。

| プロファイル | 条件 | 保証 |
| --- | --- | --- |
| `STRICT` | Spring Session の ID / 期限と結合した会話表を、選択 profile の業務 Db2 と同じ connection / 現 UOW で明示更新 | 業務更新と次会話状態を同じ local UOW で commit |
| `XA` | Session 資源と Db2 を検証済み JTA/XA manager へ参加 | 分散 commit。運用・障害試験が必須 |
| `NON_ATOMIC` | Redis、別 DataSource、非 XA | 原子性なし。版番号、冪等キー、回復ジョブと不整合メトリクスが必須 |

標準推奨は構成が単純な `STRICT` である。高可用性要件から Redis を選ぶ場合は `NON_ATOMIC` の
制約を受け入れるか、検証済み `XA` を構成する。
`DB2_DRIVER_MANAGED_HOLD` の `STRICT` は同じ native lease から会話表を更新し、Spring Session には
会話 payload / version を保存しない。Spring Session は HTTP session ID、期限、logout の管理だけを担う。

実装 (暫定判断 P-143): `SPRING_MANAGED` の `STRICT` は `cobol.cics.conversation.consistency=strict` で構成する。
`SpringStrictTaskBoundaryFactory` が task ごとの Db2 UOW を持ち、`JdbcConversationStore` の `COBOL_CONVERSATION` と
`COBOL_TASK_OUTCOME` を同じ UOW で更新して commit する。`cobol.db2.profile=DB2_DRIVER_MANAGED_HOLD` では、利用者の
`Db2NativeConnectionProvider` の bean で `DriverManagedStrictTaskBoundaryFactory` を構成し、同じ表を native lease の
connection (`DriverManagedUnitOfWorks.connection`) で更新して業務の SQL と一緒に commit する。claim と冪等キーの予約は
どちらの profile でも DataSource の別の transaction で確定するので、lease と DataSource は同じ database を指す必要がある
(利用者が保証する)。会話の行を Spring Session の session ID と期限へ結び付けることはまだ無い。

## 5. Db2 連携

### 5.1 翻訳と実行計画

コンパイラの SQL コプロセッサは `EXEC SQL` を解析し、文字列断片ではなく中立な `SqlPlan` を生成する。

```text
SqlPlan
 ├─ statementId / sourceLocation / dialect
 ├─ operation (SELECT, INSERT, OPEN, FETCH, ...)
 ├─ parameter descriptors (mode, COBOL layout, SQL type, null indicator)
 ├─ result descriptors
 ├─ cursor options (holdability, sensitivity, updateability)
 └─ normalized SQL / static package metadata
```

動的 SQL も値を SQL 文字列へ連結せず、prepare と bindings を分ける。識別子など bind できない
動的部分は許可規則と dialect validator を通す。SQL ログは既定で値を出さず statementId と
実行時間だけを記録する。

### 5.2 SQL 実行 profile

#### 5.2.1 `SPRING_MANAGED`

Spring Boot が自動構成する `DataSource` と pool を利用し、種類を固定しない。標準の単純 SQL は
`JdbcOperations`、カーソルは Spring が transaction-bound Connection を取得する
`DataSourceUtils` を使った限定的な低レベル callback で実装する。

Spring `JdbcTemplate` は SQLWarning を既定で無視するため、アプリケーション共有 instance の既定値に
依存しない。COBOL SQL adapter 専用の実行器が Statement / ResultSet / Connection の warning chain と
update count を close 前に採取し、通常結果と warning を同じ `SqlOutcome` へ保存する。低レベルで作る
全 Statement へ `DataSourceUtils.applyTransactionTimeout` 相当の transaction timeout と、設定された
query timeout / cancel policy を適用する。

`SPRING_MANAGED` profile の禁止事項は次のとおりである。

- 中立層または生成コードによる `DataSource.getConnection()`。
- JDBC URL、資格情報、HikariCP 設定の独自読み込み。
- COBOL の commit / rollback から `Connection.commit()` / `rollback()` を呼ぶこと。
- transaction-bound Connection、Statement、ResultSet を task 外または HTTP session に保存すること。

DataSource が複数ある場合は自動推測せず、アダプタ用 qualifier を要求する。read / write 分離は
同一 UOW と Db2 semantics を壊さない routing DataSource が提供された場合だけ利用する。

#### 5.2.2 `DB2_DRIVER_MANAGED_HOLD`

`WITH HOLD` が必須の task は Spring transaction / connection management を使用しない。task の最初の
SQL で Db2 adapter 専用 `Db2NativeConnectionProvider` から `ConnectionLease` を遅延取得し、同じ lease を
task 内の全 COBOL SQL と複数 commit 間で使用する。provider は IBM JDBC `DataSource` または dedicated
pool を所有できるが、対象 DataSource を Spring transaction manager へ登録しない。Spring IoC は provider
の構成と lifecycle 管理に使用してよい。

adapter は `autoCommit=false`、実効 isolation、schema、read-only、holdability を取得時に検証する。
hold cursor は Connection または Statement へ `HOLD_CURSORS_OVER_COMMIT` を明示し、driver が生成する
Db2 cursor の `WITH HOLD` と、commit 後の ResultSet 継続を実 Db2 で確認する。COBOL の明示 commit /
rollback と CICS syncpoint は lease の `Connection.commit()` / `rollback()` へ直接写像する。

この直接操作は Db2 adapter の内部だけに許可し、生成コード、中立層、業務 Java へ `Connection` を公開しない。
同一 task から `JdbcTemplate`、`DataSourceUtils`、Spring Data、JPA、`@Transactional` による SQL を呼んだ場合は、
同一 UOW へ参加したように見せず fail-fast にする。同じ UOW に必要な Java service は、lease-aware な
`SqlExecutorPort` または専用中立 callback port を使用する。

task-scoped registry は ResultSet、Statement、lease を所有し、cursor close 後にだけ lease を返す。正常終了、
ABEND、timeout、client disconnect、shutdown をすべて cleanup 経路に含める。返却時は変更可能な connection
property を基準値へ戻して検証し、reset できない connection は pool へ戻さず破棄する。lease は HTTP request /
CICS task を越えず、Spring Session や `ConversationEnvelope` に保存しない。

### 5.3 ホスト変数と SQLCA

ホスト変数の変換は `SqlValueCodec` として `cobol-db2` に置く。パック 10 進、ゾーン 10 進、
2 進、固定長文字、VARCHAR group、日付・時刻、null indicator をバイト精度で処理し、暗黙の
double 変換を行わない。出力値は全項目を検証してから COBOL storage へ反映し、途中失敗による
部分更新を避ける。

診断は二段階で変換する。

```text
SQLException / SQLWarning
        │ vendorCode, SQLState, chain, statement phase
        ▼
     SqlFailure (中立診断)
        │ Db2DialectDiagnosticMapper
        ▼
SQLCODE / SQLSTATE / SQLERRD / SQLWARN
```

`0`、`+100`、`-803`、`-805`、`-911`、`-913` を適合性試験の必須集合とする。SQLWarning は
例外経路だけを見て失わないよう、Connection / Statement / ResultSet の warning chain も収集する。
未知の vendor code を誤った既知 SQLCODE に丸めず、元の SQLSTATE と code を保存した診断にする。

ただし JDBC error code / SQLState だけから SQLCA 全項目を再現できるとは仮定しない。SQLCODE、SQLSTATE、
SQLERRMC、SQLERRP、SQLERRD(1〜6)、SQLWARN の各 field と SQL operation ごとに次の fidelity を公開する。

| fidelity | 意味 |
| --- | --- |
| `EXACT` | driver / result から同じ意味の値を取得し、実 Db2 oracle と一致 |
| `DERIVED` | JDBC update count 等から仕様上同値に導出できる |
| `UNAVAILABLE` | JDBC から取得不能。推測せず deterministic な未提供値と診断を設定 |

特に -805 は static package mode と dynamic JDBC mode を分け、dynamic mode で発生しない code を合成しない。
SQLERRD の row count、-911 / -913 reason、cursor capability、truncation warning は statement 種別ごとの
実 Db2 test を合格条件とする。

### 5.4 UOW profile と完了規則

`SPRING_MANAGED` adapter は `PlatformTransactionManager` と `TransactionDefinition` のプログラム的 API を
利用する。一つの CICS task が外側の controller transaction へ偶然参加しないよう、task 所有 UOW は
`PROPAGATION_REQUIRES_NEW` とする。controller に `@Transactional` を付けない。

interface が同じという理由で任意の transaction manager を受け入れない。初期 `SPRING_MANAGED` local
capability は
対象 Db2 DataSource と同じ `JdbcTransactionManager` または `DataSourceTransactionManager` に限定する。
起動時に manager、DataSource、`SqlExecutorPort` の resource identity と suspension / timeout capability を
probe し、不一致なら fail-fast する。JTA は別 `XA` profile、`JpaTransactionManager` と独自 manager は
JDBC 参加と複数 syncpoint の adapter contract test 合格時だけ許可する。

初期版では JPA `EntityManager` を明示 `COMMIT` / `SYNCPOINT` の前後にまたいで使用しない。commit 後の
persistence context が stale entity を保持し得るためである。Java service は同じ JDBC manager へ参加する
ものに限定し、ORM 対応時は flush / clear / rebind 規則を別 ADR で定める。

`DB2_DRIVER_MANAGED_HOLD` adapter は専用 lease を UOW として扱い、`Connection.commit()` /
`rollback()` を直接実行する。この例外は同 profile 内に閉じ、Spring の transaction synchronization を
併用しない。task の静的 call closure と SQL inventory を配備時に検査し、`WITH HOLD` がある entry は
この profile の明示選択がなければ起動を拒否する。動的 call で未宣言の hold cursor に到達した場合も
OPEN 前に診断して拒否し、実行途中で profile を切り替えない。

| 事象 | 既定動作 |
| --- | --- |
| 最初の SQL / transactional resource access | UOW を遅延開始 |
| SQL `COMMIT` | 現 UOW commit、カーソル規則適用、次 UOW は遅延開始 |
| SQL `ROLLBACK` | 現 UOW rollback、非 hold cursor close、次 UOW は遅延開始 |
| CICS `SYNCPOINT` | SQL commit と同じ UOW を commit |
| CICS `SYNCPOINT ROLLBACK` | 同じ UOW を rollback |
| 正常 `RETURN` / task 終了 | profile 所有 UOW で会話表を保存して commit。Spring Session publish は profile 別整合性規則に従う |
| 未処理 `ABEND` / infrastructure failure | active UOW を rollback |
| handled CICS condition | command outcome を返し、規則が要求しない限り rollback-only にしない |
| Db2 deadlock / timeout | SQLCODE 規則で -911 / -913 等へ写像し、Db2 が UOW を rollback した場合は状態を同期 |

`SPRING_MANAGED` では、Java サービスが同じ thread で同じ transaction manager を使う限り COBOL SQL と
同一 UOW に参加する。
`REQUIRES_NEW` を Java サービス側で使うと CICS atomicity から外れるため、利用者が意図を明示する。
`DB2_DRIVER_MANAGED_HOLD` では通常の Spring Java service は参加しない。参加が必要な Java service は
同じ lease-aware port を使用し、Spring transaction annotation を禁止する。

`SPRING_MANAGED` local capability が一つの同期点で原子的に扱う durable resource は、一つの Db2
DataSource と、
同じ接続で更新する `STRICT` 会話表までである。CICS file / temporary queue adapter が別データベースや
message broker を更新する場合は、検証済み JTA/XA の `XA` capability、または原子性が異なる outbox
方針を明示する。起動時の capability 検査で不整合な組み合わせを拒否し、local transaction だけの
複数資源更新を CICS の完全な rollback と称しない。

`DB2_DRIVER_MANAGED_HOLD` で業務更新と会話 envelope を原子的にする場合は、同じ native lease から
Db2 会話表を更新する。Spring Session へ会話 payload / version も publish する構成だけは Db2 commit と
同一 UOW にならないため、`NON_ATOMIC` の outcome journal / idempotency 回復規則を適用する。

CICS 外で Java から COBOL を呼ぶ場合は、入口に次の completion policy を必須とする。

| 方針 | 用途 |
| --- | --- |
| `CICS_TASK` | task coordinator が正常終了 / ABEND で完了する |
| `EXPLICIT` | COBOL の COMMIT / ROLLBACK だけで完了し、未完了ならエラーにする |
| `HOST_MANAGED` | 呼出元 Spring transaction へ参加し、COBOL の明示 commit を禁止または savepoint 規則へ変換する |

### 5.5 カーソルと `WITH HOLD`

通常カーソルの handle は `CobolSession` 内の `CursorRegistry` に置き、ResultSet を外へ公開しない。
UOW 完了、`CANCEL`、ABEND、session close の各経路で close する。close 失敗は主例外を置換せず
suppressed diagnostic として記録する。

Db2 JDBC ドライバは JDBC holdability を提供するが、Spring の transaction manager は通常
transaction 完了時に Connection を解放する。さらに spool は native cursor の lock、可視性、I/O、
fetch error の発生時点を変える。このため未分類 cursor に近似方式を自動適用しない。

| 戦略 | 位置づけ | 動作 |
| --- | --- | --- |
| `REJECT_UNVERIFIED` | 未分類 cursor の既定 | 翻訳・起動時にソース位置つき診断を出し、明示選択まで実行しない |
| `PORTABLE_SPOOL` | cursor 単位 opt-in、近似 | commit 前に未 FETCH 行を bounded spool へ実体化。意味差と容量を compatibility report に記録 |
| `DB2_DRIVER_MANAGED_HOLD` | task 単位 opt-in、native | task の全 SQL を専用 connection lease で実行し、同じ物理接続を commit 間で維持。実 Db2 crash 試験合格時だけ有効 |

spool はメモリ上限を超えると暗号化可能な一時ファイルへ退避し、task / cursor / tenant ごとの総量、
行数、時間を制限する。close または task 終了で消去する。`FOR UPDATE`、sensitive scroll、LOB locator
など実体化で意味が変わる `WITH HOLD` は spool を拒否する。全 `WITH HOLD` を SQL inventory で列挙し、
選択戦略、根拠、証拠レベルを compatibility manifest に残す。

`DB2_DRIVER_MANAGED_HOLD` は cursor 単位で Spring UOW に混ぜる戦略ではなく、task 全体の execution
profile である。hold cursor を開いた task は正常終了まで connection を専有する。`RETURN` で疑似会話の
次 task へ移る際は全 cursor と lease を閉じ、次 task は検索条件と resume token から再開する。

### 5.6 SQL 方言

`Db2Dialect` は SQL 構文の分類、診断写像、型名、特殊レジスタ、分離レベル、cursor capability を
提供する。PostgreSQL 等への移行アダプタは同じ `SqlExecutorPort` を実装するが、変換不能な機能を
黙って近似せず、翻訳時または起動時に compatibility report へ出す。

## 6. 自動構成と設定

自動構成は次の条件で有効にする。

- CICS API と Spring MVC が classpath にあり `cobol.cics.enabled=true` のとき CICS Web 入口を構成。
- BMS Thymeleaf adapter と Thymeleaf が classpath にあり `cobol.cics.ui.enabled=true` のとき
  BMS view resolver、template、version 付き JavaScript / CSS resource を構成。
- `SPRING_MANAGED` は Db2 API、Spring JDBC、単一または明示指定 DataSource、transaction manager が
  揃うとき構成する。
- `DB2_DRIVER_MANAGED_HOLD` は IBM JDBC driver、専用 `Db2NativeConnectionProvider`、lease 上限が
  揃うときだけ構成し、Spring transaction manager へ対象 DataSource が登録されていないことを検査する。
- 利用者が同じ中立ポートの Bean を定義している場合は自動構成を後退させる。
- 必須の transaction registry、program catalog、execution profile、DataSource / provider 選択が曖昧なら
  fail fast する。同一 entry の call closure が複数 profile を要求する構成も拒否する。

想定する設定名前空間を示す。値は例であり、実装時に metadata と検証規則を同時に提供する。

```yaml
spring:
  datasource:
    connection-fetch: lazy
  lifecycle:
    timeout-per-shutdown-phase: 30s

cobol:
  cics:
    enabled: true
    endpoint: /cics
    max-concurrent-tasks: 200
    task-timeout: 30s
    conversation:
      consistency: strict
      ttl: 20m
      max-commarea-bytes: 32767
      max-container-bytes: 1048576
    ui:
      enabled: true
      compatibility: web-3270
      terminal-profile: model-2-24x80
      theme: green-on-black
      viewport: scale-or-scroll
      accessible-aid-panel: true
  db2:
    enabled: true
    default-execution-profile: spring-managed
    entry-profiles:
      HOLDTRN: db2-driver-managed-hold
    spring-managed:
      datasource-ref: applicationDataSource
      transaction-manager-ref: transactionManager
      hold-cursor-strategy: reject-unverified
      spool:
        memory-limit: 8MiB
        task-limit: 128MiB
    driver-managed-hold:
      connection-provider-ref: db2NativeConnectionProvider
      max-leases: 32
      acquire-timeout: 2s
      max-task-duration: 30s
      reset-on-return: strict
```

`SPRING_MANAGED` の資格情報、TLS、Db2 driver properties は既存の Spring Boot / DataSource 構成へ
委ねる。native provider は外部 secret と専用設定参照を使い、パスワードをアプリケーション設定へ複製しない。
`entry-profiles` は TRANSID だけでなく Java / batch entry definition にも同じ規則で適用する。

## 7. 障害、セキュリティ、運用

### 7.1 障害境界

| 分類 | 例 | 外部結果 | UOW |
| --- | --- | --- | --- |
| COBOL 正常結果 | RETURN、handled condition | 業務応答 | 規則どおり commit |
| CICS ABEND | 明示 ABEND、未処理 condition | 安全な abend id、詳細は内部 | rollback |
| SQL 業務診断 | duplicate、not found | SQLCA で COBOL が判断 | SQLCODE 別規則 |
| 会話競合 | 二重送信、版不一致 | conflict と再読込指示 | 新規更新を開始しない |
| 基盤障害 | Spring pool / native lease 枯渇、Session store 障害 | 一時障害、correlation id | rollback / outcome unknown を区別 |

HTTP status は transport の結果であり EIBRESP や SQLCODE と一対一にしない。レスポンスに stack trace、
SQL 文、資格情報、COMMAREA 内容を出さない。

### 7.2 セキュリティ

- Spring Security で認証・TRANSID 単位の認可を行い、未導入時は外部公開を fail closed とする構成を用意する。
- 会話 ID を session fixation 対策済みの不透明値とし、principal / tenant と結合する。
- 動的 program 名、container 名、SQL identifier は allow-list と長さ・文字集合で検証する。
- COMMAREA、container、SQL host value は通常ログへ出さない。診断採取は権限、mask、保持期限を要求する。
- native connection provider の資格情報は外部 secret から取得し、Spring 管理 DataSource の password を
  ログ、Actuator environment、設定複製によって漏らさない。
- spool と Session store の保存データを暗号化対象として分類し、終了時削除と孤児回収を行う。
- Thymeleaf は通常 escape だけを使い、BMS literal / value の `th:utext` と inline script を禁止する。
- CSP は `script-src` の nonce / hash または self の version 付き module に限定し、CSRF token を全 AID
  送信へ付与する。protected / DRK / hidden 属性はサーバ側認可や機密データ除去の代わりにしない。

### 7.3 観測性

低 cardinality のタグだけを meter に使う。conversation ID、user ID、SQL text をタグにしない。

| 指標 / observation | 主な属性 |
| --- | --- |
| `cobol.cics.task` | transid、outcome、abend-code-class |
| `cobol.program.call` | program-id、call-kind、outcome |
| `cobol.db2.statement` | statement-id、operation、sqlcode-class |
| `cobol.uow` | completion、rollback-reason |
| `cobol.conversation.conflict` | store-kind |
| `cobol.cursor.spool.bytes` | strategy、outcome |
| `cobol.db2.native.lease` | state、outcome、wait-class |
| `cobol.bms.screen` | map-id、profile、outcome |
| `cobol.bms.input.rejected` | reason-class、aid-class |

trace context は task と Java サービス呼び出しをつなぐが、疑似会話をまたいで同じ span を開いたままに
しない。次要求には安全な link / correlation を使う。

### 7.4 graceful shutdown

Spring Boot の graceful shutdown 開始時に `TaskAdmissionPort` を閉じ、readiness を out-of-service にする。
新規 CICS task を拒否し、実行中 task を shutdown phase の上限まで待つ。期限超過時は UOW を rollback
できる協調取消し点を使い、Thread の強制停止は行わない。保存済み疑似会話は次インスタンスで再開できる。
native lease は全 cursor / statement を閉じてから返却し、shutdown 上限後も回収不能な connection は
再利用せず破棄して outcome unknown を記録する。

## 8. フレームワーク変更と Spring Boot 更新

### 8.1 交換可能性の規則

次の型は `cobol-cics`、`cobol-db2`、生成クラスの公開 API / 永続形式に含めない。

- `org.springframework.*`
- `jakarta.servlet.*`
- `java.sql.Connection` / `Statement` / `ResultSet`
- HikariCP、Db2 driver の実装型
- Jackson、Spring Session の内部直列化型
- Thymeleaf、HTML / DOM、browser event の型

別フレームワークへ移行するときは Web endpoint、BMS view、conversation store、transaction manager、
JDBC adapter、observability を置換し、同じ port contract test を通す。`ConversationEnvelope` には形式 version を持たせ、
rolling upgrade では旧形式の読み取りと新形式の書き込みを少なくとも一版重ねる。

ここでいう交換可能性は、同一 thread 上の同期 COBOL 実行と imperative transaction を提供できる host に
限る。WebFlux / R2DBC、actor、remote worker へ移す場合は、thread ownership、continuation、cancellation、
transaction context の前提が変わるため adapter 交換だけでは対応しない。新しい task coordinator と ADR、
同等の subsystem oracle を必要とする。

### 8.2 Spring Boot 4.1 更新方針

`cobol-spring-boot-4-*` は Spring Boot BOM を利用し、Spring component の個別 version を固定しない。
内部クラス、非公開 Session repository 実装、pool implementation への反射アクセスを禁止する。

CI は最低限次を実行する。

| レーン | 目的 |
| --- | --- |
| Boot 4.1.0 | 宣言した基準版との互換性 |
| 最新 Boot 4.1.x | patch 更新の追随 |
| 次 minor / major の最新公開版 | 非 blocking の早期警告。移行ガイドと deprecation を確認 |
| framework-free contract | Spring を外して中立ポートの安定性を確認 |

リリースごとに Boot、Framework、Spring Session、Thymeleaf、対応 browser、Db2 JDBC driver、Java の
互換表を公開する。
更新時は `ApplicationContextRunner` による自動構成、MockMvc による CICS 入口、実 Db2 による
トランザクション・SQLCA・hold cursor、Session store 別の疑似会話を再試験する。Spring Boot major
変更でアダプタ API が変わる場合も、生成 COBOL と中立ポートのバイナリ互換を先に検証する。

AOT / native image は初期保証に含めない。対応する場合は program catalog の事前列挙、reflection hint、
Session serializer を別 capability として設計し、通常 JVM 実行を変えない。

## 9. テスト戦略

### 9.1 中立契約試験

`cobol-subsystem-testkit` で次を adapter 共通シナリオにする。

- LINK / XCTL / RETURN の制御継続位置と session 共有。
- EIBRESP、ABEND、HANDLE CONDITION、SYNCPOINT の結果。
- 疑似会話の版競合、期限、COMMAREA / container のコピー分離。
- SQL host variable、null indicator、SQLCA、warning chain。
- commit / rollback / rollback-only と cursor close の順序。
- `WITH HOLD` spool の境界、上限、削除、非対応 cursor の診断。
- `DB2_DRIVER_MANAGED_HOLD` の commit 後 FETCH、rollback / ABEND close、lease reset、profile 混在拒否。
- BMS map の座標、基本・拡張属性、field wrap、DBCS cell / byte length、SEND / RECEIVE option。
- JavaScript とサーバ `BmsInputDecoder` に同じ入力 trace を与え、MDT、AID、cursor、入力拒否結果が
  一致すること。

JUnit から COBOL を試験する方法と program / SECTION Mock は[設計 76](76-junit-testing.md)を使う。
テスト単位では `SqlExecutorPort`、`ConversationStorePort`、CICS file / queue port も fake に差し替え、
呼出履歴と UOW 境界を検証できるようにする。

### 9.2 Spring slice / 統合試験

- `ApplicationContextRunner`: classpath 条件、利用者 Bean 優先、曖昧 DataSource の fail-fast。
- MockMvc: TRANSID 解決、認証、payload 上限、会話競合、transport mapping。
- MockMvc / Thymeleaf: map model、escaped literal / value、CSRF、CSP、version / idempotency の round trip。
- `@SpringBootTest`: Java service と COBOL SQL が同じ transaction で commit / rollback されること。
- `@SpringBootTest` + 実 Db2: native profile が Spring transaction を開始せず、同一 lease で複数 commit 後も
  FETCH を継続し、task 終了時に全 JDBC resource を解放すること。
- Spring Session JDBC / Redis: `STRICT` と `NON_ATOMIC` の保証差、JDBC repository の
  `REQUIRES_NEW` からの分離、commit 前後の障害注入。
- 実 Db2 または許諾済みコンテナ: vendor code、SQLSTATE、warning、deadlock、cursor holdability。
- shutdown 試験: 新規受付停止、実行中 task の完了、期限超過 rollback、会話再開。

BMS UI は対応 browser ごとに headless browser E2E を実行し、Tab / Backtab、PF / PA / Enter、
insert / overwrite、ASKIP、NUM、MDT、keyboard lock、IME を操作 trace で検証する。`WEB_3270_STRICT` は
固定 viewport、配布 font、device pixel ratio で golden screenshot を比較し、cell boundary のずれも
数値検査する。axe 等の自動アクセシビリティ検査と keyboard-only 操作も release gate に含める。

実ホスト画面が利用できる場合は、個人情報を除去した画面 capture、cursor / AID trace、BMS map から
golden data を作る。画像だけで合否を決めず、field 座標・属性・送受信 byte の構造比較を併用する。

H2 等の互換モードだけで Db2 の適合性を合格にしない。高速試験には fake / H2 を使えても、Db2 固有の
SQLCA と cursor の release gate は実 Db2 を要求する。

### 9.3 subsystem oracle と証拠レベル

Hercules V2 は CICS、Db2、3270 browser UI の oracle ではない。各適合性項目へ次の証拠レベルを付ける。

| レベル | 根拠 | 表示可能な主張 |
| --- | --- | --- |
| `V2-SUBSYSTEM` | IBM CICS / Db2 実環境から採取した sanitized command、SQLCA、screen / AID trace と一致 | 対象 profile / version で実機一致 |
| `V1` | IBM 公開仕様と構造化 test vector に一致 | 仕様準拠。実機一致は未確認 |
| `V0` | 合理的推測または Web 上の近似 | experimental。互換保証なし |

要件 ID、BMS / SQL / CICS feature、test vector、oracle、証拠レベル、adapter / driver / browser version を
traceability matrix にする。golden screenshot だけで BMS を `V2-SUBSYSTEM` とせず、field 座標、属性、
symbolic map byte、cursor、AID、MDT trace を比較する。

### 9.4 容量、backpressure、障害耐性試験

`max-concurrent-tasks` を固定の標準値として扱わない。deployment ごとに次を測定して capacity plan を作る。

- servlet executor の active / reserved thread と bounded admission queue。
- task ごとの `CobolSession` / WORKING-STORAGE / EXTERNAL / BMS snapshot の最大 resident bytes。
- Spring Db2 pool と native dedicated pool の size、reserved capacity、connection / lease wait、query
  timeout、SQL を使わない task の割合。
- held cursor / spool の行数・byte 数・一時領域・暗号化 overhead。
- Spring Session / outcome journal の payload、TTL、cleanup throughput。

health / readiness と shutdown 用に servlet / Db2 の capacity を予約し、全 worker が pool wait で塞がらない
ことを load / soak test で確認する。queue、task、SQL、Session publish の timeout と overload response を
一つの deadline から導出し、commit outcome unknown と未開始を区別する。process kill、Db2 failover、
Session store outage、disk full、spool limit、browser retry を crash point として試験する。

## 10. 段階的な実装順序

実装は次の順序で進める。

1. 中立 command / SQL plan / UOW / conversation / BMS screen モデルと contract test kit。
2. Spring Boot 4.1 DataSource / transaction adapter と単純 SQL、SQLCA。
3. 同期 MVC task coordinator、LINK / XCTL / RETURN、EIB、Spring Session JDBC。
4. cursor、明示 COMMIT / ROLLBACK / SYNCPOINT、`PORTABLE_SPOOL`。
5. BMS parser、Thymeleaf 共通 view、CSS grid、server input decoder。
6. JavaScript terminal state machine、主要 BMS 属性、browser / visual / accessibility 試験。
7. file / queue port、Redis / XA profile、Actuator / graceful shutdown。
8. task 専有 lease、混在拒否、実 Db2 crash test を備えた `DB2_DRIVER_MANAGED_HOLD` と将来 framework adapter。

各段階で中立契約試験と Boot 4.1 互換マトリクスを release gate にする。

敵対的レビューの P0 gate が未合格の段階は experimental とし、CICS / Db2 / BMS の互換保証を表示しない。

## 11. 敵対的レビューによる実装開始ゲート

| Gate | 合格条件 |
| --- | --- |
| UOW profile | Spring manager / DataSource identity と native provider / lease ownership、混在拒否、timeout、crash point が確定 |
| SQLCA | field / statement ごとの `EXACT` / `DERIVED` / `UNAVAILABLE` matrix が実 Db2 で検証済み |
| `WITH HOLD` | 全 cursor を inventory 化し、必須 entry の task-wide native profile、lease 容量、task 境界 close を実 Db2 で承認済み |
| conversation | STRICT と NON_ATOMIC の commit / crash / retry / expiry state machine が合格 |
| BMS renderer | 難画面 spike が cell cursor、wrap、DBCS / IME、AID / MDT、accessibility 基準を合格 |
| oracle | CICS / Db2 / BMS の traceability matrix と証拠レベルを公開可能 |
| capacity | bounded queue、memory / pool / spool budget と load / soak threshold が確定 |

## 12. 未決事項と実装開始条件

次は実装開始前に対象環境から確定する必要がある。確定しても上記の境界は変更しない。

- Db2 の製品・版、IBM JDBC driver 版、local transaction と JTA/XA のどちらを採るか。
- `DB2_DRIVER_MANAGED_HOLD` 対象 entry、最大同時 lease 数、最大 task 時間、native provider / pool の方式。
- Spring Session の保存先と、要求される疑似会話・業務更新間の原子性。
- 現行 COBOL が利用する CICS command、BMS 属性、最大 COMMAREA / container サイズ。
- 現行端末 model、code page、DBCS、map 端折返し、動的属性上書き、SEND option の利用実態。
- 対応 browser、配布可能な monospace font、標準 viewport / device pixel ratio、許容する視覚差分。
- `WITH HOLD` で `FOR UPDATE`、scroll、LOB locator を利用しているプログラムの棚卸し。
- TRANSID の認証・認可、tenant 分離、再送時の業務冪等性要件。
- shutdown 猶予、最大 task 時間、同時 task 数、pool / spool 容量の性能目標。

これらは環境 profile と capability を決める入力であり、Spring 型を中核 API へ入れる理由にはしない。

## 13. 参照資料

- [Spring Boot 4.1 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)
- [Spring Boot: SQL Databases](https://docs.spring.io/spring-boot/reference/data/sql.html)
- [Spring Framework: Controlling Database Connections](https://docs.spring.io/spring-framework/reference/data-access/jdbc/connections.html)
- [Spring Framework: Programmatic Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)
- [Spring Framework: Declarative Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-decl-explained.html)
- [Spring Framework: Annotated Controllers](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html)
- [Spring Boot: Servlet Web Applications](https://docs.spring.io/spring-boot/reference/web/servlet.html)
- [Thymeleaf: Tutorial - Thymeleaf + Spring](https://www.thymeleaf.org/doc/tutorials/3.1/thymeleafspring.html)
- [Thymeleaf: Tutorial - Using Thymeleaf](https://www.thymeleaf.org/doc/tutorials/3.1/usingthymeleaf.html)
- [Spring Session: JDBC Guide](https://docs.spring.io/spring-session/reference/guides/boot-jdbc.html)
- [Spring Session: Redis Guide](https://docs.spring.io/spring-session/reference/guides/boot-redis.html)
- [Spring Session: API and JDBC transaction behavior](https://docs.spring.io/spring-session/reference/api.html)
- [Spring Boot: Graceful Shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html)
- [Spring Boot Actuator: Endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)
- [Spring Boot Actuator: Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)
- [IBM Db2: JDBC ResultSet characteristics](https://www.ibm.com/docs/en/db2/11.5.x?topic=drija-characteristics-jdbc-resultset-under-data-server-driver-jdbc-sqlj)
- [IBM Db2: JDBC exceptions and warnings](https://www.ibm.com/docs/en/db2/11.5.x?topic=jap-exceptions-warnings-under-data-server-driver-jdbc-sqlj)
- [IBM Db2: JDBC driver properties](https://www.ibm.com/docs/en/db2/11.5.x?topic=pdsdjs-common-data-server-driver-jdbc-sqlj-properties-all-database-products)
- [IBM Db2: Creating and deploying DataSource objects](https://www.ibm.com/docs/en/db2/11.5.x?topic=source-creating-deploying-datasource-objects)
- [IBM Db2: JDBC and SQLJ connection pooling](https://www.ibm.com/docs/en/db2/12.1.x?topic=java-jdbc-sqlj-connection-pooling-support)
- [IBM Db2 for z/OS: SQLCA fields](https://www.ibm.com/docs/en/db2-for-zos/13.0.0?topic=sqlca-description-fields)
- [IBM Db2 for z/OS: Held and non-held cursors](https://www.ibm.com/docs/en/db2-for-zos/13.0.0?topic=cursors-held-non-held)
- [IBM CICS: Synchronization points](https://www.ibm.com/docs/en/cics-ts/6.x?topic=work-synchronization-points)
- [IBM CICS: RESP and RESP2 options](https://www.ibm.com/docs/en/cics-ts/5.6.0?topic=format-resp-resp2-options)
- [IBM CICS: Using the HANDLE CONDITION command](https://www.ibm.com/docs/en/cics-ts/6.x?topic=handling-using-handle-condition-command)
- [IBM CICS: How CICS keeps track of what to do](https://www.ibm.com/docs/en/cics-ts/6.x?topic=handling-how-cics-keeps-track-what-do)
- [IBM CICS: Rules for calling subprograms](https://www.ibm.com/docs/en/cics-ts/6.x?topic=programs-rules-calling-subprograms)
- [IBM CICS: EIB fields](https://www.ibm.com/docs/en/cics-ts/6.x?topic=areas-eib-exec-interface-block)
- [IBM CICS: Defining map fields by using DFHMDF](https://www.ibm.com/docs/en/cics-ts/6.x?topic=map-defining-fields)
- [IBM CICS: BMS macro DFHMDF](https://www.ibm.com/docs/en/cics-ts/6.x?topic=macros-dfhmdf)
- [IBM CICS: Setting the display characteristics](https://www.ibm.com/docs/en/cics-ts/6.x?topic=output-setting-display-characteristics)
