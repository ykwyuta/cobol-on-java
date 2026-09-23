# z/OS 実機での挙動調査 — 実行手順書

z/OS の環境が使えるようになったときに、暫定判断 (`docs/decisions/provisional.md`) のうち
**実機でしか決着しないもの**を一度にまとめて測るための手順である。何を問うのかは
[scenarios.md](scenarios.md) に、原文と道具は `tools/zos-probe/` にある。

実機の時間は限られている。測り直しを避けるため、**一度流したら出力をすべて持ち帰る**ことを
最優先にする。解釈は持ち帰ってから手元でやる。

## 0. 全体の流れ

```
手元                                   z/OS
────                                   ────
1. 原文の検査 (check-source.sh)
2. YOURID を置き換える
3. 送る  ─────────────────────────▶   4. PRBALLOC (一度だけ)
                                       5. PRBBLDC / PRBBLDP / PRBBLDA (翻訳)
                                       6. PRBRUNC / PRBRUNP / PRBRUNA / PRBABND
                                       7. JCLCOND ... JCLCP
8. 持ち帰る ◀─────────────────────────
9. ローカル側を流す (run-local.sh)
10. 突き合わせる (ProbeTool.java)
11. 暫定判断を書き直す
```

## 1. 前提

| 項目 | 内容 |
| --- | --- |
| 製品 | Enterprise COBOL 6.x、Enterprise PL/I 5.x / 6.x、HLASM、DFSORT、z/OS 2.4 以降 (`JOBLIB` と `SET` の混在、`DSNTYPE=LIBRARY` を使う) |
| 権限 | 自分の高位修飾子の下にデータセットを作れること。`DELETE ... GDG FORCE` が拒まれる場合は §7.3 |
| 手元 | JDK 21、Maven (オフラインでよい)、Git Bash などの POSIX シェル、Zowe CLI か FTP |
| 容量 | 約 30 シリンダ。`JCLCP` の出力が最も大きい (DBCS 4 表で約 15 万行) |

**持ち帰る前に書き留めること** (結果の解釈に要る。設計 26 §4 と同じ考え方)

- 各製品の版と PTF 水準 (翻訳リストの 1 行目、`CEE3` のメッセージ、`ASMA` の見出し)
- 翻訳オプションの既定値。翻訳リストの先頭の「オプション一覧」をそのまま残す。
  既定の `NUMPROC` / `ARITH` / `TRUNC` / `ADV` / `NUMBER` / `RENT` はサイトで変えられている
  ことがある。**既定が違えば、変種の意味も変わる**
- LE の実行時オプションの既定 (`CEEPRMxx`)。`STORAGE` と `DEBUG` の既定値が要る
- ボリュームが SMS 管理かどうか (`JCLSPACE` の区画の数が変わる)

## 2. 手元での準備

### 2.1 原文の検査

```bash
sh tools/zos-probe/check-source.sh
```

実機へ送る原文は **IBM-1047 で表せる字 (ASCII の印字文字) だけ**で書き、**72 桁を超えない**。
日本語の注記を書くと、DBCS の SO/SI が入って桁がずれ、実機で原文が壊れる。そのため
`tools/zos-probe/` の原文の注記は英語にしてあり、日本語の説明は scenarios.md に置いている。
この検査が通らないものは送らない。

`cobol/CBLC72A.cbl` 〜 `CBLC72C.cbl` は **72 桁目の位置そのものが観測の対象**である。
編集器で整形しない。作り直すときは `sh tools/zos-probe/gen-col72.sh`。

### 2.2 高位修飾子を置き換える

すべての JCL は `YOURID.PROBE.` を文字どおり書いている (SYSIN の中の名前も含む)。
JCL の記号を使わないのは、同じ JCL をこの処理系のジョブ実行でもそのまま流すためである
(この処理系は `DD *,SYMBOLS=` を持たない)。

```bash
mkdir -p build/zos-probe && cp -r tools/zos-probe/jcl build/zos-probe/
sed -i 's/YOURID/USER01/g' build/zos-probe/jcl/*.jcl
```

`USER01` は自分の高位修飾子に換える。続いて **JOB カード** (`(ACCT)`、`CLASS`、`MSGCLASS`) を
サイトの決まりに合わせる。ビルド用の 3 本 (`PRBBLDC` / `PRBBLDP` / `PRBBLDA`) は、先頭の
`SET` にある製品のライブラリ名 (`IGY.V6R4M0.SIGYCOMP`、`IBMZ.V6R1M0.SIBMZCMP`、
`CEE.SCEELKED` など) もサイトの名前に合わせる。翻訳系が LNKLST にあるなら `STEPLIB` の
行を消してよい。**存在しないライブラリを `STEPLIB` に書くと、ジョブ全体が JCL エラーになる。**

### 2.3 送る

先に `PRBALLOC` だけを送って流し (§3.1)、ライブラリを作ってから残りを送る。

Zowe CLI の場合 (ファイル名の拡張子を落としたものがメンバ名になる):

```bash
zowe zos-files upload dir-to-pds tools/zos-probe/cobol "USER01.PROBE.SRC.COBOL"
zowe zos-files upload dir-to-pds tools/zos-probe/copy  "USER01.PROBE.SRC.COPY"
zowe zos-files upload dir-to-pds tools/zos-probe/pli   "USER01.PROBE.SRC.PLI"
zowe zos-files upload dir-to-pds tools/zos-probe/hlasm "USER01.PROBE.SRC.ASM"
zowe zos-files upload dir-to-pds build/zos-probe/jcl   "USER01.PROBE.JCL"
```

FTP の場合は `ascii` モードで 1 本ずつ `put cobol/CBLNUM.cbl 'USER01.PROBE.SRC.COBOL(CBLNUM)'`。
写し句は `.cpy` を落として `PRBWS` / `PRBPD` / `PRBDBGC` にする。

送ったあと、ISPF で `CBLC72A` を開き、`"` が 72 桁目にあることを目で確かめる
(`COLS` 行コマンド)。転送の途中で行末の空白が落ちたり、タブが混ざったりしていないことの確認である。

## 3. 実機で流す

どのジョブも**出力を消さずに残す**。流し直すときは、前の出力を持ち帰ってから流す。

### 3.1 ライブラリを作る (一度だけ)

`PRBALLOC` を投入する。RC=0 を確かめる。

### 3.2 翻訳する

`PRBBLDC`、`PRBBLDP`、`PRBBLDA` を投入する。

**翻訳が失敗しても、それは結果である。**次のものは失敗しうるし、失敗したときの診断そのものが
問いへの答えになる。

| 原文 / 変種 | 失敗したら何が分かるか |
| --- | --- |
| `CBLNUMM` (`NUMPROC(MIG)`) | 現行の翻訳系が `MIG` を持たない → P-004 は「実装を分ける必要がない」で解消 |
| `CBLBIN31` | 19 桁以上の 2 進を翻訳系が受けない → P-005 の要求そのものを見直す |
| `CBLC72A` / `CBLC72B` | 72 桁目の引用符をどう読んだか (P-084)。診断の番号と文面を残す |
| `CBLCP` | 翻訳系に `NATIONAL-OF` がない版。その場合は `JCLCP` の `ICONV` の段だけを使う |
| `ASMDC2` | 浮動小数点の定数の書き方が版で違う。受けなかった行を記録し、原文を直して流し直す |

翻訳リストの中で**特に残すもの**:

- `CBLSYNC` の `MAP` (データ項目の変位と長さ。P-111 の 2 つ目の観測)
- `PLIMAP` の `AGGREGATE` (構造の要素の変位。P-184 の 2 つ目の観測)
- `ASMDC1` / `ASMDC2` / `ASMDC3` の組み立て表の目的コード欄 (P-018、P-171)
- `CBLDEBUG` / `CBLDBGN` の翻訳リストの行番号の欄 (P-080 は `DEBUG-LINE` とこれを比べる)

### 3.3 言語の probe を流す

`PRBRUNC`、`PRBRUNP`、`PRBRUNA`、`PRBABND` を投入する。

どのステップも `COND=EVEN` を持ち、前のステップが異常終了しても流れ続ける。
**異常終了は結果である。**次のステップは異常終了しうる。

| ジョブ / ステップ | 起こりうること | 残すもの |
| --- | --- | --- |
| `PRBRUNC` `NUMM`、`BIN31`、`C72A`、`C72B` | `S806` (翻訳が失敗してロードモジュールがない) | 何もしなくてよい。§3.2 の診断が結果 |
| `PRBRUNC` `ACPT` | 入力が尽きた `ACCEPT` での異常終了 (P-083) | 完了コードと LE のメッセージ |
| `PRBRUNA` `E07` `E08` `E09` `PM21` | `S0C8` / `S0CA` (P-174) | 完了コード。`CEEDUMP` の PSW |
| `PRBABND` のほとんど | `S0C7`、`U4038`、`S806`、`S0CB` | 完了コード、`IGZ` / `CEE` のメッセージ、`CEEDUMP` |

### 3.4 JCL の probe を流す

次の順に投入する。前のジョブの結果を読むものがあるので、**順番を守る**。

1. `JCLCOND` → `JCLCONR`
2. `JCLDISP` → `JCLDISP2`
3. `JCLGDG` → `JCLGDG2`
4. `JCLSPACE`
5. `JCLPDS`
6. `JCLUTIL`
7. `JCLTSO`
8. `JCLCP`

`JCLDISP2` は JCL エラーで止まるかもしれない。止まったこと自体が P-054 の答えである。

## 4. 持ち帰る

### 4.1 ジョブの出力

ジョブごとに、スプールの全部を持ち帰る。置き場の形を揃えておくと、`ProbeTool` が
そのまま突き合わせられる。

```bash
mkdir -p tools/zos-probe/results/zos
zowe zos-jobs download output JOB01234 -d tools/zos-probe/results/zos/PRBRUNC
```

Zowe はステップ名のディレクトリの下に DD ごとのファイルを作るはずである
(`.../PRBRUNC/JOB01234/NUMN/SYSOUT.txt` の形。**この形は実機で確かめていない**。Zowe の版で
違っていたら、次の段落の形に並べ直す)。`ProbeTool` は **ファイルを直に含むディレクトリの
名前をステップ名**として読むので、この形のままでよい。手元のローカル側 (`results/local/PRBRUNC/NUMN/SYSOUT.txt`)
と同じ名前になる。

SDSF から手で保存する場合も、`<ジョブ>/<ステップ>/<DD>.txt` の形に置く。

**JES のメッセージ (`JESMSGLG`、`JESYSMSG`) も必ず持ち帰る。**完了コード、ステップの
「FLUSH」「NOT RUN」、`IEF142I` / `IEF285I` の DISP の結末は、ここにしか出ない。

### 4.2 データセット

**レコードのまま** (変換なし) で持ち帰る。印字の制御文字と長さが観測の対象だからである。

```bash
zowe zos-files download data-set "USER01.PROBE.OUT.PRTF"  --binary -f results/zos/OUT/PRTF.bin
zowe zos-files download data-set "USER01.PROBE.OUT.STRM1" --record -f results/zos/OUT/STRM1.rec
```

| データセット | 取り出し方 | 読み方 |
| --- | --- | --- |
| `OUT.PRTF` `OUT.PRTFN` `OUT.LINF` `OUT.LINFN` `OUT.PRTC` `OUT.SYNCF` | `--binary` (固定長) | `ProbeTool print <file> --fixed <LRECL>` |
| `OUT.STRM1` `OUT.STRM2` `OUT.STR2` | `--record` (可変長、RDW つき) | `ProbeTool print <file> --rdw` |
| `OUT.CPNAT` | 文字 (既定の変換) でよい。中身は 16 進だけ | `ProbeTool cp <file>` |
| `DISP.A`、`GDG.G000nV00`、`UT.SORTED`、`UT.REPRO` | 文字でよい | 目で見る |

**どのデータセットも `LISTCAT ... ALL` か ISPF 3.4 の属性を書き留める。**`RECFM` (とくに `A` が
付くか)、`LRECL`、`BLKSIZE` は中身からは分からない。`CBLPRNT` の問い (P-063) は
「`LRECL` が 20 か 21 か」である。

## 5. ローカル側を流す

```bash
mvn -B -o -q install -DskipTests
sh tools/zos-probe/run-local.sh
```

`install` を先に流すのは、`run-local.sh` が `~/.m2` の成果物で起動するためである。作業木の
変更を反映させるには、先に入れ直さなければならない。

結果は `tools/zos-probe/results/local/` に、実機と同じ `<ジョブ>/<ステップ>/SYSOUT.txt` の形で残る。
翻訳で断ったものは `compile.txt` に診断がある。**断ったことも観測である** (例: `CBLBIN31` は
16 byte の 2 進が未実装だと断る。実機が受けるなら P-005 は要る、受けないなら要らない)。

## 6. 突き合わせる

### 6.1 16 進の観測 (COBOL / PL/I / HLASM)

```bash
java tools/zos-probe/ProbeTool.java prb tools/zos-probe/results/zos/PRBRUNC tools/zos-probe/results/local/PRBRUNC
```

出力は `DIFF` / `ZOS-ONLY` / `LOCAL-ONLY` の行と、最後の件数である。

| 判定 | 意味 | 次にすること |
| --- | --- | --- |
| `MATCH` | 16 進が一致した | その暫定判断の「実機と突き合わせていない」を消せる |
| `DIFF` | 両方にあるが違う | 実機が正。scenarios.md の該当の行を見て、どの規則が違うかを決める |
| `ZOS-ONLY` | ローカルが出していない | ローカルが翻訳で断ったか、途中で止まった。`compile.txt` とローカルの `SYSOUT.txt` の末尾を見る |
| `LOCAL-ONLY` | 実機が出していない | 実機が異常終了したか、翻訳できなかった。JES のメッセージを見る |

**変種のステップ (`NUMP`、`FUNE`、`PRNTN` など) の `DIFF` は、この処理系がそのオプションを
読んでいない証拠である** (P-023)。ローカルは変種でも同じクラスを流している。

### 6.2 印字の観測

```bash
java tools/zos-probe/ProbeTool.java print results/zos/OUT/PRTF.bin --fixed 21
java tools/zos-probe/ProbeTool.java print results/zos/OUT/STRM1.rec --rdw
```

ローカル側の印字は `results/local/PRBRUNC/PRNT/PRTF` (制御文字を持たず、空行で紙送りを表す、P-063)
と `results/local/PRBRUNP/STRM1/SYSOUT.txt` (改頁は `\f`) である。形が違うので、行ごとに目で
突き合わせる。見る点は scenarios.md の各行に書いてある。

### 6.3 コードページ

```bash
java tools/zos-probe/ProbeTool.java cp results/zos/OUT/CPNAT.txt
```

CCSID ごとに、JDK のチャーセット (この処理系が使っているもの) との一致・不一致を数え、
不一致の例を 20 件まで出す。1390 / 1399 は JDK に無いので x-IBM939 と比べる。その差が
P-002 で作らなければならない変換表の差分である。

### 6.4 JCL の観測

JCL の probe は、ステップが流れたかどうか・完了コード・データセットの結末が観測である。
[scenarios.md の JCL の節](scenarios.md#jcl) の表を写し、実機の欄を埋める。ローカル側は
`results/local/jcl/<ジョブ>.txt` のジョブログを読む。

## 7. 困ったとき

### 7.1 `S806` がどのステップでも出る

`JOBLIB` の名前が違うか、`PRBBLDC` のバインドが失敗している。`YOURID.PROBE.LOAD` の
メンバ一覧を見る。

### 7.2 `DISPLAY` の行が 2 行に折れている

`SYSOUT` の DD の `LRECL` が短いサイトがある。probe の行は最長で約 110 桁である。
`//SYSOUT DD SYSOUT=*,LRECL=255` に換えて流し直す (結果の中身は変わらない)。

### 7.3 `DELETE ... GDG FORCE` が権限で拒まれる

`JCLGDG` の `DELGDG` を次の 2 文に換える。

```
  DELETE YOURID.PROBE.GDG.* PURGE
  DELETE YOURID.PROBE.GDG GDG
```

### 7.4 `CBLCP` が不正な DBCS の組で止まる

`JCLCP` の `D` の札を分ける (`01399 D 064 127` と `01399 D 128 254` など)。止まった範囲を
さらに半分に分けて、止まる組を突き止める。**止まったこと自体を結果として残す** —— この
処理系の変換で同じ組をどう扱うか (置換文字か例外か) の根拠になる。

### 7.5 翻訳リストで既定のオプションが想定と違う

たとえば既定が `NUMPROC(PFD)` だった場合、`CBLNUM` と `CBLNUMP` は同じ意味になる。
`PRBBLDC` の `PARM.COMPILE` に `NUMPROC(NOPFD)` を明示した変種を足して流し直す。
**既定に頼った観測は、既定が分からないと読めない。**

## 8. 結果を処理系に戻す

持ち帰った観測は、CLAUDE.md の増分の型に従って処理系に戻す。

1. `ProbeTool` の結果を scenarios.md の「実機」の欄に写す (日付、製品の版を添える)
2. 暫定判断ごとに、`provisional.md` の該当項目を書き直す。**実機の結果と、それを採った
   製品の版を根拠として書く**。一致したものは「実機と突き合わせていない」を消す
3. 違ったものは、1 つの暫定判断 = 1 つの増分として直す。そのとき、実機の 16 進を
   期待値にした試験を書く (自分で考えた期待値ではなく)
4. 持ち帰った出力は**リポジトリに入れない** (IBM 製品の出力で、ライセンスの扱いが決まって
   いない)。scenarios.md には観測した値だけを書き、出力の置き場は手元に残す
