# cobol-on-java

JVM 上で動作する COBOL 処理系。IBM メインフレーム (z/OS + Enterprise COBOL for z/OS +
Language Environment) 上での実行時の**振る舞い**を可能な限り忠実に再現し、
既存のメインフレーム資産をソース無修正で JVM 上へ移行できることを目指す。

## ドキュメント

- [作業の進め方](CLAUDE.md) — 測って直して測る回し方と、そこで学んだこと
- [要件定義書](docs/requirements.md) — プロジェクトの目的、互換性レベルと検証レベルの定義、
  機能要件 / 非機能要件、決定事項、開発フェーズ、リスクと未決事項
- [設計: 全体アーキテクチャ](docs/design/00-overview.md)
- [設計: cobol-runtime (P0-a)](docs/design/10-runtime-p0a.md)
- [設計: cobol-oracle (V2 期待値の採取)](docs/design/20-oracle.md)
- [設計: 検証基盤 (CCVS85 と OSS コーパス)](docs/design/25-verification.md)
- [設計: cobol-compiler のプリプロセッサ (P0-b)](docs/design/30-compiler-preprocessor.md)
- [設計: cobol-compiler の構文解析 (P0-b)](docs/design/40-parser.md)
- [設計: データ部の記憶域割り付け (P0-b)](docs/design/50-data-layout.md)
- [設計: 手続き部と一意名の解決 (P0-b)](docs/design/60-procedure.md)
- [設計: 報告書作成機能](docs/design/65-report-writer.md)
- [設計: コード生成 (P0-b)](docs/design/70-codegen.md)
- [設計: Java 連携](docs/design/75-java-interop.md)
- [設計: JUnit による COBOL 単体テスト](docs/design/76-junit-testing.md)
- [設計: Spring Boot 4.1 による CICS / Db2 連携](docs/design/77-spring-cics-db2.md)
- [設計: CICS 資源ポート (時間・間隔・端末・システム情報) と BMS 実行時](docs/design/79-cics-resource-ports.md)
- [設計: BMS 画面の Web 描画 (Thymeleaf adapter) と renderer spike](docs/design/81-bms-web-renderer.md)
- [設計: 端末へ出す START (TERMID) と一時データの trigger level による ATI](docs/design/83-cics-terminal-start-ati.md)
- [設計: デモ環境の簡易認証 (principal と CICS の user ID、transaction と START USERID の権限)](docs/design/84-cics-demo-security.md)
- [設計: 断っていた CICS / Db2 の形の暫定仮仕様 (file control の残り、SYSID、NOSUSPEND、TD、START)](docs/design/85-cics-provisional-specs.md)
- [設計: IMS サブシステム連携 (IMS DB / IMS TM)](docs/design/78-ims-subsystem.md)
- [設計: ファイル入出力](docs/design/80-file-io.md)
- [構文・振る舞いリファレンス](docs/syntax-and-behavior-reference.md) — サポート構文と文ごとの意味論・実行時挙動の一覧
- [未対応構文とその理由](docs/unsupported-syntax-and-rationale.md) — 未対応の構文・オプション、設計判断の根拠、代替手段
- [利用ガイド](docs/guide.md) — コンパイラ起動オプション、単一・複数プログラムの翻訳と実行手順
- [アーキテクチャ決定記録 (ADR)](docs/decisions/README.md)
- [敵対的設計レビュー: Java / JUnit / CICS / Db2 / BMS](docs/reviews/2026-09-09-interop-adversarial-review.md)
- [IMS の概要と対応検討](docs/research/ims-overview-and-support-scope.md) — IMS (TM/DB) の仕組み、CICS/Db2 との違い、COBOL (DL/I) 連携と移行スコープ
- [IMS TM と IBM MQ のアーキテクチャ関係解説](docs/research/ims-tm-and-mq-architecture.md) — メッセージキュー駆動モデル、OTMA 連携、MPP プログラムの仕組み
- [RabbitMQ + JMS による IMS TM メッセージ基盤の実現検討](docs/research/ims-tm-rabbitmq-jms-proposal.md) — Docker Compose 環境、JMS 3.0 抽象化、COBOL 透過的実行方式
- [IMS DB の RDB ストレージエンジン化検討報告](docs/research/ims-rdb-storage-engine-report.md) — PostgreSQL / Db2 を用いた非正規化・生バイト格納による透過的 DL/I 実現方式
- [IMS DB 運用管理ツールの設計提案](docs/research/ims-db-admin-tools-proposal.md) — メインフレーム互換ユーティリティ (DFSURGU0/DFSURGL0) とモダン CLI
- [IMS MFS と CICS BMS の比較・Web UI 再現検討](docs/research/ims-mfs-bms-comparison-and-web-ui-proposal.md) — 中立スクリーンモデルと Thymeleaf/CSS Grid による 3270 画面再現
- [IMS DB のロック競合・デッドロック回避設計](docs/research/ims-db-locking-and-deadlock-avoidance.md) — ルートアンカーロック、MVCC活用、DMLソート、U0777自動リトライ
- [DL/I の複雑仕様と実行時セマンティクス詳細検討](docs/research/ims-dli-complex-semantics-report.md) — コマンドコード (*D/*F/*P)、親境界 (Parentage)、重複キー規則、ステータスコード完全対応
- [暫定対応の記録](docs/decisions/provisional.md) — 先送りした判断と、その解消条件

## 主要な技術方針

要件定義書 第 15 章「決定事項」より抜粋。

| 項目 | 決定 |
| --- | --- |
| 互換性の基準 | Enterprise COBOL for z/OS 6.x の外部挙動 |
| 検証オラクル | Hercules (z/Architecture 命令レベル)。z/OS 実機は利用しない |
| 互換性の管理 | 目標を表す互換性レベル L0〜L3 と、裏付けを表す検証レベル V0〜V2 の 2 軸 |
| 期待値の採取 | Hercules の `.tst` / `loadcore` 機構。採取と回帰を同一機構で回す |
| コード生成 | ASM による JVM バイトコードの直接生成 |
| メモリモデル | 全データ項目を連続バイト列上のオフセット・長さのビューとして表現 |
| 実装言語 | Java 21 |
| 構文解析 | 手書きプリプロセッサ + ANTLR4 のハイブリッド |
| ジョブ実行 | 内部ジョブモデル + JCL フロントエンド |
| データセット | EBCDIC 生バイト既定 + DD 単位の変換アダプタ |

## モジュール

| モジュール | 責務 | 状態 |
| --- | --- | --- |
| `cobol-runtime` | データ表現・10 進演算・編集移送・文字コード変換・データセットの意味論 | P0-a 第 1 増分 実装済。順編成・相対編成・索引編成の読み書き、割当ての検査 (領域の限り・形・開く段)、区分データセットのディレクトリ (メンバの一覧と並び) を追加 |
| `cobol-oracle` | Hercules 用テストの生成と期待値の採取 | 第 1 増分 実装済 |
| `cobol-verify` | 外の基準で測る。NIST CCVS85 と OSS コーパスを処理系へ流し、合格率と未対応構文を数える | 第 1 増分 実装済。コーパスは同梱せず取得スクリプトで持ってくる |
| `cobol-job` | 内部ジョブモデル・ジョブ実行・JCL と宣言的形式のフロントエンド | FR-130〜FR-137 と FR-141〜FR-143 のうち、内部モデル・実行機構・宣言的形式・JCL (目録手続き・シンボリックパラメタ・`IF`・`DISP`・`ABENDCC` を含む)、ユーティリティ (`IEFBR14` / `IEBGENER` (`GENERATE` / `RECORD` による組み替えを含む) / `IEBCOPY` / `IDCAMS` / `SORT` (`OUTFIL` の振り分け・見出しと末尾・分割、欄の書式と `TO=` / `EDIT=` を含む) / `ICETOOL` (操作子はすべて) / `IKJEFT01`)、`SPACE`、目録 (`KEEP` / `CATLG` / `UNCATLG` / `VOL=SER`)、区分データセットのメンバと一覧・別名・ISPF 統計・ディレクトリの上限、世代データグループ (相対世代・`LIMIT` によるロールオフ)、異常終了コードと診断出力を実装済。ユーティリティも翻訳した資産と同じ検査を通る |
| `cobol-compiler` | プリプロセッサ・構文解析・ASM によるコード生成 | P0-b 着手。主要COBOL文に加え、静的PROGRAM / TRANSIDと単純COMMAREAを使う初期`EXEC CICS` subsetをクラスファイルまで変換する |
| `cobol-ims` | IMS の DBD / PSB と DL/I 呼び出しの中立モデル (設計 78) | DBDGEN / PSBGEN の原文を読む。Bank-of-Z の DBD 9 本・PSB 8 本がすべて読める (P-153)。`CALL 'CBLTDLI'` の DB 呼び出し (GU / GN / GNP / GH* / ISRT / REPL / DLET) をメモリの上の階層型データベースで動かす (P-154)。ジョブの `EXEC PGM=DFSRRC00,PARM='DLI,...'` でバッチを流し、データベースをデータセットに書き戻す (P-155)。I/O PCB の GU / GN / ISRT / PURG と、1 つの JVM の中の電文のキューで MPP を動かす (P-156)。I/O PCB への GU・基本 CHKP・SYNC を同期点とし、ROLB と異常終了は最後の同期点まで戻す (P-157)。`PARM='BMP,...'` は電文を読まない形だけ受け、I/O PCB を置いて CHKP できる (P-158)。SSA のコマンドコード C / D / F / L / N / P / Q を扱う (U / V は断る、P-159)。データベースの置き場は中立の口の裏にあり、既定はデータセット、`cobol.ims.jdbc.url` を指定すれば `cobol-ims-rdb` の RDB の表 (P-160)。電文のキューは中立の口の裏にあり、既定はこの JVM の中、`cobol.ims.jms.factory` を指定すれば `cobol-ims-jms` が JMS で運ぶ (P-162、P-165)。記号 CHKP が退避した域を業務の更新と同じ確定で置き場に残し、XRST が作業域か `CKPTID=` の検査点から書き戻す (P-164)。SPA、電文を読む BMP、GSAM は未実装 (Bank-of-Z がどれも使っていないので測る基準が無い、P-166) |
| `cobol-ims-rdb` | IMS のデータベースを RDB の表に生バイトで置く JDBC の置き場 (設計 78 §3.2、ADR-0013) | `IMS_SEGMENT_STORE` / `IMS_ROOT_INDEX` を H2・PostgreSQL・Db2 に作り、同期点ごとに変わった根だけを書き直す。方言の差を知るのは `ImsSchema.Dialect` だけで、どれも実サーバで測っている (PostgreSQL 17.11 は `infra/postgres`、Db2 12.1 は `infra/db2`)。主キーに `ROOT_SEQ` を足した (P-160)。ルートアンカーロック (ADR-0015) は同期点の確定で昇順に押さえ、根の版で遅れた更新を競合として止め、確定のあと他の領域の確定を読み直す (P-161)。処理済みの電文を `IMS_MESSAGE_INBOX` に業務の更新と同じトランザクションで書き、再配信を捨てる。保持期間 (既定 7 日) を過ぎた ID は、置き場を開くときに落とす (P-163)。記号 CHKP が退避した域を `IMS_CHECKPOINT` に同じ確定で書く (P-164)。取引コードのキューを読む領域を `IMS_QUEUE_LEASE` の借用で 1 つに限り、2 つ目は起こさずに断る (P-167)。競合したら電文駆動の領域を置き場から読み直して頭から動かし直し、使い切れば U0777 で落とす (P-168)。GH の時点の排他と根ごとの遅延読み込みは未実装 |
| `cobol-ims-jms` | IMS TM の電文のキューを JMS 3.0 で運ぶアダプタ (設計 78 §4、ADR-0014) | 取引コードごとのキューを `BytesMessage` で読み、LL / ZZ 付きのセグメントを運ぶ。応答は端末ごとのキューへ。同期点で取り出しと応答を 1 つの JMS のトランザクションで確定する (P-162)。ブローカは `infra/rabbitmq` の compose。実ブローカ (RabbitMQ 4.1.8) での起動と試験を確認し、Bank-of-Z のオンライン 5 本をブローカ越しに測った (P-162)。`cobol.ims.jms.factory` に `ConnectionFactory` のクラス名を書くと差し込まれる (P-165)。`JMSMessageID` を運び、置き場の inbox で再配信を捨てる (P-163)。XA、SPA (P-166)、`CHNG` は未実装 |
| `cobol-db2` | Db2 SQL / SQLCA / cursor / UOW の中立契約 | experimentalなprofile固定、遅延UOW、型付きhost variable / codec、fidelity行列を実装 |
| `cobol-db2-jdbc` | Spring管理外のDb2 JDBC connection lease / UOW adapter | task専用lease、native SQL executor、commit跨ぎ、reset / discardを実装。Db2 Communityで中立portからcommit後FETCHを検証。障害試験は未実装 |
| `cobol-spring-boot-4-autoconfigure` | Spring Boot 4.x 固有機能を中立ポートへ接続 | Spring Boot 4.1.1 基準の `SPRING_MANAGED` Db2 UOW、初期SQL executor、非hold cursorを実装。CICS task の coordinator の自動構成と、JSON の入口 `POST /api/cics/{transid}` (Spring Security があるときだけ、P-135) を実装。同じ冪等キーの再送には task を動かさず commit した結果を返す (P-142)。`cobol.cics.conversation.consistency=strict` で、会話と冪等キーの結果を業務の Db2 と同じ UOW で表に確定する (P-143)。driver管理 `WITH HOLD` は未実装 |
| `cobol-spring-boot-4-bms-thymeleaf` | BMS 画面の Thymeleaf view、端末 JavaScript、CSS | 表示モデル、共通 template、端末操作、form の入力変換を実装。Bank-of-Z の 2 画面をブラウザで測り、JavaScript の有無によらず全 field の行・桁・幅が一致 (設計 81)。ブラウザの入口 `POST /cics/{transid}` は Spring Security があるときだけ構成し、COMMAREA と画面は server の会話ストアから読む。会話ストアの既定は 1 つの JVM の中だけ (P-134) |

## ビルド

```
mvn test
```

Java 21 と Maven 3.9 以上が必要。

## 翻訳して動かす

```
java -cp <classpath> dev.cobolonjava.compiler.Main -d out HELLO.cbl
java -cp out:<classpath> cobol.generated.HELLO
```

## ジョブとして動かす

```
java -cp <classpath> dev.cobolonjava.job.Main -d out -w work payroll.jobs
java -cp <classpath> dev.cobolonjava.job.Main -d out -w work -b data -p proclib payroll.jcl
```

記述形式は拡張子で見分ける。`.jcl` なら JCL、それ以外は宣言的形式である。どちらも同じ
内部モデルへ落ちるので結果は同じになる。プロセスの終了コードはジョブの終了コードである。
詳しくは[設計 90](docs/design/90-job.md) を参照。

生成したクラスは `main` を持つので、そのまま起動できる。
`-I` でコピー句のディレクトリ、`--free` で自由形式、`-q` で翻訳時オプションを指定する
(`-q SSRANGE` で添字と部分参照の範囲を実行時に検査する)。
ソースの `CBL` / `PROCESS` に書いた指定のほうがあとに重なる。

```cobol
       IDENTIFICATION DIVISION.
       PROGRAM-ID. HELLO.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-I     PIC 9(3) COMP VALUE 0.
       01 WS-TOTAL PIC 9(5) COMP-3 VALUE 0.
       PROCEDURE DIVISION.
       MAIN-START.
           DISPLAY 'COBOL ON JAVA'
           PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 5
               ADD WS-I TO WS-TOTAL
           END-PERFORM
           DISPLAY 'SUM 1..5 = ' WS-TOTAL.
```

```
COBOL ON JAVA
SUM 1..5 = 00015
```

### V2 検証 (Hercules オラクル) を含めて実行する

```
HERCULES=/path/to/hercules mvn test
```

Hercules (SDL Hyperion 4.x) を実行オラクルとして、ランタイムの出力を
z/Architecture 命令の実行結果とバイト列で突き合わせる。
Hercules が見つからない場合、V2 テストは失敗ではなくスキップされる。
`-r` に対応しない旧版 (Ubuntu の `hercules` パッケージが提供する 3.x など) を
検出した場合も、理由を示してスキップする。
詳細は [設計 20](docs/design/20-oracle.md) を参照。

### 検証基盤 (CCVS85 と OSS コーパス) を実行する

```
sh tools/verify/fetch-ccvs85.sh build/verify
CCVS85=build/verify/newcob.val mvn -B -pl cobol-verify -am test
```

NIST の COBOL-85 検証スイート (CCVS85) を処理系へ流し、モジュールごとの受理率と、
通らなかった理由を多い順に出す。コーパス本体はリポジトリに<b>同梱しない</b>
(要件 NFR-042)。取得スクリプトが URL とコミットハッシュで固定して持ってくる。
指定が無ければ、失敗ではなくスキップされる。Hercules と同じ構えである。

数だけを見るなら、道具を直に呼べる。

```
java -cp cobol-runtime/target/classes:cobol-compiler/target/classes:cobol-verify/target/classes:\
$(find ~/.m2 -name 'antlr4-runtime-*.jar'):$(find ~/.m2 -name 'asm-9*.jar') \
  dev.cobolonjava.verify.Main ccvs85 build/verify/newcob.val      # 翻訳が通るか
```

動かして合否まで見るなら `ccvs85-run` である。検査プログラムは<b>自分で答え合わせ
をして印字する</b>ので、その紙を読めば「規格どおりに動くか」まで測れる。

```
  dev.cobolonjava.verify.Main ccvs85-run build/verify/newcob.val  # 規格どおりに動くか
```

詳細は [設計 25](docs/design/25-verification.md) を参照。

## 現在のステータス

要件定義フェーズ完了 (要件定義書 第 15 章に決定事項)。
P0-a (ランタイム先行) と V2 期待値の採取基盤を実装済み。P0-b (コンパイラ) に着手。ジョブ実行 (JCL を含む) を実装済。
外の基準で測る検証基盤 (CCVS85 と OSS コーパス) を実装済。ただし<b>コーパスには
資産がまだ 1 つも書かれていない</b> — 取ってくる仕掛けと固定の仕方は置いてあるが、
行を足すのはライセンスを確かめる作業であり、確かめていないものは書けない。
CCVS85 は測り切ったので、次に要るのはこちらである (設計 25)。
NIST CCVS85 の<b>受理率</b>は 96.9% (458 本中 444 本、壊れたもの 0 本) である。
<b>動かした合格率</b>は 95.8% (361 本中 346 本) であり、検査ごとに数えると 100.0%
(8723 件流れて 1 件落ちた) である。<b>壊れる本も返ってこない本も 0 である</b>。
落ちている 1 件は OBNC1M の `STOP 定数` であり、<b>卓の人がジョブを落とす</b>ことを
前提にした検査である。断る 14 本も、支えないと決めてあるものと配布物の打ち間違いだけである。
受理率は「翻訳が通るか」、合格率は
「規格どおりに動くか」であり、後者が要件 NFR-040 の言う数である。
このうち 127 件は検査スイート自身が「人が紙を見て決めろ」と言っている検査であり、
<b>道具は確かめていない</b>。17 本がそれを抱えたまま「通った」に入っているので、
その分だけ合格率は甘い。
`ACCEPT` の検査は卓の人が決まった値を打ち込むことを前提にしている。その札束は
道具の側に置いてある (`OperatorInput`)。値は<b>原文が決めている</b> — 検査は
`ACCEPT` のすぐあとで対になる項目と比べており、合否を決めるのはプログラムのほうである。
配布物の検査プログラムは<b>全数を流している</b> (札が足りずに流せないものは無い)。
CICS / BMS は、手元に取得した Bank-of-Z (同梱しない) の CICS 資産 32 本を
`verify corpus <cobol> -I <copy> -I <bms>` で流して測っている。<b>翻訳が通るのは 32 本 (全数)</b>である。
全診断を数えると、BMS 記号マップ写し句、`DFHAID`、EIB、`RETURN IMMEDIATE`、`PROGRAM(データ名)`、
`LENGTH` を省いた `COMMAREA`、`ASSIGN`、`ASKTIME` / `FORMATTIME`、`DELAY`、`SEND MAP` / `TEXT` /
`CONTROL`、`RECEIVE MAP`、`ABEND ABCODE(データ名)`、`BIF DEEDIT`、`EXEC SQL` の初期 subset、
`USAGE POINTER`、`LENGTH OF`、`GET` / `PUT CONTAINER`、`ENQ` / `DEQ` はもう止めていない (設計 79)。CRECUST も非同期 API と
LE の写し句を入れて通るようになり、翻訳を止める CICS 命令は残っていない。file control (`READ` / `WRITE` / `REWRITE` / `DELETE` / `UNLOCK` と browse) は
KSDS / RRDS を、バッチと同じデータセットで扱う (設計 82、P-131、P-136)。Bank-of-Z はこの命令群をほとんど使わないので、
振る舞いは CICS TS の命令の頁を基準に試験で固定している。一時記憶・一時データのキュー (`WRITEQ` / `READQ` / `DELETEQ`
の `TS` / `TD`) は 1 つの JVM の中で持つ (P-137)。`START` / `RETRIEVE` / `CANCEL` は region に構成した間隔制御が
端末を持たない task を起こす (P-138)。LE の `CEEIGZCT` (CEE000 だけ) と `CEEDAYS` / `CEELOCT` を持つ (P-139)。
非同期 API (`RUN TRANSID` / `FETCH ANY` / `FETCH CHILD` / `FREE CHILD`) は region に構成した port が子の task を動かす (P-140)。`INQUIRE ASSOCIATION` は task 自身の origin data に限る (P-132)。`RECEIVE MAP ... ASIS` は端末が UCTRAN でも大文字にしない。
`DFHVALUE` と `INQUIRE` / `SET TERMINAL UCTRANST` は task の端末に限って扱う。CVDA の数は CICS TS の表による
(TXSeries の表は数が違う。P-130)。
`DFHBMSCA` は公開文書の意味を 3270 の属性 byte で表して作る (P-129)。
XFRFUN の `INCLUDE SQLDA` は、公開文書の欄の説明から作った暫定の形 (SQLVAR 750 個、番地は 4 byte の POINTER) で置く。
浮動小数点項目は `+ - *` の `COMPUTE`、転記、比較を扱う (P-127)。BNK1TFN は 28 byte の域に `LENGTH(29)`、
BNK1CCS は 5 byte の域に `LENGTH(248)` を書いている。はみ出す byte の中身はホストの記憶域の並びで決まるので、
暫定の仕様として項目の番地から LENGTH の byte を渡し、記憶域の端を越える分は binary zero を詰める (P-146)。
file control の ESDS (RBA / XRBA)、`TOKEN`、`NOSUSPEND`、`CONSISTENT` / `REPEATABLE`、`MASSINSERT`、BDAM (RRDS に写す)、
構成した自 region の `SYSID` も、設計 85 の暫定の仕様で変換する (P-147。RBA の数は実機と一致しない)。
TD の区画外のキューは順編成のデータセットに置き、回復可能なキューは STRICT の task の業務の UOW に入れる (P-148)。
START の `CHANNEL` / `ATTACH` / `NOCHECK` / `SYSID`、REQID の無い `CANCEL`、TS / TD の `SYSID` / `NOSUSPEND` も暫定の仕様で変換する (P-149)。
`SET ptr TO ADDRESS OF` と `SET ADDRESS OF` は、POINTER に実行単位の中で振った番号を置いて扱う (P-150)。
その上に、file control / TS / TD / RETRIEVE の `SET` を置き場の番地として入れた (P-151)。
IMS は、同じ Bank-of-Z の IMS の COBOL 11 本を `verify corpus <cobol> -I <copy>` で流して測っている。
<b>翻訳が通るのは 10 本</b>である (測り始めは 0 本)。手続き部の先頭の `ENTRY "DLITCBL" USING` をプログラムの引数とし (P-152)、
`PROGRAM-ID` の終止符の欠落と、演算子に空白を置かない `<=1` を受ける。残る IBTRAN は OO COBOL の `REPOSITORY` と JNI で止まる。
DBDGEN / PSBGEN の原文は `verify ims-gen <置き場>` で測り、<b>DBD 9 本・PSB 8 本がすべて読める</b> (P-153)。
`CALL 'CBLTDLI'` の DB 呼び出しは、メモリの上の階層型データベースで動く (P-154)。DL/I には CCVS85 や Hercules に
あたる外の基準が無いので、振る舞いは公開仕様の説明から起こして試験で固定しており、<b>実機と突き合わせていない</b> (P-099)。
バッチは `EXEC PGM=DFSRRC00,PARM='DLI,プログラム,PSB'` で流す。`//IMS` のライブラリに PSB と DBD の原文を置き、
データベースは DBD の `DATASET DD1=` の DD に書き戻す (P-155)。Bank-of-Z の<b>読み込み 5 本を JCL で流すと全段 RC=0 で、
各データベースのセグメント数が入力の件数と一致する</b> (顧客 100、口座 265、顧客口座 265、履歴 265、取引の状態 265)。
測定の JCL と入力のデータセットは資産から作るので同梱しない。
オンライン (MPP) は I/O PCB への GU / GN / ISRT / PURG を持ち、`verify ims-mpp <置き場> -d <翻訳した組> -p <プログラム> -s <PSB> -m <電文>`
で電文を流して測る (P-156)。読み込んだデータベースに対し、<b>翻訳が通るオンライン 5 本 (IBLOGIN1 / IBGCUDAT / IBSCUDAT /
IBACSUM / IBLOGOUT) はすべて復帰コード 0 で、資産の意図どおりの応答を返す</b> (ログインの成功・二重ログイン・パスワード誤り・
顧客なし、顧客の取得と更新、口座の要約、ログアウト)。前の段の更新はデータベースに書き戻され、次の段が読む。
電文のキューは既定ではこの JVM の中だが、`-Dcobol.ims.jms.factory=<ConnectionFactory のクラス名>` を指定すると
`cobol-ims-jms` が差し込まれ、電文がブローカを経由する (P-165)。<b>RabbitMQ 4.1.8 越しに同じオンライン 5 本を流しても、
すべて復帰コード 0 で応答はメモリのキューと同じ</b>である。応答 10 件はブローカの端末ごとのキュー (LTERM001 に 5、
LTERM002 に 2、LTERM003 に 3) に届き、取引コードのキューはすべて空になった (取り出しが ACK されている)。
ブローカを通すと電文が `JMSMessageID` を持つので、RDB の置き場と併せると冪等化 (P-163) が実際に効く。H2 の置き場へ
読み込んでから IBLOGIN1 をブローカ越しに流すと、<b>`IMS_MESSAGE_INBOX` に処理した 4 件の ID が業務の更新と同じ
トランザクションで残る</b>。ファイルから電文を作るときは ID を持たないので、そこでは冪等化は効かない。
ブローカを落として再開しても、取引コードのキューの電文は残る。領域が確定しないまま落ちても (JVM を叩き落としても)
取り出した電文はキューへ戻る。再開したあと<b>積まずに残りだけを流すと、応答は 4 件で、順序は積んだときのまま</b>である。
1 通目が `LOGIN SUCCESSFUL`、2 通目が同じ端末で `CUSTOMER ALREADY LOGGED IN` になるので、入れ替わっていれば分かる。
同じ取引コードを 2 つの領域が読むと順序は崩れる。そこで<b>置き場の借用の行で取引コードを 1 つの領域に限り、
2 つ目は起こさずに断る</b> (P-167)。実測では、2 つ目の領域は電文を 1 通も取らずデータベースにも触れずに断られ、
1 つ目の応答は投入した順のままだった。借用が働くのは RDB の置き場と JMS のキューがそろったときだけである。
I/O PCB への GU、基本形の CHKP と SYNC を同期点とし、同期点で DB PCB の位置を捨てる。
ROLB と異常終了は最後の同期点まで戻すので、途中で異常終了しても確定した電文の更新は残る (P-157)。
`EXEC PGM=DFSRRC00,PARM='BMP,プログラム,PSB'` も受け、I/O PCB を置いて CHKP で確定しながら流せる (電文を読む `IN=` は断る、P-158)。
記号 CHKP は退避した域を<b>業務の更新と同じ確定</b>で置き場に残し、XRST は作業域か `PARM` の `CKPTID=` の検査点から書き戻す (P-164)。
確定が失敗すれば検査点も残らないので、再始動した域とデータベースの状態が揃う。GSAM のデータセットの位置づけ直しは持たない。
データベースの置き場は、`-Dcobol.ims.jdbc.url=<JDBC の URL>` を指定すると `cobol-ims-rdb` の RDB の表になる (P-160)。
Bank-of-Z の読み込み 5 本を H2 のファイルの DB へ流しても全段 RC=0 で、表のセグメント数は入力の件数と一致し、オンライン 5 本の
応答はデータセットの置き場と同じである。顧客口座 (CUSTACCS) は同じ顧客の根が最大 5 つ重なり、ADR-0013 の主キーに `ROOT_SEQ` を
足さなければ置けなかった。
<b>実 PostgreSQL (17.11) でも同じ結果である</b>。`BYTEA` と `COLLATE "C"` を使う PostgreSQL の枝は長いあいだ
一度も実行しておらず、対応を主張しているだけだった。`infra/postgres` の環境を足して測ったところ、読み込み 5 本は
全段 RC=0 でセグメント数 100 / 265 / 265 / 265 / 265、オンライン 5 本も全て復帰コード 0 で、データセットと H2 の
置き場に一致した。
<b>実 Db2 (12.1) でも同じ結果である</b>。IMS の資産がいちばん移りやすい RDB であり、これまでは断っていた。
方言の差は 3 つだけだった (製品名に機種が入る、`VARBINARY` の上限が 32672 byte、素の `SELECT CURRENT_TIMESTAMP`
が使えない)。差を知るのは `ImsSchema.Dialect` だけである。
これとは別に、<b>原文から出力バイト列まで</b>を 1 本のバッチとして流す検査がある
(`BatchJobEndToEndTest`)。COBOL を翻訳し、JCL で 3 段 — 抽出・整列・印字 — を流し、
段の間のデータセットと最後の紙をバイトで突き合わせる。JCL と宣言的形式が<b>同じ
バイト列</b>を出すことも見る。要件 13 章が P1 の受け入れ基準に置いている形である。
テスト 2382 件 (この環境で `mvn -B -o clean test` で流れた数)。実サーバを使う試験は環境変数で有効にしたときだけ流れる。
有効化していなければ、実 Db2 の 2 件 (`cobol-db2-jdbc`)、実 RabbitMQ の 2 件 (`cobol-ims-jms`)、
IMS の置き場を実 PostgreSQL と実 Db2 で流す 4 件ずつ (`cobol-ims-rdb`) がスキップされる。
うち 6 件はコーパスを取ってきていなければスキップされる。
Hercules 上での実行と突き合わせる**検証レベル V2** の検査は、期待値を採れない環境では流れない。
`STRING` / `UNSTRING` のように単一の機械語命令に対応しない意味論は、V1 に留まるのが正しい
(詳細は[設計 20](docs/design/20-oracle.md))。
V2 のうち 4 件は合成ジェネレータが生成した組み合わせ (加減算 121 件、乗算 121 件、除算 99 件、
数値編集 90 件) をそれぞれ一度に検証する。

V2 で裏付けが取れているのは以下である。

- 10 進加減乗除とゼロ結果の符号 (`AP` `SP` `MP` `DP` `ZAP`)
- 丸め (`SRP`) と符号ニブルの受理規則
- 数値編集のうちゼロ抑制・小切手保護・`CR`/`DB`・単純挿入 (`ED`)、および浮動挿入 (`EDMK`)
- ゾーン10進との相互変換 (`PACK` `UNPK`)
- データ例外 (`S0C7`) と 10 進除算例外 (`S0CB`) の発生条件
- 数値比較の条件コード (`CP`)
- `INSPECT ... CONVERTING` (`TR`)
- IBM 16 進浮動小数点 (`COMP-1` / `COMP-2`) の表現と加減乗除 (`AD` `SD` `MD` `DD` `LE` `STE`)
- 合成ジェネレータによる 485 通りの組み合わせ (演算 341 件、数値編集 90 件、浮動挿入 54 件)

## ライセンス

Apache License 2.0 — [LICENSE](LICENSE) および [NOTICE](NOTICE) を参照。

IBM、z/OS、z/Architecture、CICS、Db2、IMS、MVS、Enterprise COBOL は IBM 社の商標であり、
本プロジェクトは IBM 社とは無関係である。

Hercules は検証用の外部オラクルとしてのみ利用し、そのソースコードは本製品に取り込まない
(Hercules は QPL であり Apache-2.0 と非互換であるため)。
