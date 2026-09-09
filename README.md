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
- [設計: ファイル入出力](docs/design/80-file-io.md)
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
| `cobol-compiler` | プリプロセッサ・構文解析・ASM によるコード生成 | P0-b 着手。`WRITE ... ADVANCING` による行送りを含む。`MOVE`・算術文 (`CORRESPONDING` を含む)・`COMPUTE`・`IF`・`EVALUATE`・`PERFORM` (`VARYING` を含む)・`GO TO`・`CALL`・`INITIALIZE`・`SEARCH` / `SEARCH ALL`・`ACCEPT`・`DISPLAY`・`INSPECT`・`STRING`・`UNSTRING` を含むプログラムが、ソースからクラスファイルまで通って動く |

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
外の基準で測る検証基盤 (CCVS85 と OSS コーパス) を実装済。
NIST CCVS85 の<b>受理率</b>は 96.9% (458 本中 444 本、壊れたもの 0 本) である。
<b>動かした合格率</b>は 93.9% (361 本中 339 本) であり、検査ごとに数えると 99.9%
(8715 件流れて 9 件落ちた) である。<b>壊れる本も返ってこない本も 0 である</b>。
受理率は「翻訳が通るか」、合格率は
「規格どおりに動くか」であり、後者が要件 NFR-040 の言う数である。
このうち 127 件は検査スイート自身が「人が紙を見て決めろ」と言っている検査であり、
<b>道具は確かめていない</b>。16 本がそれを抱えたまま「通った」に入っているので、
その分だけ合格率は甘い。
`ACCEPT` の検査は卓の人が決まった値を打ち込むことを前提にしている。その札束は
道具の側に置いてある (`OperatorInput`)。値は<b>原文が決めている</b> — 検査は
`ACCEPT` のすぐあとで対になる項目と比べており、合否を決めるのはプログラムのほうである。
配布物の検査プログラムは<b>全数を流している</b> (札が足りずに流せないものは無い)。
テスト 1811 件 (この環境で流れた数)。
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
