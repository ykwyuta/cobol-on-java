# ADR-0005: JUnit 連携を製品ランタイムから分離した cobol-junit に置く

| 項目 | 内容 |
| --- | --- |
| 状態 | 採用 |
| 日付 | 2026-09-09 |
| 関連要件 | FR-195, FR-197, FR-204, ARC-4 |
| 関連設計 | [設計 75](../design/75-java-interop.md), [設計 76](../design/76-junit-testing.md) |

## 文脈

既存のコンパイラテストは、各テストクラスが `CobolCompiler`、独自 `ClassLoader`、
`ProgramContext.capturing`、`Storage` の準備を繰り返している。処理系自身の試験には使えるが、
利用者が業務 COBOL を Java の JUnit 5 テストへ組み込むには、コンパイル、セッション、出力捕捉、
データ変換、後片付けを毎回実装しなければならない。

JUnit の `Extension` を `cobol-runtime` に置けば利用は簡単になる一方、製品実行だけを行う環境にも
JUnit API が推移依存し、ランタイムの独立性を損なう。JUnit のライフサイクルと COBOL の
実行単位を暗黙に結ぶと、並列テストで状態を共有する危険もある。

## 決定

新しい Maven モジュール `cobol-junit` を設ける。このモジュールだけが JUnit Jupiter API に
依存し、`cobol-runtime` と `cobol-compiler` は JUnit に依存しない。

`cobol-junit` は次の2層を持つ。

- JUnit に依存しない呼び出し・fixture の中核。ただし配置は当面 `cobol-junit` 内部とする
- `BeforeEachCallback`、`AfterEachCallback`、`ParameterResolver` を実装する
  `CobolExtension`

コンパイル済みの不変な `ProgramCatalog` と生成クラスはテストクラス内で再利用できる。
`CobolSession`、作業場所、`EXTERNAL`、特殊レジスタ、Mock の呼び出し履歴、捕捉出力、仮想データ
セットはテストメソッドごとに新しく作り、`afterEach` で必ず閉じる。テストが失敗した場合だけ、
マスキング済みの COBOL 呼び出し履歴と診断 ID を JUnit のレポートへ添付する。

既定のライフサイクルは1テストメソッド1セッションとする。複数呼び出しにまたがる
`WORKING-STORAGE` や `CANCEL` を検査するテストは、そのメソッド内で同じ fixture を繰り返し使う。
テストクラス全体でセッションを共有するモードは提供しない。

JUnit 並列実行では、コンパイルキャッシュだけを共有する。キャッシュキーには COBOL ソース、
copybook、コンパイラオプション、コンパイラバージョンのハッシュを含め、値は不変にする。

## 影響

- 製品ランタイムの依存関係と成果物サイズは変わらない。
- 利用者は `test` scope で `cobol-junit` を追加するだけで JUnit 5 から COBOL を実行できる。
- JUnit 以外のテストフレームワーク向けアダプタを追加するときも、製品ランタイムを変更せずに済む。
- `cobol-junit` は実行時コンパイルを行うため `cobol-compiler` に依存する。事前コンパイル済み
  クラスだけを使う構成でも同じ API を使用できる。

## 却下した案

### JUnit Extension を cobol-runtime に含める

製品実行とテストの依存関係が混ざり、JUnit のメジャー更新がランタイム API の互換性へ影響するため
採用しない。

### アノテーションだけで全設定を表す

文字列、複数 copybook、Java Mock、データセット、固定時計などをアノテーション属性へ押し込むと
型安全性と再利用性が落ちる。アノテーションは簡単なソース指定に限り、主要設定は builder API とする。

### テストクラスごとに1つの CobolSession を共有する

テスト順序への依存と並列実行時の競合を生み、`WORKING-STORAGE` の残留が別テストを通してしまうため
採用しない。
