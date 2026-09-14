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
| `cobol-db2` | Db2 SQL / SQLCA / cursor / UOW の中立契約 | experimentalなprofile固定、遅延UOW、型付きhost variable / codec、fidelity行列を実装 |
| `cobol-db2-jdbc` | Spring管理外のDb2 JDBC connection lease / UOW adapter | task専用lease、native SQL executor、commit跨ぎ、reset / discardを実装。Db2 Communityで中立portからcommit後FETCHを検証。障害試験は未実装 |
| `cobol-spring-boot-4-autoconfigure` | Spring Boot 4.x 固有機能を中立ポートへ接続 | Spring Boot 4.1.1 基準の `SPRING_MANAGED` Db2 UOW、初期SQL executor、非hold cursorを実装。driver管理 `WITH HOLD`、CICS MVC / Session は未実装 |

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
`verify corpus <cobol> -I <copy> -I <bms>` で流して測っている。<b>翻訳が通るのは 24 本</b>である。
全診断を数えると、BMS 記号マップ写し句、`DFHAID`、EIB、`RETURN IMMEDIATE`、`PROGRAM(データ名)`、
`LENGTH` を省いた `COMMAREA`、`ASSIGN`、`ASKTIME` / `FORMATTIME`、`DELAY`、`SEND MAP` / `TEXT` /
`CONTROL`、`RECEIVE MAP`、`ABEND ABCODE(データ名)`、`BIF DEEDIT`、`EXEC SQL` の初期 subset、
`USAGE POINTER`、`LENGTH OF`、`GET` / `PUT CONTAINER` はもう止めていない (設計 79)。残る CICS 命令は
`INQUIRE` / `SET TERMINAL` (各 2)、`ENQ` / `DEQ` (各 1)、`INQUIRE ASSOCIATION` (1)、`DFHBMSCA` (1) である。
言語側では `INCLUDE SQLDA` (1)、LE の `CEEIGZCT` (1) が残っている。浮動小数点項目は `+ - *` の `COMPUTE`、
転記、比較を扱う (P-127)。BNK1TFN は 28 byte の域に `LENGTH(29)` を書いており、はみ出す 1 byte の中身が
ホストの記憶域の並びで決まるため、推測せず断ったままにしている。ABNDPROC は `EXEC CICS WRITE` (ファイル制御) で止まる。
これとは別に、<b>原文から出力バイト列まで</b>を 1 本のバッチとして流す検査がある
(`BatchJobEndToEndTest`)。COBOL を翻訳し、JCL で 3 段 — 抽出・整列・印字 — を流し、
段の間のデータセットと最後の紙をバイトで突き合わせる。JCL と宣言的形式が<b>同じ
バイト列</b>を出すことも見る。要件 13 章が P1 の受け入れ基準に置いている形である。
テスト 2061 件 (この環境で流れた数)。実 Db2 を使う試験は有効化していないので
`cobol-db2-jdbc` の 2 件はスキップされる。
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
