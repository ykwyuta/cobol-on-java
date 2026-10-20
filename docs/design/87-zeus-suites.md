# 設計 87: Zeus の検査スイート (ZCVS / ZPVS / ZHVS) を流す

| 項目 | 内容 |
| --- | --- |
| 状態 | ZCVS (COBOL) を実装。ZPVS (PL/I) と ZHVS (HLASM) は後続の増分 |
| 対応要件 | NFR-042 (コーパスを同梱しない)、CLAUDE.md §1 (外の基準を先に置く)・§6 (道具が失敗を作らない) |
| 検証レベル | V1。Zeus のスイートが仕様から書いた期待値で、こちらの処理系を翻訳・実行して測る |
| 場所 | `cobol-verify` の `dev.cobolonjava.verify.suite`、コマンド `verify zcvs <zeus>/zcvs` |

## 1. 位置づけ

同じ権利者の別の処理系 Zeus は、IBM Enterprise COBOL / PL/I / HLASM の振る舞いを確かめる自己検査のスイートを
持つ (Zeus `d2f1cfc`、`zcvs/`、`zpvs/`、`zhvs/`)。CCVS85 は 85 年の規格までであり、Enterprise COBOL の拡張
(TRUNC / NUMPROC の組み合わせ、国字と DBCS、`PROCEDURE-POINTER`、`RETURNING`、VSAM の状態、テープ、LE の呼び出しなど)
を測る外の基準を、こちらは持っていなかった。ZCVS はそこを埋める。

期待値は Zeus の実行結果を採録したものではなく、仕様から書いたものである (Zeus `zcvs/SCENARIOS.md`)。
ただし実機の z/OS で流して確かめたものではない。こちらで落ちたときに、どちらが正しいかは仕様に戻って決める。

## 2. 取り込み方

`tools/verify/corpus.tsv` に Zeus のリポジトリとコミットを書き、`tools/verify/fetch-corpus.sh` で取る。同梱しない。

- CLAUDE.md の決めごと (コーパスは同梱せず URL とコミットで固定する) と、CCVS85 の扱いに揃えた
- ADR-0016 は Zeus から移す部分を Apache-2.0 で出すことを認めているが、スイートは<b>外の基準</b>であり、こちらの
  成果物の一部ではない。取ってきて流し、数を記録する
- スイートが増えたら、`corpus.tsv` のコミットを進めて測り直す。数がどのコミットに対するものかは
  `PROVENANCE.tsv` に残る

## 3. 流し方

Zeus のハーネス (`crates/zeus-ccvs/src/runner.rs`、`manifest.rs`) と同じ規則にする。

| 点 | 扱い |
| --- | --- |
| モジュール | `manifest.toml` の `required_modules` と `optional_modules` の順 |
| 補助プログラム | スイートの `lib/` とモジュールの `lib/` を翻訳し、主プログラムと同じ読み込み器へ入れる。CALL はそこから引く |
| 写し句 | `copy/`。`COPY X OF COPYALT` は `copyalt/` から引く |
| CCSID | 翻訳時オプション `CODEPAGE(n)` として渡す。こちらが受けない CCSID は翻訳系が断り、「翻訳できない」と数える |
| PARM | ジョブの実行 (`JobRunner`、P-090) と同じ形で主プログラムへ渡す |
| 終了コード | `expect_exit_code` が無ければ 0 を求める (Zeus は終了状態の成功を求める) |
| 入力 | `名前.DAT` があれば ACCEPT へ渡す |
| ファイル | DD 名は作業場所の同じ名前のファイルを指す。JCL の DD 割り当ては読まない (テープの AWS は持たない) |
| 時間 | 翻訳と実行のそれぞれ 60 秒。返らなければ見捨てて次へ進む |

## 4. 数え方

| 期待 | 期待どおり | 数えるもの |
| --- | --- | --- |
| `run` | 報告が最後まで書かれ、`coverage.json` の全検査が PASS、終了コードが合う | 検査ごとに 通った / 落ちた / 出なかった |
| `compile-only` | 翻訳が通る | 1 本 |
| `compile-error` | 翻訳が断る | 1 本。Zeus の診断と同じ語を含むかは参考に数える |

- <b>出なかった検査</b>を数える。報告の「n OF m」だけでは、途中で止まって書かれなかった検査が見えない。
  `coverage.json` はスイートの側がレビューして固定した一覧で、出る順に並んでいる
- 断るべきものの合否に Zeus の診断の文面 (`diagnostic_contains`) を使わない。それは Zeus の処理系の文面であり、
  こちらが正しく断っても文面の違いで落ちる (CLAUDE.md §6)。同じ語を含んだかは参考に数える
- 翻訳系や実行時の例外は「壊れた」と数え、止めずに次へ進む

数は門にしない (CLAUDE.md §7)。

## 5. 2026-10-20 の測定 (Zeus `d2f1cfc`)

初回は 158 本中 40 本だった。74 本が `ASSIGN TO REPORT` の構文誤りで止まっていたので直し (予約語と同じ綴りの
DD 名、`3ef0286`)、次の数になった。

| | 本数 | 期待どおり | 検査 | 通った | 落ちた | 出なかった |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 実行 | 93 | 24 | 1,431 | 651 | 34 | 746 |
| 翻訳で断るべきもの | 45 | 28 | | | | |
| 翻訳が通るべき対照 | 20 | 12 | | | | |
| 計 | 158 | 64 | | | | |

止まった行で数えた塊 (多い順):

| 塊 | 本数 | 例 |
| --- | ---: | --- |
| 断るべきものを通した (`END PROGRAM` の欠落、重複、`RECURSIVE`、LOCAL-STORAGE の制限など) | 17 | ZE101N〜ZE145N |
| CCSID 939 / 930 / 1390 / 1399 の翻訳 (P-177) | 9 | ZN101A |
| TRUNC(OPT / BIN)、NUMPROC(PFD / MIG) の翻訳時オプション | 7 | ZA102A |
| `RETURNING` (CALL と手続き部の見出し) | 7 | ZP101A の補助 ZPS03A |
| `PROCEDURE-POINTER` | 5 | ZP105A |
| `REPLACE` の疑似テキストでない被演算子 | 4 | ZS105A |
| 浮動小数点の編集 PICTURE (`+99.99E+99`) | 4 | ZA120A |
| ファイルの状態の拡張域 (`FILE STATUS IS fs ext-fs`) | 4 | ZF107A |
| `OMITTED` の CALL 引数 | 2 | ZP104A |
| `FUNCTION ABS` | 2 | ZA121A |
| `88 ... WHEN SET TO FALSE` | 2 | ZX102A |
| 国字の類 (`INITIALIZE ... NATIONAL`、`GROUP-USAGE NATIONAL`、国字の編集 PICTURE、国字の定数) | 6 | ZN105A |
| 落ちた検査 (ODO の長さ、2 進の境界、NUMPROC の符号、CODE-SET、代替索引、テープの HDR など) | 11 本 34 件 | ZD113A |
| そのほか 1 本ずつ (`RECORDING MODE S / U`、`TALLY`、`CEEDAYS`、`TITLE`、`SET ... UP BY` の書き方など) | 14 | ZS101A |

ZPVS と ZHVS は後続の増分で同じハーネスに載せる。
