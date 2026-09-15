# 設計 85: 断っていた CICS / Db2 の形の暫定仮仕様

| 項目 | 内容 |
| --- | --- |
| 状態 | 仕様を定義 (2026-09-15)。§3 (P-146)、§4・§5 (P-147、SET を除く)、§7 (P-148)、§8 と TS / TD の SYSID・NOSUSPEND (P-149) を実装。POINTER / ADDRESS OF と SET (§6、§5.5) が残る |
| 対応要件 | 設計 79、設計 82、設計 83、設計 84 |
| 検証レベル | V0〜V1。実機の CICS と突き合わせていない |
| 暫定判断 | P-146〜P-150 (§5 は P-147) |

## 1. 位置づけ

この処理系は「近い値を黙って返すくらいなら断る」を方針としてきた。利用者は、次の形について**暫定の仕様を定義し、
断らずに変換する**ことを求めた。

- START の CHANNEL / ATTACH、POST の CANCEL、SYSID (遠隔)、NOSUSPEND 全般
- TD の区画外キューと、回復可能なキュー
- ESDS、RLS、BDAM、MASSINSERT など file control の残り
- Bank-of-Z の BNK1TFN / BNK1CCS (COMMAREA より長い LENGTH) と XFRFUN (SQLDA)

ここでの仕様は**実機の振る舞いを確かめたものではない**。公開文書に値があるものはそれに従い、無いものはこの文書で
決めて暫定判断に残す。「黙って」ではなく「書いて」近い値を返す、という扱いである。

## 2. 利用者が決めたこと (2026-09-15)

| 問い | 決定 |
| --- | --- |
| file control などの SET | COBOL の POINTER と `SET ADDRESS OF` / `ADDRESS OF` を入れてから、SET を入れる |
| SYSID | region の構成に「この region として扱う SYSID」の一覧を置き、そこにある名前は自 region で処理する。無い名前は SYSIDERR (RESP 53) |
| 回復可能な TD | task の STRICT の UOW に入り、同期点の commit で確定、ROLLBACK と ABEND で取り消す。ATI は commit のあと |
| BDAM | 相対レコード編成に写す。1 block = 1 record |

## 3. COMMAREA より長い LENGTH と SQLDA (P-146、実装済み)

- `COMMAREA(項目) LENGTH(n)` で n が項目より長ければ、項目の番地から n byte を渡す。記憶域の続きがあればその上の view、
  端を越える分は binary zero
- `INCLUDE SQLDA` は公開文書の欄の説明から作った形 (SQLVAR 750 個固定、番地は 4 byte の POINTER) を置く

## 4. SYSID と NOSUSPEND の共通の規則 (P-150)

### 4.1 SYSID

| 項目 | 規則 | 出典 |
| --- | --- | --- |
| 構成 | `CicsEnvironment` に「この region として扱う SYSID」(1〜4 文字) の一覧を持つ。既定は空 | — |
| 一覧にある名前 | SYSID を書かなかったのと同じに自 region で処理する | — |
| 一覧に無い名前 | SYSIDERR (RESP 53)。RESP2 は file control は 130、START / CANCEL / TS / TD は 0 | READ / WRITE の頁 (130)、START / CANCEL / WRITEQ TD の頁 (RESP2 を示さない) |
| file control の LENGTH / KEYLENGTH | 遠隔では必須と文書は書くが、自 region で処理するので求めない | READ の頁 |

### 4.2 NOSUSPEND

| 命令 | 規則 | 出典 |
| --- | --- | --- |
| READ UPDATE、DELETE、READNEXT / READPREV UPDATE、CONSISTENT / REPEATABLE の READ | 他の task が更新のために持つ record なら待たずに RECORDBUSY (RESP 101 / RESP2 107)。文書は RLS の file に限るが、どの file でも受ける (INVREQ 55 にしない) | READ の頁 |
| WRITE | 待つ場面が無いので何も変えない | WRITE の頁 |
| ENQ | これまでどおり ENQBUSY | 設計 79 |
| WRITEQ TS / WRITEQ TD / READQ TD | 置き場が満ちるのを待つ場面が無いので何も変えない | — |
| FETCH | これまでどおり NOTFINISHED | 設計 82 §7 |

## 5. file control の残り (P-147)

### 5.1 RBA と XRBA、ESDS

| 項目 | 規則 | 出典 |
| --- | --- | --- |
| ESDS の定義 | `CicsFileDefinition.Organization.ESDS`。record はバッチの順編成のデータセットに到着順に置く | — |
| RBA の数え方 | record の RBA は、並びの中でその record より前にある record の長さの和とする。CI / CA の制御情報は数えない (実機の値とは違う) | 推定 |
| RBA / XRBA の形 | RBA は 4 byte の 2 進 (fullword)、XRBA は 8 byte の 2 進 | READ の頁 |
| WRITE (ESDS) | 常に終わりに足し、RIDFLD に RBA / XRBA を返す | WRITE の頁 |
| READ (RBA) | 一致する RBA の record。無ければ NOTFND (80) | READ の頁 |
| REWRITE (ESDS) | 長さを変えれば ILLOGIC (RESP2 110)。後ろの RBA が変わるのを避ける。文書に条件が無いので、他の区分に入らない VSAM の誤りとした | 推定 |
| DELETE (ESDS) | INVREQ (RESP2 21)。ESDS の record は消せない | DELETE の頁 |
| browse (RBA) | STARTBR / RESETBR は RBA 以上の最初の record に位置づけ、READNEXT / READPREV は RIDFLD に RBA を返す | 推定 |
| KSDS / RRDS の RBA | 同じ数え方を鍵の順 / 相対レコード番号の順で行う。record を足すと後ろの RBA は変わる | 推定 |

### 5.2 TOKEN と browse の UPDATE

| 項目 | 規則 | 出典 |
| --- | --- | --- |
| READ ... TOKEN | UPDATE を含む。fullword の token を返す。TOKEN を使う task は複数の record を同時に持てる | READ の頁 |
| REWRITE / DELETE / UNLOCK ... TOKEN | token の record を対象にする。持っていない token は INVREQ (RESP2 47) | REWRITE / DELETE / UNLOCK の頁 |
| READNEXT / READPREV ... UPDATE TOKEN | 読んだ record を更新のために持ち、token を返す。UPDATE と TOKEN は一緒に書かせる | 推定 |
| DELETE ... RIDFLD TOKEN | 翻訳で断る (TOKEN は READ UPDATE の record を消す形) | 推定 |

### 5.3 RLS の読み取りの完全性、MASSINSERT

| 項目 | 規則 | 出典 |
| --- | --- | --- |
| UNCOMMITTED | これまでどおり何も変えない | READ の頁 |
| CONSISTENT | 他の task が更新のために持つ record なら放すまで待つ (NOSUSPEND なら RECORDBUSY)。持たずに読む | READ の頁 |
| REPEATABLE | CONSISTENT と同じ。UOW の終わりまで共有 lock を持つ振る舞いは持たない | 推定 (実機との差) |
| RLS の file | 定義の区別を持たず、どの file でも CONSISTENT / REPEATABLE / NOSUSPEND を受ける | — |
| MASSINSERT | 普通の WRITE と同じに直ちに書く。鍵の昇順は確かめない。UNLOCK で終える形は受け、何も変えない | WRITE の頁 (順と UNLOCK)、確かめは推定 |

### 5.4 BDAM (DEBKEY / DEBREC)

| 項目 | 規則 |
| --- | --- |
| 定義 | `Organization.BDAM`。相対レコード編成のデータセットに置く |
| RIDFLD | 先頭 4 byte を 0 起点の相対 block 番号 (2 進) とし、相対レコード番号 = block 番号 + 1 に写す |
| 1 block | 1 record |
| DEBREC | RIDFLD の 5〜8 byte 目を block の中の相対 record 番号 (0 起点) とする。0 だけが record を持ち、ほかは NOTFND |
| DEBKEY | RIDFLD の 5 byte 目からを鍵とし、block の record の鍵 (定義の位置と長さ) と一致すれば読む。違えば NOTFND |
| MASSINSERT | BDAM では INVREQ (RESP2 38) (WRITE の頁) |
| DELETE | INVREQ (RESP2 27) (DELETE の頁) |
| REWRITE | 可変長の record の長さを変えれば INVREQ (RESP2 46) (REWRITE の頁) |

### 5.5 SET (P-148)

`SET(pointer)` は region が record の置き場を用意し、その番地を POINTER に置く。置き場は同じ file の次の READ、
REWRITE / DELETE / UNLOCK、SYNCPOINT まで有効 (READ の頁)。§6 の `ADDRESS OF` を入れてから実装する。
TS / TD / RETRIEVE の SET も同じ置き場の規則にする。

## 6. POINTER と ADDRESS OF (P-148)

| 項目 | 規則 |
| --- | --- |
| POINTER の値 | 実行時の記憶域 (Storage) と位置への参照を持つ 4 byte の番地。値そのものは session の中の番号で、ホストの番地ではない |
| `SET ADDRESS OF 連絡節の項目 TO pointer` | 項目 (01 / 77) を pointer の記憶域の位置へ結ぶ |
| `SET pointer TO ADDRESS OF 項目` | 項目の記憶域と位置を pointer に置く |
| `ADDRESS OF` の比較と NULL | 同じ記憶域と位置なら等しい。NULL は番号 0 |
| 結んでいない連絡節の項目 | これまでどおり参照で失敗させる (設計の S0C4 相当) |

## 7. TD の区画外キューと回復可能なキュー (P-149)

### 7.1 区画外 (extrapartition)

| 項目 | 規則 | 出典 |
| --- | --- | --- |
| 定義 | `CicsTransientDataQueueDefinition` に EXTRA の種類、データセット、INPUT / OUTPUT、固定長 / 可変長、record の長さ | TDQUEUE の定義 |
| 置き場 | バッチの順編成のデータセット (ジョブがそのまま読み書きできる) | — |
| WRITEQ (OUTPUT) | 終わりに足す。固定長で長さが違えば LENGERR | WRITEQ TD の頁 |
| WRITEQ (INPUT のキュー) | INVREQ | WRITEQ TD の頁 |
| READQ (INPUT) | 先頭から順に読み、終わりで QZERO。読んだ位置は region の中で 1 つ | READQ TD の頁 (推定を含む) |
| READQ (OUTPUT のキュー) | INVREQ | READQ TD の頁 |
| 開けないデータセット | NOTOPEN (19) | WRITEQ TD / READQ TD の頁 (区画外のキューだけの条件) |
| DELETEQ | INVREQ | 推定 |
| 回復、ATI | 持たない | TDQUEUE の定義 |
| 構成 | 区画内の port に `withExtrapartition` で足す。区画外の名前が区画内の同じ名前より先に引かれる | — |

### 7.2 回復可能な区画内キュー (RECOVSTATUS=LOGICAL)

- JDBC のキュー (`JdbcCicsTransientData`) は、回復可能なキューの WRITEQ / READQ / DELETEQ を呼び手の transaction
  (STRICT の task の UOW) に入れる。同期点の commit で確定し、ROLLBACK と ABEND で取り消す
- 1 つの JVM の中のキューは、task の中に変更を貯め、同期点の commit で反映し、ROLLBACK と ABEND で捨てる
- ATI は、commit のあとに trigger level を見る (TDQUEUE の定義: 論理回復のキューは commit まで attach しない)
- task の UOW の外 (STRICT の境界を持たない task) では、回復不能と同じに直ちに確定する (実機との差)
- STRICT の境界は task の services に `CicsTaskConnection` を置く。SPRING_MANAGED は Spring の transaction に束ねられた
  connection、DB2_DRIVER_MANAGED_HOLD は native lease の connection を渡す
- JDBC のキューの行の lock は task の commit まで残る。実機の QBUSY (25) は返さず、他の task は待つ (実機との差)
- 1 つの JVM の中のキューの task の暗黙の同期点は、coordinator が task を commit したあと (PROTECT の START と同じ所) で確定する。
  正常に返らなかった task は、資源を返すときに取り消す

## 8. START の CHANNEL / ATTACH、POST の CANCEL (P-150)

| 項目 | 規則 | 出典 |
| --- | --- | --- |
| START CHANNEL | 出した時点の channel の container の写しを START に持たせ、起こした task の現在の channel にする。INTERVAL / TIME / AFTER / AT と REQID は書けない (翻訳で断る。文書の形)。FROM とは併記できない | START CHANNEL の頁 |
| START CHANNEL の RETRIEVE | ENVDEFERR (FROM の無い START と同じ) | 推定 |
| START ATTACH | 自 region で直ちに、端末の無い task を起こす。EIBREQID は null、CANCEL できない。FROM のデータは写しで渡す (文書は番地で渡す) | START ATTACH の頁 |
| START ATTACH の条件 | LENGERR (LENGTH が 0 以下)、TRANSIDERR (28 / 11)、NOTAUTH (70 / 7) | START ATTACH の頁 |
| CANCEL (REQID 無し) | task 自身の POST を取り消す。POST をまだ持たないので NOTFND (13) | CANCEL の頁 |
| CANCEL TRANSID / SYSID | TRANSID は受けて何も変えない (自 region で処理する)。SYSID は §4.1 | CANCEL の頁 |
| START NOCHECK | 自 region では条件を返す (文書どおり)。EIBREQID は null | START の頁 |

## 9. 実装の順番

1. COMMAREA より長い LENGTH、SQLDA (実装済み)
2. SYSID と NOSUSPEND の共通の規則、file control の残り (SET を除く)
3. TD の区画外キューと回復可能なキュー
4. START の CHANNEL / ATTACH / NOCHECK、CANCEL の REQID 無し / TRANSID / SYSID、TS / TD の SYSID と NOSUSPEND
5. POINTER と ADDRESS OF
6. file control / TS / TD / RETRIEVE の SET
