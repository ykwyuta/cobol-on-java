# 設計 83: 端末へ出す START (TERMID) と一時データの trigger level による ATI

| 項目 | 内容 |
| --- | --- |
| 状態 | 設計を決めた (2026-09-15)。§10 の増分 1 (端末の表と lease、会話の参照の移動) と増分 2 (START と TD の表、dispatcher)、増分 3 (START TERMID)、増分 4 (TD の ATI の FILE)、増分 5 (ATI の TERMINAL と固定の端末名) を実装 |
| 対応要件 | 設計 77 §4、設計 81 §5、設計 82 §5・§6 |
| 検証レベル | V0。実機の CICS と突き合わせていない |
| 暫定判断 | P-144 |

## 1. 位置づけと測定

設計 82 は START の `TERMID` と、一時データ (TD) の trigger level による自動の task の開始 (ATI) を断っている。
どちらも「端末が空いたら task を起こし、その画面を端末へ出す」ことを要り、ブラウザの入口 (設計 81 §5) と
複数の JVM の置き場 (P-143) の上に設計する必要があるためである。

測定: Bank-of-Z の CICS の COBOL 32 本は `START`、`RETRIEVE`、`WRITEQ TD` / `READQ TD` のどれも使わない。
構成の資料にある trigger 付きの TD の定義は `TRAA` (`ATIFACILITY(FILE)`、`TRIGGERLEVEL(1)`) の 1 つだけで、
これは sockets の製品のキューであり業務の資産ではない。**測定では順番を決められない**ので、CICS TS の公開文書を基準にする。
実機の CICS のソースは参照しない。

## 2. 公開文書から決まること

| 項目 | 文書の記述 | 出典 |
| --- | --- | --- |
| TERMID | 1〜4 文字。起こす task の principal facility (端末) | START の頁 |
| TERMIDERR | RESP 11。START の時点で端末が定義されていない。RESP2 は示されない | START の頁 |
| 満了時に端末が無い | START は捨てられる。START のあと端末が消えれば (log off 等)、START は端末の定義と一緒に捨てられる | START の頁 |
| 満了した START と端末 | task は端末が空くのを待って作られることがある | START の頁 |
| RETRIEVE | 満了した START のデータを満了の順にすべて読む。task を起こした START のデータから始める。尽きれば ENDDATA 29 | RETRIEVE の頁 |
| RETRIEVE WAIT | 満了したデータを読み尽くしていれば、次のデータが満了するまで待つ | RETRIEVE の頁 |
| trigger level | 区画内のキューの record の数が trigger level に達すると、定義の TRANSID の task を起こす。0 は ATI をしない。既定 1、上限 32767 | TDQUEUE の定義、ATI の頁 |
| 端末を要る ATI | 直ちに、または端末に関わる task が無くなったときに起こす | ATI の頁 |
| 逐次 | trigger の task はキューに対して逐次にしか動かない。1 つを attach したら、それが終わるまで次を attach しない | ATI の頁 |
| 再び arm | 回復不能のキューは QZERO まで読んだとき。論理回復のキューは QZERO まで読んだ task が終わったとき | ATI の頁 |
| 空にしなかった | 端末へ送るキューなら trigger は戻らず、同じ task をまた起こす。file のキューなら task の終わりで trigger が戻る | ATI の頁 |
| ABEND | 空にする前に trigger の task が abend すれば、QZERO で戻るまで次の task は予定しない | ATI の頁 |
| ATIFACILITY | `FILE` は端末と結び付かない。`TERMINAL` は端末が空くまで task を起こさない。`SYSTEM` は DTP の session | TDQUEUE の定義 |
| FACILITYID | TERMINAL / SYSTEM の名前。書かなければキューの名前。FILE では空 | TDQUEUE の定義 |
| USERID | trigger の task の user ID。`ATIFACILITY(FILE)` のときだけ | TDQUEUE の定義 |

## 3. 利用者が決めたこと (2026-09-15)

| 問い | 決定 | 実機との差 |
| --- | --- | --- |
| 端末へ出す task の画面をブラウザへどう届けるか | server で task を動かして端末の現在の画面にし、SSE で押し出す。SSE が無ければ次の要求で先に見せる (§7) | 押し出しの遅れ (§8.3) |
| 満了時に端末が疑似会話の途中 (RETURN TRANSID 待ち) なら | 会話が終わるまで待つ | 文書は「端末に task が無いとき」とだけ書く。実機は会話の途中でも起こすかもしれない。確かめるまで P-144 |
| 別の利用者の端末へ出すか | 同じ owner の端末だけ。ほかは TERMIDERR | CICS はどの端末にも出せる (security を除く)。ブラウザ端末は利用者ごとなので、情報漏えいを防ぐ側に倒した |
| 置き場の範囲 | 最初から JDBC で複数の JVM (§8) | — |

## 4. 端末

### 4.1 端末の表

いまの端末名は JVM ごとの連番 (`W` と 36 進 3 桁、設計 81 §5) で、複数の JVM では重なる。端末が「定義されているか」
「空いているか」「疑似会話の途中か」を JVM の外から見る必要があるので、端末を表に置く。

| 列 | 意味 |
| --- | --- |
| `TERMINAL_ID` | 4 文字。主キー。登録の INSERT の一意性で複数 JVM の割り当てを決める |
| `OWNER_NAME` | 端末を持つ利用者 (principal)。START TERMID の owner の照合に使う |
| `EXPIRES_AT` | HTTP session の失効に合わせて延ばす。過ぎた端末は無いものとし、purge で消す |
| `LEASE_TOKEN` / `LEASED_UNTIL` | 端末で task が動いている印。これがある間、端末は空いていない |
| `CONVERSATION_ID` / `CONVERSATION_VERSION` | 端末の疑似会話。ある間、端末へ出す task を起こさない (§3) |
| `SCREEN_VERSION` / `SCREEN` | 端末の現在の画面と版。ブラウザが持つ版と比べる (§7) |

- 実装 (増分 1): `CicsTerminalRegistryPort` (既定は 1 つの JVM の中) と `JdbcTerminalRegistry` (STRICT の構成)。表は
  `JdbcConversationStore.SCHEMA` の DDL に足した。`SCREEN_VERSION` / `SCREEN` の列は画面の byte 列の形を決める §7 の増分で足す。
  端末の lease の長さは会話の lease (`CicsTaskPolicy.leaseDuration`) と同じにした
- 実装 (増分 5): 固定の端末名は `CicsBrowserTerminalNames` の bean (principal 名 → 端末名) で与え、
  `CicsTerminalRegistryPort.registerNamed` で登録する。同じ利用者の別の HTTP session は同じ端末を分け合い、別の利用者が
  使っている間は 409 で task を動かさない。どれかの session が破棄されると端末と会話を捨てるので、同じ利用者の残りの
  session は次の要求で端末を登録し直し、会話は失う
- 端末の名前の割り当て: 既定は `W` と 36 進 3 桁を乱数で選んで INSERT し、重なれば選び直す。同時に持てる端末は
  46656 までである。足りなければ接頭の文字を構成で増やす。利用者ごとに固定の端末名を構成で与えることもでき、
  `ATIFACILITY(TERMINAL)` の `FACILITYID` のように名前を決め打ちする資産はこれを使う
- HTTP session は端末名だけを持つ。会話の参照 (いまは HTTP session に置く `BrowserConversation`) は端末の表へ移し、
  複数の JVM のどこからでも「会話の途中か」が分かるようにする
- 端末が消えるのは、session の破棄 (`CicsBrowserSessionListener`)、期限切れの purge。消した端末へ向けた未満了の
  START も一緒に消す (§2 「端末の定義と一緒に捨てられる」)

### 4.2 端末の lease

task を動かす前に、端末の行を CAS で lease する (別の transaction で直ちに確定。会話の claim と同じ形)。
ブラウザの要求で動く task も、端末へ出す START / ATI の task も同じ lease を取るので、1 つの端末で task は同時に 1 つになる。
lease が取れなければ、ブラウザの要求は 409 (端末が使用中)、端末へ出す task は後で試し直す (§8.2)。

JSON API の入口は端末名を持たない (設計 77 §4.4)。端末へ出す task は JSON API の要求には届かない。

## 5. START TERMID

| 段 | 扱い |
| --- | --- |
| 命令 | `TERMID` の端末が表に無いか期限切れなら TERMIDERR 11 (RESP2 0)。端末の owner が START を出した task の owner と違っても TERMIDERR 11 とする (§3。名前だけで他人の端末の有無を見せない) |
| 登録 | `COBOL_START` の行 (§8.1) に `TERMINAL_ID` を付けて置く。`PROTECT` は task の UOW の中で INSERT し (STRICT の同じ UOW)、ほかは別の transaction で直ちに確定する |
| 満了 | 端末が表に無ければ START を捨てる。端末が lease 中か会話の途中なら待つ。空いていれば端末を lease して task を起こす |
| 起こす task | 端末を principal facility とし (EIBTRMID)、owner と user ID は端末の owner。同じ TRANSID と端末の満了した START はまとめて 1 つの task にし、RETRIEVE が満了の順に全部読む |
| task の画面 | task の SEND が端末の現在の画面になる。RETURN TRANSID なら端末は疑似会話に入る (以後の要求はブラウザから) |
| 端末の無い START | 設計 82 §6 のまま。1 つの START が 1 つの task になり、RETRIEVE はそのデータだけを読む |
| CANCEL | 未満了の行を REQID で消す。端末を待っている満了済みの START は消せない (推定。§9) |

実装 (増分 3):

- 翻訳は `TERMID` を定数か 4 byte の英数字項目で受ける。`TERMIDERR` (11) は `DFHRESP` と `HANDLE CONDITION` で使え、
  EIBRCODE は間隔制御の群 (X'10') の binary zero とした
- TERMIDERR を返すのは START の port である。端末の登録を持つ `JdbcCicsStarts` が命令の時点で端末と owner を確かめる。
  1 つの JVM の中の `CicsStartPort.inMemory` は端末を知らないので、TERMID の START を推測で通さず失敗させる
- 端末へ出す task は `CicsStartPort.conversing` が coordinator で起こす。端末を principal facility (EIBTRMID) にし、
  RETURN IMMEDIATE は端末の入力なしで 8 回まで続け、最後の task の次の疑似会話を端末に置く
- 同じ端末と TRANSID の START は、dispatcher が端末を lease した時点で満了していたものをまとめる。task の間に満了した
  START は次の task になる (§9 の推定)
- 満了時に端末の owner が START の owner と違えば (端末の名前が振り直された)、その START を捨てる
- task の画面はまだ端末に置かない (§7 の増分で置く)。それまで、端末へ出す task の画面はブラウザに届かない
- `PROTECT` の START を task の UOW の中で INSERT する形は入れていない。STRICT の境界が START の置き場を知る必要があり、
  別の増分に残す。PROTECT の START は同期点の commit のあとに登録する

`RETRIEVE WAIT` は端末へ出す task で意味を持つ (同じ端末と TRANSID の次の START を待つ)。待ちの上限は task の期限とし、
この設計の最後の増分で入れる (§10)。

## 6. TD の ATI

### 6.1 キューの定義

`CicsTransientDataQueueDefinition` に次を足す。

| 属性 | 扱い |
| --- | --- |
| `triggerLevel` | 0〜32767。0 は ATI をしない |
| `transaction` | 起こす TRANSID。`triggerLevel` が 1 以上なら必須 |
| `facility` | `FILE` か `TERMINAL`。`SYSTEM` (DTP) は断る |
| `facilityId` | `TERMINAL` の端末名。書かなければキューの名前。固定の端末名 (§4.1) を指す |
| `userId` | `FILE` の task の user ID。書かなければ region の構成の既定の user ID。どちらも無ければ構成の時点で断る (推測で空の user ID にしない) |

回復可能なキュー (`RECOVSTATUS`) は設計 82 §5 のまま持たない。TD は回復不能なので、WRITEQ / READQ TD は task の UOW に入れず、
別の transaction で直ちに確定する。

### 6.2 trigger の状態

キューごとに状態を 1 行に持ち、状態の変わり目はすべて CAS の UPDATE で行う。

| 状態 | 入る時 | 出る時 |
| --- | --- | --- |
| `ARMED` | 初め、QZERO まで読んだ、FILE の task が正常に終わった | WRITEQ のあと record の数が trigger level 以上 → `PENDING` |
| `PENDING` | 上のとおり。TERMINAL の task が空にせず正常に終わった | dispatcher が task を attach → `ATTACHED`。TERMINAL は端末が空くまで `PENDING` のまま |
| `ATTACHED` | task を attach した | QZERO まで読んだ → `ARMED` (task は続く。そのあとの WRITEQ は task の終わりまで次を attach しない)。正常に終わった → FILE は `ARMED`、TERMINAL で空でなければ `PENDING`。ABEND → `BLOCKED` |
| `BLOCKED` | 空にする前の ABEND | 誰かが QZERO まで読んだ → `ARMED` |

- 「達する」は WRITEQ のあとの数が trigger level **以上**と読む。等しいときだけにすると、task が空にしなかったキューは
  二度と arm されない。文書は比べ方を書かないので推定である (P-144)
- `ATTACHED` の間に QZERO まで読んで `ARMED` になったあとの WRITEQ は、文書の「逐次にしか動かない」に従い、
  task が終わるまで新しい task を attach しない。終わった時点で数が trigger level 以上なら `PENDING` にする
- FILE の task の owner は region の構成の owner 名 (例 `cics-region`)、TERMINAL の task の owner は端末の owner

実装 (増分 4): `CicsTransientDataQueueDefinition` に trigger level、TRANSID、ATIFACILITY、FACILITYID、USERID を足し、
`JdbcCicsTransientData` が trigger の状態を持つ。

- 「QZERO まで読んだ」は READQ TD が QZERO を返したときと読んだ。最後の record を読んだだけでは戻さない (推定)
- `ATTACHED` の間の QZERO で `ARMED` に戻っても、task の token がある間は次の task を起こさない (逐次)。token は
  `ATTACHED` / `ARMED` / `PENDING` とは別の列に持ち、task の終わりか期限で外す
- task の終わりの状態の変え方は token が合うときだけにした。期限で片付けたあとに古い task が終わっても状態を変えない
- FILE の task の正常な終わりで trigger を戻したとき、数が trigger level 以上なら直ちに `PENDING` にする (推定)
- trigger の task の例外はすべて ABEND と同じに扱う (`BLOCKED`)
- 1 つの JVM の中の `inMemory` のキューと、launcher を渡さない `JdbcCicsTransientData` は、trigger level を書いた定義を断る。
  USERID も region の既定の user ID も無い trigger の定義は、推測で空の user ID の task を起こさず構成の時点で断る
- 実装 (増分 5): `ATIFACILITY(TERMINAL)` は、FACILITYID (無ければキューの名前) の端末が登録されていて、task が動いておらず、
  疑似会話の途中でもないときに、端末を lease して端末の owner で起こす。user ID は端末の owner から作る
  (`CicsTerminalTasks.userIdOf`)。端末が登録されていなければ `PENDING` のまま待ち、捨てない (文書は端末が空くまで起こさないと
  だけ書く)。空にせず正常に終われば `PENDING` にして同じ task をまた起こし、task が返した次の疑似会話を端末に置く
- 端末で task を起こす手順 (IMMEDIATE の連鎖を含む) は START TERMID と共通の `CicsTerminalTasks.run` にした
- `DELETEQ TD` は trigger の状態を変えない (文書が書かない)

## 7. ブラウザへの配信

- 端末へ出す task が SEND した画面は、端末の行の `SCREEN` と `SCREEN_VERSION` に置く (task の UOW で、会話と一緒に確定)
- ブラウザは画面ごとに `SCREEN_VERSION` を hidden で持ち、`GET /cics/terminal/events` の SSE を開く。server は端末の版が
  進んだら `screen` event を送り、ブラウザは現在の画面を読み直す (描き方は設計 81 のまま 1 つ)
- SSE を開けない、切れた、JavaScript が無い場合: 次の要求で、送られた版が端末の版より古ければ **入力を動かさず**現在の画面を返す。
  3270 では画面が書き換わった時点で古い画面への入力は成り立たないためである。冪等キーは記録しない (task を動かしていない)
- SSE の要求も認証と端末の owner を確かめる。event に画面の中身を載せない (版だけ)。中身は通常の要求で CSRF と一緒に読む

## 8. 複数の JVM

### 8.1 表

`JdbcConversationStore.SCHEMA` と同じ DataSource に置く。

| 表 | 主な列 |
| --- | --- |
| `COBOL_TERMINAL` | §4.1 |
| `COBOL_START` | `REQUEST_ID` (主キー)、`TRANSID`、`TERMINAL_ID` (null 可)、`EXPIRES_AT`、`DATA` 等の RETRIEVE の項目、`OWNER_NAME`、`USER_ID`、`CLAIM_TOKEN` / `CLAIMED_UNTIL` |
| `COBOL_TD_QUEUE` | `QUEUE_NAME` (主キー)、`TRIGGER_STATE`、`RECORD_COUNT`、`TASK_TOKEN` / `TASK_UNTIL` |
| `COBOL_TD_RECORD` | `QUEUE_NAME`、`SEQUENCE` (主キー)、`DATA` |

TS のキューと非同期 API の子は、この設計の範囲に入れず 1 つの JVM の中のままとする (§9)。

実装 (増分 2): `JdbcCicsStarts` (STRICT の構成で `CicsStartPort` の bean になる) と `JdbcCicsTransientData` (キューの定義を
持つ利用者が作る)。表の DDL は `JdbcConversationStore.SCHEMA` に足した。

- `COBOL_START` は端末の無い START だけを置く。`START_TOKEN` は行ごとの乱数で、dispatcher が見てから消すまでに同じ REQID で
  登録し直された行を消さないために使う。端末を待つ START の claim の列は増分 3 で足す
- REQID を書かない START の名前は `JV` と 36 進 6 桁の**乱数**にした。JVM ごとの通し番号では JVM をまたいで重なるためである。
  乱数でも未満了の START と重なりうるが、そのとき FROM を持つ START は IOERR になる (推定の範囲を越えない失敗)
- 満了したが dispatcher がまだ起こしていない START は CANCEL で取り消せない (NOTFND)。1 つの JVM の中の実装で満了と同時に
  起こすのと同じに見せる
- TD の操作はキューの行を UPDATE して lock してから行う。H2 で 2 つの置き場の 4 つの task が同時に読んでも、どの record も
  1 度だけ取り出した
- `PROTECT` の START はこの増分でも task の同期点の commit のあとに登録する (設計 82 §6)。task の UOW の中で INSERT する形
  (§5) は増分 3 でも入れていない (§5 の実装の注)

### 8.2 dispatcher

- 各 JVM が一定の間隔 (構成、既定 1 秒) で、満了した START と `PENDING` のキューを探し、行を CAS で claim してから task を起こす。
  どの JVM が起こしても 1 回だけになる
- 端末へ出すものは、端末の lease も取れたときだけ起こす。取れなければ claim を外して次の周期に回す
- **START の attach は高々 1 回**: claim した行は task を起こす前に消す (START のデータは task の中で持つ)。JVM がその間に止まれば
  START は失われる。回復不能の START が region の停止で失われるのと同じ側に倒し、2 回動かすことはしない。`PROTECT` の START も
  登録までを守るもので、attach のあとは同じである (推定。P-144)
- trigger の task の token に期限を持たせ、JVM が止まって期限が過ぎれば `ATTACHED` を `BLOCKED` と同じに扱う
  (task が終わったか分からないので、次の QZERO まで起こさない)

### 8.3 SSE の配信

SSE の接続はどれか 1 つの JVM が持つ。画面を作った JVM とは違いうるので、接続を持つ JVM が自分の端末の版を一定の間隔で
まとめて読んで event を送る。押し出しの遅れはその間隔までである。

## 9. 断るもの・確かめていないこと

- 断る: `ATIFACILITY(SYSTEM)`、回復可能な TD のキュー、区画外のキュー、START の `USERID` (設計 82 のまま)、`SYSID`、
  TERMID に APPC の session、JSON API の要求への端末へ出す task
- 確かめていない (P-144):
  - 疑似会話の途中の端末に実機が task を起こすか。起こすなら待っている COMMAREA と TRANSID をどう扱うか
  - trigger level の比べ方 (以上か、等しいときか)
  - 端末を待っている満了済みの START を CANCEL で消せるか
  - attach のあとに region が止まったときの START の扱い
  - 同じ端末と TRANSID の START をまとめる範囲 (起こした時点で満了していた分か、task の間に満了した分も含むか)

## 10. 増分の順番

1. 端末の表と端末の lease。会話の参照を HTTP session から端末の表へ移す (ブラウザの入口の振る舞いは変えない)
2. `COBOL_START` と `COBOL_TD_QUEUE` / `COBOL_TD_RECORD` の JDBC の置き場と dispatcher。端末の無い START と TD を複数の JVM へ広げる
3. START TERMID (owner の照合、会話の途中は待つ、満了した START をまとめて RETRIEVE)
4. TD の ATI の `FILE`
5. TD の ATI の `TERMINAL` と固定の端末名
6. SSE の配信と、古い版の入力を動かさない形
7. `RETRIEVE WAIT`

各増分は H2 で試験し、複数の JVM は 1 つの試験の中で 2 つの dispatcher と 2 つの coordinator を同じ DataSource に向けて確かめる。
