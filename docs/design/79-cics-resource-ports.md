# 設計文書 79: CICS 資源ポート (時間・間隔・端末・システム情報) と BMS 実行時

| 項目 | 内容 |
| --- | --- |
| 対応要件 | FR-160, FR-161, FR-162, FR-163, FR-166, FR-167, NFR-034, NFR-036, NFR-037 |
| 関連 ADR | [ADR-0007](../decisions/0007-framework-neutral-subsystem-ports.md), [ADR-0008](../decisions/0008-cics-on-spring-mvc-and-session.md), [ADR-0010](../decisions/0010-bms-thymeleaf-terminal-ui.md) |
| 関連設計 | [設計 77](77-spring-cics-db2.md) (task 境界、会話、BMS の UI 方針) |
| ステータス | 起草 (2026-09-14)。§10 の 1 (動的 PROGRAM) 、2 (ASSIGN APPLID / PROGRAM、`CicsEnvironment` の APPLID)、3 (ASKTIME / FORMATTIME、`CicsEnvironment` の時計) を実装済み |
| 証拠レベル | 断りのない限り V1 (IBM 公開仕様の記述) または V0。実 CICS の trace とは突き合わせていない |

## 1. 目的と範囲

設計 77 は task 境界・会話・UOW と、BMS 画面を Web で再現する方針を決めた。本書はその間を埋める。
生成 COBOL が発行する **時間・間隔・端末・システム情報・動的なプログラム名** の各 `EXEC CICS` を、
中立な command とポートでどう表し、どこで何を断るかを決める。

Spring MVC / Thymeleaf / JavaScript の画面実装は範囲外であり、設計 77 §4.5 と ADR-0010 に従う。
本書が決めるのは、その adapter が受け取る **中立な画面状態と入力** までである。

## 2. 何から作るかは測定が決める

Bank-of-Z の CICS 資産 32 本 (同梱しない。`verify corpus` で測る) に現れる命令の数と、
翻訳を最初に止めている理由から順序を決める。

| 命令 | 出現数 | 使われている形 |
| --- | ---: | --- |
| `ASSIGN APPLID` / `ASSIGN PROGRAM` | 128 / 128 | 受取側 `PIC X(8)` |
| `LINK PROGRAM(データ名)` | 127 | `WS-ABEND-PGM PIC X(8) VALUE 'ABNDPROC'` |
| `ASKTIME` / `FORMATTIME` | 37 / 36 | `ABSTIME` (`S9(15) COMP-3`)、`DDMMYYYY`、`TIME`、`DATESEP` |
| `SEND MAP` / `RECEIVE MAP` | 28 / 9 | `MAPSET`、`FROM` / `INTO`、`ERASE`、`DATAONLY`、`MAPONLY`、`CURSOR`、`FREEKB`、`ALARM` |
| `SEND TEXT` / `SEND CONTROL` | 9 / 8 | `FROM`、`ERASE`、`FREEKB` |
| `DELAY` | 8 | `FOR SECONDS(データ名)` |
| `GET` / `PUT CONTAINER` | 6 / 6 | `CHANNEL`、`FLENGTH` |

実装順序は §10 に置く。数の多いものほど先に作り、1 増分ごとに測り直す。

## 3. 共通の決めごと

### 3.1 option の値は翻訳時に形を確定する

CICS translator は option の値を「定数」「データ名」「データ域 (受取側)」に分ける。本処理系も
翻訳時に次の型規則を検査し、**実行時に長さや型を推測しない**。

| 種別 | 例 | 翻訳時の検査 | 実行時に渡すもの |
| --- | --- | --- | --- |
| 名前 (送り側) | `PROGRAM(WS-PGM)`、`MAPSET(...)` | 英数字、長さが仕様の長さ以下 | その項目の byte 列 |
| 数値 (送り側) | `SECONDS(WS-N)` | 数字項目または整数定数 | `Decimal` |
| 受取域 | `APPLID(X)`、`ABSTIME(T)` | 仕様の長さ・形と一致 | 項目の `DataView` |
| 構造 (送受) | `FROM(map-O)`、`INTO(map-I)` | 群項目または英数字、長さが翻訳時に決まる | 項目の `DataView` |

受取域の長さが仕様と違う場合は翻訳を拒否する。host では CICS が固定長を書き込み、隣の項目を
上書きしうるが、その振る舞いを黙って再現すると壊れた資産がそのまま動く。
ただし資産が仕様より短い受取域を持つ形で host 上で動いている場合 (§6.2)、その事実を
暫定判断に記録したうえで、**書き込む範囲を 01 レベルの記憶域の中に限る**。

### 3.2 実行時の情報はポートから得る

生成 COBOL と `CicsRuntimeOps` は時計、端末、region の構成を直接持たない。
`CicsExecution` が task ごとに一つ持つ `CicsEnvironment` から得る。

```java
record CicsEnvironment(
        Optional<Applid> applid,            // ASSIGN APPLID
        Clock clock,                        // ASKTIME
        CicsIntervalPort interval,          // DELAY
        Optional<CicsTerminalPort> terminal,// SEND / RECEIVE
        Optional<BmsMapsetCatalog> mapsets) // SEND MAP / RECEIVE MAP の定義
```

- 構成されていないポートを使う命令は、推測値で進まず **実行時に失敗**させる
  (`CicsTaskStateException`)。例: APPLID を構成していない region で `ASSIGN APPLID`。
- 地方時は `CicsTaskContext.hostZone` に従う (暫定判断 P-113)。JVM の既定の時間帯を使わない。
- adapter (Spring 等) がポートを実装する。`cobol-cics` は Spring 型を持たない (NFR-034)。

### 3.3 RESP と EIBRCODE

`EIBRESP` / `EIBRESP2` は各 command の結果から設定する。`DFHRESP` と `HANDLE CONDITION` で
使える condition 名は、**結果を生成できるものだけ** を増やす (現行の方針のまま)。

`EIBRCODE` は command 群ごとに値の体系が違い、手元の公開情報だけでは全 condition の byte を
確定できない。Db2 の SQLCA と同じく field の fidelity を明示する。

| fidelity | 意味 | 該当 |
| --- | --- | --- |
| `EXACT` | 公開仕様の値を置く | プログラム制御の `PGMIDERR` |
| `UNAVAILABLE` | 値を持たない。binary zero を置き、fidelity 表に記録する | 本書で追加する condition |

`EIBRCODE` を読んで分岐する資産が見つかったら、その condition を `EXACT` へ上げる作業を先にする。
Bank-of-Z は `RESP` で判定しており、`EIBRCODE` を読まない。

## 4. プログラム制御: 動的な PROGRAM

`LINK` / `XCTL` の `PROGRAM` にデータ名を許す。

- 翻訳時: 英数字 1〜8 byte の項目に限る。数字項目、群項目、部分参照は断る。
- 実行時: byte 列を実行時 code page で文字にし、**末尾の空白だけ** を落とす。`ProgramId` として
  正しくない名前 (空、先頭空白、許可文字外) は推測で直さず `INVREQ` 相当として失敗させる。
  名前として正しいが catalog に無ければ、静的名と同じく `PGMIDERR`, `RESP2=1`。
- 許可は従来どおり catalog が決める。データ名からクラス名や Bean 名を組み立てない
  (設計 77 §7.2)。
- `COMMAREA` の `LENGTH` を省いたときは、translator と同じくデータ項目の翻訳時の長さを使う。
  `LENGTH(データ名)` と `LENGTH OF` は後続増分とする。
- `SYNCONRETURN` は `LINK` だけに受け、local LINK では効果を持たない (暫定判断 P-114)。

## 5. システム情報: ASSIGN

| option | 受取域 | 値 | 構成が無いとき |
| --- | --- | --- | --- |
| `ABCODE` | `X(4)` | 実装済み | — |
| `APPLID` | `X(8)` | `CicsEnvironment.applid` を右空白詰め | 実行時失敗 |
| `PROGRAM` | `X(8)` | **その ASSIGN を実行している program** の catalog 上の名前 | — |

`PROGRAM` は PROGRAM-ID ではなく、task がその program を起動したときの名前である。
LINK / XCTL / 初期 program の起動名を `CicsExecution` が program 入口ごとに記録し、
invocation token から引く。1 つの `ASSIGN` に複数 option を書く形は、option ごとの規則を
すべて満たすときだけ受ける。

## 6. 時間

### 6.1 ASKTIME

`ASKTIME ABSTIME(data-area)` は `S9(15) COMP-3` (PL8) の受取域へ、**1900-01-01 00:00:00 (地方時)
からのミリ秒** を置く。同時に `EIBDATE` / `EIBTIME` をその時刻へ更新する。
時刻は `CicsEnvironment.clock`、地方時は `hostZone` から得る。`hostZone` が無ければ失敗させる。

### 6.2 FORMATTIME

初期 subset は Bank-of-Z が使う `ABSTIME`、`DDMMYYYY`、`YYYYMMDD`、`MMDDYYYY`、`TIME`、
`DATESEP[(c)]`、`TIMESEP[(c)]` とする。既定の区切りは日付 `/`、時刻 `:` である。

| option | 書く内容 |
| --- | --- |
| `DDMMYYYY` 等 | `DATESEP` ありなら `dd/mm/yyyy` の 10 文字、なしなら 8 文字 |
| `TIME` | `TIMESEP` ありなら `hh:mm:ss` の 8 文字、なしなら `hhmmss` の 6 文字 |

**受取域の長さは未確定である。** 公開仕様は日付の受取域を「10 文字」、時刻を「8 文字」と書く
版があり、Bank-of-Z は `TIME` を `PIC 9(6)` で受けている。区切りの無い形で書く文字数だけを
置くのか、固定長を置くのかを host で確かめるまで、実装は次に留める。

- 書く文字数は上表の「区切りの有無で決まる長さ」とする。
- 受取域がその長さより短ければ翻訳を拒否する。長ければ先頭から書き、残りは変えない。
- この扱いを暫定判断に記録し、host の trace を解消条件とする。

## 7. 間隔: DELAY

`DELAY` は `CicsIntervalPort.delay(Duration, Instant deadline)` へ渡す。

- 受ける形: `FOR [HOURS(n)] [MINUTES(n)] [SECONDS(n)] [MILLISECS(n)]`、`INTERVAL(hhmmss)`。
  値は定数またはデータ名。`TIME`、`UNTIL`、`REQID` は後続増分とし、現状は翻訳時に断る。
- 値の範囲: `FOR` で 1 つだけ書いた単位は上限まで、複数書いたときは下位の単位を 59 以下
  (ミリ秒は 999 以下) に限る。範囲外は `INVREQ` 相当として実行時に失敗させる。
  `RESP2` の値は未確認であり推測しない。
- **task の期限を越える待ちは始めない。** HTTP 要求に束縛された同期 task であり
  (設計 77 §2)、期限を越えて待つと coordinator の lease 前提が崩れる。越える場合は待たずに
  失敗させる。host の CICS は task 期限の概念が違い、ここは意図した差である。
- 標準実装は `Thread.sleep` ではなく、割り込みで起きて task を失敗させる待ちとする。
  試験では時計を進めるだけの fake を使う。

## 8. 端末と BMS 実行時

### 8.1 構成要素

```text
生成 COBOL ──EXEC CICS SEND MAP / RECEIVE MAP──▶ CicsRuntimeOps
                                                   │
                     BmsMapsetCatalog ◀─────────── │ (mapset 名 → BmsModel.Mapset と版)
                                                   ▼
                                           BmsScreenComposer (cobol-cics、中立)
                                                   │ BmsScreenSnapshot
                                                   ▼
                                           CicsTerminalPort (adapter が実装)
                                                   │
                           Spring MVC + Thymeleaf + JavaScript (設計 77 §4.5)
```

- `BmsMapsetCatalog`: mapset 名から `BmsModel.Mapset` と **定義の版** を返す。翻訳時に
  記号マップを作ったのと同じ BMS 原文から作る。版が違えば実行を拒否する
  (記号マップと物理マップの byte 位置がずれるため)。
- `BmsScreenSnapshot`: 画面の中立状態。map ID と版、terminal profile、各 field の現在の
  データ・属性・MDT、cursor 位置、keyboard lock、alarm。HTML / DOM を含まない (ADR-0010)。
- `CicsTerminalPort`: `send(snapshot, options)` と `receive()`。受け取った入力は
  `BmsTerminalInput` (AID、cursor 位置、変更 field とその値) であり、adapter が
  サーバ側で再検証したあとの値である (設計 77 §4.5.4)。

### 8.2 疑似会話との関係

HTTP 上の task は「送信して終わる」。`SEND MAP` を発行した task は `RETURN TRANSID` で終わり、
端末入力は **次の task** の要求として届く。したがって:

- task 内の `SEND` は snapshot を task 結果へ積み、`CicsTaskReply` とともに adapter へ渡す。
- 次の task の `RECEIVE MAP` は、要求に含まれる `BmsTerminalInput` と、会話に保存した
  直前の snapshot から入力 map を作る。snapshot は `ConversationEnvelope.screenState` に保存する
  (設計 77 §4.6)。
- 会話型 (`SEND` のあとに同じ task で `RECEIVE` を待つ) は同期 HTTP で表せない。
  入力の無い `RECEIVE` は `MAPFAIL` ではなく **実行時の失敗** とし、会話型の資産を検出する。

### 8.3 SEND MAP の合成規則

記号マップ (`FROM`) と物理マップ (BMS 定義) から snapshot を作る。規則は公開仕様による (V1)。

| option | 合成 |
| --- | --- |
| 既定 | 物理マップの固定文字・属性に、記号マップのデータを重ねる |
| `MAPONLY` | 物理マップだけ。`FROM` を読まない |
| `DATAONLY` | 記号マップのデータと属性だけを、現在の画面の同じ field に重ねる |
| `ERASE` | 重ねる前に画面を消す |
| `FREEKB` / `ALARM` / `FRSET` | keyboard 解除 / 警報 / 全 field の MDT を落とす |
| `CURSOR` (値なし) | 記号 cursor: 長さ (L) に `-1` を置いた最初の field |
| `CURSOR(n)` | 画面先頭からの位置 |

記号マップの読み方:

- データ (O) の先頭 byte が `X'00'` の field は、データを送らず物理マップの値を使う。
- 属性 (A) が `X'00'` なら物理マップの属性を使う。それ以外は 3270 属性 byte として読む。
- 拡張属性 (C / H / P / V / U / M / T) も `X'00'` は「変えない」。
- 記号マップの byte 位置は `BmsSymbolicMapWriter` と同じ規則から計算する。写し句の文字列を
  実行時に解析しない。

### 8.4 RECEIVE MAP の分解規則

`BmsTerminalInput` と snapshot から、`INTO` の入力 map (I 側) を作る。

| 部分 | 値 |
| --- | --- |
| L (長さ) | 入力された文字数。変更されていない field は 0 |
| F (flag) | field を消去 (EOF) したときだけ `X'80'`、それ以外 `X'00'` |
| I (データ) | 入力値。`JUSTIFY` の既定と詰め文字は未確認 (§11) |
| EIBAID / EIBCPOSN | 入力の AID と cursor 位置 |

変更 field が 1 つも無い、または AID が `CLEAR` / `PA1〜3` のとき `MAPFAIL` (RESP 36)。

### 8.5 SEND TEXT / SEND CONTROL

- `SEND CONTROL`: 画面の内容を変えず、`ERASE` / `FREEKB` / `ALARM` / `CURSOR` だけを snapshot へ反映。
- `SEND TEXT FROM(x) LENGTH(n)`: map を使わない文字画面。`ERASE`、`FREEKB` を受ける。
  改行と頁の分割規則は後続増分とし、初期は 1 画面に収まる長さに限る。

## 9. チャネルとコンテナ

`PUT` / `GET CONTAINER` は既存の `CicsPayload` の container をチャネル単位へ広げる。
上限検査は `CicsTransactionDefinition` の値を使う。詳細は後続増分で本書へ追記する。

## 10. 実装順序

1 増分ごとに `verify corpus` で測り、翻訳を最初に止めている理由の変化をコミットに書く。

1. 動的 `PROGRAM` (§4)
2. `ASSIGN APPLID` / `PROGRAM` と `CicsEnvironment` (§3.2, §5)
3. `ASKTIME` / `FORMATTIME` (§6)
4. `DELAY` (§7)
5. BMS 実行時: `BmsMapsetCatalog`、`BmsScreenSnapshot`、`SEND CONTROL` / `SEND MAP` (§8.1〜8.3, 8.5)
6. `RECEIVE MAP` と会話への snapshot 保存 (§8.2, 8.4)
7. チャネルとコンテナ (§9)

## 11. 未決事項

いずれも実装時に暫定判断へ記録し、host の trace を解消条件にする。

- FORMATTIME の受取域に書く長さ (§6.2)
- DELAY の範囲外で返る RESP2 (§7)
- RECEIVE MAP の I 部分の詰め文字と `JUSTIFY` の既定、NUM field の扱い (§8.4)
- 本書で追加する condition の EIBRCODE byte (§3.3)
- `ASSIGN PROGRAM` が alias で起動された program に対して返す名前 (§5)
