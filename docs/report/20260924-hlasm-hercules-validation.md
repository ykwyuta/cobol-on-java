# HLASM 実行の Hercules 比較 (2026-09-24)

## 実行環境

- 参照実装: [SDL Hyperion 4.9.1 公式リリース](https://github.com/SDL-Hercules-390/hyperion/releases/tag/Release_4.9.1) の Windows x64 ポータブル版。`herclin.exe` を使用。
- 配布 ZIP の SHA-256: `C94522F60139C43D08ECDFBD317BE0A85A1F02B73CBB74DF47A40040F73152C9`。
- 実行コマンド: `$env:HERCULES = 'C:\path\to\herclin.exe'; mvn -B -o -pl hlasm-assembler -am test -q`。
- 結果: `HerculesExecutionOracleTest` 26 件 (正常終了 19 件、プログラム割込み 7 件)。失敗 0、エラー 0、スキップ 0。
- 22 件時点の全体回帰テストは `hlasm-assembler` 156 件、`cobol-oracle` 51 件で失敗 0。追加した 4 件は比較テストを単独で再実行して通過した。

## 比較方法

`HerculesExecutionOracleTest` が HLASM の原文を 1 度組み立て、得た同じ機械語を Hercules と
`Cpu` に渡す。Hercules では作業域を `0x400`、`Cpu` では `AddressSpace` の区画に置き、
ベースレジスタ R4 をそれぞれの先頭に設定する。実行後の作業域、R0 / R1 / R2、条件コードを比較する。
`EDMK` と `TRT` が作業域を指す R1 は、基点からの変位で比較する。
戻り番地 R14 とコードの基点 R15 は各環境に合わせて設定し、比較対象から外す。
プログラム割込みでは、IBM の [z/Architecture Principles of Operation](https://www.ibm.com/docs/en/module_1678991624569/pdf/SA22-7832-14.pdf?cp=HW11W) に従い、
Hercules の低位記憶域 `0x8E`–`0x8F` にある割込みコードを `MachineException.code()` と比較する。
割込み時の作業域も全バイト比較する。

| ケース | 主な命令と観測点 | 結果 |
| --- | --- | --- |
| `mvc-overlap` | 重なる領域への `MVC` | 一致 |
| `clc-less` | `CLC` の条件コード | 一致 |
| `ap-positive` | `AP` の結果と条件コード | 一致 |
| `srp-left` | `SRP` の桁移動 | 一致 |
| `mvo` | `MVO` のニブル転記 | 一致 |
| `edmk` | 編集結果と R1 の指す位置 | 一致 |
| `slda-overflow` | `SLDA` のあふれと条件コード | 一致 |
| `srda-negative` | `SRDA` の負数シフト | 一致 |
| `trt` | `TRT` の検出位置、関数バイト、条件コード | 一致 |
| `ex-mvc` | `EX` が変更した `MVC` の長さ | 一致 |
| `bxh` | `BXH` の増分、比較、分岐 | 一致 |
| `mvc-256` | `MVC` の最大長 256 バイトと重なり | 一致 |
| `trt-no-match` | `TRT` で検出対象なし | 一致 |
| `ap-overflow-unmasked` | マスク無効の 10 進桁あふれ | 一致 |
| `dp-remainder` | 10 進除算の商と剰余 | 一致 |
| `ap-invalid-digit` | 無効な数字ニブルによるデータ例外 `0x07` | 一致 |
| `dp-zero` | 10 進ゼロ除算の割込み `0x0B` | 一致 |
| `dr-zero` | 固定小数点ゼロ除算の割込み `0x09` | 一致 |
| `mr-odd-register` | 奇数レジスタ対の仕様例外 `0x06` | 一致 |
| `ex-of-ex` | `EX` を `EX` で実行したときの例外 `0x03` | 一致 |
| `ap-overflow-masked` | マスク有効の 10 進桁あふれ `0x0A` と結果バイト | 一致 |
| `a-overflow-masked` | マスク有効の固定小数点あふれ `0x08` | 一致 |
| `pack` | ゾーン 10 進からパック 10 進への変換 | 一致 |
| `unpk` | パック 10 進からゾーン 10 進への変換 | 一致 |
| `bxle-even` | 偶数増分レジスタに対する比較レジスタの選択と分岐 | 一致 |
| `ex-branch` | `EX` による `BC` のマスク変更と分岐 | 一致 |

## 範囲

これは選んだ入力に対する実行比較であり、全命令、全境界値、全プログラム割込み、
標準リンケージ、OS サービスの一致を示すものではない。対応する `.asm` 資産コーパスはないため、
既存の `hlasm-corpus` 実行器による実資産の受理率・実行一致率は測定できない (P-175)。
合成ケースは実資産の代わりに境界値と例外を再現できるようテストに保存した。
