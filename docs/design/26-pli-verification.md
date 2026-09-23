# 設計 26: PL/I の外部検証とコーパス

## 1. 背景

COBOL には NIST CCVS85 があり、規格の要求を実行可能な検査プログラムとして使える。
PL/I には ANSI X3.53-1976 / ISO 6160:1979 という言語規格はあるが、NBS は
1984 年の調査で「包括的な検証テストは利用できない」と報告している。

- [ISO 6160:1979](https://www.iso.org/standard/12406.html)
- [NBS Special Publication 500-117, Selection and Use of General-Purpose Programming Languages](https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nbsspecialpublication500-117v1.pdf)

IBM ZUnit は PL/I の単体試験を作る道具であり、処理系の言語規格適合性を判定する
共通スイートではない。また、Enterprise PL/I は ANSI General Purpose Subset の選択した
機能に加えて IBM 拡張を持つ。このため、IBM の結果と一致することと、規格に適合することを
同じ数として扱わない。

- [IBM ZUnit の PL/I テストケース](https://www.ibm.com/docs/en/developer-for-zos/16.0.x?topic=deprecated-generating-editing-pli-test-cases)
- [IBM Enterprise PL/I が参照する規格](https://www.ibm.com/docs/en/zos-basic-skills?topic=zos-more-information-about-pli-language)

## 2. 目的

PL/I 対応を次の三層で継続的に測り、次に実装する機能を数から決める。

| 層 | 測るもの | 判定 |
| --- | --- | --- |
| 1. 言語機能 | 小さく独立した構文・型・式・制御構造 | 規格から作った期待値と一致する |
| 2. 参照処理系 | IBM Enterprise PL/I で翻訳・実行できる外部コーパス | 同じ入力に対する標準出力が一致する |
| 3. 実資産 | Bank-of-Z のバッチ、Db2、IMS | ジョブまたは電文の業務結果が一致する |

有限個の試験で処理系の正しさを証明することはできない。ここで得るのは、再現可能な
差分、機能別の通過率、壊れた資産の一覧である。

## 3. コーパスの境界

外部コーパスと IBM から採取した出力はリポジトリへ同梱しない。ライセンスと来歴が異なる
原文を Apache-2.0 の配布物に混ぜず、取得元、版、SHA-256、文字コード、参照処理系の版と
コンパイラ指定を別の manifest に固定する。

置き場は次の形にする。

```text
pli-corpus/
  arithmetic/
    fixed-decimal-01.pli
    fixed-decimal-01.out
  control/
    iterative-do-01.pli
    iterative-do-01.out
  syntax-only/
    declarations-01.pli
```

- `.pli` / `.pl1` が検査プログラムである。
- 同名の `.out` があれば、UTF-8 の期待標準出力として実行結果を照合する。
- `.out` がなければ翻訳の受理だけを測る。
- 直下のディレクトリ名を機能区分として数える。
- 改行は比較時だけ LF に揃える。空白、桁、符号は変えない。

実行は次のコマンドで行う。

```text
java -cp <classpath> dev.cobolonjava.verify.Main pli-corpus <directory> [-o report]
```

CI や Maven から継続測定するときは、同じ置き場を環境変数 `PLI_CORPUS` で渡す。
未設定の環境では外部コーパス試験をスキップする。

報告は人向けの表と CSV を出す。CSV は区分ごとの本数、翻訳数、参照出力一致数、不一致数、
診断による拒否数、例外・時間切れ数を持つ。合格率に下限を置いてビルドの門にはしない。

## 4. 参照出力の採取

参照処理系での採取は次の情報を一組として保存する。

1. Enterprise PL/I の製品版と PTF 水準
2. `*PROCESS` とコンパイラ・リンク・Language Environment 指定
3. 入力データセットと文字コード
4. 復帰コード、標準出力、診断
5. ソースと出力の SHA-256

IBM 固有機能を使う検査には `ibm-extension`、ISO/ANSI の要求だけを狙う検査には
`standard` の区分を付ける。同じ振る舞いが規格と IBM で違うときは、期待値を黙って IBM に
合わせず、別の検査として記録する。

## 5. 検証器の故障分離

1 本の翻訳または実行が例外や無限ループになっても残りを測り続ける。各検査には 60 秒の
上限を置き、結果を次の五つに分ける。

| 状態 | 意味 |
| --- | --- |
| `COMPILED` | 期待出力のない原文を翻訳できた |
| `PASSED` | 期待出力と一致した |
| `REJECTED` | 位置付き診断を出して拒否した |
| `WRONG_OUTPUT` | 実行できたが期待出力と違った |
| `CRASHED` | 診断を経ずに壊れた、または時間切れ |

`CRASHED` を未対応構文に混ぜない。前者は現在の実装の欠陥、後者は今後の機能だからである。

## 6. 機能の増分

最初の機能表は次の順で育てる。

| 優先 | 機能群 | 現在 |
| --- | --- | --- |
| 1 | 宣言、`CHAR`、`FIXED BIN/DEC`、代入、算術・比較 | 初期 subset 実装済み |
| 2 | `IF`、`DO WHILE/UNTIL`、反復指定 `DO ... TO ... BY` | 実装済み |
| 3 | 文字列、`SUBSTR`、`TRIM`、連結、PICTURE | Bank-of-Z subset 実装済み |
| 4 | 配列、添字、`DO` の複数指定、`LEAVE` / `ITERATE` | 構造の外の配列 (多次元・下限・`BASED`・`INIT` の並びと係数) と擬似変数 `SUBSTR` は実装済み。2026-09-24 に、構造の中の要素の配列 (要素の間隔は境界合わせの倍数)、ビット列の配列 (UNALIGNED ならビット単位で詰まる)、配列への代入の右辺の配列の式 (要素ごと) を足した。構造の配列 (`1 S(3)`、`2 R(3), 3 ...`)・配列への代入でない所の配列の式・`DO` の複数指定・`LEAVE` / `ITERATE` は断る。要素の間隔の規則は実機と突き合わせていない (P-185) |
| 5 | 条件処理、`ON` / `SIGNAL` / `REVERT` | `ON ENDFILE` のみ |
| 6 | 記録・ストリーム I/O の全形式 | 順編成入力と `PUT` の初期 subset |
| 7 | SQL、IMS、外部呼出し | Bank-of-Z subset 実装済み |

各増分は「外部または規格由来の検査を追加する → 数を取る → 最大の失敗群を実装する →
全体を測り直す」の順で進める。
