# デモ #002: ファイル入出力デモ (順編成ファイルの書き込みと読み込み)

本デモは、`cobol-on-java` における **ファイル入出力（順編成・固定長レコード: RECFM=F）** の動作デモです。
COBOL プログラムからデータセット（ファイル）へレコードを書き出し、別プログラムで読み込んで集計表示するバッチ処理の流れを体験できます。

---

## 構成ファイル

* [`WRITE-DATA.cbl`](file:///d:/workspace/cobol-on-java/demo/002/WRITE-DATA.cbl) : 書込みプログラム。`CUSTFILE` に 3 件の顧客データ（ID、氏名、売上）を書き込みます。
* [`READ-DATA.cbl`](file:///d:/workspace/cobol-on-java/demo/002/READ-DATA.cbl) : 読込みプログラム。`CUSTFILE` を末尾（`AT END`）まで順次読み込み、整形表示および合計売上金額を集計します。
* [`run_demo.bat`](file:///d:/workspace/cobol-on-java/demo/002/run_demo.bat) : Windows 環境用一括ビルド・実行バッチファイル。

---

## ファイル入出力の特徴（IBM ホスト互換仕様）

1. **生バイトの EBCDIC 格納 (FR-110)**:
   * 生成される `CUSTFILE` は UTF-8 ではなく、ホスト環境と同じ EBCDIC（IBM-1047）生バイト列として保存されます。
2. **属性サイドカーファイル (`.meta`)**:
   * メインフレームの VTOC / カタログ情報に相当する属性メタデータ（レコード様式 `recfm=F`、レコード長 `lrecl=20`、文字コード `codepage=IBM-1047`）が `CUSTFILE.meta` として自動生成・管理されます。

---

## 実行方法 (Windows)

コマンドプロンプトまたは PowerShell で本ディレクトリから `run_demo.bat` を実行するか、エクスプローラーからダブルクリックして実行します。

```cmd
demo\002\run_demo.bat
```

### 実行結果イメージ

```text
===================================================
 cobol-on-java DEMO #002 (File I/O: Write & Read)
===================================================

[1/4] Checking Environment...
[2/4] Compiling COBOL files (WRITE-DATA.cbl, READ-DATA.cbl)...

[3/4] Step 1: Writing records to file (cobol.generated.WRITE_DATA)...
---------------------------------------------------
OPEN OUTPUT STATUS: 00
CLOSE STATUS: 00
FINISHED WRITING 3 RECORDS TO CUSTFILE.

[4/4] Step 2: Reading records from file (cobol.generated.READ_DATA)...
---------------------------------------------------
OPEN INPUT STATUS: 00
--- CUSTOMER RECORDS ---
ID=1001 NAME=ALICE      SALES=    50,000
ID=1002 NAME=BOB        SALES=    75,000
ID=1003 NAME=CHARLIE    SALES=   120,000
------------------------
TOTAL RECORDS: 003
TOTAL SALES  :    245,000
---------------------------------------------------
[SUCCESS] Demo #002 completed successfully.
```
