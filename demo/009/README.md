# デモ #009: BMS + COBOL + H2 による Todo アプリ

本デモは、**画面 (BMS)・業務処理 (COBOL)・データベース (H2)** の 3 つを 1 本の
オンライントランザクションにまとめた、動くサンプルアプリケーションです。

メインフレームの CICS アプリケーションとまったく同じ形で書いてあります。

| 層 | 実体 | 使っている仕組み |
| --- | --- | --- |
| 画面 | [`TODOSET.bms`](TODOSET.bms) | BMS マクロ (`DFHMSD` / `DFHMDI` / `DFHMDF`)。記号マップ写し句は**翻訳時に原文から生成**される (要件 FR-162) |
| 業務処理 | [`TODOAPP.cbl`](TODOAPP.cbl) | `EXEC CICS SEND MAP` / `RECEIVE MAP` / `RETURN TRANSID ... COMMAREA` による疑似会話 |
| データ | H2 (インメモリ) | COBOL の `EXEC SQL`。`SELECT` / `INSERT` / `UPDATE` / `DELETE` とカーソル (`OPEN` / `FETCH` / `CLOSE`) |
| 端末 | [`TodoTerminal.java`](TodoTerminal.java) | `BmsScreenSnapshot` を 24x80 の文字格子として描き、打ち込んだ 1 行を 3270 の入力に見立てる |

**COBOL の外に業務ロジックはありません。** 画面の組み立ても、入力の検証も、SQL も、
すべて翻訳した `TODOAPP.cbl` の中で起きます。Java 側が持つのは端末と、タスクを起こす
ための配線 (H2 の `DataSource`、Db2 作業単位、CICS タスク境界) だけです。

---

## 1. 画面

```
    +--------------------------------------------------------------------------------+
 1  | TODO LIST  --  cobol-on-java                                 TRANSID: TODO     |
 2  |                                                                                |
 3  |    NO ST   TASK                                                                |
 4  |                                                                                |
 5  |     1 [ ]  read the BMS map TODOSET.bms                                        |
 6  |     2 [X]  build the project with mvn                                          |
 7  |     3 [ ]  write the design note                                               |
 8  |                                                                                |
...
14  | COMMAND ==>                                                                    |
15  |                                                                                |
16  | Added entry 3.                                                                 |
17  |                                                                                |
18  | ENTRIES:   3   OPEN:   2   DONE:   1   TASK:   3                               |
19  |                                                                                |
20  |                                                                                |
21  | ADD text | DONE n | OPEN n | DEL n | LIST                                      |
22  | ENTER=RUN   PF3=EXIT   CLEAR=REDISPLAY                                         |
23  |                                                                                |
24  |                                                                                |
    +--------------------------------------------------------------------------------+
    cursor at row 14 column 16
```

`TASK:` の数字は **COMMAREA に入れて次のタスクへ渡している**カウンタです。
画面を 1 枚出すたびに CICS タスクが終わり、次の入力で新しいタスクが始まる
—— 疑似会話がそのとおり動いていることが、この数字で見えます。

### 操作

| 入力 | 動作 |
| --- | --- |
| `ADD <本文>` | 追加する。番号はデータベースが決める (`SELECT COALESCE(MAX(TODO_ID),0)+1`) |
| `DONE <番号>` | 済みにする |
| `OPEN <番号>` | 済みを取り消す |
| `DEL <番号>` | 消す |
| `LIST` | 読み直す |
| `PF3` (`EXIT` / `QUIT` も可) | 会話を終える。`SEND TEXT` で別れの画面を出して `RETURN` する |
| `CLEAR` | 画面を出し直す。3270 の CLEAR は入力を送らないので `RECEIVE MAP` は `MAPFAIL` になる |
| 空行 | 同上。変更した欄が無いので `MAPFAIL` |

---

## 2. BMS マップの作り

`TODOSET.bms` は組立て済みの写し句を同梱しません。`COPY TODOSET.` を書くと、
翻訳器が同じ置き場の `TODOSET.bms` を読み、記号マップをその場で作ります
(`-I demo/009` がそれを指示します)。**BMS を直したのに写し句が古いまま、という
ずれが起きない**ようにするためです。

一覧の 8 行は `OCCURS=8` の 1 本の欄です。

```
LINE     DFHMDF POS=(5,1),LENGTH=79,OCCURS=8,ATTRB=(PROT,NORM),COLOR=TURQUOISE
```

BMS の `OCCURS` は各回を**続けて**並べるので、1 回が画面の 1 行ぶん (79 桁 + 属性 1 桁)
のときだけ行と一致します。生成される記号マップはこうなります。

```cobol
       01  TODOMAPO REDEFINES TODOMAPI.
           02  FILLER PIC X(12).
           02  DFHMS2 OCCURS 8 TIMES.
             03 FILLER PICTURE X(3).
             03 LINEO  PIC X(79).
```

COBOL 側は `MOVE WS-LINE TO LINEO(WS-ROWS)` と添字で書けます。

---

## 3. 疑似会話とデータベースの作業単位

1 回の画面が 1 つの CICS タスクです。タスクは次のように進みます。

1. `EIBCALEN = 0` なら会話の 1 本目。COMMAREA を初期化する
2. そうでなければ `EXEC CICS RECEIVE MAP` で端末入力を取り、コマンドを実行する
3. カーソルで一覧を読み直し、記号マップの 8 行を組み立てる
4. `EXEC CICS SEND MAP ... ERASE CURSOR FREEKB` で画面を出す
5. `EXEC CICS RETURN TRANSID('TODO') COMMAREA(WS-COMMAREA)` でタスクを終える

`EXEC SQL` は `Db2Execution` を通じてタスクの作業単位へ届きます
([設計 77 §4.6](../../docs/design/77-spring-cics-db2.md))。`TodoTerminal` は
タスクごとに `Db2TaskRuntime` を開き、正常に返れば確定、例外で終われば取り消します。
**画面が変わるたびに確定する**ので、H2 の中身は常に画面と一致します。

---

## 4. 実行方法

事前にプロジェクト全体をビルドしておきます。

```
mvn clean install -DskipTests
```

### Windows

```cmd
demo\009\run_demo.bat
```

### Linux / macOS

```sh
demo/009/run_demo.sh
```

### 打たずに一通り見る

`--demo` を付けると、追加・完了・削除・再開・終了を順に流します。

```sh
demo/009/run_demo.sh --demo
```

色を付けたくない端末では `--no-color` を足してください。

---

## 5. 翻訳の指定

```
java -cp <classpath> dev.cobolonjava.compiler.Main \
     -d demo/009/bin -I demo/009 demo/009/TODOAPP.cbl
```

`-I demo/009` の 1 つで 3 種類の写し句が揃います。

| `COPY` / `INCLUDE` | どこから来るか |
| --- | --- |
| `COPY TODOSET.` | `demo/009/TODOSET.bms` から生成した記号マップ |
| `COPY DFHAID.` | 翻訳器が持つ CICS のシステム写し句 (`DFHENTER` / `DFHPF3` / `DFHCLEAR`) |
| `EXEC SQL INCLUDE SQLCA END-EXEC.` | 翻訳器が持つ Db2 の SQLCA (136 byte) |

生成物 (`demo/009/bin`) はリポジトリに含めません。上の手順で作り直せます。

---

## 6. ブラウザで動かす (Spring Boot + Thymeleaf)

同じ `TODOSET.bms` と `TODOAPP.cbl` を、**書き換えずに**ブラウザの 3270 画面として出す版を
[`web/`](web/) に置いてあります。`TodoTerminal` が `BmsScreenSnapshot` を文字の格子に
置き直しているところを、`cobol-spring-boot-4-bms-thymeleaf` の Thymeleaf template が
HTML に置き直します ([設計 81](../../docs/design/81-bms-web-renderer.md))。

### 実行方法

```cmd
demo\009\run_web.bat
```

```sh
demo/009/run_web.sh
```

`http://localhost:8080/` を開き、`demo` / `demo` でログインすると TODO transaction の開始画面が出ます。
`Start TODO` で最初の画面、あとは COMMAND 欄に打って `Enter`、終えるときは `PF3` です。
`Clear` は入力を送らないので、端末版と同じく `MAPFAIL` の画面になります。

### 端末版との違い

Java 側に書いたのは [`TodoWebApplication.java`](web/src/main/java/demo/web/TodoWebApplication.java) の
bean 2 つ (transaction の登録と、翻訳した TODOAPP の実行) だけです。端末版が手で書いていたものは、
処理系の自動構成と設定 ([`application.properties`](web/src/main/resources/application.properties)) が受け持ちます。

| 端末版 (`TodoTerminal`) | ブラウザ版 |
| --- | --- |
| 1 行を読んで `BmsTerminalInput` を作る | `CicsBrowserController` が form を `BmsTerminalInput` にする |
| 文字の格子に描く | `templates/cobol/bms/screen.html` が描く。PF キーは button とキーボードの両方 |
| COMMAREA を変数で次の task へ渡す | 会話ストアが持つ。`cobol.cics.conversation.consistency=strict` で H2 の表に置く |
| task ごとに `Db2TaskRuntime` を開く境界を手で書く | STRICT の task 境界。**業務の SQL と次の会話が同じ UOW で確定する** (設計 77 §4.6) |
| 認証なし | `cobol.cics.security.mode=demo` の簡易認証。ブラウザの入口は認証と CSRF が無ければ開かない (設計 84) |
| 端末名 `T001` | HTTP session ごとに `W` + 3 文字の端末名 |

表の作成と初期の 2 行は `todo-schema.sql` / `todo-data.sql` を Spring Boot の SQL 初期化が流します。
会話の表は処理系が持つ DDL (`cobol-conversation-schema.sql`) を同じ H2 に作ります。

### 確かめ方

```sh
mvn -f demo/009/web/pom.xml test
```

[`TodoWebApplicationTest`](web/src/test/java/demo/web/TodoWebApplicationTest.java) が、ブラウザと同じ
hidden (冪等キー、会話の ID と版、画面の版) を載せ直しながら、開始 → ADD → DONE → DEL → CLEAR → PF3 を
ブラウザの入口に送り、画面の文面と H2 の表の中身の両方を確かめます。事前に `run_web` か 5 章の翻訳で
`demo/009/bin` を作っておいてください。

`TODO_TEXT` は `VARCHAR(60)` ですが、COBOL のホスト変数が固定長の `PIC X(60)` なので、
**後ろの空白ごと入ります** (Db2 と同じ振る舞い)。端末版でも同じです。
