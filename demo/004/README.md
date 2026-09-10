# デモ #004: JCL ユーティリティ連携 (SORT / IEBGENER) デモ

本デモは、メインフレーム（z/OS）のバッチシステムで最も多用される代表的システムユーティリティ **`SORT` (DFSORT)** および **`IEBGENER`** を含むバッチジョブ（JCL）の実行デモです。

COBOL プログラムで生成された取引データセットに対して、ユーティリティによる複製（バックアップ）、条件抽出（`OUTFIL INCLUDE`）、数値フィールドによる並べ替え（`SORT FIELDS`）を一連のジョブステップとして実行し、後続の COBOL 集計プログラムで整形出力します。

---

## 構成ファイル

* [`GEN-TRANS.cbl`](file:///d:/workspace/cobol-on-java/demo/004/GEN-TRANS.cbl) : 取引データ生成 COBOL プログラム。`ASSIGN TO TXOUTDD` により 5 件の取引レコードを EBCDIC データセット (`RAWTRANS.DAT`) へ出力します。
* [`PRT-REPORT.cbl`](file:///d:/workspace/cobol-on-java/demo/004/PRT-REPORT.cbl) : 集計レポート出力 COBOL プログラム。ソート・抽出後のデータセット (`FILTERED.DAT`) を読み込み、件数と合計金額を SYSOUT へ印字します。
* [`UTILJOB.jcl`](file:///d:/workspace/cobol-on-java/demo/004/UTILJOB.jcl) : バッチジョブを定義した JCL ファイル。
* [`run_demo.bat`](file:///d:/workspace/cobol-on-java/demo/004/run_demo.bat) : Windows 環境用一括ビルド・実行バッチファイル。

---

## JCL ジョブ (`UTILJOB.jcl`) の解説

```jcl
//UTILJOB  JOB  (ACCT),'UTILITY DEMO',CLASS=A
//*
//* STEP 1: 取引テストデータを生成する
//GEN      EXEC PGM=GEN_TRANS
//TXOUTDD  DD   DSN=RAWTRANS.DAT,DISP=(NEW,CATLG)
//SYSPRINT DD   SYSOUT=*
//*
//* STEP 2: IEBGENER で元データセットの複製（バックアップ）を作成
//BACKUP   EXEC PGM=IEBGENER
//SYSUT1   DD   DSN=RAWTRANS.DAT,DISP=SHR
//SYSUT2   DD   DSN=BAKTRANS.DAT,DISP=(NEW,CATLG)
//SYSPRINT DD   SYSOUT=*
//*
//* STEP 3: DFSORT で「東部(E1)かつ有効(A)」のレコードのみ抽出し、金額降順で整列
//SORTSTEP EXEC PGM=SORT
//SORTIN   DD   DSN=RAWTRANS.DAT,DISP=SHR
//SYSOUT   DD   SYSOUT=*
//SYSIN    DD   *
  SORT FIELDS=(7,5,ZD,D)
  OUTFIL FNAMES=SORTOUT,INCLUDE=(5,2,CH,EQ,C'E1',AND,12,1,CH,EQ,C'A')
/*
//SORTOUT  DD   DSN=FILTERED.DAT,DISP=(NEW,CATLG)
//*
//* STEP 4: 抽出・ソートされた結果データセットを読み込んで集計レポートを出力
//REPORT   EXEC PGM=PRT_REPORT
//RPTINDD  DD   DSN=FILTERED.DAT,DISP=SHR
//SYSPRINT DD   SYSOUT=*
```

### ステップごとの解説
1. **`STEP 1 (GEN)`**: COBOL プログラム `GEN_TRANS` を実行。`RAWTRANS.DAT`（EBCDIC 形式）が作成されます。
2. **`STEP 2 (BACKUP)`**: ホスト互換ユーティリティ `IEBGENER` を実行。`SYSUT1` の内容をそのまま `SYSUT2` (`BAKTRANS.DAT`) へバイト単位で完全複製します。
3. **`STEP 3 (SORTSTEP)`**: ホスト互換ソートユーティリティ `SORT` (`DFSORT`) を実行。
   - `SYSIN` 内の `SORT FIELDS=(7,5,ZD,D)` により、7桁目から5桁のゾーン10進数（`ZD`）金額フィールドを降順（`D`）に並べ替えます。
   - `OUTFIL FNAMES=SORTOUT,INCLUDE=(5,2,CH,EQ,C'E1',AND,12,1,CH,EQ,C'A')` により、地域が `E1` かつステータスが `A` のレコードのみを抽出し、`FILTERED.DAT` へ書き出します。
4. **`STEP 4 (REPORT)`**: COBOL プログラム `PRT_REPORT` を実行。ソート・フィルタ済みのレコードのみを順次読み込み、レポートを出力します。

---

## 実行方法 (Windows)

```cmd
demo\004\run_demo.bat
```

### 実行結果出力例
```text
GEN-TRANS: 5 TRANSACTIONS GENERATED.
IEB147I 5 RECORDS COPIED
ICE143I 0 BLOCKSET SORT TECHNIQUE SELECTED
ICE054I 0 RECORDS - IN: 5, OUT: 5
ICE224I 0 RECORDS WRITTEN TO SORTOUT: 2
ICE052I 0 END OF DFSORT
======================================
  SORTED & FILTERED REPORT (E1 & A)   
======================================
ID   REGION  AMOUNT  STATUS
---- ------  ------  ------
T005   E1    02100   A
T001   E1    01500   A
--------------------------------------
TOTAL RECORDS:     2
TOTAL AMOUNT :       3,600
======================================
UTILJOB.GEN ENDED - RC=0
UTILJOB.BACKUP ENDED - RC=0
UTILJOB.SORTSTEP ENDED - RC=0
UTILJOB.REPORT ENDED - RC=0
```
