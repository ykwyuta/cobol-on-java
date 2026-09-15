# 設計 82: CICS の file control、一時記憶・一時データのキュー、START / RETRIEVE

| 項目 | 内容 |
| --- | --- |
| 状態 | file control (§3)、一時記憶 (§4)、一時データ (§5)、START / RETRIEVE / CANCEL (§6)、非同期 API (§7) を実装 |
| 対応要件 | FR-080, FR-101、設計 77 §4、設計 79 |
| 検証レベル | V1。実機の CICS と突き合わせていない |

## 1. 位置づけ

Bank-of-Z の CICS 資産 32 本は、この文書の命令をほとんど使わない (ファイル制御は ABNDPROC の `WRITE FILE` だけ)。
そのため測定では増分の順を決められない。代わりに CICS TS の公開文書の、命令ごとの頁と表を基準に置く。

- **文書に数と意味が書かれた条件だけを返す。** 書かれていない形は、近い条件を選ばず `CicsTaskStateException` で失敗させる
- 実機の CICS のソースは参照しない

## 2. 共通の決めごと

| 項目 | 決めごと | 出典 |
| --- | --- | --- |
| RESP の数 | 表にある名前はその数、表から読めなかった名前は命令の頁の数を使う | CICS TS 6.x「Response codes of EXEC CICS commands」、命令の頁 |
| ENDFILE | 20。READNEXT の頁の値。AEIx の abend の並び (AEIS NOTOPEN 19、AEIT ENDFILE、AEIU ILLOGIC 21) とも合う | READNEXT の頁、TXSeries の AEIx の一覧 |
| EIBFN | 表の値 | CICS TS 5.6「Function codes of EXEC CICS commands」 |
| EIBRCODE | binary zero。群ごとの byte を確かめていない。起きてよい群の外で起きたら処理系の誤りとして失敗させる | 設計 79 §3.3 |
| 回復 | すべて回復不能。`SYNCPOINT ROLLBACK` は書いたものを戻さない | — |
| 構成 | region の構成 `CicsEnvironment` が port を持ち、task どうしで分け合う。複数の JVM では分け合わない | 設計 79 §3.2 |

## 3. file control

### 3.1 file の定義

`CicsFileDefinition` が 1 つの file を表す。

| 項目 | 内容 |
| --- | --- |
| 編成 | KSDS (主鍵の位置と長さ) と RRDS。ESDS は RBA の数え方を公開文書から決められないので持たない |
| 長さ | 固定長か可変長 (最大の長さ) |
| 許す操作 | READ / UPDATE / ADD / DELETE / BROWSE (FILE 定義の同名の属性にあたる) |
| 置き場 | バッチの `IndexedDataSet` / `RelativeDataSet` と同じ形のデータセット。ジョブがそのまま読める |

命令のたびにデータセットを開いて閉じ、region の中の命令は 1 つの監視で順に通す。大きな file の性能は測っていない。

### 3.2 命令と返す条件

| 命令 | EIBFN | 返す条件 (RESP / RESP2) |
| --- | --- | --- |
| READ | 0602 | FILENOTFOUND 12/1、NOTFND 13/80、INVREQ 16/20 (UPDATE を許さない)・25・26・28 (READ UPDATE の二重)・42、LENGERR 22/11、IOERR 17/120 |
| WRITE | 0604 | FILENOTFOUND、DUPREC 14/150、NOSPACE 18/100、LENGERR 22/12 (最大を越える)・14 (固定長と違う)、IOERR |
| REWRITE | 0606 | FILENOTFOUND、INVREQ 16/30 (READ UPDATE が無い)、NOTFND 13/80、LENGERR 12・14、IOERR |
| DELETE | 0608 | FILENOTFOUND、INVREQ 16/20・22 (KSDS 以外の総称)・25・26・31 (RIDFLD も READ UPDATE も無い)・42、NOTFND 13/80、IOERR |
| UNLOCK | 060A | FILENOTFOUND |
| STARTBR | 060C | FILENOTFOUND、INVREQ 16/20・25・26・33 (REQID が使用中)・42、NOTFND 13/80、IOERR |
| READNEXT | 060E | FILENOTFOUND、ENDFILE 20/90、INVREQ 16/25・26・34 (browse が無い)・37 (RRN と鍵を変えた)・42、LENGERR 22/11、IOERR |
| READPREV | 0610 | FILENOTFOUND、ENDFILE 20/90、NOTFND 13/80、INVREQ 16/24 (GENERIC の browse)・26・37・41 (browse が無い)、LENGERR 22/11、IOERR |
| ENDBR | 0612 | FILENOTFOUND、INVREQ 16/35 |
| RESETBR | 0614 | FILENOTFOUND、INVREQ 16/25・26・36 (browse が無い)・37・42、NOTFND 13/80、IOERR |

条件の既定の扱い (ABEND) は、ほかの命令と同じく `HANDLE CONDITION` が無ければ task を失敗させる。
`HANDLE CONDITION` に書ける名前へ FILENOTFOUND、NOTFND、DUPREC、INVREQ、IOERR、NOSPACE、LENGERR、ENDFILE を足した。

### 3.3 鍵と RIDFLD

- `KEYLENGTH` を書かなければ定義の鍵の長さ。GENERIC を書かない `KEYLENGTH` は定義の長さと同じでなければ INVREQ 26。
  GENERIC の `KEYLENGTH` は負なら 42、鍵の長さ以上なら 25
- `KEYLENGTH(0) GENERIC GTEQ` は先頭の record を指す。GTEQ の無い `KEYLENGTH(0)` の結果は文書に無いので失敗させる
- 総称か GTEQ の READ と、browse の読みは、見つけた record の完全な識別 (鍵か 4 byte の相対レコード番号) を RIDFLD へ返す
- RRN の RIDFLD は 4 byte。1 以上の番号を読み書きする。0 は GTEQ の位置づけにだけ使える

### 3.4 読んだ record の移し方

- `LENGTH` を書かなければ INTO の長さを最大の長さとする。COBOL の翻訳系が INTO の長さを補う形にあたる
- 可変長の record が長ければ切り詰めて LENGERR 11。`LENGTH` の域には record の本来の長さを置く
- 可変長の record が短ければ record の長さだけを移す。INTO の残りは変えない (文書は「予測できない」とする)
- 固定長の record を違う長さで読む形は LENGERR 13 だが、そのとき域へ何を移すかが書かれていないので失敗させる
- READ UPDATE で切り詰めた場合、record を持ち続けるかが書かれていないので失敗させる

### 3.5 更新のための排他

- READ UPDATE で得た record は、REWRITE / DELETE / UNLOCK、SYNCPOINT、task の終わりで返す
- 他の task が持つ record への READ UPDATE と DELETE は task の期限まで待ち、越えれば失敗させる。更新しない READ は待たない
- REWRITE は鍵を変えてはならない。変えたときの条件は書かれていないので失敗させる
- READ UPDATE を持ったまま同じ file へ RIDFLD つきの DELETE をする形は、結果が書かれていないので失敗させる

### 3.6 browse の位置

| 場面 | 振る舞い | 出典 |
| --- | --- | --- |
| STARTBR の既定 | KSDS / RRDS とも GTEQ | STARTBR の頁 |
| RIDFLD がすべて X'FF' | データセットの終わりへ位置づけ、READPREV が最後の record を返す | STARTBR の頁 |
| 向きを変える | 同じ record をもう一度返す | 「Browsing records」の頁 |
| READPREV が STARTBR / RESETBR の直後 | RIDFLD の record が無ければ NOTFND 80 | READPREV の頁 |
| RIDFLD を変えた | GTEQ の browse は変えた値以上の最初の record から、GENERIC の browse は総称の鍵で位置づけ直す | READNEXT の頁 |
| EQUAL の browse で RIDFLD を変えた | 文書に無いので失敗させる | — |
| KEYLENGTH を変えた (GENERIC) | その長さの総称の鍵で位置づけ直す。`KEYLENGTH(0)` は先頭へ | READNEXT の頁 |
| 終わりを越えた | ENDFILE 90 | READNEXT / READPREV の頁 |
| SYNCPOINT、task の終わり | browse は終わる | 「Browsing records」の頁 |
| RESETBR が NOTFND | 次の読みの位置は書かれていないので、次の READNEXT / READPREV を失敗させる | — |

総称の browse が総称の鍵に合わなくなったあとも読み続けるかは、文書に書かれていない。ここでは読み続ける
(位置づけだけが総称であり、終わりは ENDFILE が決める)。

### 3.7 断るもの

`SET` (CICS が持つ域への pointer)、`SYSID`、`RBA` / `XRBA`、`TOKEN`、`NOSUSPEND`、`CONSISTENT` / `REPEATABLE` (RLS)、
`DEBKEY` / `DEBREC` (BDAM)、`MASSINSERT`、browse の `UPDATE`、ESDS。いずれも表す file や記憶域の設計を持たない。

## 4. 一時記憶のキュー (TS)

`CicsTemporaryStoragePort`。既定の `inMemory()` は 1 つの JVM の中で task どうしが分け合う。

| 項目 | 決めごと | 出典 |
| --- | --- | --- |
| 定義 | 要らない。`WRITEQ TS` がキューを作る | WRITEQ TS の頁 |
| 名前 | 16 byte (QNAME) に空白を詰めて持つ。`QUEUE` の 8 byte の名前は、空白を足した `QNAME` と同じキューとする | 頁は名前の長さだけを書く。同じキューかは確かめていない |
| item | 番号は 1 から。1 つのキューに 32767 まで、長さは 1〜32763 | WRITEQ TS の頁 |
| `ITEM` (WRITEQ) | `REWRITE` があれば書き換える item の番号 (入力)、無ければ書いた item の番号 (出力) | WRITEQ TS の頁 |
| `NEXT` | 「直前に読まれた record の次」。読まれた位置はキューに 1 つで、task をまたぐ。`ITEM` で読んだ item も直前に読まれた record に数える | READQ TS の頁の文面をそのまま読んだ |
| `MAIN` / `AUXILIARY` | 受けるが違いは無い | — |
| 回復 | 持たない | — |

| 命令 | EIBFN | 返す条件 (RESP2 はすべて 0。頁が値を示さない) |
| --- | --- | --- |
| WRITEQ TS | 0A02 | LENGERR 22 (長さが 0・負・32763 超)、ITEMERR 26 (item の数の上限、REWRITE の番号が範囲外)、QIDERR 44 (REWRITE でキューが無い)、INVREQ 16 (名前がすべて binary zero) |
| READQ TS | 0A04 | QIDERR 44、ITEMERR 26 (番号が範囲外、NEXT が終わりを越えた)、LENGERR 22 (切り詰めた)、INVREQ 16 |
| DELETEQ TS | 0A06 | QIDERR 44、INVREQ 16 |

断るもの: `SYSID` (遠隔・共有のキュー)、`NOSUSPEND`、`SET`、`WRITEQ TS` の `NUMITEMS`、`ITEM` も `NEXT` も無い `READQ TS`
(既定を頁が示さない)、`TS` を省いた形。X'FA'〜X'FF'、`**`、`$$`、`DF` で始まる名前は CICS が使うと頁にあるが、
条件を示さないので失敗させる。EIBRSRCE は置かない。

## 5. 一時データのキュー (TD)

`CicsTransientDataPort`。region の構成で定義した区画内 (intrapartition) のキューだけを持つ。既定の `none()` はキューを持たない。

| 項目 | 決めごと |
| --- | --- |
| 定義 | `CicsTransientDataQueueDefinition` (1〜4 文字の名前、record の最大の長さ)。定義の無い名前は QIDERR |
| 読み | 先に書いた record から取り出し、読んだ record は消える。切り詰めても record は消え、LENGTH の域には本来の長さを置く |
| `DELETEQ TD` | キューの record をすべて消す。定義は残る |
| 回復、ATI | 持たない。trigger level による task の開始は無い |

| 命令 | EIBFN | 返す条件 (RESP2 はすべて 0) |
| --- | --- | --- |
| WRITEQ TD | 0802 | QIDERR 44、LENGERR 22 (長さが 0 か定義の最大を越える) |
| READQ TD | 0804 | QIDERR 44、QZERO 23 (空)、LENGERR 22 (切り詰めた) |
| DELETEQ TD | 0806 | QIDERR 44 |

断るもの: 区画外 (extrapartition) のキュー、`SYSID`、`NOSUSPEND` (QBUSY)、`SET`。DISABLED はキューの状態を持たないので返さない。

## 6. 間隔制御の START / RETRIEVE / CANCEL

`CicsStartPort`。region の構成の既定は `none()` で、START と CANCEL は失敗する。task を起こす先 (coordinator) を
region の構成は知らないからである。`inMemory(clock, defined, launcher)` が 1 つの JVM の中で満了を待ち、
`launching(coordinator)` が coordinator で task を起こす。Spring Boot では `CicsTaskAutoConfiguration` がこの組み合わせを
bean にし、利用者が `CicsEnvironment.withStarts` で region の構成へ入れる。

| 項目 | 決めごと | 出典 |
| --- | --- | --- |
| INTERVAL / AFTER | 今からの間隔。INTERVAL を書かなければ INTERVAL(0) で直ちに | START の頁、「Expiration times」 |
| TIME / AT | task の地方時 (`hostZone`) の今日のその時刻。hh が 23 を越えれば翌日以降。地方時が無ければ失敗させる | 「Expiration times」 |
| 過ぎた時刻 | 6 時間前までなら直ちに。それより前は翌日のその時刻とする | 前半は「Expiration times」。後半は頁が明示せず、時刻として読んだ推定 |
| 値の範囲 | 時 0〜99、分・秒 0〜59。AFTER / AT で単位を 1 つだけ書けば MINUTES 5999、SECONDS 359999 まで。越えれば INVREQ 4 / 5 / 6 | START の頁 |
| REQID | 書かなければ `JV` と 36 進 6 桁の名前を作り、EIBREQID に置く。形は実機と合わせていない (利用者は名前として持つだけ) | START の頁 (EIBREQID に置くこと) |
| 起こす task | 端末と COMMAREA を持たない。START を出した task と同じ owner と user ID で、別の thread から coordinator で起こす。起こした task の失敗は記録するだけ | START の頁 (USERID を書かなければ出した task の user ID) |
| RETRIEVE | START で起きた task だけ。1 度読めば次は ENDDATA。START が書かなかった option (FROM の無い START への INTO を含む) は ENVDEFERR で、読んだことにしない。長いデータは切り詰めて LENGERR | RETRIEVE の頁 |
| CANCEL | 未満了の START を REQID で取り消す。無ければ NOTFND | CANCEL の頁 |
| 回復 | 未満了の START は JVM が止まれば消える | — |

| 命令 | EIBFN | 返す条件 |
| --- | --- | --- |
| START | 1008 | INVREQ 16/4・5・6、LENGERR 22 (LENGTH が 0 以下)、TRANSIDERR 28、IOERR 17 (FROM を持つ START の REQID が未満了の START と重なる) |
| RETRIEVE | 100A | ENDDATA 29、ENVDEFERR 56、LENGERR 22 |
| CANCEL | 100C | NOTFND 13 |

RESP2 は、INVREQ の 4 / 5 / 6 のほかは頁が示さないので 0 とする。

断るもの: START の `TERMID` (端末へ出す task)、`USERID`、`SYSID`、`PROTECT` (同期点まで遅らせる)、`NOCHECK`、`CHANNEL`、
`ATTACH`、RETRIEVE の `SET` と `WAIT`、`REQID` の無い CANCEL (POST の取消し)、CANCEL の `TRANSID` / `SYSID`。
FROM の無い START の REQID が重なる形と、START で起きていない task の RETRIEVE は、条件が書かれていないので失敗させる。

## 7. 非同期 API (RUN TRANSID / FETCH / FREE CHILD)

`CicsAsyncPort`。既定の `none()` では命令は失敗する。`inMemory(registry, launcher)` は 1 つの JVM の中で子の task を
別の thread で動かし、`launching(coordinator)` が coordinator で子を起こす。Spring Boot では `CicsTaskAutoConfiguration` が
bean にし、利用者が `CicsEnvironment.withAsync` で region の構成へ入れる。Bank-of-Z の CRECUST が使う。

| 項目 | 決めごと | 出典 |
| --- | --- | --- |
| RUN の channel | RUN を出した時点の container の写しを子へ渡す。子は RUN の CHANNEL の名前で現在の channel を開く | RUN TRANSID の頁 |
| 子の token | 16 byte。中身は区別できればよく、形は実機と合わせていない | RUN TRANSID の頁 (長さ) |
| 子の task | 端末と COMMAREA を持たず、親と同じ owner と user ID で、別の UOW として動く | — |
| reply channel | 子が終えたときの現在の channel。FETCH で `JVREPLY` と 9 桁の名前を付けて親の channel として置き、CHANNEL の域へ返す。RUN に CHANNEL が無ければ空白 | FETCH の頁 (16 文字の名前、channel を持たなければ空白)。名前の形は推定 |
| COMPSTATUS | NORMAL 1016、ABEND 900 (ABCODE に code)。ABEND のときの CHANNEL は空白とした。SECERROR 1214 は認可を持たないので起きない | CVDA の表、FETCH の頁 |
| 子の ABEND 以外の失敗 | 完了の状態が文書に無いので、その子を FETCH した親を失敗させる | — |
| FETCH の待ち | NOSUSPEND なら待たない。TIMEOUT (ミリ秒、0〜40800000) か task の期限まで待つ。両方を書く形は断る | FETCH の頁 |
| FREE CHILD | token を無効にする。親の task の終わりには全部の子の token を返す | FREE CHILD の頁 |

| 命令 | EIBFN | 返す条件 |
| --- | --- | --- |
| RUN TRANSID | 343E | TRANSIDERR 28/1、DISABLED 84/50 |
| FETCH ANY | 3444 | NOTFINISHED 113/52 (NOSUSPEND)・53 (TIMEOUT)、NOTFND 13/1 (取り出していない子が無い)、INVREQ 16/52 (子が無い)・241 (TIMEOUT の値) |
| FETCH CHILD | 3442 | NOTFINISHED 113/52・53、INVREQ 16/50 (token が正しくない、FREE 済み)・51 (取り出し済み)・241 |
| FREE CHILD | 3446 | INVREQ 16/50 |

断るもの: RUN の `USERID`、`NOSUSPEND` と `TIMEOUT` の併記、RUN の CHANNEL に無い channel の名前 (条件が無い)。
