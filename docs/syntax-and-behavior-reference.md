# サポート構文・振る舞いリファレンス (Syntax and Behavioral Reference)

本書は、`cobol-on-java` がサポートする COBOL 構文の一覧、各構文の仕様、翻訳時および実行時の具体的な「振る舞い（セマンティクス）」をまとめたリファレンスドキュメントです。

本処理系は、単に構文を受理するだけでなく、**IBM メインフレーム (Enterprise COBOL for z/OS 6.x + Language Environment + z/Architecture) の外部挙動を忠実に再現する**ことを設計方針としています。

---

## 目次

1. [ソースコード形式・字句規則・プリプロセッサ](#1-ソースコード形式字句規則プリプロセッサ)
2. [見出し部 (IDENTIFICATION DIVISION)](#2-見出し部-identification-division)
3. [環境部 (ENVIRONMENT DIVISION)](#3-環境部-environment-division)
4. [データ部 (DATA DIVISION)](#4-データ部-data-division)
   - 4.1 節の構成
   - 4.2 レベル番号とデータ階層
   - 4.3 主要句とセマンティクス (`PICTURE`, `USAGE`, `REDEFINES`, `OCCURS` 等)
5. [手続き部 (PROCEDURE DIVISION) - 文と制御](#5-手続き部-procedure-division---文と制御)
   - 5.1 基本代入・転記 (`MOVE`, `INITIALIZE`, `SET`)
   - 5.2 算術演算文 (`ADD`, `SUBTRACT`, `MULTIPLY`, `DIVIDE`, `COMPUTE`)
   - 5.3 制御構文 (`IF`, `EVALUATE`, `PERFORM`, `GO TO`, `ALTER`, `CONTINUE`, `NEXT SENTENCE`)
   - 5.4 プログラム制御・終了 (`CALL`, `CANCEL`, `EXIT PROGRAM`, `GOBACK`, `STOP RUN`)
   - 5.5 文字列操作文 (`STRING`, `UNSTRING`, `INSPECT`)
   - 5.6 表操作文 (`SEARCH`, `SEARCH ALL`)
   - 5.7 入出力・画面入出力文 (`ACCEPT`, `DISPLAY`)
   - 5.8 ファイル入出力文 (`OPEN`, `CLOSE`, `READ`, `WRITE`, `REWRITE`, `DELETE`, `START`)
   - 5.9 整列・併合文 (`SORT`, `MERGE`, `RELEASE`, `RETURN`)
   - 5.10 報告書作成機能 (`INITIATE`, `GENERATE`, `TERMINATE`)
   - 5.11 サブシステム連携文 (`EXEC CICS`, `EXEC SQL`)
6. [組み込み関数 (INTRINSIC FUNCTIONS)](#6-組み込み関数-intrinsic-functions)
7. [条件式と照合順序 (CONDITIONS & COLLATING SEQUENCE)](#7-条件式と照合順序-conditions--collating-sequence)
8. [デバッグ・宣言節 (DECLARATIVES & DEBUGGING)](#8-デバッグ宣言節-declaratives--debugging)

---

## 1. ソースコード形式・字句規則・プリプロセッサ

### 1.1 ソース形式 (Source Format)
- **固定形式 (Fixed Format, 既定)**:
  - 1〜6 桁: 一連番号領域 (シーケンス番号)。読み飛ばします。
  - 7 桁目: 標識領域。
    - `*` または `/`: 行全体を注釈（コメント）として扱います。
    - `-`: 文字列定数の継続行。B 領域（12〜72 桁）の引用符から文字列を連結します。
    - `D` または `d`: デバッグ行。コンパイル時オプション `WITH DEBUGGING MODE` 有効時のみ通常の COBOL 文としてコンパイルし、無効時はコメントとして扱います。
  - 8〜11 桁: A 領域（部見出し、節見出し、段落名、01/77 レベル記述等）。
  - 12〜72 桁: B 領域（一般文、文の継続、従属データ項目等）。
  - 73〜80 桁: 見出し領域（識別番号等）。無視して切り捨てます。
- **自由形式 (Free Format, `--free` 指定時)**:
  - カラム制限なし。`*>` 以降を行末コメントとして扱います。

### 1.2 プリプロセッサ指示文・プロセス文
- **`CBL` / `PROCESS`**:
  - ソース冒頭に記述し、`-q` オプション相当（例: `SSRANGE`, `NOSSRANGE`）をソース単位で指定・上書きできます。
- **`COPY` 文**:
  - 構文: `COPY コピー句名 [IN/OF ライブラリ名] [REPLACING ==前== BY ==後==] [SUPPRESS]`
  - 振る舞い: `-I` で指定された探索パスからファイルを読み込み、字句解析前にテキスト展開します。擬似テキスト（`==...==`）や識別子の置換を厳密に実行します。
- **`REPLACE` 文**:
  - 構文: `REPLACE ==前== BY ==後==` / `REPLACE OFF`
  - 振る舞い: 指定範囲以降のソーステキスト全体に対するマクロ置換を行います。
- **条件付きコンパイル指示文**:
  - `>>DEFINE`, `>>IF`, `>>ELSE`, `>>END-IF`, `>>EVALUATE` をサポートし、コンパイル対象コードの動的切り替えを行います。

---

## 2. 見出し部 (IDENTIFICATION DIVISION)

### サポート構文
```cobol
IDENTIFICATION DIVISION. (または ID DIVISION.)
PROGRAM-ID. プログラム名 [IS [COMMON | INITIAL | RECURSIVE] PROGRAM].
[AUTHOR. ...]
[INSTALLATION. ...]
[DATE-WRITTEN. ...]
[DATE-COMPILED. ...]
[SECURITY. ...]
```

### 振る舞い
- **`PROGRAM-ID`**:
  - 生成される JVM クラス名（既定パッケージ `cobol.generated.<プログラム名>`）およびプログラム識別子となります。
  - `INITIAL` 属性: 他のプログラムから `CALL` されて `GOBACK` / `EXIT PROGRAM` した際、次回の呼び出し時に `WORKING-STORAGE SECTION` が自動的にコンパイル時の初期値へ再初期化されます。
- **注記段落 (`AUTHOR`, `INSTALLATION` 等)**:
  - 構文上受理され、安全に読み飛ばされます（覚え書きとして無視）。
- **同一ソース内の複数プログラム (`END PROGRAM`)**:
  - `END PROGRAM <プログラム名>.` で区切ることで、単一ソースファイル内に複数の COBOL プログラムを記述可能。プログラムごとに独立したクラスファイルを生成します。

---

## 3. 環境部 (ENVIRONMENT DIVISION)

### サポート構文
```cobol
ENVIRONMENT DIVISION.
[CONFIGURATION SECTION.
 [SOURCE-COMPUTER. ...]
 [OBJECT-COMPUTER. [PROGRAM COLLATING SEQUENCE IS 照合名].]
 [SPECIAL-NAMES.
    [ALPHABET 照合名 IS (STANDARD-1 | STANDARD-2 | NATIVE | EBCDIC | リテラル...)]
    [CLASS 級名 IS リテラル [THRU リテラル]...]
    [CURRENCY SIGN IS 文字列]
    [DECIMAL-POINT IS COMMA]
    [SYMBOLIC CHARACTERS 文字名 ARE 整数... [IN 照合名]]
    [機能名 IS 呼び名]
    [UPSI-0 THRU UPSI-7 [IS 呼び名] [ON STATUS IS 条件名] [OFF STATUS IS 条件名]]
 ]]
[INPUT-OUTPUT SECTION.
 FILE-CONTROL.
    SELECT [OPTIONAL] ファイル名 ASSIGN TO 外部名/DD名
    [ORGANIZATION IS (SEQUENTIAL | LINE SEQUENTIAL | INDEXED | RELATIVE)]
    [ACCESS MODE IS (SEQUENTIAL | RANDOM | DYNAMIC)]
    [RECORD KEY IS 主キー項目名]
    [ALTERNATE RECORD KEY IS 副キー項目名 [WITH DUPLICATES]]
    [RELATIVE KEY IS 相対キー項目名]
    [FILE STATUS IS 状態コード項目名].
 [I-O-CONTROL.
    [SAME [RECORD | SORT-MERGE] AREA FOR ファイル名1 ファイル名2...]
 ]]
```

### 振る舞い
- **`PROGRAM COLLATING SEQUENCE` & `ALPHABET`**:
  - プログラム全体の文字比較、`SORT`、`FUNCTION CHAR` / `ORD`、`HIGH-VALUE` / `LOW-VALUE` の基準となる照合順序（256 バイトのマッピング表）を差し替えます。
- **`SPECIAL-NAMES` の機能名結び付け**:
  - `SYSOUT`, `SYSPRINT`, `SYSLIST`, `CONSOLE` 等の機能名に呼び名を割り当て、`DISPLAY ... UPON 呼び名` や `ACCEPT ... FROM 呼び名` の入出力先（標準出力、標準エラー、環境変数値、JCL SYSIN 等）を制御します。
  - `UPSI-0`〜`UPSI-7` (User Programmable Status Indicator): 外部スイッチの状態フラグ（ON/OFF）を条件名として参照可能にします。
- **`FILE-CONTROL`**:
  - 順編成 (`SEQUENTIAL`)、行順編成 (`LINE SEQUENTIAL`)、索引編成 (`INDEXED`)、相対編成 (`RELATIVE`) の編成およびアクセスモードを設定します。
  - `SAME RECORD AREA`: 指定された複数ファイルのレコード記憶域領域を同一アドレス（同一バッファ）として割り当てます。

---

## 4. データ部 (DATA DIVISION)

### 4.1 節の構成
- **`FILE SECTION`**:
  - `FD` (ファイル記述項) および `SD` (整列作業ファイル記述項) を記述。
  - レコード定義の `01` レベル項目は、ファイル読み書き時のレコードバッファとして動作します。
- **`WORKING-STORAGE SECTION`**:
  - プログラム実行中の作業用記憶域。プログラムロード時または `INITIAL` 指定時に初期化されます。
- **`LOCAL-STORAGE SECTION`**:
  - プログラムの呼び出しごとにスタック上に確保される局所記憶域（再帰呼び出し対応）。
- **`LINKAGE SECTION`**:
  - 親プログラム（呼び出し元）から渡された引数、または CICS の `DFHCOMMAREA` へのポインタ参照として記憶域を割り当てます（実体は呼び出し元のアドレスを参照）。
- **`REPORT SECTION`**:
  - 報告書作成機能 (`RD`) 用の報告集団定義（[5.10 節](#510-報告書作成機能-initiate-generate-terminate) 参照）。

### 4.2 レベル番号と階層
- `01`, `02`〜`49`: 集団項目および基本項目。番号が若いほど上位、大きいほど下位の従属項目となります。
- `77`: 独立データ項目（他の項目に従属せず、下位項目も持たない）。
- `66`: `RENAMES` 句専用レベル（既存項目の範囲を別名で再定義）。
- `88`: 条件名（直前の項目が特定の値または範囲を満たすかを判定する論理フラグ）。

### 4.3 主要句とセマンティクス

| 句 | 構文例 | 振る舞い |
| --- | --- | --- |
| **`PICTURE` / `PIC`** | `PIC X(10)`, `PIC S9(7)V99 COMP-3` | 項目の型（英数字、英字、数字、数字編集）と桁数を厳密に定義。編集文字 (`Z`, `*`, `+`, `-`, `CR`, `DB`, `.`, `,`, `/`, `0`, `B`, `$`) のマスク変換規則はホストと完全一致。 |
| **`USAGE`** | `USAGE COMP-3`, `BINARY` 等 | メモリ上のバイナリ表現形式を決定（下表参照）。省略時は `DISPLAY`。 |
| **`VALUE`** | `VALUE 'ABC'`, `VALUE 123` | 項目の初期値を設定。88 レベルでは値または `THRU` による範囲指定が可能。 |
| **`REDEFINES`** | `05 B REDEFINES A` | 新たな記憶域を確保せず、対象項目と同じメモリ開始位置を別のデータ定義で共有。 |
| **`OCCURS`** | `OCCURS 10 TIMES [DEPENDING ON N]` | 配列・テーブルを定義。`DEPENDING ON` による可変長配列に対応。`INDEXED BY` で指標名を宣言。 |
| **`SIGN`** | `SIGN IS TRAILING SEPARATE` | ゾーン10進数の符号位置（LEADING/TRAILING）と独立バイト（SEPARATE）の有無を指定。 |
| **`JUSTIFIED`** | `JUSTIFIED RIGHT` | 英数字項目の転記時に右詰めで格納し、余白を左側空白で埋める。 |
| **`BLANK WHEN ZERO`**| `BLANK WHEN ZERO` | 数値の値がゼロの場合、記憶域を空白で埋める。 |
| **`SYNCHRONIZED`**| `SYNC [LEFT \| RIGHT]` | ホスト互換のワード境界アライメント（パディング）を適用。 |

#### USAGE の内部表現と振る舞い
- **`DISPLAY`**: EBCDIC ゾーン10進数または文字列（1 桁 1 バイト）。正の符号ニブルは `0xC`/`0xF`、負は `0xD`。
- **`COMPUTATIONAL-3` / `COMP-3` / `PACKED-DECIMAL`**: パック10進数。1 バイトに 2 桁を詰め、末尾ニブルを符号 (`0xC`/`0xD`/`0xF`) とします。偶数桁時は先頭にゼロニブルを補完。
- **`COMPUTATIONAL` / `COMP` / `BINARY` / `COMP-4`**: ビッグエンディアン 2進整数（2/4/8 バイト）。`TRUNC(STD/OPT/BIN)` オプションに応じた桁数トランケート処理を実施。
- **`COMPUTATIONAL-5` / `COMP-5`**: ネイティブ 2 進数。PICTURE 桁数に関わらず記憶域のフルビットレンジを使用。
- **`COMPUTATIONAL-1` / `COMP-1`**: 32 ビット短精度浮動小数点（IBM HFP 形式 / IEEE 形式の選択可能）。
- **`COMPUTATIONAL-2` / `COMP-2`**: 64 ビット倍精度浮動小数点（IBM HFP 形式 / IEEE 形式）。
  - 浮動小数点項目は、読むとき HFP の値を厳密な 10 進に直します。比較もこの値で行います。
  - 浮動小数点から固定小数点への転記と、浮動小数点を含む `COMPUTE` の固定小数点の受取側は、受取項目の最下位の桁で丸めます。浮動小数点項目へ入れるときは表せない桁を切り捨てます (暫定判断 P-018、P-127)。
  - `COMPUTE` で浮動小数点を含む式は `+`、`-`、`*` と符号反転だけを扱います。除算・べき乗、`ADD` 等の文、`ON SIZE ERROR`、数字編集項目への転記、`DISPLAY` は未対応です。
- **`INDEX`**: 4 バイト指標項目。テーブルのバイトオフセット値を保持。
- **`POINTER`**: アドレス参照項目。

---

## 5. 手続き部 (PROCEDURE DIVISION) - 文と制御

### 5.1 基本代入・転記 (`MOVE`, `INITIALIZE`, `SET`)

#### `MOVE` 文
```cobol
MOVE 送出側 TO 受取側1 [受取側2 ...]
MOVE CORRESPONDING 集団項目1 TO 集団項目2
```
- **振る舞い**:
  - 受取側の分類に応じて適切な型変換（数値の小数点アライメント、英数字の左右詰めと余白パディング、数字編集文字の展開、集団項目間の生バイト転記）を実行します。
  - 送出側と受取側のいずれかが集団項目の場合、編集や数値変換を行わず**純粋なバイト列転記**となります。
  - 符号付き数値から英数字への転記時、受取側が基本項目なら符号を破棄して絶対値の文字列表現とし、受取側が集団項目なら符号バイトをそのまま転記します。
  - `CORRESPONDING`: 両集団項目直下の同名基本項目同士を一対一で転記します。

#### `INITIALIZE` 文
```cobol
INITIALIZE 項目1 [項目2 ...] [WITH FILLER]
  [REPLACING (NUMERIC | ALPHANUMERIC | ...) DATA BY 値]
```
- **振る舞い**:
  - 指定された項目の配下にある基本項目に対し、数値項目にはゼロ、英数字項目には空白を初期値として一括格納します。
  - 既定では `FILLER` および `REDEFINES` された項目は初期化をスキップしますが、`WITH FILLER` 指定時は `FILLER` 項目も初期化対象となります。

#### `SET` 文
```cobol
SET 条件名 TO TRUE
SET 指標名1 [指標名2 ...] TO (指標名3 | 整数 | 項目)
SET 指標名1 [UP | DOWN] BY 整数
SET 呼び名 TO (ON | OFF)
```
- **振る舞い**:
  - `SET 条件名 TO TRUE`: 88 レベル条件名に対応する値を親データ項目へ自動転記します。
  - `SET 指標名 TO ...`: テーブルの要素位置（オフセット）を計算して指標レジスタを更新します。
  - `SET 呼び名 TO ON/OFF`: ジョブやプログラムが参照する UPSI スイッチの状態を変更します。

---

### 5.2 算術演算文 (`ADD`, `SUBTRACT`, `MULTIPLY`, `DIVIDE`, `COMPUTE`)

#### サポート構文
```cobol
ADD 被演算子... TO 受取項目... [ROUNDED] [ON SIZE ERROR 文] [NOT ON SIZE ERROR 文]
ADD 被演算子... GIVING 受取項目... [ROUNDED] ...
ADD CORRESPONDING 集団項目 TO 集団項目 ...

SUBTRACT 被演算子... FROM 受取項目... [ROUNDED] ...
SUBTRACT 被演算子... FROM 被演算子 GIVING 受取項目... [ROUNDED] ...
SUBTRACT CORRESPONDING 集団項目 FROM 集団項目 ...

MULTIPLY 被演算子 BY 受取項目... [ROUNDED] ...
MULTIPLY 被演算子 BY 被演算子 GIVING 受取項目... [ROUNDED] ...

DIVIDE 被演算子 INTO 受取項目... [ROUNDED] ...
DIVIDE 被演算子 (INTO | BY) 被演算子 GIVING 受取項目... [ROUNDED]
  [REMAINDER 剰余受取項目] ...

COMPUTE 受取項目... [ROUNDED] = 算術式 [ON SIZE ERROR 文] [NOT ON SIZE ERROR 文]
```

#### 振る舞い
- **被演算子の評価先行評価 (FR-043)**:
  - 文に現れるすべての被演算子は、受取項目の更新前に一度だけ評価されて局所スタックに保持されます（例: `DIVIDE B INTO A GIVING R1 A R2` において、`A` の更新によって `R2` の計算値が狂うことはありません）。
- **中間結果の精度 (FR-040, FR-041)**:
  - 2 進浮動小数は経由せず、IBM Enterprise COBOL の規則に厳格に従った固定小数点 10 進中間結果を計算します。
  - 中間結果の整数部・小数部桁数はコンパイル時に決定され、除算の商の桁数 `dmax` は文全体の最大小数桁から決定されます。
- **`ROUNDED` (丸め)**:
  - 受取項目への最終代入時にのみ適用されます。中間結果は切り捨てられます。
- **`ON SIZE ERROR`**:
  - ゼロ除算、または受取項目の整数桁あふれが発生した場合に発火します。
  - **重要**: 発火時、あふれが発生した受取項目の中身は変更されず（元の値が保護される）、`ON SIZE ERROR` 句の文が実行されます。指定がない場合は上位桁が切り捨てられた値が格納されます。
- **`REMAINDER` (剰余)**:
  - 商を切り捨てた結果に基づき、`剰余 = 被除数 - (商 × 除数)` を正確に算出して格納します。

---

### 5.3 制御構文 (`IF`, `EVALUATE`, `PERFORM`, `GO TO`, `ALTER`, `CONTINUE`, `NEXT SENTENCE`)

#### `IF` 文
```cobol
IF 条件 THEN
    文...
[ELSE
    文...]
[END-IF]
```
- **振る舞い**: 条件判定結果に応じて分岐。終止符（ピリオド）または `END-IF` で範囲を閉じます。

#### `EVALUATE` 文
```cobol
EVALUATE 主語1 [ALSO 主語2 ...]
  WHEN 目的語1 [ALSO 目的語2 ...]
    文...
  [WHEN OTHER
    文...]
[END-EVALUATE]
```
- **振る舞い**:
  - 主語と目的語を評価し、最初に一致した `WHEN` 節のブロックを実行します。
  - `TRUE`, `FALSE`, 条件式, 値, `THRU` による範囲指定, `ANY` をサポートし、コンパイラ内部で最適化された判定木（`IF-ELSE` 連鎖）へと展開されます。

#### `PERFORM` 文
```cobol
PERFORM [手続き名1 [THRU 手続き名2]]
PERFORM 手続き名 [THRU 手続き名] 回数 TIMES
PERFORM 手続き名 [THRU 手続き名] [WITH TEST (BEFORE | AFTER)] UNTIL 条件
PERFORM 手続き名 [THRU 手続き名] [WITH TEST (BEFORE | AFTER)]
    VARYING 項目1 FROM 初期値 BY 増分 UNTIL 条件1
    [AFTER 項目2 FROM 初期値 BY 増分 UNTIL 条件2 ...]
PERFORM [回数 TIMES | UNTIL 条件 | VARYING ...]
    インライン文...
END-PERFORM
```
- **振る舞い**:
  - 手続き呼び出し（アウトライン）および `END-PERFORM` によるインラインループの双方に対応。
  - `VARYING ... AFTER`: 多重ループにおいて、内側の変数は外側が 1 ステップ進むたびに `FROM` の値へ再初期化されて再評価されます。
  - `WITH TEST AFTER`: ループ本体を最低 1 回実行してから終了条件を判定します（既定は `TEST BEFORE`）。

#### `GO TO`, `ALTER`, `NEXT SENTENCE`
- **`GO TO 段落名`**: 指定段落へ無条件分岐します。
- **`GO TO 段落名... DEPENDING ON 項目`**:
  - 項目の値が 1 なら第 1 段落、2 なら第 2 段落へ分岐。
  - **重要**: 値が 1 未満または段落数を超える場合、エラーとならず分岐せずに次の文へそのまま進みます（規格準拠）。
- **`ALTER 段落名1 TO PROCEED TO 段落名2`**:
  - `GO TO` 単独文で構成された段落のジャンプ先を実行時に動的に書き換えます。
- **`CONTINUE` と `NEXT SENTENCE` の違い (FR-061)**:
  - `CONTINUE`: 単なる無処理（No-Op）。直後の文へ進みます。
  - `NEXT SENTENCE`: 現在の文（終止符ピリオドで区切られた文）の残りをすべてスキップし、**次のピリオドの直後にある文の先頭**へ直接ジャンプします。

---

### 5.4 プログラム制御・終了 (`CALL`, `CANCEL`, `EXIT PROGRAM`, `GOBACK`, `STOP RUN`)

#### `CALL` 文
```cobol
CALL 呼び先 [USING [BY (REFERENCE | CONTENT | VALUE)] 引数...]
  [ON EXCEPTION / OVERFLOW 文]
  [NOT ON EXCEPTION 文]
[END-CALL]
```
- **振る舞い**:
  - 静的呼び出し（文字列リテラル）および動的呼び出し（データ項目値）に対応。
  - `BY REFERENCE` (既定): 実引数のメモリ領域をそのまま引き渡すため、呼び出し先での変更が呼び出し元に即座に反映されます。
  - `BY CONTENT`: 実引数の複製（コピー領域）を渡すため、呼び出し先での変更は呼び出し元に影響しません。
  - `BY VALUE`: プリミティブ値渡し。
  - 特殊レジスタ `RETURN-CODE`: 呼び出し先プログラムが設定した復帰コードが呼び出し元へ伝播します。

#### `CANCEL` 文
```cobol
CANCEL 呼び先...
```
- **振る舞い**: 指定されたプログラムをアンロード状態とし、次回 `CALL` された際に `WORKING-STORAGE` がコンパイル時初期値から再開されるようにリセットします。

#### 終了文の差異 (FR-067)

| 文 | サブプログラム（`CALL` された側）での動作 | 主プログラム（起点）での動作 |
| --- | --- | --- |
| **`STOP RUN`** | 呼び出し階層をすべて破棄し、プロセス全体を終了。 | プロセス全体を終了。 |
| **`GOBACK`** | 呼び出し元プログラムへ制御を復帰。 | プロセス全体を終了。 |
| **`EXIT PROGRAM`**| 呼び出し元プログラムへ制御を復帰。 | **何もしない（No-Op）**。次の文へ進む。 |

---

### 5.5 文字列操作文 (`STRING`, `UNSTRING`, `INSPECT`)

#### `STRING` 文
```cobol
STRING 送出側... DELIMITED BY (区切り項目 | SIZE)
  INTO 受取項目 [WITH POINTER ポインタ項目]
  [ON OVERFLOW 文] [NOT ON OVERFLOW 文]
[END-STRING]
```
- **振る舞い**:
  - 複数の文字列を連結して受取項目へ格納します。受取側の未使用領域は空白で埋められず、既存のバイトがそのまま保持されます。
  - `POINTER`: 書き込み開始位置（1 オリジン）を指定。書き込んだ文字数分だけポインタが自動加算されます。
  - 受取項目の長さを超過した場合、`ON OVERFLOW` 句が実行されます。

#### `UNSTRING` 文
```cobol
UNSTRING 送出側 DELIMITED BY [ALL] 区切り文字 [OR [ALL] 区切り文字2 ...]
  INTO 受取項目 [DELIMITER IN 区切り受取] [COUNT IN 分割長受取] ...
  [WITH POINTER ポインタ項目] [TALLYING IN カウンタ]
  [ON OVERFLOW 文]
[END-UNSTRING]
```
- **振る舞い**:
  - 送出文字列を区切り文字に従って分割し、複数の受取項目へ順次転記します。
  - `ALL`: 連続する同一区切り文字を 1 個の区切り文字として集約します。
  - `DELIMITER IN`: 実際に分割に用いられた区切り文字を格納。
  - `COUNT IN`: 切り出された文字列の文字数を格納。

#### `INSPECT` 文
```cobol
INSPECT 項目 TALLYING カウント項目 FOR (CHARACTERS | ALL 文字 | LEADING 文字) [(BEFORE | AFTER) INITIAL 文字] ...
INSPECT 項目 REPLACING (CHARACTERS BY 文字 | (ALL | LEADING | FIRST) 文字 BY 文字) [(BEFORE | AFTER) INITIAL 文字] ...
INSPECT 項目 CONVERTING 変換元文字 TO 変換先文字 [(BEFORE | AFTER) INITIAL 文字]
```
- **振る舞い**:
  - 単一パスで文字列を走査し、文字のカウント（`TALLYING`）、置換（`REPLACING`）、および 1 対 1 の文字マッピング変換（`CONVERTING`）を実行します。
  - `BEFORE` / `AFTER INITIAL`: 指定文字が出現する前または後の範囲に限定して走査を行います。

---

### 5.6 表操作文 (`SEARCH`, `SEARCH ALL`)

#### `SEARCH` (逐次探索)
```cobol
SEARCH テーブル名 [VARYING 指標名/項目名]
  [AT END 文]
  WHEN 条件 文
[END-SEARCH]
```
- **振る舞い**:
  - 現在の指標（Index）位置からテーブル要素を順次走査します。
  - **重要**: `SEARCH` は指標の初期化を行いません（直前の `SET 指標 TO 1` 等の位置から探索を開始）。
  - 条件が一致した時点で探索を終了し、指標は一致位置を保持します。末尾に達した場合は `AT END` 句を実行します。

#### `SEARCH ALL` (2 分探索)
```cobol
SEARCH ALL テーブル名
  [AT END 文]
  WHEN キー項目 = 比較値 [AND キー項目2 = 比較値2 ...]
    文
[END-SEARCH]
```
- **振る舞い**:
  - `OCCURS` 句に `ASCENDING/DESCENDING KEY` が定義されたテーブルに対し、バイナリサーチ（2分探索）を実行します。
  - 条件式にはキー項目との等号（`=`）のみ指定可能。昇順・降順の定義に従って高速に探索します。

---

### 5.7 入出力・画面入出力文 (`ACCEPT`, `DISPLAY`)

#### `DISPLAY` 文
```cobol
DISPLAY 項目/リテラル... [UPON 機能名/呼び名] [WITH NO ADVANCING]
```
- **振る舞い**:
  - 指定された項目を標準出力または `UPON` で指定された装置（`SYSOUT`, `SYSERR`, `CONSOLE` 等）へ出力します。
  - `WITH NO ADVANCING`: 出力後の改行を抑止します。

#### `ACCEPT` 文
```cobol
ACCEPT 受取項目 [FROM (DATE [YYYYMMDD] | DAY [YYYYDDD] | DAY-OF-WEEK | TIME | 呼び名)]
```
- **振る舞い**:
  - システム日付・時刻・曜日を符号なし数字形式で取得、または標準入力・JCL `SYSIN` から 1 行読み込みます。
  - `DATE`: `YYMMDD` (6 桁), `DATE YYYYMMDD`: `YYYYMMDD` (8 桁)
  - `DAY`: `YYDDD` (5 桁), `DAY YYYYDDD`: `YYYYDDD` (7 桁)
  - `DAY-OF-WEEK`: 1 桁（月曜: 1 〜 日曜: 7）
  - `TIME`: `HHMMSSss` (8 桁, 時分秒ミリ秒)

---

### 5.8 ファイル入出力文 (`OPEN`, `CLOSE`, `READ`, `WRITE`, `REWRITE`, `DELETE`, `START`)

#### サポート文一覧
- **`OPEN (INPUT | OUTPUT | I-O | EXTEND) ファイル名... [WITH NO REWIND]`**
  - ファイルを各種モードでオープン。存在しない `OPTIONAL` ファイルを開いた場合は状態コード `05` を設定。
- **`CLOSE ファイル名... [WITH LOCK | WITH NO REWIND | REEL/UNIT]`**
  - ファイルをクローズ。`WITH LOCK` は同一実行単位での再オープンを禁止。ディスクファイルに対する `REEL/UNIT/NO REWIND` は状態コード `07`（巻操作なしで成功）を設定。
- **`READ ファイル名 [NEXT] RECORD [INTO 項目] [KEY IS キー] [AT END 文] [INVALID KEY 文]`**
  - レコードを読み込み。順アクセス時は終端で `AT END`、索引・相対ファイルのランダムアクセス時はキー不一致で `INVALID KEY` を実行。
- **`WRITE レコード名 [FROM 項目] [(BEFORE | AFTER) ADVANCING (整数 | 項目 | PAGE)] [AT END-OF-PAGE 文] [INVALID KEY 文]`**
  - レコードをファイルへ出力。行送り（ADVANCING）および改ページ（PAGE）制御に対応。
- **`REWRITE レコード名 [FROM 項目] [INVALID KEY 文]`**
  - 直前に `READ` したレコードを更新（I-O モード時のみ）。
- **`DELETE ファイル名 RECORD [INVALID KEY 文]`**
  - 順アクセス時は直前に `READ` したレコード、ランダムアクセス時は指定キーに対応するレコードを削除。
- **`START ファイル名 KEY (EQUAL | GREATER | NOT LESS | ...) キー項目 [INVALID KEY 文]`**
  - 索引ファイルまたは相対ファイルにおいて、レコード実体を読み込まずに後続の順次読み出しの開始位置（カーソル）のみを位置付け。

#### ファイル状態コード (FILE STATUS) 代表例
| コード | 意味 |
| --- | --- |
| `00` | 正常終了 |
| `02` | 索引ファイルで重複キーが正常に作成/検出された |
| `04` | 読み書きしたレコード長が FD 定義と不一致 |
| `05` | `OPTIONAL` ファイルが存在しなかった |
| `07` | テープ巻操作を伴わない媒体で巻オプションが実行された |
| `10` | ファイルの終端に達した (`AT END`) |
| `21` | 順次書き込みでキー順序が不正 |
| `22` | 重複を許可しないキーで重複レコードを書き込もうとした |
| `23` | 指定キーのレコードが存在しない (`INVALID KEY`) |
| `35` | オープン対象のファイルが存在しない |
| `41` | 既にオープン済みのファイルを再度オープンしようとした |
| `42` | オープンされていないファイルに対して入出力を行おうとした |
| `43` | `READ` していない状態で `REWRITE` または `DELETE` を試みた |

---

### 5.9 整列・併合文 (`SORT`, `MERGE`, `RELEASE`, `RETURN`)

#### サポート構文
```cobol
SORT 作業ファイル名
  ON (ASCENDING | DESCENDING) KEY キー項目...
  [WITH DUPLICATES IN ORDER]
  [COLLATING SEQUENCE IS 照合名]
  (USING 入力ファイル... | INPUT PROCEDURE IS 節名1 [THRU 節名2])
  (GIVING 出力ファイル... | OUTPUT PROCEDURE IS 節名3 [THRU 節名4])

MERGE 作業ファイル名
  ON (ASCENDING | DESCENDING) KEY キー項目...
  [COLLATING SEQUENCE IS 照合名]
  USING 入力ファイル1 入力ファイル2...
  (GIVING 出力ファイル... | OUTPUT PROCEDURE IS 節名 [THRU 節名])
```

#### 振る舞い
- **`INPUT PROCEDURE` / `RELEASE`**: 入力手続き内でレコードを加工し、`RELEASE レコード名 [FROM 項目]` 文でソート作業領域へ投入します。
- **`OUTPUT PROCEDURE` / `RETURN`**: ソート完了後、出力手続き内で `RETURN 作業ファイル RECORD [INTO 項目] AT END 文` により並べ替え済みのレコードを 1 件ずつ取り出します。
- **`USING` / `GIVING`**: 手続きを介さず、指定ファイルから直接ソートへ入力し、ソート結果を指定ファイルへ直接書き出します。
- キーの照合には、キー項目の USAGE（EBCDIC 文字列、パック 10 進数、バイナリ等）に応じたホスト互換の比較論理が適用されます。

---

### 5.10 報告書作成機能 (`INITIATE`, `GENERATE`, `TERMINATE`)

#### サポート構文
```cobol
INITIATE 報告書名...
GENERATE (報告集団名 | 報告書名)
TERMINATE 報告書名...
```

#### 振る舞い
- `REPORT SECTION` で定義された報告書記述項 (`RD`) および報告集団 (`TYPE IS REPORT HEADING`, `PAGE HEADING`, `DETAIL`, `PAGE FOOTING`, `REPORT FOOTING`) に従い、頁制限 (`PAGE LIMIT`)、行カウンタ (`LINE-COUNTER`)、頁カウンタ (`PAGE-COUNTER`) を自動制御して改頁や見出し印字を処理します。
- **`INITIATE`**: 各種カウンタを初期化し、報告書の出力を開始します。
- **`GENERATE`**: 明細行 (`DETAIL`) を 1 行出力し、行カウンタを進めます。必要に応じて改ページおよび見出し・脚書きを出力します。
- **`TERMINATE`**: 最終フッターおよびレポート脚書きを出力して報告書を完了します。

---

### 5.11 サブシステム連携文 (`EXEC CICS`, `EXEC SQL`)

#### `EXEC CICS` サポート構文 (初期サブセット)
```cobol
EXEC CICS LINK PROGRAM('名前' | 英数字項目) [COMMAREA(項目)] [LENGTH(数値)] [RESP(項目)] [RESP2(項目)] [NOHANDLE] END-EXEC
EXEC CICS XCTL PROGRAM('名前' | 英数字項目) [COMMAREA(項目)] [LENGTH(数値)] ... END-EXEC
EXEC CICS RETURN [TRANSID('名前') [IMMEDIATE]] [COMMAREA(項目)] [LENGTH(数値)] END-EXEC
EXEC CICS SYNCPOINT [ROLLBACK] END-EXEC
EXEC CICS SEND MAP('名前') [MAPSET('名前')] (FROM(項目) | MAPONLY) [ERASE] [DATAONLY] [CURSOR[(n)]] [FREEKB] [ALARM] [FRSET] [RESP(項目)] [RESP2(項目)] [NOHANDLE] END-EXEC
EXEC CICS RECEIVE MAP('名前') [MAPSET('名前')] INTO(項目) [RESP(項目)] [RESP2(項目)] [NOHANDLE] END-EXEC
EXEC CICS SEND TEXT FROM(項目) [ERASE] [FREEKB] [ALARM] [RESP(項目)] [RESP2(項目)] [NOHANDLE] END-EXEC
EXEC CICS SEND CONTROL [ERASE] [FREEKB] [ALARM] [FRSET] [CURSOR(n)] [RESP(項目)] [RESP2(項目)] [NOHANDLE] END-EXEC
EXEC CICS DELAY [FOR [HOURS(n)] [MINUTES(n)] [SECONDS(n)] [MILLISECS(n)] | INTERVAL(hhmmss)] [RESP(項目)] [RESP2(項目)] [NOHANDLE] END-EXEC
EXEC CICS ASKTIME [ABSTIME(S9(15) COMP-3項目)] END-EXEC
EXEC CICS FORMATTIME ABSTIME(項目) [DDMMYYYY(項目) | YYYYMMDD(項目) | MMDDYYYY(項目)] [TIME(項目)] [DATESEP[('c')]] [TIMESEP[('c')]] END-EXEC
EXEC CICS ABEND [ABCODE('コード') | ABCODE(X(4)項目)] [CANCEL] [NODUMP] END-EXEC
EXEC CICS BIF DEEDIT FIELD(英数字項目) END-EXEC
EXEC CICS GET CONTAINER('名前' | X(16)項目) [CHANNEL('名前' | X(16)項目)] INTO(域) [FLENGTH(S9(8) COMP項目)] END-EXEC
EXEC CICS PUT CONTAINER('名前' | X(16)項目) [CHANNEL('名前' | X(16)項目)] FROM(域) [FLENGTH(S9(8) COMP項目 | 整数)] END-EXEC
EXEC CICS ASSIGN [ABCODE(X(4)項目)] [APPLID(X(8)項目)] [PROGRAM(X(8)項目)] END-EXEC
EXEC CICS HANDLE CONDITION [条件名(段落名)]... END-EXEC
EXEC CICS IGNORE CONDITION 条件名... END-EXEC
EXEC CICS HANDLE ABEND (LABEL(段落名) | CANCEL | RESET) END-EXEC
EXEC CICS PUSH HANDLE END-EXEC
EXEC CICS POP HANDLE END-EXEC
EXEC CICS READ FILE(名前) INTO(域) RIDFLD(域) [LENGTH(S9(4) COMP項目)] [KEYLENGTH(n | 項目) [GENERIC]] [GTEQ | EQUAL] [RRN] [UPDATE] END-EXEC
EXEC CICS WRITE FILE(名前) FROM(域) RIDFLD(域) [LENGTH(n | 項目)] [KEYLENGTH(n | 項目)] [RRN] END-EXEC
EXEC CICS REWRITE FILE(名前) FROM(域) [LENGTH(n | 項目)] END-EXEC
EXEC CICS DELETE FILE(名前) [RIDFLD(域) [KEYLENGTH(n | 項目) [GENERIC]] [NUMREC(S9(4) COMP項目)] [RRN]] END-EXEC
EXEC CICS UNLOCK FILE(名前) END-EXEC
EXEC CICS (STARTBR | RESETBR) FILE(名前) RIDFLD(域) [KEYLENGTH(n | 項目) [GENERIC]] [GTEQ | EQUAL] [REQID(n | 項目)] [RRN] END-EXEC
EXEC CICS (READNEXT | READPREV) FILE(名前) INTO(域) RIDFLD(域) [LENGTH(S9(4) COMP項目)] [KEYLENGTH(n | 項目)] [REQID(n | 項目)] [RRN] END-EXEC
EXEC CICS ENDBR FILE(名前) [REQID(n | 項目)] END-EXEC
EXEC CICS WRITEQ TS (QUEUE(名前) | QNAME(名前)) FROM(域) [LENGTH(n | 項目)] [ITEM(S9(4) COMP項目) [REWRITE]] [MAIN | AUXILIARY] END-EXEC
EXEC CICS READQ TS (QUEUE(名前) | QNAME(名前)) INTO(域) [LENGTH(S9(4) COMP項目)] (ITEM(n | 項目) | NEXT) [NUMITEMS(S9(4) COMP項目)] END-EXEC
EXEC CICS DELETEQ TS (QUEUE(名前) | QNAME(名前)) END-EXEC
EXEC CICS WRITEQ TD QUEUE(名前) FROM(域) [LENGTH(n | 項目)] END-EXEC
EXEC CICS READQ TD QUEUE(名前) INTO(域) [LENGTH(S9(4) COMP項目)] END-EXEC
EXEC CICS DELETEQ TD QUEUE(名前) END-EXEC
EXEC CICS START TRANSID(名前) [INTERVAL(hhmmss) | TIME(hhmmss) | (AFTER | AT) [HOURS(n)] [MINUTES(n)] [SECONDS(n)]] [FROM(域) [LENGTH(n | 項目)]] [REQID(名前)] [RTRANSID(名前)] [RTERMID(名前)] [QUEUE(名前)] [TERMID(名前) | USERID(名前)] [PROTECT] END-EXEC
EXEC CICS RETRIEVE [INTO(域) [LENGTH(S9(4) COMP項目)]] [RTRANSID(X(4)項目)] [RTERMID(X(4)項目)] [QUEUE(X(8)項目)] [WAIT] END-EXEC
EXEC CICS CANCEL REQID(名前) END-EXEC
EXEC CICS RUN TRANSID(名前) [CHANNEL(名前)] CHILD(X(16)項目) END-EXEC
EXEC CICS FETCH (ANY(X(16)項目) | CHILD(X(16)項目)) [CHANNEL(X(16)項目)] [COMPSTATUS(S9(8) COMP項目)] [ABCODE(X(4)項目)] [NOSUSPEND | TIMEOUT(n | 項目)] END-EXEC
EXEC CICS FREE CHILD(X(16)項目) END-EXEC
```
- **振る舞い**:
  - `DFHEIBLK` (EIB: `EIBTRNID`, `EIBCALEN`, `EIBFN`, `EIBRCODE`, `EIBRESP`, `EIBRESP2`) の各フィールドを CICS コマンド実行の都度更新します。
  - `EIBTIME` / `EIBDATE` / `EIBTASKN` / `EIBTRMID` / `EIBCPOSN` / `EIBAID` / `EIBDS` / `EIBREQID` は読み取り専用で参照できます。値は task 文脈に task 番号・地方時がある場合だけ設定し、出どころの無い field は binary zero のままです (暫定判断 P-113)。
  - `ASSIGN PROGRAM` は現在の LINK level で CICS が起動した program (初期 program、LINK 先、XCTL 先) の名前を返します。COBOL の `CALL` で呼んだ副 program の名前にはなりません。`ASSIGN APPLID` は region に構成した APPLID を返し、構成が無ければ実行時に失敗します (設計 79 §5)。
  - `RETURN TRANSID(...) IMMEDIATE` は、次の task を端末入力なしで始める指定を task 結果 (`CicsTaskReply.immediateNext`) に残します。次の task を起動するのは transport adapter です。
  - `BIF DEEDIT` は項目から数字以外を除き、数字を右へ詰めて左を `0` で埋めます。末尾が `-` / `CR` なら右端のゾーンを負にします (暫定判断 P-124)。
  - `ABEND ABCODE(項目)` は 4 byte の英数字項目の値を実行時に読んで ABEND コードにします。
  - file control (`READ` / `WRITE` / `REWRITE` / `DELETE` / `UNLOCK` と browse) は、region に定義した KSDS / RRDS (固定長か可変長) を、バッチと同じデータセットとして読み書きします。返す条件は公開文書に RESP2 の書かれたものだけで、書かれていない形 (鍵を変える `REWRITE`、固定長の record を違う長さで読む形など) は失敗させます。`READ UPDATE` で得た record は `REWRITE` / `DELETE` / `UNLOCK`、`SYNCPOINT`、task の終わりで返し、他の task は期限まで待ちます。file は回復不能として扱います (設計 82 §3、暫定判断 P-131、P-136)。
  - 一時記憶のキュー (`WRITEQ` / `READQ` / `DELETEQ TS`) は定義を要らず、region の中で task どうしが分け合います。`READQ TS NEXT` は直前に読まれた item の次を読み、終わりは `ITEMERR` です。一時データのキュー (`TD`) は region で定義した区画内のキューだけで、先に書いた record から取り出し、空なら `QZERO` です。どちらも回復と遠隔のキューは持ちません (設計 82 §4・§5、暫定判断 P-137)。
  - `START` は region に構成した間隔制御 (`CicsStartPort`) が満了を待ち、端末と COMMAREA を持たない task を起こします。構成が無ければ失敗します。`TIME` / `AT` は task の地方時の時刻で、6 時間前までなら直ちに始めます。起こされた task の `RETRIEVE` は `FROM` のデータと `RTRANSID` / `RTERMID` / `QUEUE` を 1 度だけ読み、次は `ENDDATA` です。`CANCEL REQID` は未満了の `START` を取り消します。`PROTECT` の `START` は同期点で登録し (task の終わりでは commit のあと)、`ROLLBACK` で取り消します (暫定判断 P-141)。`TERMID` の `START` は端末の登録を持つ `JdbcCicsStarts` だけが受け、端末が無いか別の利用者の端末なら `TERMIDERR` です。満了しても端末で task が動いているか疑似会話の途中なら待ち、同じ端末と TRANSID の満了した `START` を 1 つの task にまとめて `RETRIEVE` が満了の順に読みます。`RETRIEVE WAIT` は読み尽くしていれば次の `START` が満了するまで task の期限まで待ち、端末の無い `START` の task では失敗します (設計 83 §5、暫定判断 P-144)。`USERID` の `START` は、START を出した task の user ID が代理できなければ `NOTAUTH` (RESP2 9)、起こす task の user ID が TRANSID を起こせなければ `NOTAUTH` (RESP2 7) です。`USERID` と `TERMID` の併記は翻訳で断ります (設計 84、暫定判断 P-145)。
  - 非同期 API の `RUN TRANSID` は region に構成した `CicsAsyncPort` で子の task を別の thread に起こし、channel の写しを渡します。`FETCH ANY` / `FETCH CHILD` は終わった子の `COMPSTATUS` (`DFHVALUE(NORMAL)` / `DFHVALUE(ABEND)`)、`ABCODE`、reply channel の名前を返し、`FREE CHILD` は token を無効にします。`NOSUSPEND` で終わった子が無ければ `NOTFINISHED` です (設計 82 §7、暫定判断 P-140)。
  - `GET` / `PUT CONTAINER` は task 内の channel に container を置き、読みます。GET でデータが受取域より長ければ入る分だけ写して `LENGERR`、container が無ければ `CONTAINERERR`、channel が無ければ `CHANNELERR` です。変換 option (`DATATYPE` 等) は未対応です (設計 79 §9、暫定判断 P-125)。
  - `LINK` は同一トランザクション/セッション内で副プログラムを呼び出し、COMMAREA のコピーバックを保証します。
  - `HANDLE CONDITION` / `IGNORE CONDITION` によるエラーハンドラ段落への自動ジャンプ、および `PUSH HANDLE` / `POP HANDLE` によるハンドラ退避スタック（リンクレベル分離）を完全に再現します。

#### Language Environment の callable service

- `COPY CEEIGZCT` は feedback code の 88 レベル `CEE000` だけを定義します。ほかの記号名は未対応です。
- `CALL "CEEDAYS"` は YYYY / MM / DD と区切りの絵で日付を Lilian の日にし、`CALL "CEELOCT"` は地方時の Lilian の日、秒、Gregorian の文字列を返します。実行時に `LanguageEnvironmentServices.register` で登録します。長さの札が域を越える VSTRING や、確かめていない絵は失敗させます (暫定判断 P-139)。

#### `USAGE POINTER` と `LENGTH OF`

- `USAGE POINTER` は 4 byte の項目で、`SET 項目 TO NULL` と `SET 項目 TO 別のPOINTER` だけを扱います。`ADDRESS OF` と POINTER の MOVE は未対応です。
- `LENGTH OF 項目` は、長さが翻訳時に決まる項目の長さの整数定数になります (暫定判断 P-123)。

#### `EXEC SQL` (Db2 連携)

- `SELECT ... INTO ... FROM`、`INSERT`、`UPDATE`、`DELETE`、`DECLARE c CURSOR FOR SELECT`、`OPEN` / `FETCH ... INTO` / `CLOSE`、`COMMIT [WORK]` / `ROLLBACK [WORK]`、`DECLARE t TABLE` を翻訳します。host variable (`:名前`、`:名前:標識`) は固定長文字・COMP-3・ゾーン 10 進・2 進に限ります。結果は SQLCA の SQLCODE / SQLSTATE / SQLERRD(3) に置きます。動的 SQL、`WHENEVER`、`WHERE CURRENT OF`、`FOR UPDATE` は未対応です (暫定判断 P-121)。
- `EXEC SQL INCLUDE 名前 END-EXEC` は前処理で `COPY 名前` と同じに取り込みます。`SQLCA` は置き場に無ければ公開の宣言 (136 byte) から作ります。`SQLDA` は未対応です (暫定判断 P-120)。
- `cobol-db2` / `cobol-spring-boot-4-autoconfigure` により、`SELECT`, `INSERT`, `UPDATE`, `DELETE`, `OPEN`, `FETCH`, `CLOSE`, `COMMIT`, `ROLLBACK` の静的 SQL を中立トランザクション境界へ写像し、`SQLCA` (`SQLCODE`, `SQLSTATE`) のステータスを更新します。

---

## 6. 組み込み関数 (INTRINSIC FUNCTIONS)

`FUNCTION 関数名[(引数...)]` の形式で算術式、条件式、`MOVE` 送出側、`DISPLAY` 等に記述可能です。

| 関数名 | 引数種別 | 戻り値 | 振る舞い・備考 |
| --- | --- | --- | --- |
| **`LENGTH`** | 任意項目 | 整数 | 項目の文字位置の数（バイト長）を返します。コンパイル時に確定。 |
| **`UPPER-CASE`** | 英数字 | 英数字 | 英小文字を大文字に変換（EBCDIC コード体系準拠）。 |
| **`LOWER-CASE`** | 英数字 | 英数字 | 英大文字を小文字に変換。 |
| **`REVERSE`** | 英数字 | 英数字 | 文字列のバイト並びを反転。 |
| **`CHAR`** | 整数 (1〜256) | 英数字 (1文字)| 現在の照合順序の指定位置にある文字を返します。 |
| **`ORD`** | 英数字 (1文字)| 整数 (1〜256)| 指定文字が現在の照合順序で何番目に位置するかを返します。 |
| **`MAX` / `MIN`** | 数値または文字... | 引数依存 | 最大値 / 最小値を返します。文字比較時は照合順序に従います。 |
| **`SUM`** | 数値... | 数値 | 引数の合計値を計算。`表(ALL)` によるテーブル全要素の合算に対応。 |
| **`ORD-MAX` / `ORD-MIN`**| 数値または文字... | 整数 | 最大 / 最小となる引数が何番目か（1 オリジン）を返します。 |
| **`RANGE`** | 数値... | 数値 | 引数群の最大値と最小値の差を返します。 |
| **`INTEGER`** | 数値 | 整数 | 引数を超えない最大の整数（床関数）を返します。 |
| **`INTEGER-PART`** | 数値 | 整数 | 小数部をゼロ方向に切り捨てた整数部を返します。 |
| **`MOD`** | 数値2個 (n, m) | 数値 | 剰余算。符号は除数 $m$ に従います。 |
| **`REM`** | 数値2個 (n, m) | 数値 | 剰余算。符号は被除数 $n$ に従います。 |
| **`FACTORIAL`** | 整数 | 整数 | 階乗（$n!$）を計算します。 |
| **`NUMVAL`** | 英数字 | 数値 | 先頭・末尾の空白や正負符号を含む数字文字列を数値として解釈します。 |
| **`NUMVAL-C`** | 英数字, [通貨文字]| 数値 | 通貨記号やカンマを含む文字列を数値へ変換します。 |
| **`MEDIAN`** | 数値... | 数値 | 中央値を算出します。 |
| **`MIDRANGE`** | 数値... | 数値 | 最大値と最小値の相加平均（$(\max + \min) / 2$）を返します。 |
| **`MEAN`** | 数値... | 数値 | 相加平均を計算します。 |
| **`VARIANCE`** | 数値... | 数値 | 母分散を計算します。 |
| **`STANDARD-DEVIATION`**| 数値... | 数値 | 標準偏差（分散の平方根）を計算します。 |
| **`SQRT`** | 数値 | 数値 | 平方根。負数の場合は 0 を返します（規格準拠）。 |
| **`LOG` / `LOG10`** | 数値 | 数値 | 自然対数 / 常用対数。引数が 0 以下の場合は 0 を返します。 |
| **`EXP` / `EXP10`** | 数値 | 数値 | $e^x$ / $10^x$ を計算します。 |
| **`SIN` / `COS` / `TAN`**| 数値 (ラジアン) | 数値 | 三角関数。 |
| **`ASIN` / `ACOS` / `ATAN`**| 数値 | 数値 | 逆三角関数。値域外の引数には 0 を返します。 |
| **`ANNUITY`** | 金利, 期数 | 数値 | 元金 1 に対する毎期の返済額（年金計算）を算出します。 |
| **`PRESENT-VALUE`** | 割引率, 金額... | 数値 | 正味現在価値（NPV）を計算します。 |
| **`RANDOM`** | [シード値] | 数値 | 0 以上 1 未満の擬似乱数を返します。 |
| **`INTEGER-OF-DATE`** | YYYYMMDD | 整数 | 1601年1月1日を 1 とする通日を計算します（グレゴリオ暦）。 |
| **`INTEGER-OF-DAY`** | YYYYDDD | 整数 | 通年日（年と年初からの日数）から通日を計算します。 |
| **`DATE-OF-INTEGER`** | 整数 (通日) | 整数 | 通日から `YYYYMMDD` 形式の整数を返します。 |
| **`DAY-OF-INTEGER`** | 整数 (通日) | 整数 | 通日から `YYYYDDD` 形式の整数を返します。 |
| **`CURRENT-DATE`** | なし | 21文字 | 現在の年月日時分秒ミリ秒および協定世界時との時差 (`YYYYMMDDhhmmsscc±hhmm`) を返します。 |
| **`WHEN-COMPILED`** | なし | 21文字 | コンパイルされた年月日時分秒を返します。コンパイル時定数として展開。 |

---

## 7. 条件式と照合順序 (CONDITIONS & COLLATING SEQUENCE)

### 7.1 条件式の種類
- **関係条件 (Relational Condition)**:
  - 構文: `算術式 (EQUAL TO | = | GREATER THAN | > | LESS THAN | < | GREATER THAN OR EQUAL TO | >= | LESS THAN OR EQUAL TO | <= | NOT EQUAL TO | NOT =) 算術式`
  - 振る舞い:
    - 数値同士の比較: 代数的な比較（内部表現や PICTURE 桁数、先頭ゼロに関係なく値の大小で判定）。
    - 非数値同士の比較: 照合順序（既定は EBCDIC コード順）に基づく 1 バイトずつの文字コード比較。
- **省略された複合関係条件 (Abbreviated Combined Relation)**:
  - 構文: `IF A = 1 OR 2 AND 3` / `IF A > 10 AND < 20`
  - 振る舞い: 省略された主語（`A`）や関係演算子を補完し、論理演算子 (`AND`, `OR`) の優先順位（`AND` が `OR` より高優先）に従って正しく括弧組みして評価します。
- **級条件 (Class Condition)**:
  - 構文: `項目 IS [NOT] (NUMERIC | ALPHABETIC | ALPHABETIC-LOWER | ALPHABETIC-UPPER | 級名)`
  - 振る舞い:
    - `NUMERIC`: 項目内の全バイトが有効な数字文字（ゾーン符号含む、またはパック10進形式）であるかを検査。
    - `ALPHABETIC`: 全バイトが英字（A〜Z, a〜z）および空白であるかを検査。
    - `級名`: `SPECIAL-NAMES` の `CLASS` 句で定義された文字集合に含まれるかを検査。
- **符号条件 (Sign Condition)**:
  - 構文: `算術式 IS [NOT] (POSITIVE | NEGATIVE | ZERO)`
  - 振る舞い: 式の評価結果が 0 より大きいか、0 より小さいか、0 に等しいかを判定します。
- **条件名条件 (Condition-Name Condition, 88 レベル)**:
  - 構文: `IF 条件名`
  - 振る舞い: 親データ項目の現在の値が、88 レベル記述項に指定された単一値または `THRU` の範囲内に一致するかを判定します。

---

## 8. デバッグ・宣言節 (DECLARATIVES & DEBUGGING)

### サポート構文
```cobol
PROCEDURE DIVISION.
DECLARATIVES.
[節名 SECTION.
 USE GLOBAL? AFTER STANDARD (ERROR | EXCEPTION) PROCEDURE ON (ファイル名... | INPUT | OUTPUT | I-O | EXTEND).
 段落名.
    文...]
[デバッグ節名 SECTION.
 USE FOR DEBUGGING ON (手続き名... | ALL PROCEDURES | ファイル名... | ALL REFERENCES OF 項目名 | 項目名...).
 段落名.
    文...]
END DECLARATIVES.
```

### 振る舞い
- **`USE AFTER STANDARD ERROR PROCEDURE`**:
  - ファイル I/O 時にエラー（ファイル状態コードの先頭が `0` 以外）が発生した際、自動的に呼び出される例外ハンドラ節を定義します。
- **`USE FOR DEBUGGING`**:
  - `WITH DEBUGGING MODE` 指定時に有効化されます。
  - 手続き名監視: 指定段落へ制御が移る直前、特殊レジスタ `DEBUG-ITEM` に遷移元行番号 (`DEBUG-LINE`)、遷移理由 (`DEBUG-CONTENTS`: `START PROGRAM`, `PERFORM LOOP`, `FALL THROUGH` 等) を格納してデバッグ節を実行します。
  - ファイル監視: 対象ファイルへの入出力文の実行直後にデバッグ節を実行します。
  - 項目監視 (`ALL REFERENCES OF`): 対象データ項目を参照または更新する文が実行されるたびにデバッグ節を自動起動します。
