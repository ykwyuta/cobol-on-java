# 報告: 階層型データベース (IMS DB) の RDB ストレージエンジン化検討

| 項目 | 内容 |
| :--- | :--- |
| **作成日** | 2026-09-11 |
| **対象サブシステム** | IMS DB (DL/I) / 永続化ストレージエンジン |
| **対象 RDB** | PostgreSQL / IBM Db2 |
| **設計方針** | **非正規化ストレージエンジン方式（階層木・生バイトバイナリ格納モデル）** |
| **関連文書** | [docs/ims-overview-and-support-scope.md](ims-overview-and-support-scope.md), [設計文書 77 (Db2/CICS)](design/77-spring-cics-db2.md), [設計文書 80 (ファイル入出力/生バイト方針)](design/80-file-io.md) |

---

## 1. 検討の背景と目的

メインフレーム上の COBOL 資産移行において、IMS DB（DL/I）をオープン環境へ移行する際、最大のボトルネックとなるのが**「階層構造から RDB へのデータモデル変換（正規化）」**です。

### 1.1 なぜ「業務的な正規化」を行わないのか？
一般的なマイグレーション手法として「IMS の各セグメントを RDB の正規化されたテーブルに分解し、外部キーで結合する」アプローチがありますが、本プロジェクト（`cobol-on-java`）では以下の致命的な問題を引き起こします。

1. **COBOL データ型の非互換性**:
   - COBOL セグメントには `COMP-3`（パック 10 進数）、バイナリ、`OCCURS DEPENDING ON`（可変長配列）、同一セグメントに対する複数 `REDEFINES`（型オーバーレイ）が多用されます。これらを SQL の `VARCHAR` や `NUMERIC` 列に分解すると、エンコーディング往復変換破損や桁あふれが発生し、L3 互換（バイト単位・照合順序単位の完全互換）が原理的に崩壊します（[設計文書 80](design/80-file-io.md) の「生バイトで持つ」原則と同じ理由）。
2. **階層走査（Hierarchical Sequence）意味論の不整合**:
   - DL/I の `GN` (Get Next) や `GNP` (Get Next in Parent) は、階層ツリーを「深さ優先・先行順 (Pre-order Traversal)」で走査します。正規化された多数のテーブルに対して SQL `JOIN` や `UNION` でこの順序を毎度エミュレートするのは性能的・論理的に破綻します。
3. **未定義フィールドの存在**:
   - IMS の DBD（データベース記述）には、キー項目（Sequence Field）以外のデータ領域が単なる `BYTES=500` のような連続バイト塊としてしか定義されていないことが多く、そもそも業務的な列定義が存在しないケースが多数を占めます。

したがって、**「RDB を業務データ用テーブルとしてではなく、高信頼・トランザクション対応の階層ストレージエンジン（B-Tree + BLOB ストア）として利用する」** アプローチを採用します。

---

## 2. コアアーキテクチャ：非正規化汎用ストレージモデル

RDB（PostgreSQL / Db2）側には業務テーブルを作成せず、IMS DB の階層ツリーをそのまま格納・インデックス付けできる**「メタデータ＋階層パス＋生バイトBLOB」**の汎用スキーマを用意します。

### 2.1 物理テーブル設計

2つの基本テーブルで構成します：
1. **セグメントテーブル (`IMS_SEGMENT_STORE`)**: 各セグメントの生データと階層位置を保持。
2. **ルート検索インデックス (`IMS_ROOT_INDEX`)**: 高速なキー検索（`GU`）用。

#### テーブル 1: `IMS_SEGMENT_STORE`
```sql
CREATE TABLE IMS_SEGMENT_STORE (
    DBD_NAME          VARCHAR(8)    NOT NULL,  -- DBD名 (例: 'CUSTDBD')
    ROOT_KEY_RAW      VARBINARY(64) NOT NULL,  -- ルートセグメントのキー値 (EBCDIC生バイト)
    HIERARCHY_PATH    VARCHAR(256)  NOT NULL,  -- 階層走査パス (例: '/001/002/005')
    SEG_NAME          VARCHAR(8)    NOT NULL,  -- セグメント名 (例: 'ORDER')
    SEG_LEVEL         SMALLINT      NOT NULL,  -- 階層レベル (1=ROOT, 2=CHILD...)
    PARENT_PATH       VARCHAR(256)  NOT NULL,  -- 親セグメントの HIERARCHY_PATH
    SEQ_KEY_RAW       VARBINARY(64) NULL,      -- セグメント自体のSequence Field (生バイト)
    SEG_DATA          BLOB          NOT NULL,  -- セグメント生バイト列 (EBCDIC/バイナリそのまま)
    CREATED_AT        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_IMS_SEGMENT PRIMARY KEY (DBD_NAME, ROOT_KEY_RAW, HIERARCHY_PATH)
);

-- 親配下の走査 (GNP) およびセグメント名指定走査 (GN) 用複合インデックス
CREATE INDEX IDX_IMS_PARENT ON IMS_SEGMENT_STORE (DBD_NAME, ROOT_KEY_RAW, PARENT_PATH, HIERARCHY_PATH);
CREATE INDEX IDX_IMS_SEGNAME ON IMS_SEGMENT_STORE (DBD_NAME, ROOT_KEY_RAW, SEG_NAME, HIERARCHY_PATH);
```

#### テーブル 2: `IMS_ROOT_INDEX`
```sql
CREATE TABLE IMS_ROOT_INDEX (
    DBD_NAME          VARCHAR(8)    NOT NULL,
    ROOT_KEY_RAW      VARBINARY(64) NOT NULL,  -- ルートキー生バイト
    ROOT_KEY_HEX      VARCHAR(128)  NOT NULL,  -- 範囲検索・照合用の文字列表現
    CREATED_AT        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_IMS_ROOT PRIMARY KEY (DBD_NAME, ROOT_KEY_RAW)
);
```
*(※ PostgreSQL の場合は `VARBINARY` を `BYTEA`、`BLOB` を `BYTEA`、Db2 の場合は `VARBINARY` / `BLOB` 型を使用)*

---

## 3. DL/I 操作と SQL のマッピング・実行時振る舞い

COBOL から `CALL 'CBLTDLI'` が呼び出された際、ランタイム（`cobol-ims` エンジン）は次のようにカレントポインタを管理し、SQL を発行します。

```
+-------------------------------------------------------------------------+
| COBOL: CALL 'CBLTDLI' USING DLI-GU, DB-PCB, RECORD-AREA, SSA-ARG        |
+-------------------------------------------------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------------+
| `cobol-ims` ランタイムエンジン                                          |
|                                                                         |
|   1. SSA (Segment Search Argument) の解析                               |
|      - 対象セグメント名: 'ORDER'                                         |
|      - 探索条件: 'ORDERNO = 99887766'                                   |
|   2. カーソル・カレント位置（PCB State: Current Position）の確認        |
|   3. ストレージエンジン・クエリプランの選択                             |
+-------------------------------------------------------------------------+
                                    |
            +-----------------------+-----------------------+
            | (GU: ルート特定)                              | (GNP: 親配下走査)
            v                                               v
[ SELECT ... WHERE ROOT_KEY = ? ]               [ SELECT ... WHERE PARENT_PATH = ?
                                                  AND HIERARCHY_PATH > ?
                                                  ORDER BY HIERARCHY_PATH LIMIT 1 ]
```

### 3.1 `GU` (Get Unique - ランダム読込)
- **SSA がルートセグメントの場合**:
  ```sql
  SELECT SEG_DATA, HIERARCHY_PATH FROM IMS_SEGMENT_STORE
   WHERE DBD_NAME = ? AND ROOT_KEY_RAW = ? AND SEG_LEVEL = 1;
  ```
  - 発見した場合: `SEG_DATA` の生バイトを COBOL の受取領域へ転記、PCB のカレント位置を当該 `HIERARCHY_PATH` に更新、`STATUS-CODE = '  '` を返却。
  - 未発見の場合: `STATUS-CODE = 'GE'` (Not Found) を返却。
- **SSA に子セグメント条件が含まれる場合**:
  - ルートキーで絞り込んだ後、`SEG_NAME = ?` と `SEQ_KEY_RAW = ?`（または `SEG_DATA` 内の該当オフセット値）で検索。

### 3.2 `GN` (Get Next - 順次読込) / `GNP` (Get Next in Parent)
IMS の最大の特長である「階層順序（先行順トラバース）」は、**`HIERARCHY_PATH` の文字列比較順序** によって SQL 上で極めて自然に再現されます。

- **`HIERARCHY_PATH` の採番ルール**:
  - ルート: `/000001`
  - 第1子セグメント（タイプA）: `/000001/01-000001`
  - 第1子セグメント（タイプB）: `/000001/02-000001`
  - 孫セグメント: `/000001/01-000001/01-000001`
- **`GN`（カレント位置からの次セグメント読込）**:
  ```sql
  SELECT SEG_DATA, HIERARCHY_PATH, SEG_NAME FROM IMS_SEGMENT_STORE
   WHERE DBD_NAME = ? AND ROOT_KEY_RAW = ? AND HIERARCHY_PATH > ?
   ORDER BY HIERARCHY_PATH ASC
   FETCH FIRST 1 ROWS ONLY; -- (PostgreSQL では LIMIT 1)
  ```
  カレントルート内で終端に達した場合は、`IMS_ROOT_INDEX` から「次の `ROOT_KEY`」を取得して次の木構造へ進む。
- **`GNP`（親セグメントの配下限定の次セグメント読込）**:
  ```sql
  SELECT SEG_DATA, HIERARCHY_PATH, SEG_NAME FROM IMS_SEGMENT_STORE
   WHERE DBD_NAME = ? AND ROOT_KEY_RAW = ? 
     AND PARENT_PATH = ?
     AND HIERARCHY_PATH > ?
   ORDER BY HIERARCHY_PATH ASC
   FETCH FIRST 1 ROWS ONLY;
  ```
  親のスコープを抜けると直ちに `STATUS-CODE = 'GE'` となり、親境界（Parentage）が完全に維持されます。

### 3.3 `ISRT` (Insert), `REPL` (Replace), `DLET` (Delete)
- **`ISRT`**:
  - 挿入対象の親セグメントの `HIERARCHY_PATH` を基に、新しい枝番号を採番。
  - `INSERT INTO IMS_SEGMENT_STORE ...` を実行。重複キーなら `STATUS-CODE = 'II'`。
- **`REPL`**:
  - 直前に `GHU` / `GHN` (Get Hold) で保持したカレントの `HIERARCHY_PATH` の行を `UPDATE`。
- **`DLET`**:
  - 対象セグメントおよびその配下の子孫すべて（`WHERE HIERARCHY_PATH LIKE '/parent/path/%'`）をカスケード削除。

---

## 4. PostgreSQL と Db2 の方言差と対応

本方式は PostgreSQL と Db2 の双方でほぼ同一のロジックで動作可能ですが、ストレージエンジン層（JDBC アダプタ）で以下の微小な方言差を吸収します。

| 項目 | PostgreSQL | IBM Db2 |
| :--- | :--- | :--- |
| **バイナリ型** | `BYTEA` | `VARBINARY(64)` / `BLOB` |
| **生バイトの照合順序** | EBCDIC 生バイトは `C` 照合順序（バイト値比較）で完全一致 | `COLLATE UCA500R1_NO` または BINARY 比較で完全一致 |
| **行数制限構文** | `LIMIT 1` / `OFFSET n` | `FETCH FIRST 1 ROWS ONLY` (SQL:2008 標準) |
| **トランザクション制御** | `connection.commit()` / `rollback()` | 同左。Db2 固有の `WITH HOLD` カーソルも利用可能（[ADR-0012](../decisions/0012-db2-driver-managed-uow-for-required-hold-cursors.md)） |
| **大容量セグメント最適化** | TOAST 機構（自動インライン/圧縮） | Inline LOB (`INLINE LENGTH`) 指定で高速アクセス |

---

## 5. 本方式の利点とトレードオフ

### 5.1 圧倒的な利点
1. **COBOL 資産の無修正 100% 透過実行**:
   - `CALL 'CBLTDLI'` の呼び出し元プログラムからは、背後が本物の IMS DB なのか PostgreSQL/Db2 なのか全く意識する必要がありません。
2. **データ破壊リスクの完全排除**:
   - セグメントデータがバイト単位で無変換（BLOB）格納されるため、パック10進数（`COMP-3`）の不正なサインビットや、未定義のバイナリパディング、`REDEFINES` の型ズレでデータが壊れる事故が起こりません。
3. **スキーマ変更が不要**:
   - IMS 側に新しいセグメントやフィールドが追加されても、RDB 側の DDL 変更（テーブル追加・マイグレーション）が一切発生しません。

### 5.2 トレードオフと対策
- **外部システムから SQL で直接クエリしにくい**:
  - セグメントが BLOB（EBCDIC 生バイト）で入っているため、DBeaver や BI ツールから直接 SQL で「顧客の売上金額」を集計することはできません。
  - **対策**: これが必要な場合は、バッチまたは CDC（Change Data Capture）等で検索専用の参照用 RDB テーブルへ非同期展開（リードモデルの分離 / CQRS パターン）を行います。COBOL のオンライントランザクションやバッチ処理はあくまで `IMS_SEGMENT_STORE` を正本として扱います。
- **深さ優先探索時のクエリオーバーヘッド**:
  - `GN` で数万件を連続読込するバッチ処理の場合、1セグメントごとに `SELECT ... LIMIT 1` を発行するとレイテンシが大きくなります。
  - **対策**: `cobol-ims` ランタイム側で「ブロックプリフェッチ（`FETCH FIRST 100 ROWS ONLY` で先読みキャッシュし、メモリ上でカーソルを進める）」機能を設けることで、メインフレームと同等以上のスループットを達成します。

---

## 6. 実装に向けた段階的ロードマップ

1. **第 1 増分: 中立インターフェースとインメモリ検証 (`cobol-ims`)**
   - `CbltdliBridge` と `DliDatabasePort`（`getUnique`, `getNext`, `insert` 等）を定義。
   - メモリ上の `TreeMap` で階層パス走査とステータスコード（`'  '`, `'GE'`, `'II'`）を再現し、単体テストをパスさせる。
2. **第 2 増分: RDB ストレージアダプタ (`cobol-ims-rdb`)**
   - 本書で定義した `IMS_SEGMENT_STORE` を用いた JDBC 実装を作成。
   - PostgreSQL（Testcontainers）および Db2 Community（[2026-09-11 検証環境](report/20260911-db2-community-validation.md)）で結合試験。
3. **第 3 増分: プリフェッチキャッシュと `CHKP`（チェックポイント/コミット連携）**
   - 大量データ走査用のブロックフェッチ。
   - `CALL 'CBLTDLI' USING CHKP...` と JDBC トランザクションコミットの同期。
