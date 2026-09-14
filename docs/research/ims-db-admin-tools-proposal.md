# 検討報告: IMS DB (RDBバックエンド) 運用管理ツールと JCL 起動経路の設計

| 項目 | 内容 |
| :--- | :--- |
| **作成日** | 2026-09-11 (2026-09-13 改訂) |
| **対象サブシステム** | IMS DB (DL/I) / バッチ起動経路・運用保守ユーティリティ |
| **対応要件** | **無し** ([P-098](../decisions/provisional.md)) |
| **検証レベル** | **V0**。ユーティリティ名と役割の出典を確認していない ([P-109](../decisions/provisional.md)) |
| **関連文書** | [ims-rdb-storage-engine-report.md](ims-rdb-storage-engine-report.md), [設計文書 90 (ジョブ実行とユーティリティ)](../design/90-job.md), [設計 78](../design/78-ims-subsystem.md) |
| **改訂理由** | [2026-09-13 批判的レビュー](../reviews/2026-09-13-ims-research-critical-review.md) の IR-31〜IR-34 |

> ### 改訂の要点
>
> **1. 優先順位を入れ替えた。**改訂前は `DFSURGU0`（アンロード）と `DFSURGL0`（リロード）を
> 実装対象に選んでいたが、これらは**再編成**のためのユーティリティであり、**RDB バックエンドでは
> 再編成という作業自体が消える**。最初に作るべきは、自ら「最も需要が高い」と書いた
> IMS バッチの JCL 入口である `DFSRRC00` である（§2）。
>
> **2. 出典の無い名称を落とした。**`DFSNDRV0` は一般的な IMS 標準ユーティリティとして確認できず、
> `DFSERA10` は DB のレコードダンプではなくログの選択・整形印刷である ([P-109](../decisions/provisional.md))。
>
> **3. 対象外だったものを明示した。**DBRC、イメージコピー、リカバリ、バッチバックアウトは
> 実運用 JCL にほぼ必ずあるが、改訂前はどこにも現れていなかった（§3.3）。
>
> **4. `verify` の検査項目を、この設計で実際に壊れるものへ差し替えた**（§6）。

---

## 1. はじめに

z/OS において IMS DB を運用・管理するツールは、独立した対話式 CLI ではなく、**JCL のステップとして起動される標準バッチユーティリティプログラム**として提供されている。

本プロジェクトでは、[設計文書 90](../design/90-job.md) で `IDCAMS` や `IEBGENER`、`SORT` などを「通常の `CobolProgram` 互換クラス」として実装し、JCL から修正なしに呼び出せるアーキテクチャを採用している。IMS 関連のプログラムも同じ枠組みに載せる。

---

## 2. 最優先: `DFSRRC00`（IMS バッチの起動経路）

[ims-overview-and-support-scope.md](ims-overview-and-support-scope.md) §4.1 は「IMS バッチ / BMP」を**最も需要が高く移行の第一歩になりやすい**形態と位置づけている。ところが改訂前は、**その JCL 側の入口がどこにも設計されていなかった。**ユーティリティ実行例に `PGM=DFSRRC00` が 1 行現れるだけだった。

**第 1 増分はここである。**

### 2.1 JCL の形

```jcl
//RUNBATCH EXEC PGM=DFSRRC00,PARM='DLI,CUSTPGM,CUSTPSB'
//STEPLIB  DD DSN=IMS.SDFSRESL,DISP=SHR
//IMS      DD DSN=IMS.PSBLIB,DISP=SHR        <- PSB / DBD ライブラリ
//DFSVSAMP DD *                              <- バッファプール定義
//SYSPRINT DD SYSOUT=*
//CUSTDD   DD DSN=PROD.CUSTDB,DISP=SHR
```

### 2.2 エミュレーションの仕組み

- `DFSRRC00` を `cobol-job` のユーティリティレジストリへ登録する。
- `PARM` を解析する。第 1 パラメータが領域の種別である。
  - `DLI` — バッチ（DL/I）
  - `BMP` — バッチメッセージ処理
  - `ULU` — ユーティリティ
- 第 2 パラメータのプログラム名、第 3 パラメータの PSB 名を取り出す。
- `//IMS` DD から DBD / PSB を読み、`ImsDatabaseCatalog` を構築する（[設計 78](../design/78-ims-subsystem.md) §2.1）。
- PSB の PCB 並びどおりに PCB を構築し、`PROCEDURE DIVISION USING` へバインドする。
- `//DFSVSAMP` は受理して無視する。**RDB バックエンドではバッファプール指定に意味が無い**ため、`SYSPRINT` にその旨を出す。

---

## 3. 運用機能とメインフレーム対応表

### 3.1 対応する運用要件

| 運用要件 | メインフレーム互換名 | CLI サブコマンド | RDB バックエンド側の動作 |
| :--- | :--- | :--- | :--- |
| **セグメント閲覧・抽出**（障害調査） | — | `ims-db print` | 指定したルートキー配下の木構造を階層ツリー形式で HEX / ASCII 整形ダンプする |
| **DB 状態照会 / 統計** | `/DIS DB` 相当 | `ims-db stat` | DBD ごとのルート件数、セグメント総数、セグメントタイプ別の件数を集計する |
| **整合性検証** | — | `ims-db verify` | §6 の検査項目 |
| **データ初期化 / 削除** | （`IDCAMS DELETE` 相当） | `ims-db clear` | 指定 DBD のセグメント・インデックスを削除する |
| **一括アンロード**（バックアップ・移行） | `DFSURGU0`（**出典未確認**） | `ims-db unload` | 指定 DBD の全セグメントを階層順で抽出し、ファイルへ出力する |
| **一括ロード / リロード** | `DFSURGL0`（**出典未確認**） | `ims-db reload` | アンロードファイルから一括投入する |

> **`DFSNDRV0` は表から削除した。**一般的な IMS 標準ユーティリティ名として確認できない。
> **`DFSERA10` も削除した。**これは**ログの選択・整形印刷**のユーティリティであって、
> データベースのレコードダンプではない。残る名称にも出典を付けるまで「未確認」を明記する
> ([P-109](../decisions/provisional.md))。

### 3.2 再編成ユーティリティの位置づけ【改訂】

`DFSURGU0` / `DFSURGL0` は **HD 再編成**のためのユーティリティである。**RDB バックエンドでは再編成という作業自体が消える。**つまり移行後もっとも不要になるステップである。改訂前はこれを実装対象の筆頭に選んでいた。

**方針**: JCL に現れる再編成ステップは、**認識して no-op 化する**（「RDB バックエンドでは不要」と `SYSPRINT` に出して RC=0）方が、忠実に模倣するより価値が高い可能性がある。アンロード / リロード機能そのものは、バックアップと移行の手段として CLI 側に残す。

### 3.3 対象外だが実運用 JCL に頻出するもの【改訂で追加】

改訂前はどこにも現れていなかったが、実運用の IMS バッチ JCL にはほぼ必ずある。

| 機能 | 扱い |
| --- | --- |
| **DBRC (RECON データセット)** | IMS バッチ JCL の標準構成要素（`DBRC=Y`、RECON の 3 データセット）。**対応 / 非対応を明示する必要がある。**未決 |
| **イメージコピー** | 障害時運用の中心。RDB バックエンドでは RDB 側のバックアップで代替できる可能性がある。未決 |
| **リカバリ** | 同上。未決 |
| **バッチバックアウト** | 同上。未決 |

**「JCL 無修正」を目的にするなら、まず実資産の JCL に現れるユーティリティを頻度順に洗い出す。**推測で選んだ 2 本を作ることが目的ではない。

---

## 4. 実装形態 1: JCL バッチユーティリティ互換 (`cobol-job`)

```jcl
//UNLOAD   EXEC PGM=DFSRRC00,PARM='ULU,DFSURGU0,CUSTDBD'
//IMS      DD DSN=IMS.DBDLIB,DISP=SHR
//CUSTDD   DD DSN=PROD.CUSTDB,DISP=SHR
//DFSEXTDS DD DSN=BACKUP.CUSTDB.UNLOAD,
//            DISP=(NEW,CATLG,DELETE),
//            SPACE=(CYL,(50,10))
//SYSPRINT DD SYSOUT=*
```

- `cobol-job` のユーティリティレジストリに `DFSRRC00`（`ULU` 形式）を登録する。
- `PARM` から対象 DBD 名を取得する。
- 出力 DD に対し、階層順に読み出したセグメントヘッダ＋生データを書き出す。
- `SYSPRINT` にアンロード件数を出力する。

> **アンロード形式について**: 実機の `DFSURGU0` 出力は `DFSURGL0` が読む内部形式である。
> **本プロジェクトが独自形式を定義する場合、そのファイルを実機や他ツールへ持ち込むことはできない。**
> 形式の互換性を要件にするかどうかは未決である。

---

## 5. 実装形態 2: モダン CLI ツール (`ims-db`)

オープン環境での運用作業や CI/CD パイプラインで直接叩けるコマンドラインツールを提供する。

```bash
# 1. データベース状態・統計の確認
$ java -jar cobol-ims-tools.jar stat --dbd=CUSTDBD

DBD: CUSTDBD (Storage: PostgreSQL / coboldb, ACCESS=HIDAM)
  Root Count:      12,500
  Total Segments:  84,200
  Segments by Type:
    - CUSTROOT : 12,500
    - ORDER    : 45,200
    - ITEM     : 26,500
  Path gap (min):  62        <- 枯渇の予兆 (6 節)
  Max path depth:  3 / 15

# 2. データのダンプ・ツリー閲覧
$ java -jar cobol-ims-tools.jar print --dbd=CUSTDBD --root-key=10001 --format=tree

ROOT: CUSTROOT [Key: 10001] (Data: "TANAKA TARO         TOKYO...")
  +-- CHILD: ORDER [Key: 20260901-01] (Data: "2026-09-01 ONLINE...")
  |     +-- GRANDCHILD: ITEM [Seq: 01] (Data: "BOOK-001    0001500...")
  |     +-- GRANDCHILD: ITEM [Seq: 02] (Data: "PEN-002     0000300...")
  +-- CHILD: ORDER [Key: 20260910-05] (Data: "2026-09-10 STORE...")

# 3. ファイルへのアンロード
$ java -jar cobol-ims-tools.jar unload --dbd=CUSTDBD --output=./backup/custdb.dat

# 4. バックアップファイルからのリロード
$ java -jar cobol-ims-tools.jar reload --dbd=CUSTDBD --input=./backup/custdb.dat --truncate

# 5. 整合性チェック
$ java -jar cobol-ims-tools.jar verify --dbd=CUSTDBD
```

---

## 6. `verify` が検査すべきもの【改訂で差し替え】

改訂前の検査項目は「孤児セグメント（親が存在しない）」と「シーケンスキーの順序」だった。しかし**孤児は、書き込みが必ずエンジン経由なら通常発生しない。**

**この設計で実際に壊れるのは次である**（[P-101](../decisions/provisional.md)、[ims-rdb-storage-engine-report.md](ims-rdb-storage-engine-report.md) §5.2）。

| # | 検査項目 | 検出したいもの |
| --- | --- | --- |
| 1 | `HIERARCHY_PATH` の各段が固定幅・許可文字（数字と `-`）であること | **小数点採番の混入。**先行順走査が壊れる |
| 2 | 兄弟の `HIERARCHY_PATH` 順序が `SEQ_KEY_RAW` 順序と一致すること | 兄弟順序の破れ |
| 3 | 残ギャップの最小値 | **枯渇の予兆。**再採番が必要になる時期 |
| 4 | パス長の最大値と階層深さ | `VARCHAR(256)` 超過による切り捨て |
| 5 | データベースの照合順序がバイト値順であること | `COLLATE "C"` の欠落。走査順序が崩れる |
| 6 | 孤児セグメント（従来の検査） | エンジン外からの書き込み事故 |

孤児検出のクエリは従来どおり 1 発で書ける。

```sql
SELECT c.HIERARCHY_PATH, c.SEG_NAME
  FROM IMS_SEGMENT_STORE c
  LEFT JOIN IMS_SEGMENT_STORE p
    ON c.DBD_NAME = p.DBD_NAME
   AND c.ROOT_KEY_RAW = p.ROOT_KEY_RAW
   AND c.PARENT_PATH = p.HIERARCHY_PATH
 WHERE c.SEG_LEVEL > 1 AND p.HIERARCHY_PATH IS NULL;
```

CLAUDE.md §6 のとおり、**道具にも検査を仕込む。**

---

## 7. バックエンドが RDB であることの利点

1. **高速バルクロード**: `reload` 実行時、1 行ずつの `INSERT` ではなく PostgreSQL の `COPY FROM STDIN (BINARY)` や Db2 の `LOAD` を用いる。**効果は測ってから記録する。**改訂前は「毎秒数十万セグメントの超高速リストア」と書いていたが、測っていない。
2. **SQL による一括整合性検査**: §6 の検査の多くが単一クエリで書ける。
3. **トランザクション保護**: リロードやデータ初期化が中途失敗しても `ROLLBACK` で中途半端な状態が残らない。

---

## 8. まとめと提案

- **第 1 増分は `DFSRRC00`（IMS バッチの JCL 起動経路）である。**自ら「最も需要が高い」と位置づけた形態の入口であり、改訂前の設計から抜けていた。
- **再編成ユーティリティ（`DFSURGU0` / `DFSURGL0`）は優先度が低い。**RDB バックエンドでは再編成そのものが不要になる。認識して no-op 化する案を検討する。
- **DBRC、イメージコピー、リカバリ、バッチバックアウトは未決である。**実運用 JCL に頻出するため、対応 / 非対応を明示する必要がある。
- **ユーティリティ名と役割は出典を確認してから確定する** ([P-109](../decisions/provisional.md))。
- **`verify` は、この設計で実際に壊れるもの（パス採番・兄弟順序・ギャップ枯渇・照合順序）を検査する**（§6）。
