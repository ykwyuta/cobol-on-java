# cobol-on-java

JVM 上で動作する COBOL 処理系。IBM メインフレーム (z/OS + Enterprise COBOL for z/OS +
Language Environment) 上での実行時の**振る舞い**を可能な限り忠実に再現し、
既存のメインフレーム資産をソース無修正で JVM 上へ移行できることを目指す。

## ドキュメント

- [要件定義書](docs/requirements.md) — プロジェクトの目的、互換性レベルと検証レベルの定義、
  機能要件 / 非機能要件、決定事項、開発フェーズ、リスクと未決事項

## 主要な技術方針

要件定義書 第 15 章「決定事項」より抜粋。

| 項目 | 決定 |
| --- | --- |
| 互換性の基準 | Enterprise COBOL for z/OS 6.x の外部挙動 |
| 検証オラクル | Hercules (z/Architecture 命令レベル)。z/OS 実機は利用しない |
| 互換性の管理 | 目標を表す互換性レベル L0〜L3 と、裏付けを表す検証レベル V0〜V2 の 2 軸 |
| コード生成 | ASM による JVM バイトコードの直接生成 |
| メモリモデル | 全データ項目を連続バイト列上のオフセット・長さのビューとして表現 |
| 実装言語 | Java 21 |
| 構文解析 | 手書きプリプロセッサ + ANTLR4 のハイブリッド |
| ジョブ実行 | 内部ジョブモデル + JCL フロントエンド |
| データセット | EBCDIC 生バイト既定 + DD 単位の変換アダプタ |

## 現在のステータス

要件定義フェーズ。実装は未着手。

## ライセンス

Apache License 2.0 — [LICENSE](LICENSE) および [NOTICE](NOTICE) を参照。

IBM、z/OS、z/Architecture、CICS、Db2、IMS、MVS、Enterprise COBOL は IBM 社の商標であり、
本プロジェクトは IBM 社とは無関係である。

Hercules は検証用の外部オラクルとしてのみ利用し、そのソースコードは本製品に取り込まない
(Hercules は QPL であり Apache-2.0 と非互換であるため)。
