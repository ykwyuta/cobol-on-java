# 設計文書 78: IMS サブシステム連携 (IMS DB / IMS TM)

| 項目 | 内容 |
| :--- | :--- |
| 対応要件 | **FR-164**, NFR-032, ARC-4, ARC-7 |
| 関連 ADR | [ADR-0007](../decisions/0007-framework-neutral-subsystem-ports.md), [ADR-0010](../decisions/0010-bms-thymeleaf-terminal-ui.md), [ADR-0013](../decisions/0013-ims-db-denormalized-raw-storage-engine.md), [ADR-0014](../decisions/0014-ims-tm-rabbitmq-jms-neutral-queue.md), [ADR-0015](../decisions/0015-ims-db-locking-and-deadlock-avoidance.md) |
| ステータス | **要件確定待ち。**中立契約の設計のみ進行可 ([P-098](../decisions/provisional.md)) |
| 検証レベル | **V1 / V0**。DL/I の oracle は存在しない ([P-099](../decisions/provisional.md)) |
| 基準環境 | Java 21, Spring Boot 4.1.x, PostgreSQL / IBM Db2 |
| 改訂 | 2026-09-13。[批判的レビュー](../reviews/2026-09-13-ims-research-critical-review.md) の全 34 指摘を反映 |
| 測定 | 2026-09-15。Bank-of-Z の IMS の COBOL 11 本のうち翻訳が通るのは 10 本 (IBTRAN は `REPOSITORY` / JNI)。入口は P-152。DBD 9 本・PSB 8 本はすべて読める (`verify ims-gen`、P-153) |
| 実装 | 2026-09-16。第 1 増分の DBD / PSB の読み取りと `DFSRRC00` (DLI / DBB)、第 2 増分の SSA・PCB の状態・メモリの上の DL/I (`ImsRegion`) を置いた。Bank-of-Z の読み込み 5 本が JCL で流れる。第 4 増分の一部として I/O PCB (GU / GN / ISRT / PURG) と中立の `MessageQueue` を置き、オンライン 5 本が電文に応答する。同期点 (I/O PCB への GU、基本 CHKP、SYNC、ROLB と異常終了の巻き戻し、§3.5 の位置破棄) を入れた (P-157)。`DFSRRC00` の BMP (電文を読まない形) を受けた (P-158)。§3.4 のコマンドコードのうち C / D / F / L / N / P / Q / - を入れた (U / V は P-100 のまま断る、P-159)。第 3 増分の置き場を中立の口 `DatabaseStore` の裏に置き、`cobol-ims-rdb` が §3.2 の表 (主キーに `ROOT_SEQ` を足した) へ同期点ごとに確定する (H2 / PostgreSQL / Db2、P-160。PostgreSQL 17.11 と Db2 12.1 で Bank-of-Z を流して実測した)。第 4 増分の JMS のキュー (`cobol-ims-jms`、ADR-0014) を置き、RabbitMQ を `infra/rabbitmq` の compose に足して、実ブローカ (4.1.8) で起動と電文の試験を確認し、`verify ims-mpp` から JMS のキューを選べるようにして Bank-of-Z のオンライン 5 本をブローカ越しに測った (P-162、P-165)。ブローカを落として再開しても電文は残り、確定しないまま領域が落ちた電文は戻り、順序も保たれることを測った (P-162)。§4.4 の inbox による冪等化を入れ、処理済みの電文を業務の更新と同じトランザクションで覚えて再配信を捨てる (P-163)。§5 のルートアンカーロックは同期点の確定で `(DBD_NAME, ROOT_KEY_RAW)` 昇順に押さえ、根の版で遅れた更新を競合として止め、確定のあと他の領域の確定を読み直す (GH の時点の排他は未実装、P-161)。競合した電文駆動の領域は、置き場から読み直して頭から動かし直し、使い切れば U0777 で落とす (P-168)。記号 CHKP と XRST を入れ、退避した域を業務の更新と同じ確定で置き場に残す (P-164)。取引コードのキューを読む領域を置き場の借用で 1 つに限り、2 つ目は起こさずに断る (P-167)。SPA、電文を読む BMP、GSAM は未実装 (Bank-of-Z がどれも使っておらず測る基準が無い、P-166)。暫定判断は P-154〜P-168 |

---

## 0. この設計の位置づけ

**IMS を要求している要件は FR-164 の 1 本だけである。**

> **FR-164 (L1)**: IMS DL/I 呼び出し (`CBLTDLI` / `AIBTDLI`) のインタフェースを定義し、PCB マスク・SSA・状態コード (`GA`、`GB`、`GE`、`II` 等) を扱えるようにする。実際のデータベースへの写像は、差し替え可能なアダプタとして提供する。**IMS 連携はフェーズ 4 の目標とし、初期リリースではインタフェース定義のみとする。**

本書のうち **FR-164 の範囲内**なのは第 2 章（モジュール構成）と第 3.1 節（中立ポート）だけである。RDB ストレージエンジン、ロック方式、メッセージ基盤、MFS、運用ユーティリティは**対応する要件がまだ書かれていない**。フェーズ表 P4 の受け入れ基準にも IMS の記述は無い。

したがって本書の扱いは次のとおりとする。

- 中立ポートの設計は進めてよい。ただし**公開 API として凍結しない**（第 3.1 節・第 4.1 節の契約は未完である。第 6 章を参照）。
- ストレージ・ロック・TM・MFS・運用ツールは、要件が書かれるまで**提案**として扱う。
- **DL/I には CCVS85 や Hercules に相当する外の基準が無い。**要件 §4.3 は FR-150〜164 を V1（仕様準拠、実行による裏取り無し）止まりと明記している。本書に「忠実」「完全」「100%」といった表現は用いない。

---

## 1. 目的とスコープ

IBM メインフレーム（z/OS）上で稼働する IMS 資産を、COBOL ソースコードを修正せずに JVM 上で動かせる状態を目指す。

1. **`CALL 'CBLTDLI'` 手続き型インタフェースの受け口** (FR-164 の範囲):
   - `GU`, `GN`, `GNP`, `GHU`, `GHN`, `GHNP`, `ISRT`, `REPL`, `DLET`, `CHKP` を中立ランタイム `cobol-ims` でインターセプトする。
2. **階層型データベース (IMS DB) の RDB ストレージ化** (要件未記述):
   - 正規化を行わず、生バイト BLOB と階層走査パスでツリー構造と深さ優先探索順序を保つ ([ADR-0013](../decisions/0013-ims-db-denormalized-raw-storage-engine.md))。
3. **メッセージキュー駆動 (IMS TM / MPP)** (要件未記述):
   - JMS 3.0 を介した中立キューポートにより、電文のキューイン・キューアウトとステータスコード `'QC'` の制御を行う ([ADR-0014](../decisions/0014-ims-tm-rabbitmq-jms-neutral-queue.md))。
4. **並行性制御** (要件未記述):
   - ルートアンカーロックにより同一ルート配下の更新を直列化する ([ADR-0015](../decisions/0015-ims-db-locking-and-deadlock-avoidance.md))。
5. **バッチ起動経路と運用ユーティリティ** (要件未記述):
   - `DFSRRC00` を `cobol-job` から起動できるようにする（第 7 章）。

### 1.1 対応範囲の線引き

要件 §4.1 の原則に従い、**L0 (未対応) は必ず検出して診断を出す**。黙って近い結果を返さない。

| 機能 | 扱い | 根拠 |
| --- | --- | --- |
| HIDAM / PHIDAM / HISAM のルート順序 | 対応 (キー昇順で再現) | — |
| **HDAM / PHDAM のルート順序** | **再現しない。**無限定 `GN` の順序が実機と異なることを診断 | ランダマイザを再現できない ([P-102](../decisions/provisional.md)) |
| **二次索引 (`PROCSEQ=`)** | **L0。**`PROCSEQ=` を持つ PCB を受け付けない | 単一順序モデルの前提を壊す ([P-103](../decisions/provisional.md)) |
| **論理関係 (`LCHILD` / 連結セグメント)** | **L0** | 木でなく網になり `HIERARCHY_PATH` で表現できない |
| **GSAM** | **L0** | 記号 `CHKP` / `XRST` は入れたが、GSAM のデータセットの位置づけ直しは持たない ([P-110](../decisions/provisional.md)、[P-164](../decisions/provisional.md)) |
| **Fast Path (DEDB / MSDB)** | **L0** | `FLD` コール等、別の API 群 |
| **symbolic `CHKP` の領域退避 / `XRST`** | **実装済** | 退避した域を業務の更新と同じ確定で置き場に残し、`XRST` が作業域か `CKPTID=` の検査点から書き戻す ([P-164](../decisions/provisional.md)) |
| 会話型トランザクション (SPA) | 契約に含めるが<b>未実装</b>（第 4.3 節、[P-166](../decisions/provisional.md)） | 当初の設計から欠落していた。Bank-of-Z に会話型取引が 1 つも無いことを実測したので、測れる資産が現れるまで作らない |

---

## 2. モジュール構成と依存関係

[ADR-0007](../decisions/0007-framework-neutral-subsystem-ports.md) の中立原則に従い、特定の RDB ドライバやメッセージング製品の型をコアランタイムへ持ち込まない。

```
[ COBOL プログラム / cobol-job JCL ステップ (DFSRRC00) ]
                   |
                   v
+-------------------------------------------------------------------------+
| `cobol-ims` (中立コアモジュール)                                        |
|   - DbdParser / PsbParser: DBDGEN / PSBGEN の解析          <-- 前提     |
|   - ImsDatabaseCatalog: セグメント・フィールド・PROCOPT の中立メタモデル|
|   - CbltdliBridge: CALL 'CBLTDLI' / AIBTDLI ディスパッチャ              |
|   - SsaParser: SSA とコマンドコードの解析 (文法は 3.4 節)               |
|   - PcbStateManager: カレント位置・親境界 (Parentage) の管理             |
|   - DliDatabasePort: 階層 DB 操作の中立契約                             |
|   - ImsQueuePort: トランザクションメッセージキューの中立契約             |
+-------------------------------------------------------------------------+
         |                                                 |
         v                                                 v
+------------------------------------+  +---------------------------------+
| `cobol-ims-rdb` (RDB ストレージ)   |  | `cobol-ims-jms` (TM キュー)     |
|   - PostgreSQL / Db2 JDBC アダプタ |  |   - JMS 3.0                     |
|   - IMS_SEGMENT_STORE 階層走査     |  |   - BytesMessage & LLZZ 変換    |
|   - ルートアンカーロック排他制御   |  |   - inbox による冪等化          |
|   - ブロックプリフェッチキャッシュ |  |   - QC タイムアウト制御         |
+------------------------------------+  +---------------------------------+
```

### 2.1 `DbdParser` / `PsbParser` は前提コンポーネントである

当初のモジュール一覧にはこれらが無かったが、**次のすべてが DBD / PSB の定義を必要とする**。IMS 対応の最大の作業項目であり、第 1 増分に置く。

| 必要な場面 | 必要な情報 | 出典 |
| --- | --- | --- |
| SSA の項目修飾 `(FIELD = VALUE)` の評価 | フィールド名 → セグメント内オフセット・長さ・型 | DBD の `FIELD` 文 |
| `SEQ_KEY_RAW` の切り出しと兄弟順序 | シーケンスフィールドの位置と `UNIQUE` 有無 | DBD の `FIELD ... SEQ` |
| 兄弟の挿入位置 | `RULES=` | DBD |
| ルート順序の切り分け | `ACCESS=` (HDAM / HIDAM …) | DBD |
| 可変長セグメント | `BYTES=(max,min)` | DBD |
| 未定義フィールド名の検出 (`AK` 返却) | セグメント / フィールドの一覧 | DBD |
| L0 機能の検出 | `PROCSEQ=`, `LCHILD`, DEDB 指定 | DBD / PSB |
| ロック戦略の切替 | `PROCOPT` | PSB の `PCB` 文 |
| PCB マスクの構築順序 | PSB 内の PCB 並び | PSB |

DBDGEN / PSBGEN はアセンブラマクロであり、BMS マクロパーサと同程度の作業量を見込む。

### 2.2 依存関係の制約

- `cobol-ims` は `cobol-runtime` のみに依存する。Spring、JDBC、JMS の型を一切公開・参照しない。
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
    void checkpoint(String chkpId);   // コミット + 全 DB PCB の位置破棄 (3.5 節)
}
```

**この契約は未完である。**可変長セグメントの長さ（3.6 節）、`*D` パス呼出しでの複数レベル同時 `ISRT`、`GHU` / `GHN` / `GHNP` の Hold 指定が表現できていない。凍結前に決める。

### 3.2 物理テーブル設計（非正規化生バイト格納）

```sql
CREATE TABLE IMS_SEGMENT_STORE (
    DBD_NAME          VARCHAR(8)    NOT NULL,
    ROOT_KEY_RAW      BYTEA         NOT NULL,  -- ルートキー生バイト (Db2: VARBINARY(64))
    -- 走査順序の要。COLLATE "C" (バイト値順) を必ず指定する。
    -- データベース既定の照合順序では階層順と一致する保証が無い。
    -- 各段は固定幅・固定文字集合 (数字と '-')。小数点採番は禁止 (P-101)。
    HIERARCHY_PATH    VARCHAR(256)  NOT NULL COLLATE "C",
    SEG_NAME          VARCHAR(8)    NOT NULL,
    SEG_LEVEL         SMALLINT      NOT NULL,  -- 1=ROOT。上限 15
    PARENT_PATH       VARCHAR(256)  NOT NULL COLLATE "C",
    SEQ_KEY_RAW       BYTEA         NULL,      -- 兄弟順序の正本 (キー付きセグメント)
    SEG_LEN           INTEGER       NOT NULL,  -- 可変長セグメントの現在長 (3.6 節)
    SEG_DATA          BYTEA         NOT NULL,  -- EBCDIC 生バイト列
    CREATED_AT        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_IMS_SEGMENT PRIMARY KEY (DBD_NAME, ROOT_KEY_RAW, HIERARCHY_PATH)
);

CREATE TABLE IMS_ROOT_INDEX (
    DBD_NAME          VARCHAR(8)    NOT NULL,
    ROOT_KEY_RAW      BYTEA         NOT NULL,  -- 範囲検索もこの列で行う (バイト値順)
    CONSTRAINT PK_IMS_ROOT PRIMARY KEY (DBD_NAME, ROOT_KEY_RAW)
);
```

当初案にあった `ROOT_KEY_HEX` は削除した。`ROOT_KEY_RAW` の関数であり、バイト値順の照合が効くなら範囲検索は `ROOT_KEY_RAW` で足りる。同じ情報を 2 列に持つと、ずれたときにどちらが正本か決まらない。

`VARCHAR(256)` は階層深さの上限を決めている。1 段の最大幅と 15 段から上限を計算し、**超過は例外にする（切り捨てない）**。

### 3.3 走査順序

- **兄弟順序の正本は `SEQ_KEY_RAW`**（キー付きセグメント）。`HIERARCHY_PATH` はその写像として維持する。
- **ルート順序はアクセス方式で切り分ける**（1.1 節）。HDAM / PHDAM は再現せず診断を出す。
- 中間挿入でギャップが枯渇すると部分木の再採番が発生する。`HIERARCHY_PATH` は全子孫の `PARENT_PATH` に埋め込まれているためである。刻み幅と再採番コストは実測して決める ([P-101](../decisions/provisional.md))。
- **非キー項目で修飾された SSA は索引が効かず、部分木の走査になる。**プリフェッチしたブロックをメモリで絞る。頻出フィールドの生成列＋索引への昇格は将来の最適化とし、性能値は測るまで主張しない。

### 3.4 SSA の文法

当初案は `SEGNAME*C(FIELD  = VALUE)` の 1 形しか想定していなかった。実資産には次がある。`SsaParser` はこれらを受け、**対応しない形は `AJ` で誤魔化さず、対応していないと分かる診断を出す**。

- **無限定 SSA**（セグメント名 8 バイトのみ）。`GN` の最頻形。
- **レベルごとの複数 SSA**（親と子に 1 つずつ）。
- **ブール結合**: AND (`*` / `&`)、OR (`|`)、独立 AND (`#`)。
- **関係演算子 6 種**: `=`/`EQ`、`>`/`GT`、`<`/`LT`、`>=`/`GE`、`<=`/`LE`、`!=`/`NE`（記号形・英字形の両方）。
- **コマンドコード**: `D`（パス呼出し）、`F`（先頭）、`L`（末尾）、`N`（パス置換抑止）、`P`（親境界指定）、`C`（連結キー）、`-`（null）、`Q` / `O`、`U` / `V`。

`*C`（連結キー）は実資産で頻出するが当初の表から漏れていた。`*U` / `*V` は役割の説明が誤っていた可能性が高く、MVP から外す ([P-100](../decisions/provisional.md))。

### 3.5 `CHKP` の意味論

**`CHKP` はコミットだけではない。データベースの位置を破棄する。**`GN` ループの途中で `CHKP` を打つバッチは、`CHKP` の後に `GU` で位置を取り直さなければならない。位置破棄を再現しないと、`CHKP` を挟んだ `GN` ループは実機と違う結果を返す（実機では止まるはずのループが動き続ける、逆もある）。

`checkpoint()` は「コミット + 全 DB PCB の位置破棄」として実装し、**位置破棄が観測できるテストを第 1 増分の受け入れ条件に入れる**。symbolic `CHKP` の領域退避と `XRST` は、退避した域を**業務の更新と同じ確定**で置き場に残す形で入れた ([P-164](../decisions/provisional.md))。確定が失敗すれば検査点も残らないので、再始動した域とデータベースの状態が揃う。GSAM のデータセットの位置づけ直しは L0 のままである ([P-110](../decisions/provisional.md))。

### 3.6 可変長セグメント

`SEG_DATA` は**セグメント本体のみ**を保持し、2 バイトの長さフィールド (`LL`) は含めない。現在長は `SEG_LEN` 列で持つ。`REPL` による長さ変更を許し、`BYTES=(max,min)` の範囲検査を行う。`ISRT` 時に `LL` を読み取って `SEG_LEN` へ写す。

### 3.7 ステータスコード

FR-164 は状態コードとして「`GA`、`GB`、`GE`、`II` 等」を例示している。当初の表には **`GA` が無かった**。

| コード | 意味 | MVP |
| --- | --- | --- |
| `'  '` | 正常終了 | ○ |
| `GA` | **階層のより上位レベルへ移った**（無限定 `GN`） | ○ |
| `GK` | **同一レベルの別セグメントタイプへ移った**（無限定 `GN`） | ○ |
| `GE` | 該当なし。`GNP` で親の範囲を越えた | ○ |
| `GB` | データベースの終端 | ○ |
| `GP` | **親境界が確立していない状態での `GNP`** | ○ |
| `II` | `UNIQUE` キーの重複挿入 | ○ |
| `DJ` | Get Hold 無しの `DLET` / `REPL` | ○ |
| `DA` | `REPL` でキーフィールドを変更した | ○ |
| `AM` | **`PROCOPT` が許さない操作** | ○ |
| `AD` | 不正なファンクションコード | ○ |
| `AJ` | SSA の構文エラー | ○ |
| `AK` | SSA のフィールド名が DBD に無い | ○ |
| `AI` | 入出力エラー | 第 2 増分 |
| `QC` | キューが空 (TM) | ○ |

`GA` / `GK` は、**無限定 `GN` で木を舐めるプログラムがセグメント種別の切り替わりを検出するために使う**。返さないとループの制御が変わる。`GP` は親境界の設計と直結する。`AM` は `PROCOPT` を扱う以上セットで必要になる。

各コードの発生条件は公開仕様と突き合わせていない ([P-100](../decisions/provisional.md))。

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

**この契約は未完である。**次が表現できていないため、凍結前に決める（第 6 章）。

- ~~入力の 2 セグメント目以降を取る **I/O PCB への `GN`**~~ (実装した、[P-156](../decisions/provisional.md))
- ~~出力を複数 `ISRT` で組み立てて確定する **`PURG`**~~ (実装した、[P-156](../decisions/provisional.md))
- 宛先を差し替える **`CHNG`** と、代替 PCB (ALTPCB) への `ISRT` — <b>未実装</b>。断る
- 会話型トランザクションの **SPA**（4.3 節）— <b>未実装</b>。Bank-of-Z が使っていないので測れない
  ([P-166](../decisions/provisional.md))

これらが無いと、該当する資産は「動かない」ではなく**「1 セグメント目だけ送られる」のような部分的に正しく見える壊れ方**をする。
だから残りの 2 つは、黙って部分的に動かすのではなく<b>断る</b>。

### 4.2 MPP のメッセージ駆動ループ制御

1. `ImsQueuePort.poll(trx, timeout)` でキューからメッセージを待機受信。
2. 取得したバイト列を `LLZZ` プレフィックスを維持したまま COBOL の受取バッファへ転記し、`IO-PCB` のステータスを `'  '` に設定。
3. キューが空（タイムアウト）の場合は `'QC'` を設定。COBOL プログラムはループを抜け `GOBACK` で終了する。
4. `ISRT` された応答を、対応付け情報を付けて応答先へ送る。

### 4.3 会話型トランザクションと SPA

当初の設計は SPA (Scratch Pad Area) を扱っていなかった。IMS の会話型トランザクションでは、**SPA がメッセージの第 1 セグメントとしてプログラムへ渡され、`ISRT` で書き戻される**。

- CICS の COMMAREA と同じ課題（永続化、サイズ、有効期限、クラッシュ回復、`/EXIT` 相当の打ち切り）が発生する。[ADR-0008](../decisions/0008-cics-on-spring-mvc-and-session.md) の会話状態ストアと共通化できるかを検討する。
- **入出力バッファのレイアウトが変わる。**非会話型のサンプル（`LL` / `ZZ` / トランザクションコード / データ）は会話型には当てはまらない。

「IMS TM は CICS より対話状態の管理が少なく容易」という当初の結論は、SPA を数え落として出たものである。

**実装状況 (2026-09-16)**: <b>作っていない</b>。作る前に Bank-of-Z を測ったところ、会話型取引が 1 つも無かった
([P-166](../decisions/provisional.md))。IMS の資源定義の `CREATE TRAN` 7 本はすべて `CONV(Y)` も `SPASIZE` も持たず、
COBOL 側で `SPA` に当たるのは図形定数 `SPACES` だけである。**測る基準が無いまま作らない**という増分の型に従い、
上の仕様は作るときの下敷きとして残したうえで、実装は測れる資産が現れるまで保留する。
「SPA を数え落としていた」という指摘そのものは正しい。ここで下がったのは優先度であって、必要性ではない。

### 4.4 UOW の原子性

実機では電文のデキュー・DB 更新・応答送信が原子的に確定する。本設計は **at-least-once + 冪等化 (inbox 方式)** でこれを代替する。処理済み電文のキーを IMS DB の更新と同一 JDBC トランザクションへ書き、JDBC コミット成功後に JMS を ACK する。

**残る差は「業務ロジックが 2 度呼ばれうる」ことである。**冪等でない業務（採番、加算、外部送信）は影響を受ける。これは透過性が成り立たない領域であり、リリースノートに明記する ([P-104](../decisions/provisional.md))。

### 4.5 電文の順序

IMS はトランザクションコード単位で FIFO 処理する。第 1 増分は**キューあたり単一コンシューマ**を前提とする。実機の並列スケジューリングは再現しない ([P-105](../decisions/provisional.md))。<b>当初ここに書いていた `prefetch=1` は実装されておらず、設定しても消費者どうしの配分は変わらないことを 2026-09-16 に測った</b>。順序を守る梃子は消費者の数だけである。

そこで、**取引コードを置き場の借用の行で 1 つの領域に限り、2 つ目は起こさずに断る** ([P-167](../decisions/provisional.md))。心拍は同期点の確定に相乗りさせ、引き継がれた領域は次の同期点で競合として止まる。借用が働くのは RDB の置き場と JMS のキューがそろったときだけで、データセットの置き場や 1 つの JVM の中のキューでは働かない。

---

## 5. ロック並行性制御と障害耐性

1. **ルートアンカーロック**: `ISRT` / `REPL` / `DLET` の前に `SELECT ... FROM IMS_ROOT_INDEX ... FOR UPDATE` で直列化する。新規ルートはロック対象の行が無い（ファントム）ため、先行確保の手順を規定する。**ルートロックの獲得順序は `(DBD_NAME, ROOT_KEY_RAW)` 昇順**とし、ルート間・DBD 間の交差も防ぐ。
2. **MVCC 活用**: `GU` / `GN` / `GNP` はスナップショット参照とし行ロックを獲得しない。`GHU` / `GHN` / `GHNP` のみ排他ロックを獲る。
3. **DML は即時発行する。**当初案の「コミット前まで DML をバッファリングして昇順ソート発行」は**撤回した**。DL/I では `ISRT` 直後の `GN` がそのセグメントを見るため read-your-own-writes が壊れ、`II` の返却時点もずれる。ルートアンカーロックがあれば同一ルート配下のロック順序交差は起きないので、この対策は不要であった ([ADR-0015](../decisions/0015-ims-db-locking-and-deadlock-avoidance.md))。
4. **`PROCOPT` は `G` と `GO` を分ける。**ロック抑止は `GO` のみ。`G` は整合性つきの読み取りである ([P-106](../decisions/provisional.md))。
5. **デッドロック時の再試行**: 設定値（既定 3）で再試行する。ABEND コードと回数は実機の規定と突き合わせていない ([P-107](../decisions/provisional.md))。

**「デッドロックの撲滅」は同一ルート配下に限られる。**ルート間は獲得順序の規約に依存する。

---

## 6. 契約を凍結する前に決めること

`DliDatabasePort` と `ImsQueuePort` は FR-164 の範囲だが、現状の署名では次が表現できない。**公開 API として凍結する前に決める。**

| 未決 | 影響する契約 |
| --- | --- |
| 可変長セグメントの長さの受け渡し | `insert` / `replace` |
| `*D` パス呼出しでの複数レベル同時 `ISRT` | `insert` |
| Hold の有無 (`GHU` / `GHN` / `GHNP`) | `getUnique` / `getNext` / `getNextInParent` |
| I/O PCB への `GN`（複数セグメント入力） | `ImsQueuePort` |
| `PURG` / `CHNG` / 代替 PCB | `ImsQueuePort` |
| SPA の受け渡し | `ImsQueuePort` |
| 状態コードと診断の分離（`AJ` と「未対応」を区別する） | `DliResult` |

---

## 7. バッチ起動経路と運用ユーティリティ (`cobol-job` 連携)

### 7.1 `DFSRRC00`（最優先）

IMS バッチ / BMP は移行の需要が最も高い形態だが、当初の設計にはその**JCL 側の入口が無かった**。第 1 増分はここである。

```jcl
//RUNBATCH EXEC PGM=DFSRRC00,PARM='DLI,CUSTPGM,CUSTPSB'
//STEPLIB  DD DSN=IMS.SDFSRESL,DISP=SHR
//IMS      DD DSN=IMS.PSBLIB,DISP=SHR        <- PSB / DBD ライブラリ
//DFSVSAMP DD *                              <- バッファプール定義
//SYSPRINT DD SYSOUT=*
```

- `DFSRRC00` を `cobol-job` のユーティリティレジストリへ登録する。**実装では** `cobol-runtime` の `SystemProgramProvider` を
  `ServiceLoader` で引く口にし、`cobol-ims` が差し込む。`cobol-job` と `cobol-ims` がどちらも `cobol-runtime` にだけ依存したままになる (P-155)。
- `PARM` を解析する（`DLI` = バッチ、`BMP` = バッチメッセージ処理、`ULU` = ユーティリティ）。
- PSB をロードし、PCB リストを構築して `PROCEDURE DIVISION USING` へバインドする。
- `//IMS` DD から DBD / PSB を読む。`//DFSVSAMP` は受理して無視する（RDB バックエンドではバッファプール指定に意味が無い）。

### 7.2 運用ユーティリティの優先順位

当初案は `DFSURGU0`（アンロード）と `DFSURGL0`（リロード）を実装対象に選んでいたが、これらは **HD 再編成**のためのユーティリティであり、**RDB バックエンドでは再編成という作業自体が消える**。移行後もっとも不要になるステップを最初に作ることになっていた。

一方、実運用の JCL にほぼ必ずある次が対象外だった。

- **DBRC (RECON データセット)**: IMS バッチ JCL の標準構成要素。対応 / 非対応を明示する。
- **イメージコピー / リカバリ / バッチバックアウト**: 障害時運用の中心。

**まず実資産の JCL に現れるユーティリティを頻度順に洗い出す。**再編成系は認識して no-op 化する（「RDB バックエンドでは不要」と `SYSPRINT` に出して RC=0）方が、忠実に模倣するより価値が高い可能性がある。ユーティリティ名と役割は出典を確認してから確定する ([P-109](../decisions/provisional.md))。

### 7.3 `verify`（整合性検査）が検査すべきもの

当初案の検査項目は「孤児セグメント」と「シーケンスキーの順序」だったが、孤児は書き込みが必ずエンジン経由なら通常発生しない。**この設計で実際に壊れるのは次である。**

1. `HIERARCHY_PATH` の各段が固定幅・許可文字であること（小数点採番の混入検出）
2. 兄弟の `HIERARCHY_PATH` 順序が `SEQ_KEY_RAW` 順序と一致すること
3. 残ギャップの最小値（枯渇の予兆）
4. パス長の最大値と階層深さ
5. データベースの照合順序が期待どおり（バイト値順）であること

CLAUDE.md §6 のとおり、**道具にも検査を仕込む。**

---

## 8. 増分の順序

1. **第 1 増分**: `DbdParser` / `PsbParser` / `ImsDatabaseCatalog`、`DFSRRC00` 起動経路、L0 機能の検出と診断。
2. **第 2 増分**: `SsaParser`（3.4 節の文法）、`PcbStateManager`、インメモリの `DliDatabasePort` 実装、状態コード（3.7 節）、`CHKP` の位置破棄。
3. **第 3 増分**: `cobol-ims-rdb`。ルートアンカーロック、兄弟順序、プリフェッチ、`verify`。
4. **第 4 増分**: `cobol-ims-jms`。inbox による冪等化、SPA、複数セグメント電文。
5. **第 5 増分**: MFS。[AR-12](../reviews/2026-09-09-interop-adversarial-review.md) の BMS spike 合格が前提 ([P-108](../decisions/provisional.md))。

各増分の終わりに、**振る舞いの差が出るテスト**を書く（CLAUDE.md 増分の型 4）。例: `CHKP` を挟んだ `GN` ループ、`ISRT` 直後の `GN`、無限定 `GN` での `GA` / `GK` の返却。
