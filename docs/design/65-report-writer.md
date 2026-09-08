# 設計 65: 報告書作成機能

対象: 要件 FR-214 / 制約 C-4 / 方針 ARC-7

## 位置付け

`REPORT SECTION` に書かれた宣言から、<b>普通のレコード記述と普通の文</b>を組み立てる。
専用の実行時機構は持たない。制約 C-4 が言う「プリプロセッサ方式」を、
外部のプリプロセッサではなく<b>意味解析の段</b>でやっている。

こうする理由は 1 つである。転記も編集も詰め方も行送りも、<b>すでに外の基準で
確かめてある道</b>をそのまま通せるからである。専用の機構を作れば、そこだけ
確かめ直さなければならない。

```
REPORT SECTION の構文木
        │
        ├─ DataDivisionBuilder.addReports  … 行の姿を FD のレコード領域として作る
        │                                    数え札 (LINE-COUNTER / PAGE-COUNTER) を置く
        │
        └─ ReportLowering                 … INITIATE / GENERATE / TERMINATE を
                                             MOVE・COMPUTE・IF・WRITE の並びへ落とす
```

## 行の姿はレコード記述である

報告集団の行 1 本ごとに、01 レベルを 1 個作る。子は `COLUMN` の位置に置いた基本項目で、
間は `FILLER` で埋める。

```
01  RW-FS3-DETAIL
    LINE PLUS 1
    TYPE IS DETAIL.
    03  PIC X(12)  COLUMN 20  VALUE "DETAIL LINE ".
    03  PIC 99     COLUMN 32  SOURCE IS WS-COUNTER.
```

は、次と同じ姿の 01 になる。

```
01  RW-L$RPT$RW-FS3-DETAIL$0.       (FD のレコード領域として置く)
    05  FILLER            PIC X(19).
    05  RW-F$…$0          PIC X(12).
    05  RW-F$…$1          PIC 99.
    05  FILLER            PIC X(…).
```

名前に `$` を含む。COBOL の語に `$` は書けないので、<b>資産の名前とぶつかりようがない</b>。

行の幅は、いちばん右まで届く欄で決めている (暫定判断 P-077)。`RD` に幅を書く句が
無いためである。

置くときは、まず<b>全体を空白で埋め</b>、それから欄ごとに `MOVE` する。
ファイル節のレコード領域は `VALUE` で初期化されないので、定数の欄も置くたびに入れる。

## 行を置く場所は数え札の算術である

`LINE-COUNTER` は<b>いま頁の何行目まで書いたか</b>を持つ。次に置く行は `RW-TGT$` に決め、
送る行数を `RW-ADV$` に入れて `WRITE ... AFTER ADVANCING RW-ADV$ LINES` する。

| 書き方 | 置く行 |
| --- | --- |
| `LINE n` | 頁の n 行目。すでに n 行目を過ぎていたら頁を改めてから |
| `LINE PLUS n` | いまの位置から n 行下 |
| `LINE NEXT PAGE` | かならず頁を改めてから、本文の先頭 |

本文の集団だけが `FIRST DETAIL` と `LAST DETAIL` に従う。`FIRST DETAIL` より上には
置かず、`LAST DETAIL` を越えるなら頁を改めて本文の先頭から置き直す。
見出しと脚注は<b>頁に固定された行</b>なので、そこから頁を改めることはない。
だから頁を改める道が入れ子にならない。

## 改頁は行送りの数に負の値で載せる

`Ops.writeLine` の行数は、もともと `PAGE`(= -1) を「改頁して 1 行目へ」の意味で
持っていた。これを<b>一般化して</b>、`-k` を「改頁して k 行目へ」とした。

```java
if (lines < 0) {
    String status = file.write(pageBreak(file, width));
    for (int i = 1; i < -lines && status.equals(FileStatus.OK); i++) {
        status = file.write(blankLine(file, width));
    }
    return status;
}
```

こうすると、改頁と行送りが<b>1 回の書き込みで表せる</b>。2 回に分けると、頁の先頭に
余計な空行が 1 本出てしまう。既存の `ADVANCING PAGE` の意味は変わらない。

## 3 つの文が落ちる先

| 文 | 落ちる先 |
| --- | --- |
| `INITIATE` | `LINE-COUNTER` に 0、`PAGE-COUNTER` に 1 |
| `GENERATE` | 最初の 1 回だけ報告書の見出しと頁の見出しを置く。続けて本文を置く |
| `TERMINATE` | `GENERATE` が動いていれば、頁の脚注と報告書の脚注を置く |

数え札の動きは NIST CCVS85 の RW101A から RW104A が決めている。
`INITIATE` のあと `LINE-COUNTER` は 0 で `PAGE-COUNTER` は 1、`GENERATE` のあと
`LINE-COUNTER` は<b>その行を置いた行番号</b>に等しい。

`LINE-COUNTER` と `PAGE-COUNTER` は報告書ごとに 1 つである。報告書が 2 つあれば
同じ名前の項目が 2 つでき、修飾せずに書けば「あいまいだ」と断ることになる。
規格の決まりどおりである。

## 断っていること

制御の切れ目 — `RD` の `CONTROL` 句、`CONTROL HEADING` / `CONTROL FOOTING` の集団、
`SUM` の欄、`GENERATE 報告書名` — は<b>書けないと断る</b> (暫定判断 P-078)。

近いものを黙って出すと、<b>合計が合わない報告書</b>ができる。合わない数字は、
出ないより悪い。

## 次の増分

1. 制御の切れ目と小計の持ち回り (P-078)
2. `NEXT GROUP` 句、`GROUP INDICATE`
3. 行の幅を実機と突き合わせる (P-077)
