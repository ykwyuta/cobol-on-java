# 検討報告: IMS DB (RDBバックエンド) 運用管理コマンドラインツールの設計

| 項目 | 内容 |
| :--- | :--- |
| **作成日** | 2026-09-11 |
| **対象サブシステム** | IMS DB (DL/I) / 運用保守・バッチユーティリティ |
| **位置づけ** | メインフレーム標準の IMS ユーティリティ互換 CLI & バッチプログラム |
| **関連文書** | [docs/ims-rdb-storage-engine-report.md](ims-rdb-storage-engine-report.md), [設計文書 90 (ジョブ実行とユーティリティ)](design/90-job.md) |

---

## 1. はじめに：メインフレームにおける IMS 運用ツールとは

メインフレーム（z/OS）において、IMS DB を運用・管理するツールは、独立した対話式 CLI ではなく、**「JCL のステップとして起動される標準バッチユーティリティプログラム」**（`PGM=DFSRRC00` または `PGM=DFSURxxx`）として提供されています。

主な標準ユーティリティプログラムは以下の通りです：

| メインフレームでのユーティリティ | 役割 | 典型的な JCL 入出力 |
| :--- | :--- | :--- |
| **`DFSURPR0`**<br>(Database Prereorganization) | 初期ロードや再編成の事前準備、制御情報の生成 | `SYSIN` (制御文), `SYSPRINT` |
| **`DFSURGL0`**<br>(HD Reorganization Reload) | アンロードデータセットから DB への一括再ロード | `DFSUINPT` (入力), `DFSVSAMP` |
| **`DFSURGU0`**<br>(HD Reorganization Unload) | DB からシーケンシャルファイルへ全件アンロード | `DFSEXTDS` (出力), `SYSIN` |
| **`DFSNDRV0` / `DFSERA10`** | ログや整合性検証、レコードダンプ・診断 | `SYSUT1`, `SYSUT2`, `SYSPRINT` |
| **IMS Commands via `IKJEFT01` / MTO** | `/DIS DB`, `/STA DB`, `/STO DB` (DBの状態確認、開始、停止) | TSO または IMS オペレータ端末 |

本プロジェクト（`cobol-on-java`）では、[設計文書 90](design/90-job.md) で `IDCAMS` や `IEBGENER`、`SORT` などを「通常の `CobolProgram` 互換クラス」として実装し、JCL から無修正で呼び出せるアーキテクチャを採用しています。

したがって、IMS DB 運用ツールも **「JCL から呼べるメインフレーム互換プログラム」** と **「Linux/Windows ターミナルから直接叩ける CLI コマンド」** の 2 つの形態を同じコアエンジンから提供するのが最も整合性の高い設計となります。

---

## 2. 必要な運用機能とメインフレーム対応表

バックエンドを RDB（PostgreSQL / Db2）上の `IMS_SEGMENT_STORE` で管理する場合、運用担当者が行う作業とツールの対応関係は以下のようになります。

| 運用要件 | メインフレーム互換ユーティリティ名 | CLI サブコマンド | 処理内容 (RDB バックエンド側の動作) |
| :--- | :--- | :--- | :--- |
| **一括アンロード** (バックアップ・移行) | **`DFSURGU0`** | `ims-db unload` | `IMS_SEGMENT_STORE` から指定 DBD の全セグメントを階層順（`HIERARCHY_PATH ASC`）で抽出し、標準フォーマットファイル（EBCDIC 生バイト列）に出力する。 |
| **一括ロード / リロード** (初期投入) | **`DFSURGL0`** | `ims-db reload` | アンロードファイルから一括で `IMS_SEGMENT_STORE` および `IMS_ROOT_INDEX` へ `COPY` / バッチ INSERT する。 |
| **整合性検証** (ポインタ・孤児検査) | **`DFSNDRV0`** (または `DB-VERIFY`) | `ims-db verify` | 子セグメントの `PARENT_PATH` が実在するか、ルートキーとセグメントの整合性があるかを SQL（自己外部結合）で一括検証・レポート出力。 |
| **セグメント閲覧・抽出** (障害調査) | **`DFSERA10`** (または `DB-PRINT`) | `ims-db print` | 指定したルートキー配下の木構造を階層ツリー形式（インデント付き）で画面や `SYSPRINT` に EBCDIC/HEX/ASCII で整形ダンプ。 |
| **データ初期化 / 削除** | （`IDCAMS DELETE` 相当） | `ims-db clear` | 指定した DBD のセグメント・インデックスを RDB から全削除 (`TRUNCATE` / `DELETE`)。 |
| **DB 状態照会 / 統計** | `/DIS DB` 相当 | `ims-db stat` | DBD ごとのルート件数、セグメント総数、ストレージ使用量、各セグメントタイプ別の件数内訳を集計。 |

---

## 3. 実装形態 1: JCL バッチユーティリティ互換 (`cobol-job`)

メインフレームの既存 JCL 資産に含まれる再編成・アンロード・リロードのステップをそのまま動かせるようにします。

### 3.1 JCL 実行例 (`DFSURGU0` によるアンロード)

```jcl
//UNLOAD   EXEC PGM=DFSRRC00,PARM='ULU,DFSURGU0,CUSTDBD'
//STEPLIB  DD DSN=IMS.SDFSRESL,DISP=SHR
//DFSRESLB DD DSN=IMS.SDFSRESL,DISP=SHR
//IMS      DD DSN=IMS.DBDLIB,DISP=SHR
//CUSTDD   DD DSN=PROD.CUSTDB,DISP=SHR
//DFSEXTDS DD DSN=BACKUP.CUSTDB.UNLOAD,
//            DISP=(NEW,CATLG,DELETE),
//            SPACE=(CYL,(50,10))
//SYSPRINT DD SYSOUT=*
//SYSIN    DD *
/*
```

- **エミュレーションの仕組み**:
  - `cobol-job` のユーティリティレジストリに `DFSURGU0`（およびドライバプログラム `DFSRRC00`）を登録。
  - `PARM` から対象 DBD 名（`CUSTDBD`）を取得。
  - `DFSEXTDS` DD で指定された出力データセットに対し、RDB から階層順に吸い出したセグメントヘッダ＋生データをシーケンシャルに書き出す。
  - `SYSPRINT` にアンロード件数（ルート件数、セグメント別件数）をメインフレームと同一フォーマットで出力。

---

## 4. 実装形態 2: モダン CLI ツール (`ims-admin` / `ims-db`)

オープン環境（Linux / Windows / コンテナ）での運用作業や CI/CD パイプラインで直感的に叩けるコマンドラインツールを提供します。

### 4.1 CLI コマンド体系

```bash
# 1. データベース状態・統計の確認
$ java -jar cobol-ims-tools.jar stat --dbd=CUSTDBD

DBD: CUSTDBD (Storage: PostgreSQL / coboldb)
  Root Count:      12,500
  Total Segments:  84,200
  Segments by Type:
    - CUSTROOT : 12,500
    - ORDER    : 45,200
    - ITEM     : 26,500
  Status: HEALTHY (No orphaned segments)

# 2. データのダンプ・ツリー閲覧 (ルートキー '10001' を表示)
$ java -jar cobol-ims-tools.jar print --dbd=CUSTDBD --root-key=10001 --format=tree

ROOT: CUSTROOT [Key: 10001] (Data: "TANAKA TARO         TOKYO...")
  +-- CHILD: ORDER [Key: 20260901-01] (Data: "2026-09-01 ONLINE...")
  |     +-- GRANDCHILD: ITEM [Seq: 01] (Data: "BOOK-001    0001500...")
  |     +-- GRANDCHILD: ITEM [Seq: 02] (Data: "PEN-002     0000300...")
  +-- CHILD: ORDER [Key: 20260910-05] (Data: "2026-09-10 STORE...")

# 3. ファイルへのアンロード (標準バックアップ形式)
$ java -jar cobol-ims-tools.jar unload --dbd=CUSTDBD --output=./backup/custdb.dat

Unloading CUSTDBD from PostgreSQL...
[========================================] 12,500 roots unloaded (84,200 segments).
Output file written: ./backup/custdb.dat (14.2 MB)

# 4. バックアップファイルからのリロード
$ java -jar cobol-ims-tools.jar reload --dbd=CUSTDBD --input=./backup/custdb.dat --truncate

Reloading CUSTDBD into PostgreSQL...
Clearing existing table data... OK.
Streaming records to IMS_SEGMENT_STORE...
[========================================] 84,200 segments loaded.
Rebuilding IMS_ROOT_INDEX... OK.
Completed in 2.1s.

# 5. 階層整合性チェック (親なし孤児セグメント等の検出)
$ java -jar cobol-ims-tools.jar verify --dbd=CUSTDBD

Verifying hierarchy paths and parent-child integrity...
Checking orphaned segments... None found.
Checking sequence key ordering... OK.
Result: PASSED.
```

---

## 5. バックエンド RDB（PostgreSQL / Db2）を活かした運用上のメリット

バックエンドが RDB である利点を活かし、本ツールはメインフレームの実機ユーティリティよりも圧倒的に高速かつ安全に処理を行えます。

1. **高速バルクロード（Bulk Copy / LOAD ユーティリティ活用）**:
   - `ims-db reload` 実行時、通常の 1 行ずつの `INSERT` ではなく、PostgreSQL の `COPY FROM STDIN (BINARY)` や Db2 の `ADMIN_CMD('LOAD ...')` をバックグラウンドで呼び出すことで、**毎秒数十万セグメントの超高速リストア** を実現。
2. **SQL による一括整合性検査（`verify`）**:
   - メインフレームではポインタチェーンの破損を調べるために時間のかかるディスクスキャンが必要でしたが、RDB では次の 1 発のクエリで孤児（親が存在しない不正セグメント）を即座に検出できます：
     ```sql
     SELECT c.HIERARCHY_PATH, c.SEG_NAME 
       FROM IMS_SEGMENT_STORE c
  LEFT JOIN IMS_SEGMENT_STORE p 
         ON c.DBD_NAME = p.DBD_NAME 
        AND c.ROOT_KEY_RAW = p.ROOT_KEY_RAW 
        AND c.PARENT_PATH = p.HIERARCHY_PATH
      WHERE c.SEG_LEVEL > 1 AND p.HIERARCHY_PATH IS NULL;
     ```
3. **トランザクション保護**:
   - リロードやデータ初期化が中途失敗した場合でも、RDB の `ROLLBACK` により中途半端な破損状態が残りません。

---

## 6. まとめと提案

- **メインフレーム資産（JCL）との互換性**:
  - `DFSURGU0`（アンロード）および `DFSURGL0`（リロード）を `cobol-job` のユーティリティプログラムとして実装し、既存 JCL を無修正で完走させます。
- **モダン運用（DevOps / CI/CD）との調和**:
  - 同じロジックを呼び出す `ims-db` CLI（サブコマンド形式: `stat`, `print`, `unload`, `reload`, `verify`）を提供し、ローカル開発・テストデータの投入・本番運用を強力に支援します。
- この構成により、ホスト運用者の慣れ親しんだ JCL バッチフローを維持しつつ、オープン環境ならではの俊敏な運用性を両立できます。
