# 設計 87: Zeus の検査スイート (ZCVS / ZPVS / ZHVS) を流す

| 項目 | 内容 |
| --- | --- |
| 状態 | ZCVS (COBOL)、ZPVS (PL/I)、ZHVS (HLASM) を同じハーネスで流す |
| 対応要件 | NFR-042 (コーパスを同梱しない)、CLAUDE.md §1 (外の基準を先に置く)・§6 (道具が失敗を作らない) |
| 検証レベル | V1。Zeus のスイートが仕様から書いた期待値で、こちらの処理系を翻訳・実行して測る |
| 場所 | `cobol-verify` の `dev.cobolonjava.verify.suite`、コマンド `verify zcvs` / `verify zpvs` / `verify zhvs` (`<zeus>/z?vs`) |

## 1. 位置づけ

同じ権利者の別の処理系 Zeus は、IBM Enterprise COBOL / PL/I / HLASM の振る舞いを確かめる自己検査のスイートを
持つ (Zeus `d2f1cfc`、`zcvs/`、`zpvs/`、`zhvs/`)。CCVS85 は 85 年の規格までであり、Enterprise COBOL の拡張
(TRUNC / NUMPROC の組み合わせ、国字と DBCS、`PROCEDURE-POINTER`、`RETURNING`、VSAM の状態、テープ、LE の呼び出しなど)
を測る外の基準を、こちらは持っていなかった。ZCVS はそこを埋める。

期待値は Zeus の実行結果を採録したものではなく、仕様から書いたものである (Zeus `zcvs/SCENARIOS.md`)。

### 1.1 z/OS で確かめた基準ではない

<b>2026-10-20 時点 (Zeus `d2f1cfc`) の ZCVS / ZPVS / ZHVS は、z/OS の実機で流して期待値を確かめたものではない</b>
(Zeus `zcvs/README.md` も「local results do not certify execution on IBM z/OS」と書く)。CCVS85 (規格の検査スイート) や
Hercules (命令の実機の写し) とは、基準としての重さが違う。したがって次のように扱う。

- <b>落ちた検査は「こちらの不具合」とも「スイートの誤り」とも決めずに、仕様に戻って決める。</b>ZCVS が落ちたことだけを
  根拠に振る舞いを変えない。直すときは、コミットメッセージと暫定判断に<b>仕様の根拠</b> (IBM の公開文書の記述など) を書く
- <b>「断るべきものを通した」も同じである。</b>ZCVS の負系は Zeus がそう読んだ規則であり、Enterprise COBOL が実際に断るかは
  確かめられていない。断る規則を足すときも、仕様の根拠を書く
- 仕様が読み切れないものは、こちらが正しいとも ZCVS が正しいとも決めずに暫定判断に残し、z/OS probe (`tools/zos-probe`) で
  実機に流す候補にする
- 数は「ZCVS の期待値との一致」であって「z/OS との一致」ではない。README と報告でもそう書く

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
DD 名、`3ef0286`)、次の数になった。この直しの根拠は ZCVS ではなく、Enterprise COBOL の ASSIGN 句の説明
(assignment-name は「データ項目の名前ではなく、ただの文字の並び」) である。実機では確かめていない。

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

### ZPVS (PL/I) と ZHVS (HLASM)

言語ごとの違いは翻訳だけ (`SuiteLanguage`) で、翻訳したものはどれも `CobolProgram` のクラスなので、動かす段と報告を読む段は
COBOL と共通である。PL/I の `%INCLUDE` と HLASM のマクロはスイートの `copy/` から引く。報告の行は言語で空白の数が違い
(COBOL は 5 桁、PL/I は 1 桁、HLASM は名前を 6 桁目に置く)、PL/I と HLASM の報告は「END OF TEST-」ではなく集計の行で終わる。

| スイート | 本数 | 期待どおり | 実行 (全検査が通った) | 検査 (通った / 出なかった) | 断るべきもの (断った) | 対照 (通った) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ZPVS | 68 | 16 | 38 (0) | 318 (0 / 318) | 15 (8) | 15 (8) |
| ZHVS | 125 | 61 | 85 (22) | 537 (172 / 365) | 20 (19) | 20 (20) |

どちらも、実行する本は報告を書く前の翻訳で止まっている。

- ZPVS: 報告を書く `WRITE FILE(REPORT) FROM(...)` (レコード入出力) が未実装で、38 本中 36 本がここで止まる。ほかに値を返す
  `RETURN`、`ALLOCATE`、`LEAVE` / `ITERATE`、`LOCATE`、定数でない配列の境界
- ZHVS: 検査の行を書く補助ルーチンを `L 15,=V(ZHVRPT)` で呼ぶので、はじめは V 型の定数が未実装の 83 本がここで止まっていた。
  V 型定数と番地定数の直し (P-230)、`DCB` / `OPEN` / `PUT` / `CLOSE` (P-231)、64 ビットのレジスタと命令 (P-232)、FD 型定数、
  `CNOP`、EQU の前方参照、`OPSYN` (P-233) を入れて 61 本になった (2026-10-20)。HA / HM / HI / HD の実行は 26 本中 22 本で
  全検査が通り、落ちた検査は 0 件である。残るのは浮動小数点 (HF 57 本: `SFPC` / `PFPO` / `LD` / `STD`)、HS の補助ルーチンの
  `GETMAIN` / `GET` (EODAD) / `LINK` / `POST` (HS 4 本)、倍率の修飾子と `S'` / `I'` (HA 2 本)、SRP の丸めの桁の範囲 (HE115N)
- 断るべきものを通したのは ZPVS 7 本、ZHVS 3 本

PL/I と HLASM の翻訳系は、これまで CCVS85 のような外の基準を持たず、Bank-of-Z の資産で測っていた。実行の段で測るには、
PL/I のレコード入出力と、HLASM の V 型定数とファイルのマクロが先に要る。
