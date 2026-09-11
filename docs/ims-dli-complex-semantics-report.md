# 検討報告: DL/I (Data Language/I) の複雑仕様と実行時セマンティクス詳細検討

| 項目 | 内容 |
| :--- | :--- |
| **作成日** | 2026-09-11 |
| **対象サブシステム** | IMS DB / DL/I 実行時エンジン (`cobol-ims`) |
| **位置づけ** | `CALL 'CBLTDLI'` のメインフレーム完全互換（L3 互換・V2 検証）に向けた詳細セマンティクス定義 |
| **関連文書** | [docs/ims-overview-and-support-scope.md](ims-overview-and-support-scope.md), [docs/ims-rdb-storage-engine-report.md](ims-rdb-storage-engine-report.md) |

---

## 1. はじめに：DL/I の「見かけのシンプルさ」と「内部の複雑怪奇さ」

COBOL プログラムから見た DL/I は、単なる `CALL 'CBLTDLI' USING FUNC, PCB, IOAREA, SSA...` というサブルーチン呼び出しに過ぎません。

しかし、その背後にある **実行時セマンティクス（状態遷移、暗黙のカーソル位置、バッファ結合、キー順序規則）** は極めて精緻かつ複雑です。COBOL 資産が長年依存してきたこれらの振る舞いを正確に再現しないと、業務ロジックの判定（特に `STATUS-CODE` や探索ループ）が狂ってしまいます。

本書では、実装時に必須となる **5 つの核心的・複雑仕様** を詳細に掘り下げ、JVM 上（および RDB バックエンド）での再現方式を定義します。

---

## 2. コマンドコード (Command Codes) の詳細仕様と再現方式

SSA（Segment Search Argument: セグメント探索引数）には、セグメント名に続いて `*` と英字 1 文字（コマンドコード）を付与することで、検索・更新の振る舞いを劇的に変化させる機能があります。

```
SSA 例: CUSTROOT*D(CUSTID  = 12345678)
                ^^
                コマンドコード (*D = Path Call)
```

| コマンドコード | 名称 | メインフレームにおける厳密な挙動 | `cobol-ims` での実装方式 |
| :---: | :--- | :--- | :--- |
| **`*D`** | **Path Call**<br>(パス呼出し) | 階層の複数レベル（親と子）を **1 回の DL/I コールで連結して I/O 領域へ取得** する。例えば、ルートと子の SSA の両方に `*D` を付けると、親セグメント＋子セグメントの生データが受取バッファに連続して格納される。`ISRT` 時も 1 回で親子同時に挿入可能。 | **必須実装**。<br>通常は最下位 SSA のセグメントのみを受取バッファへコピーするが、`*D` が付いた全レベルの `SEG_DATA` を階層上位から順にメモリ上で結合（Concat）して COBOL I/O 領域へ転記する。 |
| **`*N`** | **Path Replace Suppress**<br>(パス置換抑止) | `*D` で複数レベルを取得した後の `REPL`（置換）において、特定レベルのセグメントの更新をスキップする。 | `*N` が指定されたレベルのセグメントは `UPDATE` クエリの発行を抑止する。 |
| **`*F`** | **First Occurrence**<br>(先頭取得) | 現在のカレント位置にかかわらず、その親の配下にある **「最初のセグメント」** から探索を開始する（逆戻り・先頭リセット）。 | RDB クエリの走査条件で `HIERARCHY_PATH > :current` を外し、`PARENT_PATH = :current_parent ORDER BY HIERARCHY_PATH ASC LIMIT 1` で無条件先頭を取得。 |
| **`*L`** | **Last Occurrence**<br>(末尾取得) | 該当条件に合致する兄弟セグメントのうち、**「最後のセグメント」** を取得する。 | `ORDER BY HIERARCHY_PATH DESC LIMIT 1` で逆順取得。 |
| **`*P`** | **Set Parentage**<br>(親境界の設定) | 通常は最下位セグメントが親境界となるが、途中の親セグメントの SSA に `*P` を指定することで、**そのレベルを強制的に `GNP` の親（Parentage）として固定** する。 | PCB の `currentParentPath` を最下位ではなく `*P` が指定されたセグメントのパスに設定する。 |
| **`*U` / `*V`** | **Maintain Position**<br>(位置の固定) | 階層探索で目的のセグメントが見つからなかった場合（`GE`）、通常はカレント位置が失われるが、`*U` / `*V` を付けると失敗前のカレント位置を厳格に保護・維持する。 | クエリ失敗時に PCB のカレントポインタを巻き戻すスナップショット退避機構。 |

> **設計判断**:
> `*D`（Path Call）と `*F`（First）は実務 COBOL 資産で極めて頻出するため、**第 1 増分（MVP）で必須サポート** とします。

---

## 3. 親境界（Parentage）の厳密なライフサイクルと `GNP`

DL/I の `GNP` (Get Next in Parent: 親の配下の次セグメント読込) は、「現在の親」が誰であるかに完全に依存します。この親（Parentage）の確立と失効のルールは非常に厳格です。

```
       [ROOT: 顧客 10001]  <--- ① GU 発行でここが Parentage になる
           |
           +-- [CHILD: 注文 A]  <--- ② GNP で取得 (Parentage はルートのまま維持)
           |       +-- [GRANDCHILD: 明細 1]
           |       +-- [GRANDCHILD: 明細 2]
           |
           +-- [CHILD: 注文 B]  <--- ③ GNP で取得
```

### 3.1 Parentage の確立ルール
1. **`GU` または `GN` の成功時**:
   - SSA で指定された最下位セグメント（または `*P` コマンドコードのセグメント）の直上の親、あるいはそのセグメント自身が Parentage として確立される。
2. **`GNP` 自体の呼び出し時**:
   - `GNP` はセグメントを取得しても、**「Parentage（親の場所）を前進させない」**。親は固定されたまま、配下の子孫だけを次々と走査する。

### 3.2 Parentage の失効（リセット）ルール
- 別の `GU` / `GN` を呼び出したとき（新しい Parentage に上書き）。
- `GNP` で親の配下の全セグメントを走査し終わり、**`STATUS-CODE = 'GE'` が返却された瞬間**（親境界が消滅し、以降の `GNP` は直ちに `GE` となる）。
- `ISRT` / `DLET` が失敗したとき。

### 3.3 PCB 状態管理オブジェクトの設計
ランタイム側の PCB ステートマシンに以下の内部プロパティを保持します：
```java
public class PcbInternalState {
    private String currentDbdName;
    private String currentHierarchyPath;    // 現在のカーソル位置
    private String establishedParentPath;    // 確立された親のパス (Parentage)
    private int establishedParentLevel;      // 親の階層レベル (1=ROOT, 2=CHILD...)
    private boolean parentageValid;          // 親境界が有効かどうか
    private byte[] lastRootKey;              // 現在のルートキー
}
```

---

## 4. 重複キー（Non-Unique Key）とキーなしセグメントの挿入規則 (`RULES`)

DBD（データベース記述）において、キー項目がユニークでないセグメント（`BYTES=8,START=1,TYPE=C` で `UNIQUE` がない場合）、またはキー項目自体が存在しないセグメントの挿入順序は、DBD の `RULES=` パラメータで制御されます。

| `RULES` 指定 | 意味 | RDB 上での `HIERARCHY_PATH` 採番方式 |
| :--- | :--- | :--- |
| **`RULES=(FIRST)`** | 同一キーの兄弟セグメント群の **「先頭」** に挿入する。 | 既存の同キーの最小シーケンス番号より小さい番号（または小数点・ギャップ採番）を生成して手前にねじ込む。 |
| **`RULES=(LAST)`**<br>*(一般的)* | 同一キーの兄弟セグメント群の **「末尾」** に追加する。 | `SELECT MAX(SEQ) ...` で末尾シーケンス番号を採番して後方に追加。 |
| **`RULES=(HERE)`** | 直前の `GN` や `GU` で **「現在位置していたカーソルの直前/直後」** に挿入する。 | カレントの `HIERARCHY_PATH` の直後となるギャップ値を採番。 |

> **RDB 実装上の工夫**:
> `HIERARCHY_PATH` を `/001/01-000100` のように 100 刻みで採番（ギャップを持たせる）しておくことで、`FIRST` や `HERE` による中間挿入が発生しても、他の全レコードのキーを更新することなく高速に挿入できます。

---

## 5. 複数 PCB による並行カーソル（Multiple Positioning）

COBOL プログラムの `PROCEDURE DIVISION USING ...` には、**複数の DB PCB** を渡すことができます。

```cobol
PROCEDURE DIVISION USING IO-PCB, CUST-PCB, ORDER-PCB.
```

1. **別 DBD へのアクセス**:
   - `CUST-PCB` は顧客 DB、`ORDER-PCB` は注文 DB を指す（独立動作）。
2. **同一 DBD に対する複数 PCB（マルチポジショニング）**:
   - 2 つの PCB が **同一の DBD（顧客 DB）を別々のカレント位置で走査** する形態。
   - 例: `PCB-1` で親セグメントをループ走査しながら、`PCB-2` で同じ DB の別のセグメントをランダム参照する。

> **設計方針**:
> カレント位置や Parentage の状態は、ランタイムのグローバル変数ではなく、**引数として渡された各 `DB-PCB` のインスタンスごとに完全に独立した `PcbInternalState` として保持** します。これにより、同一 DB に対する複数カーソル走査が一切の干渉なく動作します。

---

## 6. ステータスコード（Status Codes）の完全マッピング

COBOL プログラムは、`CALL 'CBLTDLI'` 直後に必ず `IF DB-STATUS = '  '` や `IF DB-STATUS = 'GE'` といった 2 文字のコードで分岐します。代表的なステータスコードの意味論と返却条件を固定します。

| コード | 意味 | 発生条件 |
| :---: | :--- | :--- |
| **`'  '`** (空白2文字) | 正常終了 | 要求されたセグメントが正常に取得・更新・挿入された。 |
| **`'GE'`** | Not Found (非該当) | 指定された条件のセグメントが存在しない。または `GNP` で親の範囲を越えた。 |
| **`'GB'`** | End of Database | データベースの物理的終端に達した（`GN` のループ終了条件）。 |
| **`'II'`** | Invalid Insert (重複キー) | `UNIQUE` キー制約のあるセグメントで、既に存在するキー値を `ISRT` しようとした。 |
| **`'DJ'`** | Delete / Replace without Hold | 直前に `GHU` / `GHN` / `GHNP` (Get Hold) を呼んでいない状態で `DLET` や `REPL` を呼んだ。 |
| **`'DA'`** | Key Field Changed | `REPL`（置換）において、セグメントのキーフィールド（シーケンス項目）の値を書き換えようとした（キーの変更は禁止）。 |
| **`'QC'`** | Queue Empty (TM のみ) | メッセージキューが空になった（MPP プログラムの正常終了合図）。 |
| **`'AJ'`** | Qualified SSA Format Error | SSA の構文エラー（括弧が閉じていない、比較演算子が不正など）。 |
| **`'AK'`** | Field Name Undefined | SSA に指定されたフィールド名が DBD に存在しない。 |

---

## 7. まとめと実装優先度

| 分類 | 機能項目 | 複雑度 | 実装優先度 |
| :--- | :--- | :---: | :---: |
| **コマンドコード** | `*D` (Path Call 一括取得/挿入) | 高 | **最優先 (MVP)** |
| | `*F` (先頭リセット) / `*L` (末尾取得) | 中 | **最優先 (MVP)** |
| | `*P` (親境界強制指定) / `*U`, `*V` (位置維持) | 中 | 第 2 増分 |
| **階層セマンティクス** | Parentage (親境界) の厳格な確立・失効 | 高 | **最優先 (MVP)** |
| | 複数 PCB の独立カーソル (Multiple Positioning) | 低 | **最優先 (MVP)** |
| | `RULES=(FIRST/LAST/HERE)` 重複キー挿入順序 | 中 | 第 2 増分 |
| **エラーハンドリング** | `'  '`, `'GE'`, `'GB'`, `'II'`, `'DJ'`, `'DA'` 返却 | 中 | **最優先 (MVP)** |

これらのセマンティクスを `cobol-ims` の中立コアにステートマシンとして組み込むことで、メインフレーム実機と寸分違わぬ動作を JVM 上で担保できます。
