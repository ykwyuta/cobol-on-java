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
| `cobol-compiler` | プリプロセッサ・構文解析・ASM によるコード生成 | 未着手 (P0-b) |

## ビルド

```
mvn test
```

Java 21 と Maven 3.9 以上が必要。

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
P0-a (ランタイム先行) の第 1 増分を実装済み。テスト 134 件。
うち 5 件は Hercules 上での実行と突き合わせる**検証レベル V2** であり、残りは V1。

## ライセンス

Apache License 2.0 — [LICENSE](LICENSE) および [NOTICE](NOTICE) を参照。

IBM、z/OS、z/Architecture、CICS、Db2、IMS、MVS、Enterprise COBOL は IBM 社の商標であり、
本プロジェクトは IBM 社とは無関係である。

Hercules は検証用の外部オラクルとしてのみ利用し、そのソースコードは本製品に取り込まない
(Hercules は QPL であり Apache-2.0 と非互換であるため)。
