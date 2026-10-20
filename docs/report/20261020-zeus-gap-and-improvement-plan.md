# Zeus との機能差、z/OS 互換性の確認状況、改善計画 (2026-10-20)

| 項目 | 内容 |
| --- | --- |
| 比べたもの | cobol-on-java `claude/jvm-cobol-requirements-1phhbu` (TN3270 の入口を足した時点)、Zeus `main` (106fdc8) |
| 読んだもの | Zeus: `README.md`、`docs/proposal/0003`〜`0022`、`docs/inventory/`、`docs/help/`、`tools/`。cobol-on-java: README の「現在のステータス」、設計、暫定判断、`tools/zos-probe`、ソース |
| 確かめ方 | Zeus のソースを読み、Zeus をビルドして (`cargo build --release`) 同じ probe を流した (§3.1)。機能の有無は両方のソースで確かめた。Zeus の業務資産の場面 (CardDemo など) は Zeus の文書の記述によるもので、流してはいない |

## 0. 読み方

「受け付ける」「動く」「z/OS と同じ結果になる」は別のことである (Zeus の README も同じ区別をしている)。
本書の表は次の記号で書く。

| 記号 | 意味 |
| --- | --- |
| ○ | 実装があり、試験がある |
| △ | 一部だけ。何が欠けているかを書く |
| × | 無い。翻訳で断るか、構文を持たない |
| — | 方式が違うので比べられない |

**ライセンスと権利**: Zeus (GPL-3.0-or-later) と cobol-on-java (Apache-2.0) は<b>どちらも同じ権利者</b>である。
権利者の判断で、Zeus のソースを読んで比べること、Zeus の実装を cobol-on-java へ移すことができる。
移すときは、権利者が該当部分を Apache-2.0 で出すことを ADR に残し、どのファイルから来たかを
コミットに書く (段階 0-7)。Hercules (QPL) の扱いは変わらない (CLAUDE.md「決めごと」)。

## 1. 方式の違い

| | Zeus | cobol-on-java |
| --- | --- | --- |
| 実装 | Rust。`.zo` モジュールをインタプリタか Cranelift で実行 | Java 21。COBOL を JVM の class に翻訳 |
| 外部サービス | Db2 は自前の DRDA クライアント、PostgreSQL は自前のプロトコル実装と方言変換、MQ は C クライアント | JDBC (Db2・H2・PostgreSQL)、JMS、Spring Boot |
| 配布 | 単独実行ファイル (`--native`) | jar、Maven プラグイン |
| 端末 | TN3270 サーバ、スクリプト端末 | ブラウザ (Thymeleaf)、TN3270 の入口 (設計 86、今回) |

cobol-on-java にしか無いもの: Java との相互呼び出し (設計 75)、JUnit による単体試験と mock (`cobol-junit`)、
Spring Boot の UOW 連携、IMS の置き場を RDB に置く (`cobol-ims-rdb`)、IMS の電文を JMS で運ぶ (`cobol-ims-jms`)、
BMS のブラウザ描画、Maven の標準ディレクトリ体系。Zeus は Java 連携を対象外にしている (Zeus 0007 §2)。

## 2. Zeus にあって cobol-on-java に無い・足りない機能

### 2.1 COBOL 言語とコンパイラ

| 機能 | Zeus | cobol-on-java | 根拠 (cobol-on-java) | 規模 |
| --- | --- | --- | --- | --- |
| 動的 SQL (`PREPARE` / `EXECUTE` / `DESCRIBE`、SQLDA) | ○ | × | `Db2SystemCopyBookResolver`「動的 SQL はまだ無い」。Bank-of-Z の XFRFUN が SQLDA を INCLUDE する | L |
| `EXEC SQL WHENEVER` | ○ | × 翻訳で断る (`unsupported EXEC SQL statement: WHENEVER`。実際に翻訳して確かめた) | — | S |
| `RECORDING MODE U` / `S`、物理ブロック (BDW / RDW / SDW)、`BLOCK CONTAINS`、`APPLY WRITE-ONLY`、`RESERVE` | ○ | △ F / V だけ | `FileDescription.recordingOf` が U / S を断る | M |
| 仮想テープ (AWSTAPE)、標準ラベル、複数巻、`REVERSED`、`NO REWIND` | ○ | × | `REVERSED` は断る (未対応構文 §2.1) | L |
| `RERUN` による検査点と再始動 | ○ | × 構文は読むが効かない | `CobolParser.g4`「どれも翻訳の結果には効かない」 | L |
| 報告書作成の `CONTROL HEADING` / `FOOTING`、`SUM`、`GENERATE 報告書名` | ○ (CCVS85 RW 40/40) | × | `DataDivisionBuilder` が断る | M |
| DBCS (`PIC G`、`DISPLAY-1`、`NSYMBOL(DBCS)`) | ○ | × | P-006。`OptionSupport` | M |
| `USAGE NATIONAL` の文の範囲 | ○ 数値・編集・グループも | △ 転記・比較・関数だけ | `NationalGuard` | M |
| コードページ | ○ 54 種 (日中韓の混在を含む)、`CBL CODEPAGE(n)` | △ 5 種 (1047 / 037 / 930 / 939 / ASCII)。`CODEPAGE` は 1047 だけ受ける。1390 / 1399 は無い (P-002) | `CodePages`、`OptionSupport` | M |
| EBCDIC の原文 (行区切り・80 byte 固定) を読む | ○ `--source-encoding` | × | 翻訳器に入口が無い | S |
| `COMP-1` / `COMP-2` の除算・べき乗・`DISPLAY`・`ON SIZE ERROR` | ○ | △ `+ - *`、転記、比較 | P-127、未対応構文 §2.3 | M |
| `CALL ... BY VALUE` | ○ | × | `ProcedureBuilder`「BY VALUE is not supported yet」 | S |
| `DYNAM` / `NODYNAM` と `CANCEL` の再ロード、`>>CALLINTERFACE`、DLL / `EXPORTALL` | ○ | △ オプション名を受けるだけ | `OptionSupport` | M |
| 翻訳リスト (`SOURCE` / `MAP` / `LIST` / `OFFSET` / `XREF` / `VBREF`)、`FLAG` | ○ | × 名前を受けて出さない | `OptionSupport` | M |
| `SORT-RETURN` / `SORT-CONTROL` などの整列の特殊レジスタ、DFSORT の制御文の上書き | ○ `vm/sort.rs` | × | 翻訳器にも実行時にも無い | S |
| `THREAD`、`LP(64)` | ○ (Zeus 独自の仮想 ABI) | × | — | M |
| 入れ子プログラム | ○ | △ 受理して動く (並んだプログラムとして読む)。`COMMON` / `GLOBAL` の見え方は確かめていない | `UnsupportedSyntaxCatalogTest` と同じ形で翻訳して確かめた | S |
| OO COBOL (`REPOSITORY`、`INVOKE`) | × 対象外 | × IBTRAN で止まる | README | — (下の §5 で機会として扱う) |
| XML / JSON (`XML PARSE` / `GENERATE`、`JSON`) | × 対象外 (ZCVS の任意モジュール ZX はある) | × | — | — |

### 2.2 CICS と 3270

| 機能 | Zeus | cobol-on-java | 根拠 | 規模 |
| --- | --- | --- | --- | --- |
| TN3270 / TN3270E の入口 | ○ | ○ (今回。設計 86) | `Tn3270EmulatorTest` | — |
| TN3270 over TLS、STARTTLS | ○ | × | P-191 | S |
| 印刷装置 (TN3270E ASSOCIATE、SCS、pr3287)、START を印刷装置へ | ○ | × | P-191 | M |
| SYSREQ / LOGOFF (SSCP-LU データ、UNBIND) | ○ | × | P-191 | S |
| 構造化 field (Read Partition Query と応答、AID X'88')、`RECEIVE BUFFER`、program 自身のデータストリーム (`SEND FROM`) | ○ | × | — | M |
| 14 bit 番地の大きい画面 (160 x 62 など、BIND を送り直す) | ○ | △ 番地は書けるが、型は model 2〜5 だけ | `Tn3270Device` | S |
| IND$FILE によるファイル転送 | ○ | × | — | M |
| 会話型の `RECEIVE` (task の中で入力を待つ)、`RECEIVE INTO` | ○ | × | `CicsRuntimeOps`「a conversational RECEIVE cannot wait within one task」 | L |
| task の並行と待ち (`DELAY`、`ENQ`、`READ UPDATE` のロック、MQGET の待ち) | ○ コルーチン | △ task は端末ごとの thread で並ぶ。`ENQ` はある。同じ task の中で待つ形は無い | 設計 83 | M |
| 台本で端末を動かす (サーバなしで `tran` / `expect`) | ○ | △ JUnit と s3270 の試験で代える | — | S |
| CICS のトリガーモニター (CKTI、MQ の開始キュー) | ○ | × MQ が無い | — | M |
| CICS のイベント処理 (GenApp の LGASTAT1) | ○ | × | — | M |
| `HANDLE ABEND PROGRAM` | ○ | × | 未対応構文 §2.5 | S |
| sign on (CESN) | × (CardDemo は自前のサインオン画面) | × | P-191 | M |

### 2.3 Db2・MQ・IMS・REST

| 機能 | Zeus | cobol-on-java | 根拠 | 規模 |
| --- | --- | --- | --- | --- |
| IBM MQ の MQI (`MQOPEN` / `MQPUT` / `MQGET` / `MQSET`、publish/subscribe、`MQCB`) | ○ | × | ソースに無い | XL |
| Db2 のユーティリティ (DSNTIAUL、DSNTEP2、DSNTIAD、`DSN RUN PROGRAM`) | ○ | × | ソースに無い。`IKJEFT01` は TSO のコマンドだけ | M |
| Db2 の SQL を PostgreSQL で流す (方言の変換、SQLSTATE から SQLCODE への対応表) | ○ | — JDBC で Db2 か互換の DB へ直接。方言の変換と SQLCODE の対応表は無い | 設計 77 | L |
| IMS の GSAM、SPA、電文を読む BMP | ○ | × | P-166 | M |
| IMS の MFS | ○ (Zeus 0006 では残りあり) | × | 調査文書だけ | L |
| IMS の SQL (`SQLIMS`) | ○ | × | — | L |
| `EXEC DLI` | ○ COBOL (PL/I は × ) | × | — | M |
| IMS のアンロード (DFSURGU0) | ○ | × | — | S |
| z/OS Connect の API プロジェクトを読んで CICS / IMS を呼ぶ | ○ | × JSON の入口 (`POST /api/cics/{transid}`) は独自の形 | P-135 | L |

### 2.4 PL/I と HLASM

| 機能 | Zeus | cobol-on-java | 規模 |
| --- | --- | --- | --- |
| PL/I の ON-unit、レコード入出力、VSAM KSDS、REGIONAL、`LOCATE` | ○ | △ Bank-of-Z の 2 本が要る分だけ (README) | L |
| PL/I の AREA / OFFSET、COMPLEX、GRAPHIC / WIDECHAR、ORDINAL、GENERIC、ENTRY 変数、FETCH | ○ | × | L |
| PL/I の EXEC SQL の動的 SQL、EXEC CICS、MQI | ○ | △ EXEC SQL の静的なものだけ | M |
| PL/I のプリプロセッサ (`%GO TO`、キーワード引数) | ○ | △ `%INCLUDE` | M |
| HLASM の `SVC`、モジュール間リンク、OBJ / GOFF の入出力 | ○ | × `SVC` は演算例外 (P-175) | M |

### 2.5 調べる・測る道具

| 道具 | Zeus | cobol-on-java | 規模 |
| --- | --- | --- | --- |
| 依存関係と未対応構文の一覧 (CSV) | ○ `zdeps` | △ `verify corpus` が診断を数える | S |
| 固定長レイアウトの写し句を作る | ○ `zlayout` | × | M |
| 文・分岐のカバレッジ (LCOV、HTML) | ○ `zcov` | × | M |
| C1 網羅の試験の自動生成 | ○ `ztestgen` (concolic) | × (`cobol-junit` は手で書く試験) | XL |
| 単独実行ファイル | ○ | — jar で配る | — |

### 2.6 逆に Zeus に無く、cobol-on-java にあるもの (probe で見えたもの)

§3.1 で同じ原文を Zeus で翻訳したら、Zeus が断ったもの:

| 原文 | Zeus の診断 | cobol-on-java |
| --- | --- | --- |
| `CBLFUNC` / `CBLFUNX` | `unknown function EXP` | `EXP` などを持つ (P-065) |
| `CBLC72B` | 72 桁目の引用符で `unterminated literal` | 次の行を見て読み分ける (P-084)。どちらが実機どおりかは未決 |
| `ASMEXE` | `MVO` を知らない、`B` の `USING` が無いと断る | `MVO` などを実行する (P-173) |
| `ASMDC2` | 浮動小数点の `DC` の書き方を断る | 組み立てる (設計 27 §5) |
| `CBLBIN31` (COMPAT) | 桁数の上限で断る | 同じく断る (P-005)。揃っている |

## 3. z/OS との互換性: いま何で確かめているか

どちらの処理系も、<b>z/OS の実機の出力とまだ突き合わせていない</b>。互換性の主張は次の 4 つに拠っている。

| 基準 | cobol-on-java | Zeus |
| --- | --- | --- |
| 言語の規格 (NIST CCVS85) | 受理 444 / 458 本 (96.9%)、流して合格 346 / 361 本 (95.8%)、検査ごとに 8723 件中 1 件だけ落ちる。全モジュールを流す | 必須 8 モジュールと SG / OB / DB / RW に合格 (GnuCOBOL の基準線と同数。Zeus 0003) |
| 命令 (Hercules) | V2: 10 進の加減乗除・丸め・編集・PACK/UNPK・S0C7 / S0CB・HFP。合成 485 件。HLASM 26 件 | `zeus-hercdiff` で 10 進命令の差を比べる (Zeus 0003 C2) |
| 業務資産 | Bank-of-Z: CICS 32 / 32 本と IMS 10 / 11 本が翻訳できる。IMS のバッチ 5 本とオンライン 5 本を流して資産の意図どおり。CardDemo と GenApp は測っていない。`corpus.tsv` に行が無い | CardDemo 41 / 41、GenApp 31 / 31、Bank-of-Z 42 / 43 が翻訳できる。各業務の場面を台本で流す。追加のコーパス 9 本 (zfam ほか、Zeus 0022) |
| 実機向けの検査一式 | `tools/zos-probe`: 暫定判断のうち解消条件が「実機で確かめる」の約 40 項目を問いにした原文と JCL。<b>ローカルの観測だけがあり、実機では流していない</b> | ZCVS (COBOL 67 本)、ZPVS (PL/I)、ZHVS (HLASM) を提案 (Draft)。ZCVS は z/OS 向けの JCL を書き出せる。<b>実機の結果は文書に無い</b> |

ここから言えること:

1. 規格への適合 (CCVS85) と 10 進命令 (Hercules) は、cobol-on-java のほうが細かく数えている。
   Zeus は「GnuCOBOL と同数」で、検査ごとの数を出していない。
2. <b>業務資産で動かした幅は Zeus がはるかに広い</b>。cobol-on-java は Bank-of-Z の翻訳の数と IMS の経路だけであり、
   CICS の場面を通しで流したものも、CardDemo / GenApp も無い。外の基準として、ここがいちばん薄い。
3. 処理系定義の振る舞い (符号ニブル、SYNC の詰め物、`NUMPROC`、`TRUNC`、JCL の DISP / GDG など) は、
   どちらも公開文書を読んで決めている。cobol-on-java は決めたことを暫定判断に「実機で確かめる」として残して
   あり、それを一度に問う probe も揃っている。<b>実機を 1 度使えれば、約 40 項目が一度に決着する</b>。
4. TN3270 はどちらも s3270 で確かめただけで、z/OS の CICS の画面とは比べていない (P-191)。

### 3.1 同じ probe を 2 つの処理系で流した

`tools/zos-probe` の COBOL・PL/I・HLASM の段を Zeus でも流し (`tools/zos-probe/run-zeus.sh`、結果は
`results/zeus/`)、cobol-on-java のローカルの観測と `ProbeTool prb` で突き合わせた。コードページは
両方 IBM-1047 に揃えた (Zeus の既定は CCSID 37。揃えないと定数の byte の差が処理系の差に化ける)。

| 突き合わせ | 行 |
| --- | --- |
| 同じ値 | 536 |
| 食い違い | 75 (11 の項目) |
| Zeus だけが出した | 15 (cobol-on-java が異常終了し、Zeus は異常終了せずに値を出した段。下の表の 1 行目) |
| cobol-on-java だけが出した | 218 (JCL の probe 47 行は Zeus で流していない。`DEBUG-LINE` 16 行は Zeus が宣言部を動かさない。残りは Zeus が翻訳を断った原文) |

<b>Zeus は実機ではない</b>。食い違った行は「少なくとも片方が実機と違う」ことだけを言い、揃った行も
実機どおりとは限らない (同じ文書を同じように読み違えうる)。それでも、実機を待たずに調べる行が絞れる。
食い違いを根拠の強さで分けた:

| 項目 | 事例 | Zeus | cobol-on-java | 根拠 | 見立て |
| --- | --- | --- | --- | --- | --- |
| 不正な 10 進データとゼロ除算 | `B.01`〜`03`、`B.08` (既定・`NUMPROC(PFD)`・`SSRANGE` の各段) | 異常終了しない。`X'F1FAF3'` に `ADD 1` で 104、ゼロ除算は値を残す | データ例外 (S0C7) と 10 進除算例外 (S0CB) で止める | `AP` / `DP` の例外の条件は Hercules で裏付け済み (V2、設計 20) | **cobol-on-java**。Zeus の不具合の候補。実資産で値が化ける形なので重い |
| `USE FOR DEBUGGING` の宣言部 | `D.P080.*` | 動かさない | 動かす | 宣言部は実行時オプション `DEBUG` で動き、既定は `NODEBUG` (Enterprise COBOL の実行時オプションの説明。85 規格の object time switch。P-192) | **Zeus** に分がありそう。cobol-on-java は「動かし過ぎ」の側。probe の `DBG2` / `DBG4` は実行時オプションを渡していないので、どちらの道具もまだ確かめていない |
| 浮動小数点から固定小数点 (`ROUNDED` なし) | `L.P127.TWO3` | 0.66666 (切り捨て) | 0.66667 (最下位で丸め) | Enterprise COBOL Programming Guide「Conversions and precision」: rounding occurs in the low-order position of the target (P-127) | **cobol-on-java**。Zeus の不具合の候補 |
| 符号つき数字項目への `INSPECT REPLACING` / `CONVERTING` | `I.P085.01`〜`04`、`11` | 符号を残す (`D7`) | 符号なしの像を書き戻す (`F7`) | 85 規格 6.19.4 一般規則 2c (符号なし項目へ移して見る)、NC216A (P-085) | **cobol-on-java** に規格の根拠。ただし書き戻しで符号を落とすことまでは規格に書いていない。実機で決める |
| `SIGN SEPARATE` の符号のバイト | `I.P085.06`、`08`、`09` | `-` を数え、`+` に置き換える | 触れない | 同上 (走査する像に符号のバイトは入らない) | **cobol-on-java** |
| `COMP-1` / `COMP-2` の `VALUE 0.1` と転記 | `L.P018.*` | 丸め (`4019999A`) | 切り捨て (`40199999`) | HLASM の `DC E'0.1'` の既定は丸め。COBOL の翻訳器の定数の扱いは文書に無い (P-018) | **Zeus** に分がありそう。`L.P127.D1TOF` / `S1TOF` の差はここから来ている |
| 空白だけの行を数字項目へ `ACCEPT` | `A.P083.2.NUM` | 空白 (`40`) | `00000` | `ACCEPT` は英数字の転記として移す | **Zeus** に分がありそう (P-083) |
| 入力が尽きたあとの `ACCEPT` | `A.P083.3.NUM` | 空白 | `00000` | 文書に無い | 実機待ち |
| 負のゼロの転記 | `N.P013.*.07`、`N.P015.*.07` | 正のゼロへ直す | `D` を保つ | 桁の違う受け側を `ZAP` で作るなら正のゼロ (Hercules で確かめた `ZAP` の規則)。同じ桁なら `MVC` かもしれない (P-013) | 受け側の形で答えが分かれうる。実機待ち |
| 符号の編集に負のゼロ | `E.P014.01`〜`03.05` | `+0000` | `-0000` | 送り側 -0.01 を整数へ切り捨てた値の符号 | 実機待ち (上と同じ問い) |
| 全桁抑制に負のゼロ | `E.P014.08.04`、`05` | 空白 | `0000` | 同上 | 実機待ち |
| 開いた直後の `LINAGE-COUNTER` | `R.P088.FIRST`、`R.P072.*` | 5 から | 4 から | 「紙は 1 行目にいて n 行送る」なら 5 (P-088 が参照実装の説明として挙げている) | **Zeus** の読みが P-088 の「ずれうる」側。実機待ち |
| `VALUE` の無い作業場所 | `W.P025.*` | 型ごとの初期化 (`INITIALIZE` と同じ) | すべて空白 | IBM の既定は文書から決められない (P-025) | 実機待ち。どちらの値も推測 |
| PL/I のビット列の詰め方 | `M.P185.M3`、`M8` | — | — | LRM の写像の規則 (P-185) | 実機待ち (翻訳リストの `AGGREGATE` と照らす) |
| HLASM のプログラムマスク | `H.20.1` | — | — | 入口のマスク (P-174) | 実機待ち |
| 範囲外の添字 (`SSRANGE` なし) | `B.05.AFTER` | `X` | `Z` | 記憶域の並びで決まる | 比べる意味が無い (実機でも記憶域の配置しだい) |

## 4. 改善計画

CLAUDE.md の型 (外の基準を先に置く → いちばん大きい塊から → 直すたびに測る) に沿って並べる。
規模は Zeus 0007 と同じ目安 (XL = 数千行、L = 千行前後、M = 数百行、S = 百行前後)。

### 段階 0: 測る道具を先に足す (機能を足す前)

| # | 規模 | 項目 | 外の基準 | 完了の目安 |
| --- | --- | --- | --- | --- |
| 0-1 | S | **z/OS の実機で `tools/zos-probe` を流す** (`docs/zos-probe/runbook.md`) | 実機 | 約 40 の暫定判断が「決着」か「直す」に分かれる |
| 0-2 | M | **CardDemo と GenApp を `corpus.tsv` に足す** (ライセンスと sha256 を確かめて固定する。NFR-042)。翻訳できる本数を数える | Zeus と同じ資産。比べられる数になる | **測った** (2026-10-20、`tools/verify/measure-apps.sh`)。CardDemo 44 本中 10 本、GenApp 31 本中 0 本 (Zeus は 41 / 41、31 / 31。Zeus は CardDemo の `app-*/cbl/*.CBL` を数えていない)。下の表 |
| 0-3 | M | Bank-of-Z の CICS の場面 (顧客・口座の照会・作成・更新・削除、入出金、振替) を **TN3270 の入口と s3270 の台本で通しに流す** | 資産の意図どおりの画面 | 場面ごとの成否が README に載る |
| 0-4 | S | `docs/unsupported-syntax-and-rationale.md` を今の状態に直す (BMS、コンテナ、`RENAMES` などは既に実装済みなのに「未対応」のまま) | 翻訳器 | **済** (2026-10-20)。表の 41 行を `UnsupportedSyntaxCatalogTest` の事例にした。旧構文の「専用の診断」は実装されていなかったので文書を直した |
| 0-5 | S | Zeus の追加コーパス (zfam、zecs、cobol-check など、Zeus 0022) のうち Apache-2.0 と両立するものを `corpus.tsv` に足す | 資産 | 翻訳の率 |

| 0-6 | S | **Zeus との突き合わせを測定に加える** (`run-zeus.sh`)。直すたびに一致と食い違いの数を README に残す | 2 つ目の独立した実装 | 食い違い 75 行の各項目に、決着か「実機待ち」が付く |
| 0-7 | S | Zeus から移す部分を Apache-2.0 で出す ADR を書く (権利者の判断を記録する) | — | **提案を書いた** (ADR-0016)。権利者の承認待ち。ICU のコードページの表と Unicode のデータは対象外 |
| 0-8 | S | §3.1 で cobol-on-java に根拠があった食い違い (不正な 10 進データとゼロ除算で止まらない、`TWO3` の丸め、`INSPECT` の符号、`SIGN SEPARATE`) と、Zeus が断った `EXP`・`MVO`・浮動小数点の `DC` を、Zeus の改善として Zeus 側に残す | — | **起票した** (2026-10-20): Zeus #24 (10 進データとゼロ除算)、#25 (浮動小数点から固定小数点の丸め)、#26 (`INSPECT` の符号)、#27 (`EXP`)、#28 (HLASM の `MVO` と `D` 形式の `DC` の注記) |

0-2 と 0-3 の結果で段階 1 の順番を入れ替える。下の順番は<b>今の見込み</b>である。

**0-2 の初回の測定で止まった行の塊** (CLAUDE.md §4。理由の文面ではなく止まった原文の行で数えた):

| 塊 | 本数 | 止まった行の例 | 扱い |
| --- | --- | --- | --- |
| `ASKTIME ABSTIME(項目)` の項目が `PIC S9(8) COMP` | GenApp 14 | `lgacdb02` 196 行 | CICS は 8 byte のパック 10 進を書く。ホストでは隣の記憶域を上書きして通る。P-146 (領域より長い `LENGTH`) と同じ形の暫定の仕様が要る |
| `RETURN TRANSID(データ名)` | CardDemo 9 | `COMEN01C` 107 行 | P-097 で定数だけにしていた |
| 部分参照の中の `LENGTH OF` | CardDemo 7 | `MOVE DFHCOMMAREA (1:LENGTH OF CARDDEMO-COMMAREA) TO ...` | 部分参照の長さに `LENGTH OF` を書けない |
| 略記した複合条件 | CardDemo 3 | `NOT = SPACES AND LOW-VALUES`、`WS-IDX > 5 OR AUTHS-EOF OR ERR-FLG-ON` | 表意定数を略記の目的語にできない。条件名を略記の目的語と読む |
| `ACCEPT ... FROM SYSIN` | CardDemo 2 | `ACCEPT PRM-INFO FROM SYSIN` | 環境名を `SPECIAL-NAMES` 無しで書く形。IBM は許す (CLAUDE.md §3 の「狭く決めていた」形) |
| BMS の `VALIDN` | GenApp 5 | `ssmap.bms` | DFHMDF の `VALIDN` を読まない |
| `ASSIGN SYSID` | GenApp 3 | — | 未実装 |
| MQ の写し句 (`CMQGMOV`、`CMQODV`) | CardDemo 3 | — | MQ (2-4) |

**同じ日に直した塊と、その奥に出た塊** (CLAUDE.md §2。直すたびに CCVS85 も測り、翻訳 444 / 458 本・実行の合格 346 / 361 本・検査 8723 件中 1 件落ちから変わらないことを確かめた):

| 直したもの | コミット | 結果 |
| --- | --- | --- |
| 写し句を大文字と小文字を区別せずに引く、`.dcl` | `2d5cc40` | GenApp の「写し句が無い」が消え、本物の未対応が出た |
| 部分参照の中の `LENGTH OF` | `36e6907` | 7 本が次の塊へ |
| `ACCEPT ... FROM SYSIN` (環境名) | `d2a8598` | 2 本が次の塊へ |
| 略記した比較の先読みが次の文の `=` を拾う | `7a18513` | 3 本が次の塊へ |
| 数字だけの手続き名を桁数の検査から外す | `8979467` | CCVS85 の NC107A が戻り、README の記録 (444 / 346) に一致 |
| `RETURN TRANSID(データ名)` | `846a8f2` | 塊が消えた |

CardDemo は 44 本中 9 → 11 本。奥に出た塊は、データ名の `MAP(...)` (7 本)、`READ` / `STARTBR` / `WRITE` の
`DATASET(...)` (`FILE` の古い同義語、8 本)、入口の別名の `ENTRY` (3 本)、`EXEC` の未知の処理系 (3 本) である。
GenApp は 0 本のままで、`ASKTIME ABSTIME` に 4 byte の 2 進項目を渡す形 (14 本) が先頭にある。


### 段階 1: 業務資産を止めている塊

| # | 規模 | 項目 | 理由 | 測り方 |
| --- | --- | --- | --- | --- |
| 1-1 | L | **動的 SQL** (`PREPARE` / `EXECUTE` / `DESCRIBE`、`SQLDA`、`EXECUTE IMMEDIATE`)、`WHENEVER` | Bank-of-Z の XFRFUN。Db2 を使う資産では多い | 実 Db2 (`infra/db2`) と H2 |
| 1-2 | M | **Db2 のユーティリティ** (DSNTEP2、DSNTIAD、DSNTIAUL、`IKJEFT01` の `DSN RUN PROGRAM`) | 表の作成と初期データの投入にジョブで使う。CardDemo / GenApp / Bank-of-Z の構築 | 実 Db2 |
| 1-3 | M | **物理ブロック** (`RECORDING MODE U` / `S`、BDW / RDW / SDW、`BLOCK CONTAINS`) | バッチのデータセットをホストから持ち込むと必ず要る | zos-probe に QSAM の事例を足す |
| 1-4 | M | **報告書作成の CONTROL と SUM** | CCVS85 RW。帳票の資産 | CCVS85 の RW モジュール |
| 1-5 | M | **DBCS (`PIC G`) と NATIONAL の残りの文**、コードページ 1390 / 1399 (P-002) | 日本の資産 | 設計 28 の検証、zos-probe の CBLCP |
| 1-6 | M | `COMP-1` / `COMP-2` の残りの演算と `DISPLAY` | Bank-of-Z が使う | Hercules の HFP (V2) |
| 1-7 | S | `CALL ... BY VALUE`、`SORT-RETURN` | 小さく、資産で頻出 | CCVS85 の IC / ST |

### 段階 2: オンラインとサブシステム

| # | 規模 | 項目 | 理由 | 測り方 |
| --- | --- | --- | --- | --- |
| 2-1 | L | **会話型の `RECEIVE` と `RECEIVE INTO`** | TN3270 の入口で、task の中で入力を待つ資産が動かない。virtual thread で task を止めて待てるので、JVM では作りやすい | s3270 |
| 2-2 | M | TN3270 の残り: TLS (JSSE)、SYSREQ / LOGOFF、Read Partition Query、大きい画面、sign on (CESN) | P-191 の解消 | s3270、`-oversize` |
| 2-3 | M | 印刷装置 (ASSOCIATE、SCS)、START を印刷装置へ | 帳票をオンラインで出す資産 | pr3287 |
| 2-4 | XL | **IBM MQ**: MQI を JMS へ写す (IMS の電文と同じく `cobol-ims-jms` の形)。CKTI のトリガーモニター | CardDemo の MQ の経路、Zeus 0005 | RabbitMQ (`infra/rabbitmq`)。IBM MQ の実ブローカがあればそれ |
| 2-5 | L | z/OS Connect の API プロジェクトを読み、Spring の入口から COMMAREA / コンテナへ写す | Bank-of-Z の API | Bank-of-Z の API プロジェクト |
| 2-6 | M | IMS の GSAM、SPA、電文を読む BMP (P-166)、DFSURGU0 | Bank-of-Z が使わないので測る基準が無い (P-166)。CardDemo の IMS の経路が基準になる | CardDemo の IMS |
| 2-7 | M | `HANDLE ABEND PROGRAM`、CICS のイベント処理 | GenApp | GenApp |

### 段階 3: 調べる道具

| # | 規模 | 項目 | 理由 |
| --- | --- | --- | --- |
| 3-1 | M | **COBOL の文・分岐のカバレッジ** (生成する class に原文の行を持たせ、JaCoCo か独自の記録で LCOV を出す) | 移行の試験の十分さを示せる。JVM の道具に乗せやすい |
| 3-2 | S | 依存関係と未対応構文の CSV (`verify corpus` を広げる) | 移行の見積もり |
| 3-3 | M | 固定長レイアウトの写し句を作る | ファイル・COMMAREA・MQ の電文の突き合わせ |
| 3-4 | XL | C1 網羅の試験の自動生成 | `cobol-junit` と組み合わせると強いが、先に 3-1 が要る |

### 移植元 (Zeus のファイル)

権利者の判断で Zeus から移せる (段階 0-7)。言語が違う (Rust → Java) ので写すのではなく書き直しになるが、
仕様の読み方と試験の事例はそのまま使える。行数は Zeus `106fdc8` のもの。

| 計画 | Zeus の実装 | 行 |
| --- | --- | --- |
| 1-1 動的 SQL、`WHENEVER` | `zeus-db2/src/host.rs`、`zeus-cobol/src/front/parser/exec.rs`、`front/syslib.rs` (SQLDA) | 1271 ほか |
| 1-2 Db2 のユーティリティ | `zeus-jcl` の DSN 系 | 要調査 |
| 1-3 物理ブロック | `zeus-ds/src/physical.rs`、`zeus-cobol/src/vm/files.rs` | 366 / 3246 |
| 1-4 報告書作成 | `zeus-cobol/src/vm/report.rs` | 473 |
| 1-5 DBCS、コードページ | `zeus-cobol/src/codepage.rs`、`codepages/` (54 種の表) | 467 + 表 |
| 1-7 整列の特殊レジスタ | `zeus-cobol/src/vm/sort.rs` | 558 |
| 2-1 会話型の `RECEIVE` | `zeus-cics/src/dispatch.rs` (コルーチン。Java では virtual thread で書き直す) | 189 |
| 2-2 TLS | `zeus-cics/src/tls.rs` | 242 |
| 2-3 印刷装置、IND$FILE | `zeus-cics/src/tn3270.rs` の ASSOCIATE と SCS、`indfile.rs` | 440 ほか |
| 2-4 MQ | `zeus-mq` (MQI、トリガーモニター `monitor.rs`) | 2685 |
| 2-5 z/OS Connect | `zeus-zconnect` | 2525 |
| 2-6 GSAM、アンロード | `zeus-ims/src/gsam.rs`、`unload.rs` | 216 / 271 |
| 2-7 イベント処理 | `zeus-cics/src/events.rs` | 351 |
| 3-1 カバレッジ | `zeus-cobol/src/coverage.rs`、`zcov` | 603 |
| 3-2 依存関係 | `zeus-deps` | 4259 |
| 3-3 レイアウト | `zeus-layout` | 2461 |
| 3-4 試験の生成 | `zeus-testgen`、`zeus-cobol/src/concolic.rs` | 8281 ほか |
| 4 テープ、再始動、翻訳リスト | `zeus-ds/src/tape.rs`、`vm/checkpoint.rs`、`listing.rs` | 855 / 554 / 492 |

### 段階 4: 後回しにするもの

仮想テープ (AWSTAPE)、`RERUN` の再始動、翻訳リスト、`DYNAM` / `CANCEL` の再ロードと DLL、`THREAD` / `LP(64)`、
PL/I の型の残り (COMPLEX、AREA など)、HLASM の `SVC` とリンク。業務資産の測定 (段階 0) で必要と分かったものから
前へ出す。

## 5. cobol-on-java が先に取れるもの

Zeus が対象外にしているが、JVM の上では自然に作れるもの:

- **OO COBOL の Java 呼び出し** (`REPOSITORY`、`INVOKE`)。Bank-of-Z の IBTRAN は Zeus でも cobol-on-java でも
  止まる 1 本であり、cobol-on-java は Java との相互呼び出し (設計 75) を既に持つ
- **IMS の Java (JMP)** と、CICS の Java program との LINK
- Spring の作業単位と CICS / IMS / Db2 の同期点を一緒に確定する形 (設計 77、P-143)

## 6. 次にやること

1. 0-4 (未対応構文の一覧を直す) と 0-2 (CardDemo / GenApp を `corpus.tsv` に足す) から始める。
   どちらも機能を足さずに、測る数を増やす作業である。0-6 (Zeus との突き合わせ) は今回の測定で道具ができた
2. (2026-10-20 済) §3.1 で「Zeus に分がありそう」とした 3 件 (P-018 の定数の丸め、P-083 の空行、P-192 の `USE FOR DEBUGGING`) は、暫定判断の「どこがずれうるか」に
   Zeus の読みを書き足し、実機の probe の問いに印を付ける。実機を待たずに変えはしない (どちらも推測である)
3. 0-2 の結果でいちばん大きい塊を選び、段階 1 の順番を決め直す
4. 実機が使えるようになったら、まず 0-1 (zos-probe) を流す。§3.1 の「実機待ち」の行が最初に見る行である
