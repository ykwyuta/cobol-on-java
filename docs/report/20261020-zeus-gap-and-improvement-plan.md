# Zeus との機能差、z/OS 互換性の確認状況、改善計画 (2026-10-20)

| 項目 | 内容 |
| --- | --- |
| 比べたもの | cobol-on-java `claude/jvm-cobol-requirements-1phhbu` (TN3270 の入口を足した時点)、Zeus `main` (106fdc8) |
| 読んだもの | Zeus: `README.md`、`docs/proposal/0003`〜`0022`、`docs/inventory/`、`docs/help/`、`tools/`。cobol-on-java: README の「現在のステータス」、設計、暫定判断、`tools/zos-probe`、ソース |
| 確かめていないこと | <b>Zeus の記述は Zeus 自身の文書による</b>。Zeus を動かして確かめてはいない。cobol-on-java の側はソースと試験で確かめたが、「要確認」と書いた行は確かめきれていない |

## 0. 読み方

「受け付ける」「動く」「z/OS と同じ結果になる」は別のことである (Zeus の README も同じ区別をしている)。
本書の表は次の記号で書く。

| 記号 | 意味 |
| --- | --- |
| ○ | 実装があり、試験がある |
| △ | 一部だけ。何が欠けているかを書く |
| × | 無い。翻訳で断るか、構文を持たない |
| — | 方式が違うので比べられない |

**ライセンスの注意**: Zeus は GPL-3.0-or-later、cobol-on-java は Apache-2.0 である。Zeus のコード、
試験、検査スイート (ZCVS / ZPVS / ZHVS) の原文を写すことはできない。Hercules と同じく
<b>外から動かして振る舞いを観測する</b>使い方に限る (CLAUDE.md「決めごと」)。

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
| `EXEC SQL WHENEVER` | ○ | 要確認 | 翻訳器のソースに `WHENEVER` が無い | S |
| `RECORDING MODE U` / `S`、物理ブロック (BDW / RDW / SDW)、`BLOCK CONTAINS`、`APPLY WRITE-ONLY`、`RESERVE` | ○ | △ F / V だけ | `FileDescription.recordingOf` が U / S を断る | M |
| 仮想テープ (AWSTAPE)、標準ラベル、複数巻、`REVERSED`、`NO REWIND` | ○ | × | `REVERSED` は断る (未対応構文 §2.1) | L |
| `RERUN` による検査点と再始動 | ○ | × 構文は読むが効かない | `CobolParser.g4`「どれも翻訳の結果には効かない」 | L |
| 報告書作成の `CONTROL HEADING` / `FOOTING`、`SUM`、`GENERATE 報告書名` | ○ (CCVS85 RW 40/40) | × | `DataDivisionBuilder` が断る | M |
| DBCS (`PIC G`、`DISPLAY-1`、`NSYMBOL(DBCS)`) | ○ | × | P-006。`OptionSupport` | M |
| `USAGE NATIONAL` の文の範囲 | ○ 数値・編集・グループも | △ 転記・比較・関数だけ | `NationalGuard` | M |
| コードページ | ○ 54 種 (日中韓の混在を含む)、`CBL CODEPAGE(n)` | △ 5 種 (1047 / 037 / 930 / 939 / ASCII)。`CODEPAGE` は 1047 だけ受ける。1390 / 1399 は無い (P-002) | `CodePages`、`OptionSupport` | M |
| EBCDIC の原文 (行区切り・80 byte 固定) を読む | ○ | 要確認 | — | S |
| `COMP-1` / `COMP-2` の除算・べき乗・`DISPLAY`・`ON SIZE ERROR` | ○ | △ `+ - *`、転記、比較 | P-127、未対応構文 §2.3 | M |
| `CALL ... BY VALUE` | ○ | × | `ProcedureBuilder`「BY VALUE is not supported yet」 | S |
| `DYNAM` / `NODYNAM` と `CANCEL` の再ロード、`>>CALLINTERFACE`、DLL / `EXPORTALL` | ○ | △ オプション名を受けるだけ | `OptionSupport` | M |
| 翻訳リスト (`SOURCE` / `MAP` / `LIST` / `OFFSET` / `XREF` / `VBREF`)、`FLAG` | ○ | × 名前を受けて出さない | `OptionSupport` | M |
| `SORT-RETURN` / `SORT-CONTROL` などの整列の特殊レジスタ、DFSORT の制御文の上書き | ○ | 要確認 | 翻訳器のソースに `SORT-RETURN` が無い | S |
| `THREAD`、`LP(64)` | ○ (Zeus 独自の仮想 ABI) | × | — | M |
| 入れ子プログラム | ○ | △ 並べる形だけ (入れ子の文法は無い) | `CobolCompiler` の説明 | M |
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
| sign on (CESN) | 要確認 | × | P-191 | M |

### 2.3 Db2・MQ・IMS・REST

| 機能 | Zeus | cobol-on-java | 根拠 | 規模 |
| --- | --- | --- | --- | --- |
| IBM MQ の MQI (`MQOPEN` / `MQPUT` / `MQGET` / `MQSET`、publish/subscribe、`MQCB`) | ○ | × | ソースに無い | XL |
| Db2 のユーティリティ (DSNTIAUL、DSNTEP2、DSNTIAD、`DSN RUN PROGRAM`) | ○ | × | ソースに無い。`IKJEFT01` は TSO のコマンドだけ | M |
| Db2 の SQL を PostgreSQL で流す (方言の変換、SQLSTATE から SQLCODE への対応表) | ○ | — JDBC で Db2 か互換の DB へ直接。方言の変換と SQLCODE の対応表は無い | 設計 77 | L |
| IMS の GSAM、SPA、電文を読む BMP | ○ | × | P-166 | M |
| IMS の MFS | ○ (Zeus 0006 では残りあり) | × | 調査文書だけ | L |
| IMS の SQL (`SQLIMS`) | ○ | × | — | L |
| `EXEC DLI` | 要確認 (COBOL) | × | — | M |
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

## 4. 改善計画

CLAUDE.md の型 (外の基準を先に置く → いちばん大きい塊から → 直すたびに測る) に沿って並べる。
規模は Zeus 0007 と同じ目安 (XL = 数千行、L = 千行前後、M = 数百行、S = 百行前後)。

### 段階 0: 測る道具を先に足す (機能を足す前)

| # | 規模 | 項目 | 外の基準 | 完了の目安 |
| --- | --- | --- | --- | --- |
| 0-1 | S | **z/OS の実機で `tools/zos-probe` を流す** (`docs/zos-probe/runbook.md`) | 実機 | 約 40 の暫定判断が「決着」か「直す」に分かれる |
| 0-2 | M | **CardDemo と GenApp を `corpus.tsv` に足す** (ライセンスと sha256 を確かめて固定する。NFR-042)。翻訳できる本数を数える | Zeus と同じ資産。比べられる数になる | 翻訳の率と、止まった行の上位 10 塊の一覧 |
| 0-3 | M | Bank-of-Z の CICS の場面 (顧客・口座の照会・作成・更新・削除、入出金、振替) を **TN3270 の入口と s3270 の台本で通しに流す** | 資産の意図どおりの画面 | 場面ごとの成否が README に載る |
| 0-4 | S | `docs/unsupported-syntax-and-rationale.md` を今の状態に直す (BMS、コンテナ、`RENAMES` などは既に実装済みなのに「未対応」のまま) | — | 一覧と翻訳器の診断が食い違わない |
| 0-5 | S | Zeus の追加コーパス (zfam、zecs、cobol-check など、Zeus 0022) のうち Apache-2.0 と両立するものを `corpus.tsv` に足す | 資産 | 翻訳の率 |

0-2 と 0-3 の結果で段階 1 の順番を入れ替える。下の順番は<b>今の見込み</b>である。

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
   どちらも機能を足さずに、測る数を増やす作業である
2. 0-2 の結果でいちばん大きい塊を選び、段階 1 の順番を決め直す
3. 実機が使えるようになったら、まず 0-1 (zos-probe) を流す
