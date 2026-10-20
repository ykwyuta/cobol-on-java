# デモ #011: 3270 エミュレータから TN3270 で動かす疑似会話

翻訳した CICS の COBOL を、**3270 エミュレータ (x3270 / c3270 / wc3270 / s3270) からそのまま操作**します。
Spring も servlet も使いません。領域は `cobol-cics` と `cobol-runtime` だけで動きます
([設計 86](../../docs/design/86-cics-tn3270.md))。

| 層 | 実体 |
| --- | --- |
| 画面 | [`GRTSET.bms`](GRTSET.bms)。記号マップは翻訳時に原文から作る |
| 業務処理 | [`GREET.cbl`](GREET.cbl)。`SEND MAP` / `RECEIVE MAP` / `RETURN TRANSID ... COMMAREA` の疑似会話 |
| 端末 | `dev.cobolonjava.cics.tn3270.Tn3270Main`。TN3270 / TN3270E で端末を受け、BMS の画面を 3270 データストリームで送る |

## 動かす

```sh
demo/011/run_demo.sh            # 領域を localhost:3270 で起こし、エミュレータを待つ
c3270 localhost:3270            # 別の端末から
```

空の画面に `GRT1` と打って Enter を押します。ホストの CICS と同じ手順です。

```
 GREETING -- COBOL ON JAVA                                  TERMINAL: U001
 YOUR NAME: ALICE_____
 HELLO, ALICE!
 TASKS: 0002
 ENTER=GREET  CLEAR=REDISPLAY  PF3=END
```

| 操作 | 動作 |
| --- | --- |
| 名前を打って Enter | `RECEIVE MAP` が読み、挨拶を返す。`TASKS` は COMMAREA で次の task へ渡した数 |
| 何も打たずに Enter / CLEAR | 変更した field が無いので `MAPFAIL` |
| PF3 | `SEND TEXT` で別れの文を出し、`RETURN` で会話を終える |
| 名前に `ABEND` | `ABEND ABCODE('GRTA')`。端末に DFHAC2206 が出て会話は外れる |
| 定義の無い TRANSID | DFHAC2001 |

`TERMINAL` は EIBTRMID です。エミュレータが LU 名を求めれば (`c3270 COJLU001@localhost:3270`)、
CICS の自動導入の既定と同じく LU 名の末尾 4 文字 (`U001`) になります。

`s3270` があれば、台本で流して終わることもできます (`apt-get install s3270`)。

```sh
demo/011/run_demo.sh --s3270
```

## 領域を自分で起こす

```sh
java -cp <翻訳した class>:cobol-cics/target/classes:cobol-runtime/target/classes \
    dev.cobolonjava.cics.tn3270.Tn3270Main \
    --bms demo/011/GRTSET.bms --transaction GRT1=GREET \
    [--port 3270] [--host 127.0.0.1] [--applid COBOLJVM] [--user USERID] [--codepage IBM-1047]
```

sign on (CESN) が無いので、既定では loopback だけで受けます。会話・端末・一時記憶はすべて 1 つの JVM の
中にあり、Db2 の作業単位は持ちません (`EXEC SQL` を使う資産は #009 の形で境界を組みます)。
取り決めない端末の機能 (TLS、印刷装置、SYSREQ など) は暫定判断 P-191 にあります。
