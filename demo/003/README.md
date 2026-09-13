# デモ #003: JCL バッチジョブ実行デモ

本デモは、`cobol-on-java` の内部ジョブ実行機構（`cobol-job`）と JCL フロントエンドを用いた **メインフレーム互換の JCL バッチジョブ実行** の動作デモです。

IBM メインフレーム（z/OS）資産のバッチ処理では、COBOL 単体だけでなく JCL により複数の処理ステップを制御し、データセット（DD）の動的割り当てや復帰コード（RC）による後続判定を行います。
本デモでは、既存の JCL をそのまま JVM 上で解析・実行する流れを体験できます。

---

## 構成ファイル

* [`GEN-SALES.cbl`](file:///d:/workspace/cobol-on-java/demo/003/GEN-SALES.cbl) : 売上データ作成プログラム。`ASSIGN TO OUTDD` により指定されたデータセットへ売上レコードを出力します。JCL の `PARM='202609'` を `LINKAGE SECTION` で受け取り対象年月を設定します。
* [`PRT-SALES.cbl`](file:///d:/workspace/cobol-on-java/demo/003/PRT-SALES.cbl) : 売上集計レポート出力プログラム。`ASSIGN TO INDD` により指定されたデータセットを順次読み込み、合計金額・総数量を集計して SYSOUT（スプール）に出力します。
* [`SALESJOB.jcl`](file:///d:/workspace/cobol-on-java/demo/003/SALESJOB.jcl) : バッチジョブを定義した JCL ファイル。
* [`run_demo.bat`](file:///d:/workspace/cobol-on-java/demo/003/run_demo.bat) : Windows 環境用一括ビルド・実行バッチファイル。

---

## JCL ジョブ (`SALESJOB.jcl`) の解説

```jcl
//SALESJOB JOB  (ACCT),'SALES BATCH',CLASS=A
//*
//INIT     EXEC PGM=IEFBR14
//*
//GEN      EXEC PGM=GEN_SALES,PARM='202609'
//OUTDD    DD   DSN=SALES.DAT,DISP=(NEW,CATLG)
//SYSPRINT DD   SYSOUT=*
//*
//         IF (GEN.RC = 0) THEN
//RPT      EXEC PGM=PRT_SALES
//INDD     DD   DSN=SALES.DAT,DISP=SHR
//SYSPRINT DD   SYSOUT=*
//         ENDIF
//*
//SKIP     EXEC PGM=IEFBR14,COND=(0,LE,GEN)
```

1. **ホスト互換ユーティリティ (`IEFBR14`) (FR-137)**:
   - `STEP1 (INIT)` で実行される `IEFBR14` はホストの代表的ダミープログラム（即座に復帰コード 0 を返却）です。
2. **PARM 渡しと動的 DD 割り当て (FR-133, FR-134)**:
   - `STEP2 (GEN)` では、JCL の `PARM='202609'` が COBOL 規約（先頭 2 バイト長 + 文字列）で渡されます。
   - `GEN-SALES` 側の `SELECT ... ASSIGN TO OUTDD` と JCL の `//OUTDD DD DSN=SALES.DAT` が結合され、物理ファイル `SALES.DAT`（EBCDIC生バイト列）とそのメタデータ `SALES.DAT.meta` が作成されます。
3. **SYSOUT スプール機構 (FR-135)**:
   - `//SYSPRINT DD SYSOUT=*` を指定することで、プログラムの標準出力や `DISPLAY` 結果がスプール領域に蓄積され、ステップ終了時にジョブログへ流し出されます。
4. **ステップ間条件分岐 (`IF-THEN` / `COND`) (FR-131, FR-136)**:
   - `IF (GEN.RC = 0) THEN`: 先行ステップ `GEN` が正常終了（RC=0）した場合にのみ、`STEP3 (RPT)` が実行されます。
   - `COND=(0,LE,GEN)`: `0 <= GENのRC`（真なら飛ばす）の判定により、`STEP4 (SKIP)` は意図通りスキップ（`NOT EXECUTED`）されます。

---

## 実行方法 (Windows)

コマンドプロンプトまたは PowerShell で `demo\003\run_demo.bat` を実行するか、エクスプローラーからダブルクリックして実行します。

```cmd
demo\003\run_demo.bat
```

### 実行結果イメージ

```text
===================================================
 cobol-on-java DEMO #003 (JCL Batch Job Execution)
===================================================

[1/4] Checking Environment...
[2/4] Compiling COBOL files (GEN-SALES.cbl, PRT-SALES.cbl)...
[3/4] Packaging cobol-job and dependencies...
[4/4] Executing JCL Batch Job (SALESJOB.jcl)...
---------------------------------------------------
GEN-SALES: TARGET MONTH = 202609
GEN-SALES: OUTPUT COMPLETED (3 RECORDS).
==================================================
             MONTHLY SALES REPORT                 
==================================================
DATE   ID   NAME         PRICE    QTY      SUBTOTAL
------ ---- ------------ ------ ----- ------------
202609 1001 APPLE           150    20       3,000
202609 1002 BANANA          100    50       5,000
202609 1003 ORANGE          120    35       4,200
--------------------------------------------------
TOTAL RECORDS: 0003
TOTAL QTY    :     105
GRAND TOTAL  :      12,200 JPY
==================================================
SALESJOB.INIT ENDED - RC=0
SALESJOB.GEN ENDED - RC=0
SALESJOB.RPT ENDED - RC=0
SALESJOB.SKIP NOT EXECUTED
---------------------------------------------------
[SUCCESS] Demo #003 completed successfully.
```
