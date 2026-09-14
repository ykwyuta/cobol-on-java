# 報告: 階層型データベース (IMS DB) の RDB ストレージエンジン化検討

| 項目 | 内容 |
| :--- | :--- |
| **作成日** | 2026-09-11 (2026-09-13 改訂) |
| **対象サブシステム** | IMS DB (DL/I) / 永続化ストレージエンジン |
| **対象 RDB** | PostgreSQL / IBM Db2 |
| **設計方針** | 非正規化ストレージエンジン方式（階層木・生バイト格納モデル） |
| **対応要件** | **無し。**IMS の要件は FR-164 のみで、本書の範囲を要求していない ([P-098](../decisions/provisional.md)) |
| **検証レベル** | **V1 / V0** |
| **関連文書** | [ims-overview-and-support-scope.md](ims-overview-and-support-scope.md), [設計 80 (ファイル入出力)](../design/80-file-io.md), [ADR-0013](../decisions/0013-ims-db-denormalized-raw-storage-engine.md) |
| **改訂理由** | [2026-09-13 批判的レビュー](../reviews/2026-09-13-ims-research-critical-review.md) の IR-02, IR-05, IR-12〜IR-16 |

> ### この資料の前提
>
> **1. 本書を要求する要件は存在しない。**IMS の要件は FR-164 の 1 本だけで、「初期リリースでは
> インタフェース定義のみとする」と定めている。本書は要件が書かれるまで提案である。
>
> **2. DL/I には oracle が無い。**改訂前の本書は「COBOL 資産の無修正 100% 透過実行」
> 「データ破壊リスクの完全排除」「メインフレームと同等以上のスループット」と書いていたが、
> **いずれも裏付けが無かった**。性能値は測るまで主張しない ([P-099](../decisions/provisional.md))。

---

## 1. 検討の背景と目的

### 1.1 なぜ「業務的な正規化」を行わないのか？

一般的なマイグレーション手法として「IMS の各セグメントを RDB の正規化されたテーブルに分解し、外部キーで結合する」アプローチがあるが、本プロジェクトでは以下の問題を引き起こす。

1. **COBOL データ型の非互換性**:
   - COBOL セグメントには `COMP-3`、バイナリ、`OCCURS DEPENDING ON`、複数 `REDEFINES` が多用される。これらを SQL の `VARCHAR` や `NUMERIC` 列に分解すると、変換の往復で情報が欠落しうる。L3（バイト忠実）を目標として掲げること自体ができなくなる（[設計 80](../design/80-file-io.md) の「生バイトで持つ」原則と同じ理由）。
2. **階層走査（Hierarchical Sequence）意味論の不整合**:
   - `GN` / `GNP` は階層ツリーを深さ優先・先行順で走査する。正規化された多数のテーブルに対して `JOIN` や `UNION` でこの順序を毎度エミュレートするのは性能的・論理的に維持困難である。
3. **未定義フィールドの存在**:
   - DBD にはキー項目以外のデータ領域が単なる `BYTES=500` のような連続バイト塊としてしか定義されていないことが多く、そもそも業務的な列定義が存在しないケースが多数を占める。

したがって、**「RDB を業務データ用テーブルとしてではなく、トランザクション対応の階層ストレージエンジンとして利用する」**アプローチを採る。

### 1.2 この方式が必要とする前提

本方式は DBD / PSB のメタデータ無しには成立しない。シーケンスフィールドの位置（兄弟順序）、`FIELD` のオフセット（SSA の項目修飾）、`ACCESS=`（ルート順序）、`RULES=`（挿入位置）、`PROCOPT`（ロック）がすべて必要である。**`DbdParser` / `PsbParser` は本方式の前提コンポーネントである**（[設計 78](../design/78-ims-subsystem.md) §2.1）。

---

## 2. コアアーキテクチャ：非正規化汎用ストレージモデル

RDB 側には業務テーブルを作成せず、階層ツリーをそのまま格納・インデックス付けできる「メタデータ＋階層パス＋生バイト」の汎用スキーマを用意する。

### 2.1 物理テーブル設計

```sql
CREATE TABLE IMS_SEGMENT_STORE (
    DBD_NAME          VARCHAR(8)    NOT NULL,
    ROOT_KEY_RAW      BYTEA         NOT NULL,  -- Db2: VARBINARY(64)
    -- 走査順序の要。COLLATE "C" (バイト値順) を必ず指定する。
    -- 各段は固定幅・固定文字集合 (数字と '-')。小数点採番は禁止 (5.2 節)。
    HIERARCHY_PATH    VARCHAR(256)  NOT NULL COLLATE "C",
    SEG_NAME          VARCHAR(8)    NOT NULL,
    SEG_LEVEL         SMALLINT      NOT NULL,  -- 1=ROOT。上限 15
    PARENT_PATH       VARCHAR(256)  NOT NULL COLLATE "C",
    SEQ_KEY_RAW       BYTEA         NULL,      -- 兄弟順序の正本
    SEG_LEN           INTEGER       NOT NULL,  -- 可変長セグメントの現在長
    SEG_DATA          BYTEA         NOT NULL,  -- EBCDIC 生バイト列 (LL を含まない)
    CREATED_AT        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_IMS_SEGMENT PRIMARY KEY (DBD_NAME, ROOT_KEY_RAW, HIERARCHY_PATH)
);

CREATE INDEX IDX_IMS_PARENT  ON IMS_SEGMENT_STORE (DBD_NAME, ROOT_KEY_RAW, PARENT_PATH, HIERARCHY_PATH);
CREATE INDEX IDX_IMS_SEGNAME ON IMS_SEGMENT_STORE (DBD_NAME, ROOT_KEY_RAW, SEG_NAME, HIERARCHY_PATH);

CREATE TABLE IMS_ROOT_INDEX (
    DBD_NAME          VARCHAR(8)    NOT NULL,
    ROOT_KEY_RAW      BYTEA         NOT NULL,  -- 範囲検索もこの列 (バイト値順)
    CONSTRAINT PK_IMS_ROOT PRIMARY KEY (DBD_NAME, ROOT_KEY_RAW)
);
```

改訂点：

- **`COLLATE "C"` を明記した。**`ORDER BY HIERARCHY_PATH` が階層順と一致するのはバイト値順の照合のときだけである。改訂前は `ROOT_KEY_HEX` の照合にだけ触れ、走査順序の要である `HIERARCHY_PATH` には触れていなかった。
- **`ROOT_KEY_HEX` を削除した。**`ROOT_KEY_RAW` の関数であり、バイト値順の照合が効くなら範囲検索はこの列で足りる。同じ情報を 2 列に持つと、ずれたときにどちらが正本か決まらない。
- **`SEG_LEN` を追加した。**可変長セグメントの現在長を持つ。`SEG_DATA` は本体のみで `LL` を含まない。
- `VARCHAR(256)` は階層深さの上限を決めている。1 段の最大幅と 15 段から上限を計算し、**超過は例外にする（切り捨てない）**。

---

## 3. DL/I 操作と SQL のマッピング

### 3.1 `GU` (Get Unique)

- **SSA がルートセグメントの場合**:
  ```sql
  SELECT SEG_DATA, SEG_LEN, HIERARCHY_PATH FROM IMS_SEGMENT_STORE
   WHERE DBD_NAME = ? AND ROOT_KEY_RAW = ? AND SEG_LEVEL = 1;
  ```
  発見した場合は生バイトを受取領域へ転記し、PCB のカレント位置を更新して `'  '` を返す。未発見なら `GE`。
- **SSA に子セグメント条件が含まれる場合**: ルートキーで絞り込んだ後、`SEG_NAME` と `SEQ_KEY_RAW`（シーケンスフィールドの場合）で検索する。

### 3.2 `GN` / `GNP`

階層順序（先行順）は `HIERARCHY_PATH` のバイト値順によって再現する。

- **`HIERARCHY_PATH` の採番ルール**（各段は固定幅）:
  - ルート: `/000001`
  - 第1子（タイプA）: `/000001/01-000100`
  - 第1子（タイプB）: `/000001/02-000100`
  - 孫: `/000001/01-000100/01-000100`
- **`GN`**:
  ```sql
  SELECT SEG_DATA, SEG_LEN, HIERARCHY_PATH, SEG_NAME FROM IMS_SEGMENT_STORE
   WHERE DBD_NAME = ? AND ROOT_KEY_RAW = ? AND HIERARCHY_PATH > ?
   ORDER BY HIERARCHY_PATH ASC
   FETCH FIRST 1 ROWS ONLY;
  ```
  カレントルート内で終端に達したら次のルートへ進む。**ただし「次のルート」の決め方はアクセス方式に依存する（3.4 節）。**
- **`GNP`**:
  ```sql
  SELECT SEG_DATA, SEG_LEN, HIERARCHY_PATH, SEG_NAME FROM IMS_SEGMENT_STORE
   WHERE DBD_NAME = ? AND ROOT_KEY_RAW = ?
     AND PARENT_PATH = ? AND HIERARCHY_PATH > ?
   ORDER BY HIERARCHY_PATH ASC
   FETCH FIRST 1 ROWS ONLY;
  ```

無限定 `GN` では、移動先のレベルとセグメントタイプに応じて **`GA` / `GK`** を返す必要がある（[ims-dli-complex-semantics-report.md](ims-dli-complex-semantics-report.md) §9）。

### 3.3 兄弟順序の正本は `SEQ_KEY_RAW` である

キー付きセグメントの兄弟は**シーケンスフィールド昇順**でなければならない。`HIERARCHY_PATH` はその写像として維持するのであって、順序の正本ではない。改訂前はこの対応付けが書かれていなかった（5.2 節、[P-101](../decisions/provisional.md)）。

### 3.4 ルート順序はアクセス方式で変わる

改訂前の本書は `IMS_ROOT_INDEX` から「次の `ROOT_KEY`」を取るとしていた。これは**ルートがキー昇順に並ぶ前提**であり、HIDAM / PHIDAM / HISAM の話である。

**HDAM / PHDAM ではルートの物理順序をランダマイジングモジュールが決めるため、キー順にならない。**無限定 `GN` でデータベース全体を走査するバッチは、実機と異なる順序でセグメントを受け取る。順序に依存した集計・ブレーク処理・出力ファイルの並びは、そのまま結果が変わる。

DBD の `ACCESS=` を読み、HDAM / PHDAM では**順序が実機と一致しないことを診断として出す**。近い順序を黙って返さない ([P-102](../decisions/provisional.md))。

### 3.5 非キー項目で修飾された SSA

SSA はシーケンスフィールド以外の任意の `FIELD` でも修飾できる。その場合セグメント内のオフセットを取り出して比較することになり、索引が効かず部分木の走査になる。プリフェッチしたブロックをメモリ側で絞る。頻出フィールドの生成列＋索引への昇格は将来の最適化とし、**性能値は測るまで主張しない**。

### 3.6 `ISRT` / `REPL` / `DLET`

- **`ISRT`**: 親の `HIERARCHY_PATH` を基に新しい枝番号を採番して挿入する。重複キーなら `II` を**その場で**返す（遅延しない。[ADR-0015](../decisions/0015-ims-db-locking-and-deadlock-avoidance.md) の対策 3 撤回を参照）。
- **`REPL`**: 直前の Get Hold で保持したカレントの行を `UPDATE` する。可変長セグメントでは `SEG_LEN` も更新する。シーケンスフィールドの変更は `DA`。
- **`DLET`**: 対象セグメントとその配下の子孫すべてをカスケード削除する。

---

## 4. PostgreSQL と Db2 の方言差

| 項目 | PostgreSQL | IBM Db2 |
| :--- | :--- | :--- |
| **バイナリ型** | `BYTEA` | `VARBINARY(64)` / `BLOB` |
| **パスの照合順序** | `COLLATE "C"` を列に明記 | バイナリ照合を明示 |
| **行数制限構文** | `LIMIT 1` | `FETCH FIRST 1 ROWS ONLY` |
| **トランザクション制御** | `connection.commit()` / `rollback()` | 同左（[ADR-0012](../decisions/0012-db2-driver-managed-uow-for-required-hold-cursors.md)） |
| **大容量セグメント** | TOAST 機構 | Inline LOB (`INLINE LENGTH`) |

---

## 5. 本方式の利点とトレードオフ

### 5.1 利点

1. **COBOL 側のソース修正を必要としない**: 呼び出し元は背後が RDB であることを意識しない。
2. **型変換に起因するデータ破損の経路が無い**: セグメントがバイト単位で無変換に格納されるため、パック 10 進のサインビットや `REDEFINES` の型ズレを SQL 列型が壊すことがない。
   - ただし「データ破壊リスクの完全排除」ではない。**この方式に固有の破損経路は 5.2 に挙げたものである。**
3. **スキーマ変更が不要**: DBD にセグメントやフィールドが追加されても、RDB 側の DDL 変更が発生しない。
   - ただし `PROCOPT=E` のために Table-per-DBD を採る場合は、DBD 追加のたびに DDL が要る（[ADR-0015](../decisions/0015-ims-db-locking-and-deadlock-avoidance.md)）。

### 5.2 トレードオフと既知の弱点

- **外部システムから SQL で直接クエリしにくい**: セグメントが生バイトで入っているため、BI ツールから直接集計できない。
  - **対策**: バッチまたは CDC で検索専用の参照用テーブルへ非同期展開する（CQRS）。COBOL 側は `IMS_SEGMENT_STORE` を正本として扱う。
- **ギャップ枯渇と再採番**: 中間挿入でギャップが尽きると、`HIERARCHY_PATH` の再採番が発生する。この列は主キーかつ全子孫の `PARENT_PATH` に埋め込まれているため、**1 ノードの再採番は部分木全体の書き換え**になる。改訂前の「他の全レコードのキーを更新することなく高速に挿入できます」は、ギャップが残っている間だけ成り立つ記述であった。刻み幅とコストは実測して決める ([P-101](../decisions/provisional.md))。
- **小数点採番は禁止**: 改訂前は枯渇の回避策として小数点採番を挙げていたが、**これは先行順走査を壊す**。実測:

  ```
  $ LC_ALL=C sort            # = バイト順 = B-Tree 順
  /000001/01-000100
  /000001/01-000100.5           <- '.' (0x2E) < '/' (0x2F) のため
  /000001/01-000100/01-000100   <- 自分の子が兄弟の後ろに落ちる
  /000001/01-000101
  ```

  `GN` が孫より先に兄弟を返す。
- **照合順序への依存**: `COLLATE "C"` を外すと走査順序が崩れる。`verify` で照合順序そのものを検査する。
- **階層深さの上限**: `VARCHAR(256)` が上限を決めている。超過は例外にする。
- **非キー修飾の全走査** (3.5 節)。
- **深さ優先探索時のクエリオーバーヘッド**: `GN` で数万件を連続読込する場合、1 セグメントごとに `LIMIT 1` を発行するとレイテンシが大きい。
  - **対策**: ブロックプリフェッチ（先読みしてメモリ上でカーソルを進める）。**効果は測ってから記録する。**
- **HDAM のルート順序を再現しない** (3.4 節)。
- **二次索引・論理関係・GSAM・Fast Path は L0** ([P-103](../decisions/provisional.md))。特に二次索引は「階層順序は 1 つ」という本方式の中核前提を壊すため、頻出するなら方式そのものの見直しが要る。

---

## 6. 実装に向けた段階的ロードマップ

1. **第 1 増分: DBD / PSB の解析** (`cobol-ims`)
   - `DbdParser` / `PsbParser` / `ImsDatabaseCatalog`。本方式の全体がこれに依存する。
   - L0 機能（二次索引、論理関係、DEDB）の検出と診断。
2. **第 2 増分: 中立インタフェースとインメモリ検証** (`cobol-ims`)
   - `CbltdliBridge` と `DliDatabasePort` を定義。
   - メモリ上の木で階層パス走査と状態コード（`GA` / `GK` / `GE` / `GB` / `GP` / `II`）を再現し、単体テストを通す。
   - `CHKP` の位置破棄、`ISRT` 直後の `GN`、無限定 `GN` の `GA` / `GK` を、**振る舞いの差が出るテスト**として書く。
3. **第 3 増分: RDB ストレージアダプタ** (`cobol-ims-rdb`)
   - 本書のスキーマを用いた JDBC 実装。PostgreSQL（Testcontainers）および Db2 Community（[2026-09-11 検証環境](../report/20260911-db2-community-validation.md)）で結合試験。
   - `verify` に照合順序・兄弟順序・ギャップ・パス長の検査を入れる。
4. **第 4 増分: プリフェッチと `CHKP` 連携**
   - ブロックフェッチ。効果を測って記録する。
   - `CHKP` と JDBC トランザクションの同期（位置破棄を含む）。
