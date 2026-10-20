# 未対応構文とその理由 (Unsupported Syntax and Rationale)

本書は、`cobol-on-java` において現時点で**サポートしていない構文（未対応構文・文脈・句・オプション）**および、それらをサポートしていない**理由・設計判断・将来の対応方針**をまとめたドキュメントです。

---

## 1. 互換性と未対応方針の基本原則

本プロジェクトでは要件定義書（[docs/requirements.md](requirements.md)）およびアーキテクチャ方針に基づき、以下の原則を厳格に順守しています。

1. **未対応構文を黙って無視しない（Fail-Closed 原則 / FR-181）**:
   - 構文解析器や意味解析器で未実装の句や文に遭遇した場合、無視して処理を継続するのではなく、**コンパイルエラー（致命的診断メッセージ）を出して明示的にコンパイルを停止**します。
   - 理由: 黙って無視すると、実行時に見当違いの計算結果や意図しないデータ破壊を招き、基幹業務移行において最も発見が困難なサイレント障害となるためです。
2. **参照実装（Enterprise COBOL for z/OS 6.x）をベースラインとする（決定 D-12）**:
   - 参照実装ですでに廃止・削除された古い構文（OS/VS COBOL 世代）は、独自拡張として復活させず原則として未対応（互換性レベル L0）とします。
3. **推測による中途半端な実装を行わない（CLAUDE.md / 決定 D-11b）**:
   - 「仕様や桁数の裏付け（Hercules 等の実機オラクルや規格）がないまま思い込みで近い値を返す」ことは行いません。仕様とオラクルが揃うまで確実にエラーとして報告します。

---

## 2. カテゴリ別未対応構文一覧

<b>この章の各行は `UnsupportedSyntaxCatalogTest` (cobol-compiler) の同じ名前の事例である。</b>
行ごとに小さな原文を翻訳器にかけ、「通る」か「この文言で断る」かを確かめている。実装が変われば
試験が落ちるので、この表を直してから試験の期待を変えること。以前この一覧は文書だけで持っており、
実装済みの BMS やチャネルを「未対応」と書いたまま、本当に断る `WHENEVER` などを載せていなかった
(2026-10-20 に試験と突き合わせて直した)。

診断の欄は翻訳器の文言の一部である。「受理」は翻訳が通ることだけを言い、振る舞いの正しさは
各機能の試験と暫定判断が受け持つ。

### 2.1 見出し部・環境部

| 事例 | 構文 | 翻訳器の扱い | 理由・方針 |
| --- | --- | --- | --- |
| `CUR2` | `CURRENCY SIGN IS 'EUR' WITH PICTURE SYMBOL 'E'` | 構文誤り (`extraneous input 'WITH'`) | 単一の `CURRENCY SIGN IS '文字'` だけを持つ (P-010) |
| `REVR` | `OPEN INPUT F REVERSED` | `OPEN ... REVERSED is not supported` | テープを逆に読む指定。順に読むと黙って違う結果になるので断る (P-081)。仮想テープを入れるときに見直す |
| `COMM` | `COMMUNICATION SECTION` | `COMMUNICATION SECTION is not supported` | 通信機能は廃要素。恒久的に断る |
| `RERUNT` | `RERUN ON ... EVERY n RECORDS OF F` | **受理するが効かない** | 文法の注記どおり読み飛ばす。普通に流す結果は変わらないが、<b>検査点を取らず、再始動もできない</b> |

### 2.2 データ部

| 事例 | 構文 | 翻訳器の扱い | 理由・方針 |
| --- | --- | --- | --- |
| `BIN20` | `PIC S9(20) COMP-5` | `binary items of 20 digits are not supported` | 2 進は 18 桁まで。`ARITH(EXTEND)` の説明が 31 桁へ広げるものに 2 進を挙げていない (P-005) |
| `PTRUP` | `SET ptr UP BY 4` | `SET of a POINTER item supports only TO NULL or another POINTER` | POINTER は実行単位の中で振った番号であり、番地の算術は持たない (P-123、P-150) |
| `FPTR` / `PPTR` | `USAGE FUNCTION-POINTER` / `PROCEDURE-POINTER` | 構文誤り | 未実装 |
| `NATL` | `PIC N USAGE NATIONAL` の転記 | **受理** | 設計 28 |
| `NATNUM` | `PIC 9(5) USAGE NATIONAL` (国字の数字) | `USAGE NATIONAL is supported only with PICTURE N` | 国字の 10 進と国字の編集は未実装 |
| `DBCS` | `PIC G USAGE DISPLAY-1` | `USAGE DISPLAY-1 is not supported yet` | DBCS 項目は未実装 (P-006) |
| `RENM` | `66 ... RENAMES A THRU B` | **受理** | 実装済み |

入れ子プログラム (`END PROGRAM` で閉じる内側のプログラム) も受理する。文法は入れ子を持たず、並んだ
プログラムとして読む (`CobolCompiler`)。`COMMON` や `GLOBAL` の見え方の規則は確かめていない。

### 2.3 手続き部

| 事例 | 構文 | 翻訳器の扱い | 理由・方針 |
| --- | --- | --- | --- |
| `INITV` | `INITIALIZE ... REPLACING NUMERIC DATA BY データ項目` | **受理** | 実装済み |
| `SRCH2` | 表の中の表への `SEARCH ALL` | **受理** | 内側の指標を動かすだけなので扱える (CLAUDE.md §3) |
| `FLTDIV` | `COMPUTE` の浮動小数点の除算・べき乗 | `supports only +, - and *` | 中間結果の精度が 10 進と食い違うので、近い値を返さず断る (P-127) |
| `FLTADD` | 浮動小数点項目への `ADD` など | `with floating-point items is not supported yet` | 同上。`COMPUTE` で書けば `+ - *` は通る |
| `FLTDSP` | 浮動小数点項目の `DISPLAY` | `DISPLAY of a floating-point item is not supported yet` | 外部浮動小数点の表示形式が未実装 |
| `BYVAL` | `CALL ... BY VALUE` | **受理** | COBOL どうしの写しとして渡す (暫定判断 P-204)。定数の BY VALUE はまだ断る |
| `CANCL` | `CANCEL 'SUB'` | **受理** | 次の呼び出しで作業場所を初期状態に戻す。JVM の class そのものは捨てない |
| `SORTRET` | 特殊レジスタ `SORT-RETURN` | `undefined data item: SORT-RETURN` | 整列の特殊レジスタ (`SORT-RETURN`、`SORT-CONTROL` など) は未実装 |
| `XMLP` / `JSONG` | `XML PARSE` / `JSON GENERATE` | 構文誤り | 未実装 |

### 2.4 ファイル

| 事例 | 構文 | 翻訳器の扱い | 理由・方針 |
| --- | --- | --- | --- |
| `RECU` / `RECS` | `RECORDING MODE U` / `S` | `RECORDING MODE U is not supported yet` | 不定長と区分 (スパン) のレコードは未実装。`F` / `V` は扱う |
| `BLKC` | `BLOCK CONTAINS 10 RECORDS` | **受理するが効かない** | ブロックを物理的に作らない。ホストのブロック化したデータセットをそのまま読むには、物理ブロック (BDW / RDW) の実装が要る |

### 2.5 報告書作成

| 事例 | 構文 | 翻訳器の扱い | 理由・方針 |
| --- | --- | --- | --- |
| `RWC` | `CONTROLS ARE ...`、`TYPE CONTROL FOOTING`、`SUM` | `a report with CONTROL breaks is not supported yet` | 固定の頁制御 (`PAGE LIMIT`、`HEADING`、`FOOTING`、`INITIATE` / `GENERATE 明細` / `TERMINATE`) は実装済み。制御の切れ目、`SUM`、`GENERATE 報告書名` は未実装 (P-078) |

### 2.6 サブシステム (`EXEC SQL` / `EXEC CICS` / `EXEC DLI` / MQ)

| 事例 | 構文 | 翻訳器の扱い | 理由・方針 |
| --- | --- | --- | --- |
| `WHENEV` | `EXEC SQL WHENEVER` | `unsupported EXEC SQL statement: WHENEVER` | 未実装。Db2 の資産で多いので優先 (改善計画 1-1) |
| `PREP` | `EXEC SQL PREPARE` (動的 SQL) | `unsupported EXEC SQL statement: PREPARE` | 動的 SQL (`PREPARE` / `EXECUTE` / `DESCRIBE`、SQLDA) は未実装 (改善計画 1-1) |
| `DLI` | `EXEC DLI` | `EXEC processor is not supported yet` | DL/I は `CALL 'CBLTDLI'` で扱う (設計 78) |
| `CXFER` | `LINK ... CHANNEL(...)` | `unsupported EXEC CICS option: CHANNEL('CH')` | `PUT` / `GET CONTAINER` と `START ... CHANNEL` は実装済み。`LINK` / `XCTL` の `CHANNEL` は未実装 |
| `CHABP` | `HANDLE ABEND PROGRAM(...)` | `accepts LABEL, CANCEL, or RESET` | 未実装 |
| `CTRAN` | `RETURN TRANSID(データ名)` | **受理** | 4 byte の英数字の域に限る。実行時に末尾の空白を落とし、TRANSID の形でなければ (小文字を含む) 推測で直さず失敗させる。`PROGRAM(データ名)` と `ABCODE(データ名)` も扱う (P-124) |
| `CRQTS` | `ITEM` も `NEXT` も無い `READQ TS` | `READQ TS requires either ITEM or NEXT` | 文書が既定を示していない形 (P-137) |
| `CSTUT` | `START ... USERID(...) TERMID(...)` | `START USERID with TERMID is not supported` | P-149 |
| `CRECV` | `RECEIVE INTO` (map を使わない端末入力) | 受理 | 2026-10-20 に実装。書式の無い画面で打たれた文字だけを読む。map の画面からの入力と `NOTRUNCATE` / `SET` は断る (P-200) |
| `MQ` | `CALL 'MQOPEN'` | **受理するが、実行で program が見つからない** | MQI は未実装 (改善計画 2-4) |

BMS (`SEND MAP` / `RECEIVE MAP`) は実装済みで、ブラウザ (設計 81) と TN3270 (設計 86) の端末から使える。
CICS の file control、一時記憶・一時データ、`START`、非同期 API、`ENQ` / `DEQ` の詳細は README の
「現在のステータス」と設計 82〜85 にある。

### 2.7 削除済み旧構文 (Enterprise COBOL 6.x で廃止された構文 / 制約 C-11)

以下の構文は IBM Enterprise COBOL for z/OS 6.x で言語仕様から削除されている。本処理系でも受理しない。

<b>2026-10-20 の訂正</b>: 以前この表は「`EXAMINE is obsolete ... Use INSPECT instead.` のような旧構文であることを
示す診断を出す」と書いていたが、<b>そういう文言は実装されていなかった</b>。どれも断るが、診断は一般の構文誤りか
未定義の名前である。下の「翻訳器の扱い」は試験 (`UnsupportedSyntaxCatalogTest`) で確かめた実際の文言である。
置き換え先を示す専用の診断は、移行の手間を減らす改善の候補である。

| 事例 | 削除された構文 | 代わりの構文 | 翻訳器の扱い |
| --- | --- | --- | --- |
| `EXAM` | `EXAMINE` 文 | `INSPECT` 文 | 構文誤り (`no viable alternative at input 'EXAMINE...'`) |
| `TRAN` | `TRANSFORM` 文 | `INSPECT ... CONVERTING` 文 | 構文誤り |
| `NOTEST` | `NOTE` 文 | `*` 注釈行、または `*>` 行内注釈 | 構文誤り |
| `ONST` | `ON` 文 (条件付き実行) | カウンタ変数と `IF` 文 | 構文誤り |
| `CDAY` | 特殊レジスタ `CURRENT-DAY` | `FUNCTION CURRENT-DATE` | `undefined data item: CURRENT-DAY` |

---

## 3. 未対応構文への遭遇時の対処方法

移行対象の既存 COBOL 資産を本コンパイラで翻訳した際に未対応エラーが発生した場合は、以下のガイドラインに従って対処してください。

1. **削除済み旧構文 (`EXAMINE`, `TRANSFORM` 等) の場合**:
   - 上記 2.7 節の対応表に従い、Enterprise COBOL 6.x 準拠の標準構文（`INSPECT` 等）へ書き換えてください。
2. **未対応 USAGE (`FUNCTION-POINTER`、`DISPLAY-1`、国字の数字項目など) の場合**:
   - 英数字項目（`PIC X`）や 4 バイトバイナリ（`COMP-5`）など、現在サポートされている表現で代替可能か検討してください。
3. **報告書の制御ブレーク (`CONTROL` 句) の場合**:
   - `REPORT SECTION` を使わず、手続き部内で手動のブレーク判定（キー項目の前回値比較と合計計算・改ページ `WRITE ... AFTER ADVANCING PAGE`）を行う標準的なバッチ集計ロジックへのリファクタリングを推奨します。
4. **CICS 未対応コマンドの場合**:
   - `RESP` / `RESP2` オプションを付与した中立 Java サービス呼び出し、または Java 側でのインターセプタ処理への移行を検討してください。
