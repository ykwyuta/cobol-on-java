# cobol-on-java

JVM 上で動作する COBOL 処理系。IBM メインフレーム (z/OS + Enterprise COBOL for z/OS +
Language Environment) 上での実行時の**振る舞い**を可能な限り忠実に再現し、
既存のメインフレーム資産をソース無修正で JVM 上へ移行できることを目指す。

## ドキュメント

- [要件定義書](docs/requirements.md) — プロジェクトの目的、互換性レベルと検証レベルの定義、
  機能要件 / 非機能要件、決定事項、開発フェーズ、リスクと未決事項
- [設計: 全体アーキテクチャ](docs/design/00-overview.md)
- [設計: cobol-runtime (P0-a)](docs/design/10-runtime-p0a.md)
- [設計: cobol-oracle (V2 期待値の採取)](docs/design/20-oracle.md)
- [設計: cobol-compiler のプリプロセッサ (P0-b)](docs/design/30-compiler-preprocessor.md)
- [設計: cobol-compiler の構文解析 (P0-b)](docs/design/40-parser.md)
- [設計: データ部の記憶域割り付け (P0-b)](docs/design/50-data-layout.md)
- [設計: 手続き部と一意名の解決 (P0-b)](docs/design/60-procedure.md)
- [設計: コード生成 (P0-b)](docs/design/70-codegen.md)
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
| `cobol-runtime` | データ表現・10 進演算・編集移送・文字コード変換の意味論 | P0-a 第 1 増分 実装済 |
| `cobol-oracle` | Hercules 用テストの生成と期待値の採取 | 第 1 増分 実装済 |
| `cobol-compiler` | プリプロセッサ・構文解析・ASM によるコード生成 | P0-b 着手。`MOVE`・算術文・`COMPUTE`・`IF`・`EVALUATE`・`PERFORM` (`VARYING` を含む)・`DISPLAY`・`INSPECT`・`STRING`・`UNSTRING` を含むプログラムが、ソースからクラスファイルまで通って動く |

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

生成したクラスは `main` を持つので、そのまま起動できる。
`-I` でコピー句のディレクトリ、`--free` で自由形式を指定する。

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

## 現在のステータス

要件定義フェーズ完了 (要件定義書 第 15 章に決定事項)。
P0-a (ランタイム先行) と V2 期待値の採取基盤を実装済み。P0-b (コンパイラ) に着手。テスト 645 件。
うち 33 件は Hercules 上での実行と突き合わせる**検証レベル V2** であり、残りは V1。
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
