# 設計文書 78: IMS サブシステム連携 (IMS DB / IMS TM)

| 項目 | 内容 |
| :--- | :--- |
| 対応要件 | FR-150〜156, NFR-032, ARC-4, ARC-7 |
| 関連 ADR | [ADR-0007](../decisions/0007-framework-neutral-subsystem-ports.md), [ADR-0010](../decisions/0010-bms-thymeleaf-terminal-ui.md), [ADR-0013](../decisions/0013-ims-db-denormalized-raw-storage-engine.md), [ADR-0014](../decisions/0014-ims-tm-rabbitmq-jms-neutral-queue.md), [ADR-0015](../decisions/0015-ims-db-locking-and-deadlock-avoidance.md) |
| ステータス | 設計完了。中立契約とアーキテクチャ定義済み。P0 実装準備中 |
| 基準環境 | Java 21, Spring Boot 4.1.x, PostgreSQL / IBM Db2, RabbitMQ 3.13 (JMS 3.0) |

---

## 1. 目的とスコープ

IBM メインフレーム（z/OS）上で稼働する IMS（Information Management System）資産を、**COBOL ソースコード無修正で JVM 上へ移行・稼働可能** にする。

1. **`CALL 'CBLTDLI'` 手続き型インタフェースの完全透過実行**:
   - `GU`, `GN`, `GNP`, `GHU`, `GHN`, `GHNP`, `ISRT`, `REPL`, `DLET`, `CHKP` の呼出しを中立ランタイム `cobol-ims` でインターセプト・実行する。
2. **階層型データベース (IMS DB) の RDB 透過ストレージ化**:
   - PostgreSQL および IBM Db2 をバックエンドとし、正規化を行わずに生バイト BLOB と階層走査パス（`HIERARCHY_PATH`）でツリー構造と深さ優先探索順序を保証する（[ADR-0013](../decisions/0013-ims-db-denormalized-raw-storage-engine.md)）。
3. **メッセージキュー駆動 (IMS TM / MPP) の中立エミュレーション**:
   - Docker Compose 上の RabbitMQ を標準とし、JMS 3.0（Jakarta Messaging）を介した中立キューポートにより、電文のキューイン・キューアウトおよびステータスコード `'QC'` 制御を再現する（[ADR-0014](../decisions/0014-ims-tm-rabbitmq-jms-neutral-queue.md)）。
4. **高並行性とデッドロックの撲滅**:
   - ルートアンカーロック（`IMS_ROOT_INDEX` の排他行ロック）により同一ルート配下の更新を直列化し、木構造内部での循環待ちデッドロックを原理的に排除する（[ADR-0015](../decisions/0015-ims-db-locking-and-deadlock-avoidance.md)）。
5. **メインフレーム互換運用バッチとモダン CLI の提供**:
   - `DFSURGU0`（アンロード）や `DFSURGL0`（リロード）を `cobol-job` のユーティリティとして提供し、JCL 資産を維持する。

---

## 2. モジュール構成と依存関係

[ADR-0007](../decisions/0007-framework-neutral-subsystem-ports.md) の中立原則に従い、特定の RDB ドライバやメッセージング製品の型をコアランタイムへ持ち込まない。

```
[ COBOL プログラム / cobol-job JCL ステップ ]
                   |
                   v
+-------------------------------------------------------------------------+
| `cobol-ims` (中立コアモジュール)                                        |
|   - CbltdliBridge: CALL 'CBLTDLI' / AIBTDLI ディスパッチャ              |
|   - SsaParser: セグメント探索引数 (SSA) とコマンドコード (*D, *F...) 解析|
|   - PcbStateManager: カレント位置・親境界 (Parentage) の厳格管理         |
|   - DliDatabasePort: 階層 DB 操作の中立契約                             |
|   - ImsQueuePort: トランザクションメッセージキューの中立契約             |
+-------------------------------------------------------------------------+
         |                                                 |
         v                                                 v
+------------------------------------+  +---------------------------------+
| `cobol-ims-rdb` (RDB ストレージ)   |  | `cobol-ims-jms` (TM キュー)     |
|   - PostgreSQL / Db2 JDBC アダプタ |  |   - JMS 3.0 / rabbitmq-jms      |
|   - IMS_SEGMENT_STORE 階層走査     |  |   - BytesMessage & LLZZ 変換    |
|   - ルートアンカーロック排他制御   |  |   - QC タイムアウト制御         |
|   - ブロックプリフェッチキャッシュ |  +---------------------------------+
+------------------------------------+
```

### 依存関係の制約
- `cobol-ims` は `cobol-runtime` のみに依存する。Spring、JDBC、JMS、RabbitMQ の型を一切公開・参照しない。
- `cobol-ims-rdb` は JDBC 標準（`java.sql.*`）に依存し、方言（PostgreSQL / Db2）は SQL 文の内部切り替えで吸収する。
- `cobol-ims-jms` は `jakarta.jms.*` に依存する。

---

## 3. IMS DB (DL/I) の中立契約とストレージエンジン

### 3.1 中立ポート定義 (`DliDatabasePort`)
```java
public interface DliDatabasePort {
    DliResult getUnique(PcbHandle pcb, List<ParsedSsa> ssaList, byte[] ioBuffer);
    DliResult getNext(PcbHandle pcb, List<ParsedSsa> ssaList, byte[] ioBuffer);
    DliResult getNextInParent(PcbHandle pcb, List<ParsedSsa> ssaList, byte[] ioBuffer);
    DliResult insert(PcbHandle pcb, List<ParsedSsa> ssaList, byte[] ioBuffer);
    DliResult replace(PcbHandle pcb, byte[] ioBuffer);
    DliResult delete(PcbHandle pcb);
    void checkpoint(String chkpId);
}
```

### 3.2 物理テーブル設計（非正規化生バイト格納）
```sql
CREATE TABLE IMS_SEGMENT_STORE (
    DBD_NAME          VARCHAR(8)    NOT NULL,
    ROOT_KEY_RAW      VARBINARY(64) NOT NULL,  -- ルートキー生バイト (PostgreSQL: BYTEA)
    HIERARCHY_PATH    VARCHAR(256)  NOT NULL,  -- 階層走査パス (/001/01-000100/...)
    SEG_NAME          VARCHAR(8)    NOT NULL,  -- セグメント名
    SEG_LEVEL         SMALLINT      NOT NULL,  -- 階層レベル (1=ROOT...)
    PARENT_PATH       VARCHAR(256)  NOT NULL,  -- 親の HIERARCHY_PATH
    SEQ_KEY_RAW       VARBINARY(64) NULL,      -- セグメントキー
    SEG_DATA          BLOB          NOT NULL,  -- EBCDIC生バイト列 (PostgreSQL: BYTEA)
    CREATED_AT        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_IMS_SEGMENT PRIMARY KEY (DBD_NAME, ROOT_KEY_RAW, HIERARCHY_PATH)
);

CREATE TABLE IMS_ROOT_INDEX (
    DBD_NAME          VARCHAR(8)    NOT NULL,
    ROOT_KEY_RAW      VARBINARY(64) NOT NULL,
    ROOT_KEY_HEX      VARCHAR(128)  NOT NULL,
    CONSTRAINT PK_IMS_ROOT PRIMARY KEY (DBD_NAME, ROOT_KEY_RAW)
);
```

### 3.3 複雑セマンティクスとコマンドコードの再現
1. **`*D` (Path Call)**: 最下位だけでなく、`*D` を含む全親レベルの `SEG_DATA` を階層順に結合して受取バッファへ転記する。
2. **`*F` (First Occurrence)**: 親配下の先頭位置へ強制リセット。
3. **親境界（Parentage）**: `GU`/`GN` で確立され、`GNP` では親位置を進めず維持。終端（`GE`）で親境界を失効。
4. **複数 PCB カーソル**: PCB インスタンスごとに独立した `PcbStateManager` を割り当て、同一 DBD に対する並行探索を可能にする。

---

## 4. IMS TM (メッセージキュー) と MPP 実行

### 4.1 中立ポート定義 (`ImsQueuePort`)
```java
public interface ImsQueuePort {
    Optional<ImsMessage> poll(String transactionCode, Duration timeout);
    void sendReply(ImsMessageContext context, byte[] payload);
    void commit();
    void rollback();
}
```

### 4.2 MPP のメッセージ駆動ループ制御
COBOL の MPP プログラムは、以下のフローで自動実行される。
1. `ImsQueuePort.poll(trx, timeout)` で RabbitMQ（JMS キュー）からメッセージを待機受信。
2. 取得したバイト列を `LLZZ` プレフィックスを維持したまま COBOL の受取バッファへ転記し、`IO-PCB` のステータスを `'  '` に設定。
3. キューが空（タイムアウト）の場合は `IO-PCB` のステータスを `'QC'` に設定。COBOL プログラムはループを抜け、`GOBACK` で正常終了する。
4. プログラムが `ISRT` で応答を出力した場合、`JMSCorrelationID` を付与して応答先キューへ送信する。

---

## 5. ロック並行性制御と障害耐性

1. **ルートアンカーロック**:
   - セグメントの `ISRT` / `REPL` / `DLET` 前に、必ず `SELECT ROOT_KEY_RAW FROM IMS_ROOT_INDEX WHERE ... FOR UPDATE` を発行して直列化。
2. **MVCC 活用**:
   - `GU` / `GN` / `GNP` はスナップショット参照（Read Committed）で行ロックを獲得しない。
3. **DML 昇順ソート**:
   - コミット前のバッファリング DML を `HIERARCHY_PATH ASC` でソートして発行。
4. **擬似 ABEND `U0777` リトライ**:
   - RDB 側でデッドロック検知時は、MPP トランザクションを自動ロールバックし、指数バックオフ後に最大 3 回まで同一電文を自動再試行する。

---

## 6. 運用ユーティリティ (`cobol-job` 連携)

メインフレーム互換の運用バッチプログラムを提供し、既存 JCL を無修正で実行可能とする。

- **`DFSURGU0` (HD Reorganization Unload)**:
  - `IMS_SEGMENT_STORE` から全セグメントを階層順（`HIERARCHY_PATH ASC`）で読み出し、`DFSEXTDS` DD のデータセットへアンロード。
- **`DFSURGL0` (HD Reorganization Reload)**:
  - アンロードファイルを読み込み、PostgreSQL `COPY` / Db2 `ADMIN_CMD('LOAD ...')` を用いて高速一括ロード。
- **モダン CLI (`ims-admin`)**:
  - `stat`（統計照会）、`print`（ツリー整形ダンプ）、`unload`、`reload`、`verify`（孤児セグメント SQL 検査）のサブコマンドを提供。
