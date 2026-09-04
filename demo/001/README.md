# デモ #001: 複数 COBOL プログラム連携デモ (CALL / LINKAGE SECTION)

本デモは、`docs/guide.md` に記載されている **主プログラム (`MAIN-JOB.cbl`)** から **副プログラム (`CALC-TAX.cbl`)** を `CALL` 文で呼び出す複数モジュール連携の動作デモです。

---

## 構成ファイル

* [`MAIN-JOB.cbl`](file:///d:/workspace/cobol-on-java/demo/001/MAIN-JOB.cbl) : 主プログラム。金額をセットし、`CALC-TAX` を呼び出して税額を表示。
* [`CALC-TAX.cbl`](file:///d:/workspace/cobol-on-java/demo/001/CALC-TAX.cbl) : 副プログラム。`LINKAGE SECTION` で受け取った金額に 10% の消費税を計算して返却。
* [`run_demo.bat`](file:///d:/workspace/cobol-on-java/demo/001/run_demo.bat) : Windows 環境用一括ビルド・実行バッチファイル。

---

## 実行方法 (Windows)

コマンドプロンプトまたは PowerShell で本ディレクトリから `run_demo.bat` を実行するか、エクスプローラーからダブルクリックして実行します。

```cmd
demo\001\run_demo.bat
```

### 実行結果イメージ

```text
===================================================
 cobol-on-java DEMO #001 (Multi-Program Linkage)
===================================================

[1/3] JavaおよびMavenの動作環境をチェックしています...
[2/3] COBOLソースファイル (MAIN-JOB.cbl, CALC-TAX.cbl) を翻訳中...

[3/3] 翻訳された主プログラム (cobol.generated.MAIN_JOB) を実行します...
---------------------------------------------------
INPUT AMOUNT :   1,000
CALCULATED TAX:     100
---------------------------------------------------
[SUCCESS] デモプログラムの実行が完了しました。
```
