# cobol-on-java 利用ガイド

本書は、`cobol-on-java` を用いて COBOL ソースコードを JVM バイトコード（クラスファイル）へ翻訳（コンパイル）し、実行する手順を解説する利用ガイドです。
特に、**複数の COBOL プログラムが互いを `CALL` で呼び出す構成**の翻訳および実行フローについて、具体的なコード例とコマンドライン手順を交えて詳細に説明します。

---

## 1. 前提条件と環境構築

`cobol-on-java` のビルドおよび実行には以下が必要です。

* **JDK 21 以上** (`java -version` で確認)
* **Apache Maven 3.9 以上** (`mvn -version` で確認)

### リポジトリのビルド

まずプロジェクトルートで Maven ビルドを実行し、コンパイラおよびランタイムのモジュールを構築します。

```bash
mvn clean package -DskipTests
```

ビルドが完了すると、各モジュールの `target/` ディレクトリに JAR ファイルが作成されます。
* コンパイラ: `cobol-compiler/target/cobol-compiler-0.1.0-SNAPSHOT.jar`
* ランタイム: `cobol-runtime/target/cobol-runtime-0.1.0-SNAPSHOT.jar`
* 依存ライブラリ（ASM, ANTLR4等）

---

## 2. コンパイラ (`Main`) の基本コマンド仕様

> 資産一式をプロジェクトとして作るなら、`Main` を直接呼ぶ代わりに `cobol-maven-plugin` を使えます。
> 標準の置き場 (`src/main/cobol`、`src/main/copybook`、`src/main/pli`、`src/main/jcl` など) に置けば
> `mvn package` で翻訳と JCL の検査まで行います。[設計 91](design/91-maven-build.md) と
> [デモ #010](../demo/010/README.md) を参照してください。翻訳の手順は `Main` と同じものです。

コンパイラの起動エントリポイントは [`dev.cobolonjava.compiler.Main`](file:///d:/workspace/cobol-on-java/cobol-compiler/src/main/java/dev/cobolonjava/compiler/Main.java) です。

### コマンド構文

```bash
java -cp <クラスパス> dev.cobolonjava.compiler.Main [オプション] <ソースファイル1> [<ソースファイル2> ...]
```

### 主なオプション一覧

| オプション | 説明 | 既定値 |
| --- | --- | --- |
| `-d <dir>` | 生成された `.class` ファイルの出力先ディレクトリを指定します。 | `.` (カレントディレクトリ) |
| `-I <dir>` | `COPY` 文（コピー句）を探索するディレクトリを指定します。**何度でも指定でき**、書いた順に探して先に見つかったものを使います（ホストの連結ライブラリと同じ規則）。同じディレクトリに BMS の原文 `<名前>.bms` があれば、`COPY <名前>.` は記号マップの写し句をその場で生成して取り込みます。 | 指定なし |
| `--free` | 自由形式（Free Format）の COBOL ソースとして解析します。 | 固定形式 (Fixed Format) |
| `-q <options>` | 翻訳時オプションを指定します（例: `-q SSRANGE` で添字・範囲検査を有効化）。 | 指定なし |

> **Note**: COBOL ソース内の `CBL` / `PROCESS` 行に書かれた指定は、コマンドラインの `-q` オプションを上書きして適用されます。

### システム写し句の自動解決

`-I` で指定したディレクトリをすべて探したあと、最後に以下の「製品が配る写し句」を引きます。
資産が同名の写し句を自前で持っていれば、そちらが優先されます。

| 写し句 | 提供元 | 内容 |
| --- | --- | --- |
| `DFHAID` ほか | CICS | AID キー（`DFHENTER` / `DFHPF3` …）と属性値 (`DFHBMSCA`) |
| `SQLCA` | Db2 precompiler | `EXEC SQL INCLUDE SQLCA END-EXEC.` が展開する 136 byte の SQLCA |
| `CEEIGZCT` | Language Environment | feedback code の記号名（`CEE000` のみ） |

そのため、CICS / BMS / Db2 を使う資産も次のように翻訳できます。

```bash
java -cp <クラスパス> dev.cobolonjava.compiler.Main -d out -I maps -I copybooks TODOAPP.cbl
```


---

## 3. 単一プログラムの翻訳と実行の流れ

シンプルな単一の COBOL プログラムを翻訳して実行する基本フローです。

### Step 1: COBOL ソースコードの用意 (`HELLO.cbl`)

```cobol
       IDENTIFICATION DIVISION.
       PROGRAM-ID. HELLO.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-MESSAGE  PIC X(20) VALUE 'Hello, COBOL on Java'.
       PROCEDURE DIVISION.
       MAIN-START.
           DISPLAY WS-MESSAGE
           STOP RUN.
```

### Step 2: 翻訳（コンパイル）の実行

出力先ディレクトリ `out` を指定してコンパイルします。

```bash
java -cp "cobol-compiler/target/*;cobol-runtime/target/*" dev.cobolonjava.compiler.Main -d out HELLO.cbl
```
*(※ Linux/macOS の場合はパス区切り文字を `:` に変更してください)*

コンパイルが成功すると、`out/cobol/generated/HELLO.class` が生成されます。

### Step 3: 実行

生成されたクラスは標準の `main` メソッドを持っているため、そのまま `java` コマンドで起動可能です。
実行時には生成クラスの出力先 (`out`) と `cobol-runtime` のクラスパスが必要です。

```bash
java -cp "out;cobol-runtime/target/*" cobol.generated.HELLO
```

**実行結果:**
```text
Hello, COBOL on Java
```

---

## 4. 複数 COBOL プログラムの変換と実行の流れ

実務で一般的な、主プログラム（親）から `CALL` 文で副プログラム（子）を呼び出し、データ（`LINKAGE SECTION`）を受け渡す複数プログラムの変換・実行フローを解説します。

### 4.1 複数プログラム連携の仕組み

1. **クラス名のマッピング規約**:
   `cobol-on-java` では、`PROGRAM-ID. <名前>.` で宣言されたプログラム名を [`ProgramSupport.classNameOf`](file:///d:/workspace/cobol-on-java/cobol-runtime/src/main/java/dev/cobolonjava/runtime/program/ProgramSupport.java) に従い Java クラス名に変換します。
   * 大文字に自動変換され、ハイフン (`-`) は下線 (`_`) に置換されます。
   * すべて `cobol.generated.<プログラム名>` パッケージ配下に配置されます。
   * 例: `PROGRAM-ID. CALC-TAX.` $\rightarrow$ `cobol.generated.CALC_TAX`

2. **`CALL` の動的解決と状態保持**:
   * 主プログラムが `CALL 'CALC-TAX' USING ...` を実行すると、ランタイムの [`ProgramContext`](file:///d:/workspace/cobol-on-java/cobol-runtime/src/main/java/dev/cobolonjava/runtime/program/ProgramContext.java) が Java のリフレクション/ClassLoader を使用して `cobol.generated.CALC_TAX` クラスを動的にロード・インスタンス化します。
   * **`WORKING-STORAGE` の状態維持**: インスタンス化された副プログラムは `ProgramContext` 内に保持され、複数回呼び出されても `WORKING-STORAGE` の変数はリセットされず維持されます（`CANCEL` 文実行時に解放）。
   * **`LINKAGE SECTION` のゼロコピー参照**: 呼び出し時に渡された引数データは、メモリコピーを行わずバイト配列のビュー（[`DataView`](file:///d:/workspace/cobol-on-java/cobol-runtime/src/main/java/dev/cobolonjava/runtime/storage/DataView.java)）を共有して引渡しされます。

---

### 4.2 具体的な複数プログラムのコード例

#### ① 副プログラム (`CALC-TAX.cbl`)
主プログラムから金額を受け取り、消費税率10%を計算して結果を返却します。

```cobol
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CALC-TAX.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-TAX-RATE    PIC 9(2)V99 VALUE 0.10.
       LINKAGE SECTION.
       01 LNK-AMOUNT     PIC 9(6) COMP-3.
       01 LNK-TAX        PIC 9(6) COMP-3.
       PROCEDURE DIVISION USING LNK-AMOUNT LNK-TAX.
       MAIN-LOGIC.
           COMPUTE LNK-TAX = LNK-AMOUNT * WS-TAX-RATE
           GOBACK.
```

#### ② 主プログラム (`MAIN-JOB.cbl`)
金額を設定して `CALC-TAX` を呼び出し、計算された税額を表示します。

```cobol
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN-JOB.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-AMOUNT      PIC 9(6) COMP-3 VALUE 1000.
       01 WS-TAX         PIC 9(6) COMP-3 VALUE 0.
       01 WS-DISP-AMT    PIC ZZZ,ZZ9.
       01 WS-DISP-TAX    PIC ZZZ,ZZ9.
       PROCEDURE DIVISION.
       MAIN-START.
           MOVE WS-AMOUNT TO WS-DISP-AMT
           DISPLAY 'INPUT AMOUNT : ' WS-DISP-AMT
           
           -- 副プログラム CALL-TAX の呼び出し
           CALL 'CALC-TAX' USING WS-AMOUNT WS-TAX
           
           MOVE WS-TAX TO WS-DISP-TAX
           DISPLAY 'CALCULATED TAX: ' WS-DISP-TAX
           STOP RUN.
```

---

### 4.3 複数の変換・実行手順

複数プログラムの変換・実行には、**「一括コンパイル」** と **「個別コンパイル」** の2つの方法があります。

#### 方法 A: 一括コンパイル（推奨）

すべての COBOL ソースファイルを指定し、同一の出力ディレクトリへ一括翻訳します。

```bash
java -cp "cobol-compiler/target/*;cobol-runtime/target/*" dev.cobolonjava.compiler.Main -d bin MAIN-JOB.cbl CALC-TAX.cbl
```

**コンパイル結果構造 (`bin/` ディレクトリ内):**
```text
bin/
└── cobol/
    └── generated/
        ├── MAIN_JOB.class
        └── CALC_TAX.class
```

#### 方法 B: 個別コンパイル

モジュールごとに個別にコンパイルする場合も、出力先ディレクトリ (`-d bin`) を共通に指定することで、実行時にクラスローダーが相互参照可能になります。

```bash
# 1. 副プログラムのコンパイル
java -cp "cobol-compiler/target/*;cobol-runtime/target/*" dev.cobolonjava.compiler.Main -d bin CALC-TAX.cbl

# 2. 主プログラムのコンパイル
java -cp "cobol-compiler/target/*;cobol-runtime/target/*" dev.cobolonjava.compiler.Main -d bin MAIN-JOB.cbl
```

---

### 4.4 主プログラムの実行

主プログラム `MAIN-JOB` のクラス名を指定して実行します。

```bash
java -cp "bin;cobol-runtime/target/*" cobol.generated.MAIN_JOB
```

**実行フローの詳細動作:**
1. JVM が `cobol.generated.MAIN_JOB.main()` を実行開始。
2. `MAIN_JOB` 内で `CALL 'CALC-TAX'` のバイトコード命令が実行される。
3. ランタイムの `ProgramContext` がクラスパス上から `cobol.generated.CALC_TAX` を自動検索・ロード。
4. 引数 `WS-AMOUNT` および `WS-TAX` のメモリ領域への参照を保持したまま `CALC_TAX` の処理が呼び出される。
5. `CALC_TAX` 内の `COMPUTE` により `WS-TAX` の記憶域が更新され、制御が `MAIN_JOB` に復帰。
6. `MAIN_JOB` が計算結果画面を出力して正常終了。

**実行結果ターミナル出力:**
```text
INPUT AMOUNT :    1,000
CALCULATED TAX:      100
```

---

## 5. コピー句 (`COPY`) を含むプログラムの変換

共通データ構造（登録集 / コピー句）を使用している場合は、`-I` オプションでコピー句ディレクトリを指定します。

### 構成例

```text
src/
├── copybooks/
│   └── CUSTREC.cpy    (顧客レコード定義)
├── MAINPROG.cbl
└── SUBPROG.cbl
```

### コンパイルコマンド

```bash
java -cp "cobol-compiler/target/*;cobol-runtime/target/*" dev.cobolonjava.compiler.Main -d bin -I src/copybooks MAINPROG.cbl SUBPROG.cbl
```

`COPY CUSTREC.` や `COPY CUSTREC REPLACING ==:TAG:== BY ==WS-CUST==.` などのプリプロセッサ展開が自動的に行われた上でコンパイルされます。

---

## 6. ファイル入出力（順編成・固定長レコード）の扱い

順編成データセット（ファイル）への書き込みおよび読み込みの基本フローです。

### 構成のポイント

* **`SELECT ... ASSIGN TO DDNAME`**: 指定した DD 名は、実行時に [`DataSetCatalog`](file:///d:/workspace/cobol-on-java/cobol-runtime/src/main/java/dev/cobolonjava/runtime/file/DataSetCatalog.java) によりカレントディレクトリの同名ファイル（`./DDNAME`）と自動的に対応付けられます。
* **EBCDIC 生バイト格納**: データセットの中身は IBM ホストと同様に EBCDIC（IBM-1047）生バイトとして書き出されます。
* **属性サイドカーファイル (`DDNAME.meta`)**: レコード様式（`recfm=F`）、レコード長（`lrecl`）、文字コードが自動でメタデータファイルとして記録され、再読込時に検証されます。

---

## 7. すぐに実行できるデモ環境

本リポジトリには、Windows 環境ですぐに実行可能なデモ一式が配置されています。
各デモの詳細および実行方法については [**`demo/guide.md` (デモシナリオガイド)**](file:///d:/workspace/cobol-on-java/demo/guide.md) を参照してください。

* [**`demo/001/` (複数プログラム CALL 連携デモ)**](file:///d:/workspace/cobol-on-java/demo/001/README.md)
  * 主プログラムから副プログラムへの `CALL ... USING` および `LINKAGE SECTION` による計算処理。
  * 実行用バッチ: [`demo/001/run_demo.bat`](file:///d:/workspace/cobol-on-java/demo/001/run_demo.bat)
* [**`demo/002/` (ファイル入出力・順編成データセットデモ)**](file:///d:/workspace/cobol-on-java/demo/002/README.md)
  * 顧客データファイル (`CUSTFILE`) へのレコード書き込み (`WRITE-DATA.cbl`) と読み込み・集計表示 (`READ-DATA.cbl`)。
  * 実行用バッチ: [`demo/002/run_demo.bat`](file:///d:/workspace/cobol-on-java/demo/002/run_demo.bat)
* [**`demo/003/` (JCL バッチジョブ実行デモ)**](file:///d:/workspace/cobol-on-java/demo/003/README.md)
  * メインフレーム互換 JCL (`SALESJOB.jcl`) による複数ステップのバッチ制御、`IEFBR14` ユーティリティ、`PARM` 渡し、DD 動的割り当て、SYSOUT スプール出力、`IF-THEN` / `COND` による条件分岐。
  * 実行用バッチ: [`demo/003/run_demo.bat`](file:///d:/workspace/cobol-on-java/demo/003/run_demo.bat)
* [**`demo/004/` (JCL ユーティリティ連携デモ)**](file:///d:/workspace/cobol-on-java/demo/004/README.md)
  * メインフレーム標準ユーティリティ (`SORT`/`DFSORT` でのフィールド並べ替え・`OUTFIL` 抽出、`IEBGENER` でのデータセット複製) の JCL バッチ実行。
  * 実行用バッチ: [`demo/004/run_demo.bat`](file:///d:/workspace/cobol-on-java/demo/004/run_demo.bat)
* [**`demo/005/` (Java ↔ COBOL 双方向相互運用デモ)**](file:///d:/workspace/cobol-on-java/demo/005/README.md)
  * Java アプリケーションから `CobolSession` を介した COBOL 呼出、および COBOL `CALL` から Java サービス (`JavaCallable`) へのコールバック。
  * 実行用バッチ: [`demo/005/run_demo.bat`](file:///d:/workspace/cobol-on-java/demo/005/run_demo.bat)
* [**`demo/006/` (JUnit 5 単体テスト & Mocking デモ)**](file:///d:/workspace/cobol-on-java/demo/006/README.md)
  * `cobol-junit` (`@RegisterExtension CobolExtension`) による COBOL 単体テスト自動化、外部サブルーチンの Java モック差し替え、セクション単位の Spy 検証。
  * 実行用バッチ: [`demo/006/run_demo.bat`](file:///d:/workspace/cobol-on-java/demo/006/run_demo.bat)
* [**`demo/007/` (CICS トランザクション & 疑似会話制御デモ)**](file:///d:/workspace/cobol-on-java/demo/007/README.md)
  * `cobol-cics` による `EXEC CICS LINK` / `XCTL` / `RETURN TRANSID(...) COMMAREA(...)` 制御、EIB 状態管理、疑似会話トランザクション境界。
  * 実行用バッチ: [`demo/007/run_demo.bat`](file:///d:/workspace/cobol-on-java/demo/007/run_demo.bat)
* [**`demo/008/` (Spring Boot & Db2 SQL 連携デモ)**](file:///d:/workspace/cobol-on-java/demo/008/README.md)
  * Spring Boot 4.x / Spring Framework 7 トランザクション管理下での Db2 互換 SQL 実行（ホスト変数バインド）と COBOL 連携、コミット/ロールバック制御。
  * 実行用バッチ: [`demo/008/run_demo.bat`](file:///d:/workspace/cobol-on-java/demo/008/run_demo.bat)

---

## 8. まとめ

* **単一/複数コンパイル**: `dev.cobolonjava.compiler.Main` に `-d <出力ディレクトリ>` を指定して実行します。
* **`CALL` 連動**: プログラム名は `cobol.generated.<PROGRAM_NAME>` クラス名に自動変換され、実行時にクラスパス上から動的に解決されます。
* **ファイル入出力**: `SELECT ... ASSIGN TO <DD名>` により EBCDIC 生バイトファイルと `.meta` サイドカーが生成・管理されます。
* **実行環境**: 生成されたクラスの出力フォルダと `cobol-runtime` を `-cp` (クラスパス) に追加し、主プログラムのクラスを起動します。
