# IBM i (AS/400) 資産のコンパイルおよび実行に向けた方式検討レポート

| 項目 | 内容 |
| :--- | :--- |
| **作成日** | 2026-09-22 |
| **更新日** | 2026-09-22 (§5 の未確認事項を調査し、結果と訂正を反映。出典は §12) |
| **対象** | **IBM i (AS/400 系)** — RPG IV / ILE / DDS / Db2 for i / CL / 5250 |
| **問い** | この処理系を基盤に IBM のオフコンを再現できるか。既存ランタイムを活かし、**H2 を拡張して Db2 を**、あわせて **RPG を**実装する形で成立するか |
| **関連文書** | [HLASM の方式検討](hlasm-support-and-execution-requirements.md), [PL/I の方式検討](pli-support-and-execution-requirements.md), [設計 26 (PL/I の外部検証)](../design/26-pli-verification.md), [設計 80 (ファイル入出力)](../design/80-file-io.md), [決定 0002 (バイト志向の呼出し契約)](../decisions/0002-byte-oriented-call-contract.md), [決定 0013 (IMS DB の非正規化格納エンジン)](../decisions/0013-ims-db-denormalized-raw-storage-engine.md) |

---

## 1. 結論を先に

**骨格は載る。ただし問いの後半 (H2 を拡張して Db2 にする) は採らない。そして最大の壁は
RPG でも Db2 でもなく、外の基準の置き方である。**

> **2026-09-22 追記。**初版で「未確認」とした 4 点を調べ、**結論が 1 つ動いた** (§5.1)。
> Hercules に相当する AS/400 エミュレータは**やはり存在しない**が、**実機には届く**
> (pub400.com、IBM Power Virtual Server)。したがって IBM i 対応は、初版に書いた
> 「PL/I と同じ等級」ではなく、**PL/I より強い位置から始められる**。
> 一方で NIST SQL Test Suite は**もう配布されていない** (§5.3 で訂正)。

| | z 側で採った手法 | IBM i |
| :--- | :--- | :--- |
| 新モジュール 1 つをフロントエンドとして足す | ○ | **○ そのまま (RPG IV)** |
| 生成クラスは `CobolProgram` ABI に着地する薄い殻 | ○ | **△ ILE の呼出し規約が足りない** |
| 意味論はランタイムが持ち、生成コードは持たない | ○ | **○ そのまま** |
| `cobol-runtime` の 10 進・編集・EBCDIC を再利用 | ○ | **◎ ほぼ無変更で効く** |
| `cobol-db2` の中立ポートを再利用 | ○ | **◎ IMS のときより相性が良い** |
| `cobol-runtime/file` を再利用 | ○ | **△ レコード単位アクセスの意味論が足りない** |
| `cobol-job` (JCL) を再利用 | ○ | **× CL は JCL ではない** |
| `cobol-cics` (BMS / 3270) を再利用 | ○ | **△ 5250 とサブファイルが足りない** |
| `cobol-verify` で外から測る | ○ | **△ 数える枠は同じ。基準の置き場所が違う** |
| Hercules を命令単位のオラクルにする | ◎ | **× エミュレータは無い。代わりに実機を使う** |

足りない部品は 6 つある。重い順に、**外の基準**、**レコード単位アクセス (RLA) の意味論層**、
**5250 と表示ファイル**、**ILE の呼出し規約**、**RPG サイクルと指示器**、
**オブジェクトとライブラリのモデル**である。

恵まれている点は 2 つある。

1. **IBM i のデータベースは下が関係データベースである。**IMS DL/I のときに必要だった専用の
   格納エンジン (決定 0013、`cobol-ims-rdb` で 2,053 行) に相当するものが要らない。
   既存の Db2 ポートの真上に載る
2. **参照処理系そのものに触れる。**PL/I の最大の弱点は「IBM Enterprise PL/I が手元に無い」
   ことだった。IBM i では実機にアクセスできる (§5.2)。エミュレータが無いことと、
   オラクルが無いことは**別である**

---

## 2. まず問いを 2 つに分ける

「AS/400 を再現する」を字義どおり取ると、単一レベル記憶、MI (機械インタフェース)、
オブジェクト権限、作業管理、ライセンス内部コードまで含む OS の仕事になる。これは別物である。

ただし**このプロジェクトは z/OS を再現していない**。再現したのは
**アプリケーションから見える面** (COBOL + CICS + Db2 + IMS + JCL) である。同じ線を i 側にも引く。

| 再現する (アプリケーションから見える面) | 再現しない |
| :--- | :--- |
| RPG IV (ILE RPG) と固定形式 RPG III | 単一レベル記憶、MI / TIMI |
| DDS 物理ファイル・論理ファイル / Db2 for i | ライセンス内部コード、Power 命令セット |
| レコード単位アクセス (RLA) と埋込み SQL | オブジェクト権限、ユーザプロファイル |
| CL (制御言語) | 作業管理 (サブシステム、経路指定項目) |
| 5250 表示ファイルとサブファイル | 単一レベル記憶に依存するポインタ意味論 |
| データ域、データ待ち行列、メッセージ待ち行列 | 保管復元形式 (SAVF)、オブジェクト変換 |
| コミットメント制御とジャーナル | スプールの内部構造 (印刷は出力の形だけ) |

この線引きは z 側と同じ形である。**OS を再現するのではなく、資産が触る面を再現する。**

---

## 3. 同じ形で載る部分

### 3.1 再利用の境界は `cobol-compiler` ではなく `cobol-runtime` である

RPG を足せるかという問いには、PL/I と HLASM が既に答えを出している。`pli-compiler/pom.xml` の
依存はこれだけである。

```text
dev.cobolonjava:cobol-runtime
dev.cobolonjava:cobol-db2
org.ow2.asm:asm
```

**`cobol-compiler` に依存していない。**新しい言語を足す作業は、COBOL 処理系の拡張ではなく、
ランタイムの上に別のフロントエンドを並べる作業である。HLASM レポート §2 の一文がそのまま効く。

> 新しい言語のフロントエンドを 1 つ足し、意味論は既存ランタイムに委ね、生成クラスは
> 既存 ABI に着地するだけの殻にする。測る道具は別モジュールに置き、数を門にしない。

### 3.2 10 進演算・編集・EBCDIC はほぼ無変更で効く

RPG IV の数値はゾーン 10 進 (`ZONED`)、パック 10 進 (`PACKED`)、2 進 (`INT` / `UNS`) であり、
COBOL の `DISPLAY` / `COMP-3` / `COMP` と**同じバイト表現**である。丸めも同じ機械の上で
定義されてきた。したがって次はそのまま使える。

| 既存資産 | IBM i での効き方 |
| :--- | :--- |
| `runtime/data` の `ZonedDecimal` / `PackedDecimal` / `BinaryDecimal` / `SignNibble` | RPG の数値型そのもの |
| `runtime/decimal` の `Decimal` / `CobolRounding` | `EVAL(H)` の半端丸め、`%DEC` の桁詰め |
| `runtime/storage` の `Storage` / `DataView` | データ構造 (DS)、`OVERLAY`、`LIKEDS` |
| `runtime/picture` | 編集の機構。編集語 (`EDTCDE` / `EDTWRD`) は別だが骨は同じ |
| `runtime/codepage` の `CodePages` | IBM-037 と IBM-930 / 939 が既にある |

`DataView` が「連続したバイト領域の上の、オフセット + 長さのビュー」であることは、
RPG のデータ構造と `OVERLAY` にそのまま対応する。COBOL の `REDEFINES` のために作った性質が、
RPG でも同じ理由で要る。

**CCSID について確かめたこと (2026-09-22)。**IBM i の `QCCSID` システム値の**出荷時の既定は
65535** である。65535 は「変換しない」を意味する。米国英語の EBCDIC は 37、日本語は
カタカナ (DBCS) 機能を選ぶと 5026、推奨されるのは 5035 である。

この事実は設計 80 の「生バイトで持つ」という決めごとと**同じ向きを向いている**。

> 変換して保存すると L3 互換が原理的に成立しない。往復変換で情報が欠落し、照合順序が
> 食い違い、パック 10 進などのバイナリ項目が壊れる。

IBM i の既定がそもそも「変換しない」であるということは、**移行してくるバイト列の解釈を
資産の側が持っている**ということである。したがって `CodePages` への 37 / 5026 / 5035 の追加は
小さい仕事だが、**サイドカーに CCSID を持たせる設計 (設計 80) をそのまま使う**のが正しい。
既定を 1 つ決めて焼き込むのではなく、ファイルごとに持つ。

### 3.3 薄い殻と `CobolProgram` ABI は骨格として使える

`run(Storage, ProgramContext, DataView[])` に着地させれば、プログラム解決 (決定 0001)、
実行単位とライフサイクル (決定 0003)、配備カタログ、`ProgramContext` の呼出しスタックと
異常終了時の覚え書きは既存のものが動く。PL/I が `pli.generated.*` として、HLASM が
`hlasm.generated.*` として着地したのと同じ形で `rpg.generated.*` が着地する。

ただし ILE の呼出し規約はこの ABI に**そのままでは載らない**。§4.3 に分けて書く。

### 3.4 Db2 のポートは IMS のときより相性が良い

ここが最も良い知らせである。

IMS DL/I は階層型であり、関係データベースの上に写すために専用の格納エンジンを書いた
(決定 0013)。`cobol-ims-rdb` は 2,053 行あり、`ImsSchema.Dialect` で H2 / PostgreSQL / Db2 の
差を吸収している。

**IBM i にはこの仕事が無い。**IBM i のデータベースは下が関係データベースであり、
DDS で作った物理ファイルは表そのものである。RPG のレコード単位アクセスは、
同じ表に対する別の API にすぎない。したがって:

- 埋込み SQL (`SQLRPGLE`) は `cobol-db2` の `SqlExecutorPort` / `UnitOfWorkPort` /
  `SqlPlan` / `SqlBindings` / SQLCA に**そのまま**着地する
- 方言差は `Dialect` に `DB2_FOR_I` を 1 つ足す形で始められる
- コミットメント制御は `UnitOfWork` の語彙に対応する (ジャーナルの要否は §4.1 で扱う)

`cobol-db2` は PL/I が既に再利用している (`pli-compiler` の依存にある)。RPG でも同じ道を通る。

### 3.5 固定形式の読み取りと DDS は既にある部品の一般化で足りる

RPG III と RPG IV の固定形式 (桁で欄が決まる、継続の規則、`*` の注記) は、
このリポジトリで**既に 3 度実装されている**。

- `cobol-compiler` の固定形式読み取りと継続処理 (COBOL の 1〜6 / 7 / 8〜72 桁)
- `cobol-ims` の `MacroReader` (DBDGEN / PSBGEN)
- `cobol-cics` の `BmsMacroReader` (`DFHMSD` 等)

DDS も同じく桁で欄が決まる記述である (名前欄、型欄、長さ欄、キーワード欄)。
`BmsMacroReader` が BMS マクロを読んで `BmsModel` を作る形は、DDS を読んで
ファイル記述と画面記述を作る形の**先例そのもの**である。

---

## 4. 同じ形では載らない部分

### 4.1 レコード単位アクセス (RLA) — `DataSet` では足りない

**ここが最大の設計課題である。**

`cobol-runtime/file` の `DataSet` は次の 6 つしか持たない。

```java
String open(OpenMode requested, boolean optional);
String read(byte[] into);
String write(byte[] from);
String rewrite(byte[] from);
String close();
String delete();   // KeyedDataSet
```

`IndexedDataSet` は副キー (`alternates`) と `readKey` を持っており、COBOL の索引編成には足りる。
RPG が要求するのはこれより**位置づけとロックに踏み込んだ意味論**である。

| RPG の操作 | 要る意味論 | `DataSet` にあるか |
| :--- | :--- | :--- |
| `CHAIN` | 鍵で 1 件取得。`%FOUND` | △ `readKey` に近い |
| `SETLL` / `SETGT` | **レコードを読まずに位置だけ決める**。`%EQUAL` | **× 無い** |
| `READE` / `READPE` | 位置から**同じ鍵の間だけ**読み進める | **× 無い** |
| `READP` | 逆方向に読む | **× 無い** |
| `UPDATE` 前提の読取りロック | 読んだ時点でレコードを**排他で押さえる** | **× 無い** |
| `UNLOCK` | 更新せずにロックだけ外す | **× 無い** |
| `%EOF` / `%ERROR` / `%STATUS` | ファイル状態の RPG 版 | △ `FileStatus` の対応表が要る |

そして、この層を**関係データベースの上にどう写すか**が難所である。

- カーソルの位置づけと `SETLL` の「読まずに位置だけ決める」は JDBC で素直に表現できない
- レコードロックは分離レベルではなく**行に対する明示的な保持**であり、
  次の `UPDATE` か `UNLOCK` まで続く
- 並行更新下で `READE` の位置がどうなるかは、**実機で測る以外に決めようがない**

したがって次の形を採る。

1. **RLA を中立ポートとして定義する** (`cobol-db2` の `SqlExecutorPort` と同じ立て方)
2. **最初の実装を `IndexedDataSet` の裏で書く**。関係データベースを持ち込む前に、
   位置づけとロックの意味論だけを固定して測れるようにする
3. **その後 JDBC 裏の実装を足し、実機と突き合わせて差を数える**

この順にするのは、CLAUDE.md §2 の「1 度測って終わりにしない」を成立させるためである。
意味論の誤りと JDBC の写し方の誤りを、別々に切り分けられる状態を先に作る。

### 4.2 RPG サイクルと指示器 — 言語ではなく実行モデル

RPG は固定論理サイクル (読む → 全体処理 → 明細処理 → 出力 → 繰り返す) を持ち、
`*INLR` を立てると終わる。`*IN01`〜`*IN99`、`*INLR`、`*INOF` といった指示器は
**プログラム全体で共有される状態**であり、条件と出力の両方を駆動する。

これは構文の話ではなく実行モデルの話なので、**ランタイム側に置く**。
「意味論はランタイムが持ち、生成コードは持たない」という方針がここでも効く。

現代の資産は `*NOMAIN` の手続き型と線形主処理が中心であり、サイクルを使うのは
古い固定形式の資産である。したがって**サイクル無しから始め、あとで足す**。

### 4.3 ILE — 決定 0002 のバイト志向の呼出し契約が足りない

決定 0002 の呼出し契約は「引数は `DataView` の並び、すべて参照渡し」である。
COBOL の `CALL ... USING` と z の標準リンケージにはこれで足りた。

ILE はそうではない。

| ILE の要素 | 内容 |
| :--- | :--- |
| プロトタイプ付き呼出し (`DCL-PR` / `DCL-PI`) | 型と個数が翻訳時に検査される |
| `VALUE` / `CONST` | **値渡しがある**。`DataView` の並びでは表現できない |
| `OPTIONS(*NOPASS : *OMIT : *VARSIZE)` | 引数が省略されうる。`%PARMS` で数を見る |
| サービスプログラム (`*SRVPGM`) と束縛 | 共有ライブラリに相当。束縛ディレクトリで解決する |
| 活性化グループ | 資源とコミットメント制御の有効範囲を決める |
| 戻り値 (`DCL-PI name type`) | COBOL の `CALL` には無い |

「参照渡しだけ」という前提を緩める必要があり、これは既存の COBOL / PL/I 側にも
波及しうる。**先に境界を決めてから書く**べき箇所である。

活性化グループは、CICS のタスクや IMS の従属領域と同じく「資源の有効範囲」であるから、
`cobol-cics` / `cobol-ims` が持つ資源ポートの立て方が参考になる。

### 4.4 5250 と表示ファイル — サブファイルが重い

`cobol-cics` の BMS は 3270 を対象にしており、`BmsMacroReader` / `BmsModel` /
`BmsScreenComposer` / `BmsInputDecoder` / `BmsAttributeCodes` という分け方は
5250 でもそのまま使える。違うのは中身である。

| | 3270 (BMS) | 5250 (DDS 表示ファイル) |
| :--- | :--- | :--- |
| 記述 | `DFHMSD` / `DFHMDI` / `DFHMDF` マクロ | DDS の記述 (桁で欄が決まる) |
| 単位 | マップセットとマップ | ファイルとレコード様式 |
| 属性 | 属性バイト | 表示属性キーワード (`DSPATR`) |
| 注意欄 | 別に置く | `ERRMSG` / `ERRMSGID` / メッセージ副ファイル |
| 一覧表示 | 自前で組む | **サブファイル (`SFL` / `SFLCTL`)** |
| 入出力 | `SEND MAP` / `RECEIVE MAP` | `EXFMT` / `WRITE` / `READ` (レコード様式単位) |

**サブファイルがこの領域を重くしている。**ページ送り、`SFLNXTCHG`、
`SFLRCDNBR` による位置づけ、`READC` による変更レコードだけの読取りは、
BMS に対応物が無い。`cobol-cics` が 16,945 行であることを踏まえると、
ここは同規模の仕事になる。

なお、Web への描画は `cobol-spring-boot-4-bms-thymeleaf` が既に
「中立画面モデル → 固定セル CSS + 端末 JavaScript」という形を持っているので、
中立モデルさえ 5250 用に作れば描画側は同じ道を通れる。

### 4.5 オブジェクトとライブラリのモデル

IBM i は `QSYS.LIB` の下にオブジェクトが並ぶ平坦な構造を持ち、
`*PGM` / `*SRVPGM` / `*FILE` / `*DTAARA` / `*DTAQ` / `*MSGQ` が同じ名前空間にいる。
プログラムとファイルの解決は**ライブラリリスト**で行われる。

| IBM i | 既存の対応物 |
| :--- | :--- |
| `*PGM` の解決 | `ProgramCatalog` (決定 0001) |
| `*FILE` の解決 | `DataSetCatalog` / `SystemCatalog` |
| ライブラリリスト | **無い。探索順を持つ解決器が要る** |
| `*DTAARA` (データ域) | 無い。小さい |
| `*DTAQ` (データ待ち行列) | `cobol-ims` の `MessageQueue` / `MessageQueueProvider` が近い |
| `*MSGQ` (メッセージ待ち行列) | 無い |

**ライブラリリストによる解決**は、既存のカタログに探索順を足す形で足りる見込みである。
データ待ち行列は `cobol-ims-jms` が RabbitMQ の裏で既に「中立キュー + 借用」を
実装しているので、同じ形が使える。

### 4.6 CL は JCL ではない

`cobol-job` の内部ジョブモデル (`Job` / `Step` / `JobRunner` / `DdAssignment` /
`StepCondition`) は再利用できる。しかし `JclReader` / `JclExpander` / `JclSymbols` は
使えない。CL は JCL とは別の言語である。

| | JCL | CL |
| :--- | :--- | :--- |
| 性質 | ジョブとステップの記述 | **手続き型の言語**。変数、`IF`、`DO`、`MONMSG` |
| 単位 | ジョブ / ステップ / DD | 命令 (`*CMD` オブジェクト) の呼出し |
| 翻訳 | しない | **コンパイルして `*PGM` になる** |
| 異常系 | 戻りコードと `COND` | **メッセージ監視 (`MONMSG`)** |
| ファイル結合 | DD 文 | `OVRDBF` (一時変更) |

CL が `*PGM` になるということは、CL も**フロントエンドの 1 つ**だということである。
RPG と同じ骨格 (薄い殻 + ランタイムが意味論を持つ) で載る。
命令 (`CRTPF`, `CPYF`, `DSPFD` …) の一つ一つは、`cobol-job` の `Utilities` が
`IEBGENER` / `IDCAMS` / `DFSORT` を載せているのと同じ形で足していける。

---

## 5. 外の基準 — エミュレータは無いが、オラクルは有る

CLAUDE.md の §1 は「外の基準を先に置く」である。この条件が、z 側と i 側で決定的に違う。

| | PL/I | HLASM | **IBM i** |
| :--- | :--- | :--- | :--- |
| 公開仕様 | ISO 6160:1979 + IBM 拡張 | z/Architecture PoP | **IBM の公開文書。言語規格は無い** |
| 適合性検査スイート | 存在しない | 存在しない | **存在しない (確認済み)** |
| 実行オラクル | IBM Enterprise PL/I (手元に無い) | **Hercules (配線済み)** | **エミュレータは無い。実機には届く (確認済み)** |
| 期待値の作り方 | 規格を読んで自分で書く | 命令を Hercules で採る | **実機から採る** |

**Hercules に相当する AS/400 エミュレータは公開されていない。**MI (TIMI) から上が非公開であり、
IBM は趣味の利用者に OS の使用許諾を出していない。OS の仮想化は POWER5 以降の IBM の機械の
上でのみ提供される。これは実装努力で埋められる種類の欠落ではない。

CLAUDE.md §3 の警告がそのまま当てはまる。

> **自分で書いた規則は、自分のテストでは破れない。**

### 5.1 未確認事項を調べた結果 (2026-09-22)

初版で「未確認」と書いた 4 つを調べた。**2 つは良い知らせ、1 つは訂正、1 つは確認である。**

| 調べたこと | 結果 |
| :--- | :--- |
| 公開されている IBM i 環境 | **ある。pub400.com。**ただし利用条件に注意が要る (§5.2) |
| NIST SQL Test Suite (FIPS 127-2) | **訂正。NIST はもう配布していない** (§5.3) |
| RPG の適合性検査スイート | **確認。存在しない** (§5.4) |
| AS/400 エミュレータ | **確認。存在しない。**ただし実機には届くので、結論が変わる (§5.2) |

**結論が 1 つ動いた。**初版は「外の基準に届かないなら PL/I と同じ等級になる」と書いたが、
**実機には届く。**したがって IBM i 対応は、PL/I より**強い**位置から始められる。
PL/I の弱さは「参照処理系が手元に無い」ことだったが、IBM i では参照処理系そのものに
アクセスできる。エミュレータが無いことは、**オラクルが無いことを意味しない。**

### 5.2 実機には届く — pub400.com と Power Virtual Server

| | pub400.com | IBM Power Virtual Server |
| :--- | :--- | :--- |
| 形態 | 共用の公開 IBM i サーバ | IBM Cloud 上の専用 LPAR |
| 費用 | 無料 | 新規利用者向けに PowerVS2500 (2,500 米ドル相当の与信、最長 90 日) |
| 権限 | `*PGMR` | LPAR の管理権限 |
| 記憶域 | 250MB | 構成による |
| 翻訳系 | **RPG / COBOL / CL / C / Java / Python / Node.js / PHP** | 構成による |
| データベース | ネイティブ (RLA) と SQL の両方 | 同左 |
| 期限 | 無し | 与信の範囲 |

**pub400.com は期待値を採るのに十分な機能を持っている。**RPG の翻訳系があり、
ネイティブ (レコード単位) と SQL の両方でデータベースに触れる。§4.1 で
「実機で測る以外に決めようがない」と書いた RLA の意味論は、ここで測れる。

**ただし利用条件を守る必要がある。**公開されている条件は次のとおりである。

- システム値の変更、利用者の作成、一般のシステム設定の変更はできない
- **「システムを攻撃したり過負荷をかけたりしないこと」**
- **「RZKH の事前の書面による通知なしに商用利用してはならない」**
- 同一の個人・組織で複数の口座を持ってはならない

ここから、**このプロジェクトの使い方に対する制約が 2 つ出る。**

1. **自動採取を回すなら、先に許可を取る。**`cobol-oracle` が Hercules に対してやっているような
   「試験ごとに起動して期待値を採る」形は、共用機では**過負荷になりうる**。
   採取の頻度と量を決めてから問い合わせる
2. **商用利用の線を確かめる。**本プロジェクトは Apache-2.0 の公開物であり商用製品ではないが、
   「商用利用」の解釈は先方が持つ。**こちらで決めない**

したがって次の形を採る。

- **探索と少量の採取は pub400.com で行う**
- **量のある採取と回帰は Power Virtual Server の専用 LPAR で行う**。専用機なら
  共用機の制約を受けず、採取の自動化も自分の責任の範囲に収まる
- **採取した期待値はリポジトリへ焼き込み、以後はオラクル無しで回帰できるようにする**。
  要件 FR-212 が Hercules に対して決めた「採取モードと回帰モードを分ける」形をそのまま使う

### 5.3 訂正 — NIST SQL Test Suite はもう入手できない

**初版の記述は誤っていた。**「公開されていると承知している」と書いたが、
**NIST は既に配布を止めている。**

次の 3 つの URL はいずれも `https://www.nist.gov/itl/` へ 302 で転送され、内容が残っていない。

- `https://www.itl.nist.gov/div897/ctg/sql_form.htm` (取得の入口)
- `https://www.itl.nist.gov/div897/ctg/dm/sql_info.html` (説明)
- `https://www.itl.nist.gov/div897/ctg/sql-testing/sqlman60.htm` (版 6.0 の利用者案内)

残っているのは利用者案内の PDF (NISTIR 5998) だけである。**道具の説明書はあるが、道具が無い。**

版 6.0 は 1996 年 12 月 31 日に完成し、FIPS PUB 127-2 の Transitional SQL を対象とする。
NIST・英 NCC・ギリシャの Computer Logic R&D の共同で作られた。

代わりに置ける候補は次のとおりである。

| 候補 | 内容 | 使えるか |
| :--- | :--- | :--- |
| **`elliotchance/sqltest`** | SQL:2016 (Part 2) の BNF から検査を生成する。**MIT** | **使える。**生成元が規格の BNF なので、由来が明確である |
| NISTIR 5998 | 版 6.0 の利用者案内 PDF | 検査そのものは無い。**何を測るべきかの手がかりにはなる** |
| 既存の Db2 試験 | このリポジトリが既に持っている | **増分 1 で最初に使うのはこれである** |

`elliotchance/sqltest` が MIT であることは重要である。Hercules (QPL) に対して
「外から呼ぶだけ、ソースを読んで写さない」という線を引いたのと違い、**これは読んでよい**。

ただし注意が要る。これは **NIST の適合性検査ではなく、規格の BNF から機械的に生成した
検査**である。したがって「適合性検査スイートに通った」とは言えない。
**言えるのは「この検査に通った」までである。**言い方を混ぜない。

### 5.4 確認 — RPG の適合性検査スイートは存在しない

NIST の Validated Products List が対象にしていた言語は **COBOL、Fortran、Ada、Pascal、C、
M (MUMPS)、および データベース言語 SQL** である。**RPG は入っていない。**
ANSI / ISO の RPG 言語規格も見つからなかった。

したがって RPG の「公開仕様」として使えるのは **IBM が公開している ILE RPG の言語解説書
(SC09-2508)** である。これは IBM Documentation から版ごとに PDF で公開されている。

この位置づけは、PL/I とも HLASM とも違う。

| | 公開仕様の性質 |
| :--- | :--- |
| HLASM | **規格に相当する公開文書** (Principles of Operation) がある |
| PL/I | 規格 (ISO 6160:1979) はあるが、**参照処理系は規格 + IBM 拡張**なので同じ数で扱えない |
| **RPG** | **規格は無い。あるのは IBM の公開文書だけ。**つまり「規格への適合」は最初から測れない。測れるのは**実機との一致**だけである |

**これは制約であると同時に、単純化でもある。**COBOL では「規格に合っているか」と
「IBM と合っているか」を分けて数える必要があった (CLAUDE.md §3 の表がまさにそれである)。
RPG にはその 2 本立てが無い。**基準は実機 1 つである。**

### 5.5 先行事例 — Jariko (Apache-2.0)

調査の過程で、**JVM 上の RPG 処理系が既に存在する**ことが分かった。

| 項目 | 内容 |
| :--- | :--- |
| 名前 | `smeup/jariko` (JAva virtual machine Rpg Interpreter written in KOtlin) |
| ライセンス | **Apache-2.0** |
| 方式 | **構文木を歩く実行器** (ANTLR4 で構文解析)。翻訳系ではない |
| 実装済み | **RLA (`READ` / `WRITE` / `CHAIN` / `SETLL` 等)**、データ域、利用者空間 |
| **実装しないと明言** | **DDS、サブファイル、CL、DDM ファイル、複数メンバのファイル、OS/400 のシステム API** |
| 出自 | Sme.UP (伊) が自社製品を移行するために作っている |

**この先行事例は、このレポートの判断を 2 つ裏づけている。**

1. **方式**: RPG に対して実際に採られているのは「構文木を歩く実行器」である。
   `PliRuntime.Executor` と同じ形であり、§3.1 と増分 3 の方針と一致する
2. **難所の位置**: Jariko が「実装しない」と明言したものが、**DDS・サブファイル・CL**である。
   これは §4.4 と §4.6 で重いと書いた箇所と**完全に一致する**。
   独立した実装者が同じ場所で線を引いている

Apache-2.0 であることは、Hercules (QPL) との決定的な違いである。**読んでよい。**
ただし CLAUDE.md の決めごと「IBM 製品のソースは参照しない」は別の話として残る。
Jariko は IBM の製品ではないので、参照してよい。

**なお、この事実はこのプロジェクトの位置づけを変えない。**Jariko は RPG 単体の実行器であり、
DDS・サブファイル・CL・SQL・ジョブ・Db2 for i を含む「オフコンの面を再現する」という
問いには答えていない。**むしろ、答えていない部分がそのままこのプロジェクトの範囲である。**

### 5.6 実資産と外部コーパス

公開されている RPG の資産はある。いずれも**ライセンスを個別に確かめてから使う**。

- `SJLennon/IBM-i-RPG-Free-CLP-Code` — 自由形式 RPGLE、埋込み SQL、CL の実例
- `patriciocostilla/rpg-examples`、`richardschoen/rpgclcodingexamples`
- GitHub の `rpgle` / `as400` のトピック
- IBM の `rpg-genai-data`

ただし**これらは「小さな実例」であって Bank-of-Z のような一貫した業務資産ではない。**
§10 の未決事項「通すべき資産は何か」は、これでは解決しない。
**Bank-of-Z の IBM i 版を自分で作るのが、いちばん確実である。**

### 5.7 届くことが分かったので、測り方を先に決める

実機に届くと分かった以上、`cobol-oracle` が Hercules を包んだのと同じ形で包める。
`HerculesRunner` が「バージョン番号ではなく能力で検査し、使えない理由を握りつぶさない」
(`Detection.Unavailable`) 形を持っているのは、そのまま手本になる。

要件 FR-212 の「採取モードと回帰モードを分ける」も、そのまま効く。実機は常に手元に
あるとは限らないので、**採取した期待値を焼き込んでオラクル無しで回帰できる形**が要る。
これは共用機の負荷を下げることにも直結する (§5.2)。

### 5.8 守るべき線

1. **V2 の言い方を守る。**要件 4.2 の V2 は「Hercules と一致」であって「実機と一致」ではない。
   **IBM i では実機そのものから採るので、z 側より一段強い。**この差は書き分ける。
   「Hercules と一致」と「IBM i の実機と一致」を同じ V2 の語で並べない
2. **出典の線を守る。**IBM 製品のソースは参照しない。公開仕様 (ILE RPG 言語解説書、
   DDS 解説書) と**外から観測した入出力**だけを根拠にする。実機に触れるということは、
   **観測はできるが中は見ない**ということである
3. **先行実装の扱いを分ける。**Jariko は Apache-2.0 なので読んでよい。Hercules (QPL) とは
   立場が違う。ただし読んだ場合はその旨を NOTICE に書く
4. **共用機に負荷をかけない** (§5.2)。自動採取は事前に許可を取るか、専用 LPAR で行う
5. **コーパスを同梱しない** (要件 NFR-042)。取得元・版・SHA-256 を manifest に固定する
6. **`elliotchance/sqltest` に通ったことを「適合性検査に通った」と言わない** (§5.3)

---

## 6. H2 を拡張しない理由

問いの後半「H2 を拡張して Db2 を実装する」について、採らない理由を分けて書く。

### 6.1 事実確認 — H2 はこのリポジトリの Db2 実装ではない

`grep` で確かめられる。**H2 が現れるのはすべて test スコープである。**

- `cobol-db2-jdbc`、`cobol-ims-rdb`、`cobol-spring-boot-4-autoconfigure` の `pom.xml` で
  test スコープの依存として入っている
- 本体の Db2 は中立ポート (`SqlExecutorPort` / `UnitOfWorkPort` / `Db2ExecutionProfile`) であり、
  方言差は `ImsSchema.Dialect` が持つ
- 実 Db2 と実 PostgreSQL で測って裏を取っている (「PostgreSQL の置き場を実サーバで測り、
  未検証だった方言の枝を裏づける」「IMS の置き場に Db2 の方言を入れ、実 Db2 で Bank-of-Z を
  流して測る」)

### 6.2 根拠は既にコードの中に書き残されている

```java
// cobol-ims-rdb/src/main/java/dev/cobolonjava/ims/rdb/JdbcDatabaseStore.java:683
// H2 と PostgreSQL は黙って巻き戻すので、この差は Db2 で測って初めて出た。
```

**H2 は Db2 の振る舞いを隠した。**H2 を Db2 に「育てる」と、この 1 行が見つけた種類の差は
二度と出なくなる。測る道具が測られる側になるからである。

これは CLAUDE.md §6 が警告する形の裏返しである。§6 は「検査の道具が、処理系の失敗を
作ってはならない」と書いているが、より危ないのは**道具が処理系の成功を作る**ことである。
HLASM レポート §5.2 が「自分のアセンブラが誤った機械語を作ると、Hercules も自分の
インタプリタも同じ誤った機械語を実行し、結果が一致してしまう」と書いたのと同じ形である。

### 6.3 ライセンス (2026-09-22 に原文で確認)

H2 の公式のライセンス説明は次のとおりである。

> H2 is dual licensed and available under the MPL 2.0 (Mozilla Public License Version 2.0)
> or under the EPL 1.0 (Eclipse Public License).

そして**改変したときの義務が明記されている**。

> **Modifications to the H2 source code must be published.**
>
> (You don't need to provide the source code of H2 if you did not modify anything.)

依存として使う分には問題がない。**改変した瞬間に義務が生じる。**
「H2 を拡張して Db2 for i にする」は、まさにこの改変にあたる。

NOTICE が掲げている「公開仕様と観測できる振る舞いだけを根拠にした独立実装」という
立て方と相性が悪い。Hercules を QPL であるという理由で「外から呼ぶだけ、ソースを読んで
写さない」と決めたのと同じ判断が、ここでも要る。

### 6.4 そもそも不要である

§3.4 に書いたとおり、IBM i のデータベースは下が関係データベースである。
**Db2 for i の SQL 面は、既存の Db2 ポートに方言を 1 つ足す作業**であり、
データベースエンジンを書く作業ではない。

書く必要があるのは SQL エンジンではなく **RLA 層** (§4.1) である。
そしてそれは H2 の内側ではなく、**ポートとして外側に**置くべきものである。
そうすれば `IndexedDataSet` 裏と JDBC 裏の 2 つの実装を並べて測れる。

### 6.5 代わりに採る形

| やらない | 代わりに |
| :--- | :--- |
| H2 を fork して Db2 for i にする | `Dialect` に `DB2_FOR_I` を足す。既存の Db2 試験を i で流して差を数える |
| H2 の内側に RLA を実装する | RLA を中立ポートとして定義し、実装を差し替え可能にする |
| H2 を Db2 の代用として測定に使い続ける | H2 は**引き続き test スコープの土台**として使う。ただし数の裏付けには使わない |

---

## 7. 規模の見当

| 層 | 既存の実績 | IBM i の見込み |
| :--- | ---: | :--- |
| RPG フロントエンド | PL/I 約 1,900 行 / COBOL 51,674 行 | **両者の中間**。固定形式と自由形式の 2 つ、`/COPY`、SQL 前処理 |
| RLA の意味論層 | `cobol-ims-rdb` 2,053 行 | 同程度。位置づけとロックが中心 |
| 5250 と表示ファイル | `cobol-cics` 16,945 行 | **同規模**。サブファイルが重い |
| CL | `cobol-job` 16,853 行 | 言語部分は小さい。命令の数が効く |
| ランタイム拡張 | `cobol-runtime` 18,768 行 | **小さい**。10 進と編集は既にある |
| 測る道具 | `cobol-verify` 5,904 行 | 同形 + 実機との突き合わせ |

**「同じ手法」ではあるが「同じ規模」ではない。**HLASM レポート §6 と同じ結論になる。
最も大きいのは 5250 であり、最も設計が要るのは RLA である。

---

## 8. 進め方の提案

CLAUDE.md の増分の型に従い、**測る道具を先に置く**。

| 増分 | 内容 | 終わりの判定 |
| :--- | :--- | :--- |
| **0** | **外の基準の目処を立てる** | **済 (§5)。**実機に届くことが分かった。NIST SQL Test Suite は入手できないと分かった |
| **0b** | **実機への口を作る。**pub400.com の口座を取り、採取の可否と頻度を先方に確認する。量のある採取は Power Virtual Server へ寄せる | 手で 1 本、RPG を翻訳して流せる |
| **0c** | **通すべき資産を 1 本決める** (§10)。公開資産は「小さな実例」しかないので、Bank-of-Z の IBM i 版を作るのが確実 | subset の境界が決まる |
| **1** | `cobol-db2` に `DB2_FOR_I` 方言を足し、**既存の Db2 試験を i で流して差を数える**。H2 は触らない | 差が数で出る |
| **2** | **RLA ポートを中立に定義**し、まず `IndexedDataSet` 裏で実装する。`SETLL` / `SETGT` / `CHAIN` / `READE` / ロック | 位置づけとロックの意味論が固定される |
| **3** | `rpg-compiler` を PL/I と同じ骨格で足す。**サイクル無しの `*NOMAIN` / 手続き型サブセットから** | RPG 1 本が翻訳・実行できる |
| **4** | 埋込み SQL (`SQLRPGLE`)。既存の Db2 ポートへ着地させる | PL/I の `EXEC SQL` と同じ形になる |
| **5** | RLA の JDBC 裏実装。増分 2 で固定した意味論を、関係データベースの上で満たす | 2 つの実装が同じ数を出す |
| **6** | DDS 物理ファイル・論理ファイルの記述を読む | ファイル定義が原文から作れる |
| **7** | RPG サイクルと指示器 | 固定形式の古い資産が通る |
| **8** | ILE (プロトタイプ、`VALUE`、サービスプログラム、活性化グループ) | 決定 0002 の拡張として記録する |
| **9** | 5250 と表示ファイル。サブファイルは別増分に分ける | 中立画面モデルができる |
| **10** | CL | `*PGM` として配備カタログに載る |

増分 1 を実装の先頭に置くのは、**既にある資産で、既にある道具で、すぐ数が出る**からである。
増分 2 を増分 3 より先にするのは、RPG の文法より RLA の意味論のほうが決めるのが難しく、
先に固定しておかないとフロントエンドの形が決まらないからである。

**増分 0b を 0c より先にするのは、実機に触れないと 0c の判断材料が揃わないからである。**
どの機能が実際に使われているかは、公開されている小さな実例より、実機で動く形を
1 本作ってみるほうが早く分かる。

---

## 9. 最初から「書けない」と断ると決めるもの

「近い値を黙って返すくらいなら、書けないと断る」。以下は最初から断ると決めておく。

| 範囲 | 理由 |
| :--- | :--- |
| 単一レベル記憶に依存するポインタ意味論 (空間ポインタの永続化) | 記憶モデルの前提そのものが違う |
| MI 命令、ライセンス内部コード | 非公開であり、観測もできない |
| オブジェクト権限、ユーザプロファイル、監査 | 実行環境の前提が違う。安全側に倒すなら実装しないほうがよい |
| 作業管理 (サブシステム記述、経路指定項目、ジョブ待ち行列の優先度) | 資産の業務結果に現れない |
| 保管復元形式 (SAVF)、オブジェクト変換 | 移行の道具であって実行系ではない |
| 浮動小数点 (`FLOAT`) | 桁の規則を突き合わせていない。COBOL 側でも同じ理由で慎重に扱っている |
| 時刻の値そのものに依存する資産 | 固定できる値に載らない限り断る |
| RPG の `/FREE` 以前の完全な固定形式 (C 仕様書の全演算) | 資産を見てから範囲を決める |

---

## 10. 未決の問い

### 10.1 初版の未決事項のうち、解けたもの

| 問い | 答え |
| :--- | :--- |
| 実機に届くか | **届く** (§5.2)。pub400.com は無料、RPG 翻訳系あり、RLA と SQL の両方に触れる |
| NIST SQL Test Suite は入手できるか | **できない** (§5.3)。`elliotchance/sqltest` (MIT) が代替候補 |
| RPG の適合性検査スイートはあるか | **無い** (§5.4)。規格自体が無いので、基準は実機 1 つになる |
| AS/400 エミュレータはあるか | **無い** (§5)。ただし実機に届くので、結論は変わらない |
| IBM i の既定 CCSID | `QCCSID` の既定は **65535 (変換しない)** (§3.2)。設計 80 のサイドカー方式と合う |

### 10.2 残っている最大の未決事項

**通すべき資産は何か。**PL/I には `BNKSTMT.pli` と `IBLOGIN.pli` という具体があり、
**その 2 本が subset の境界を決めた**。HLASM レポート §9 が「対象資産が無い」ことを
最大の未決事項に挙げたのと同じ問題が、IBM i にもある。

§5.6 で公開資産を探したが、**見つかるのは「小さな実例」だけ**であり、
一貫した業務資産ではない。したがってこの問いは**調査では解けない**。
Bank-of-Z の IBM i 版を作るか、資産を持っている相手を見つけるかである。

### 10.3 新しく出た未決事項

- **pub400.com で自動採取をしてよいか。**「過負荷をかけない」「商用利用は事前の書面による
  通知が要る」という条件がある (§5.2)。**こちらで解釈を決めず、先方に訊く**
- **Jariko を読むか。**Apache-2.0 なので読んでよいが、読むなら NOTICE に書く (§5.5)。
  読まずに独立に書くという選択もある。**先に決める**
- **`elliotchance/sqltest` を使うか。**MIT で由来は明確だが、NIST の適合性検査ではない。
  使うなら数の呼び方を分ける (§5.3)

### 10.4 そのほか

- RPG III (固定形式) と RPG IV (自由形式) のどちらを先にするか。資産で決まる。
  なお Jariko は両形式を扱っている
- DDS と SQL DDL のどちらでファイルを定義するか。両方あるが、先に片方へ寄せるべきである
- 論理ファイル (キー順、選択/除外、結合) を索引として写すか、ビューとして写すか
- コミットメント制御の前提であるジャーナルを、どこまで再現するか
- 既存の `cobol-cics` / `cobol-ims` と同居させるか、別のトップレベルとして分けるか
- モジュール名をどうするか (`rpg-compiler` / `ibmi-runtime` / `cobol-db2` の拡張)

---

## 11. まとめ

問い「この処理系を基盤に IBM のオフコンを再現できるか」への答え。

**骨格は載る。**新モジュール 1 つ、薄い殻、既存 ABI への着地、意味論はランタイムが持つ、
`cobol-verify` で外から測る、数を門にしない — これらはそのまま成り立つ。
10 進演算・編集・EBCDIC・記憶モデルは**ほぼ無変更で効く**。
そして Db2 のポートは、IMS のときより**相性が良い**。IBM i のデータベースは下が関係
データベースであり、決定 0013 のような専用格納エンジンは要らない。

**問いの後半 (H2 を拡張して Db2 にする) は採らない。**H2 はこのリポジトリでは test スコープの
土台であって Db2 の実装ではない。そして `JdbcDatabaseStore.java:683` が記録しているとおり、
**H2 は Db2 の振る舞いを隠した実績がある**。育てれば測る道具が 1 つ消える。
必要なのは SQL エンジンではなく RLA 層であり、それは H2 の内側ではなくポートとして外側に置く。

**外の基準については、調べた結果で結論が 1 つ動いた。**Hercules に相当する AS/400
エミュレータは**やはり存在しない**。しかし**実機には届く** (pub400.com は無料で RPG 翻訳系と
RLA を持ち、Power Virtual Server は専用 LPAR を貸す)。**エミュレータが無いことと、
オラクルが無いことは別である。**PL/I の弱点は参照処理系が手元に無いことだったが、
IBM i ではそれに触れる。したがって IBM i 対応は、初版に書いた「PL/I と同じ等級」ではなく
**PL/I より強い位置から始められる**。

**代わりに、初版の記述を 1 つ訂正した。**NIST SQL Test Suite (FIPS 127-2) は
**もう配布されていない**。NIST の 3 つの URL はいずれも内容ごと消えており、
残っているのは利用者案内の PDF だけである。代替は `elliotchance/sqltest` (MIT) だが、
**これは規格の BNF から生成した検査であって適合性検査ではない**ので、言い方を分ける。

**そして先行事例が 1 つ見つかった。**`smeup/jariko` は Apache-2.0 の JVM 上の RPG 実行器で、
構文木を歩く方式、RLA 実装済み、**DDS・サブファイル・CL は実装しないと明言**している。
独立した実装者が、このレポートが重いと判断した場所と**同じ場所で線を引いている**。
これは §4.4 / §4.6 の判断の裏づけであると同時に、**線の向こう側がこのプロジェクトの
範囲である**ことを示している。

**次にやること**は、増分 0b (実機への口を作り、採取の可否を先方に確認する) と
増分 0c (通すべき資産を 1 本決める) である。**残っている最大の未決事項は資産であり、
これは調査では解けない。**

---

## 12. 出典

§5 と §3.2、§6.3 の確認に使ったもの (2026-09-22 に参照)。

**実機**

- [PUB400.COM — your public IBM i server](https://pub400.com/whatdo.html) — `*PGMR` 権限、250MB、期限なし、RPG / COBOL / CL / C / Java / Python / Node.js / PHP、ネイティブと SQL の両方。商用利用と過負荷の禁止
- [PUB400: Your Free IBM i Playground (IT Jungle, 2024)](https://www.itjungle.com/2024/03/04/pub400-your-free-ibm-i-playground/)
- [Getting started with IBM Power Virtual Server](https://www.ibm.com/docs/SS6QJ7/getting-started.html) — IBM Cloud 上の AIX / IBM i の LPAR
- [PowerVS2500: Try IBM Power Virtual Server free (IBM Community, 2026-01)](https://community.ibm.com/community/user/blogs/val-besong/2026/01/13/powervs2500-try-ibm-power-virtual-server-free-for) — 新規利用者に 2,500 米ドル相当の与信、最長 90 日

**エミュレータが無いこと**

- [IBM AS/400 (Wikipedia)](https://en.wikipedia.org/wiki/IBM_AS/400) — TIMI の位置づけ
- [Your own AS/400 (Try-AS/400 wiki)](https://try-as400.pocnet.net/wiki/Your_own_AS/400) — 一般の機械での全体エミュレーションは存在しない。OS の仮想化は POWER5 以降の IBM の機械の上だけ

**SQL の検査**

- `https://www.itl.nist.gov/div897/ctg/sql_form.htm` — **`https://www.nist.gov/itl/` へ 302。内容は残っていない**
- `https://www.itl.nist.gov/div897/ctg/dm/sql_info.html` — **同上**
- `https://www.itl.nist.gov/div897/ctg/sql-testing/sqlman60.htm` — **同上**
- [User's Guide for the SQL Test Suite, Version 6.0 (NISTIR 5998)](https://nvlpubs.nist.gov/nistpubs/Legacy/IR/nistir5998.pdf) — 利用者案内の PDF だけが残っている
- [elliotchance/sqltest](https://github.com/elliotchance/sqltest) — SQL:2016 の BNF から検査を生成する。MIT

**RPG の規格と仕様**

- [Validated Products List (NISTIR 5731)](https://csrc.nist.gov/pubs/ir/5731/final) — 対象言語は COBOL / Fortran / Ada / Pascal / C / M (MUMPS) / SQL。**RPG は無い**
- [ILE RPG Language Reference (SC09-2508)](https://www.ibm.com/docs/it/ssw_ibm_i_76/pdf/sc092508.pdf) — IBM が版ごとに公開している言語解説書
- [IBM RPG II](https://en.wikipedia.org/wiki/IBM_RPG_II) / [IBM RPG III](https://en.wikipedia.org/wiki/IBM_RPG_III) — ANSI / ISO の言語規格は見つからなかった

**先行事例**

- [smeup/jariko](https://github.com/smeup/jariko) — Apache-2.0。Kotlin で書かれた JVM 上の RPG 実行器。RLA・データ域・利用者空間を実装。**DDS / サブファイル / CL / DDM / 複数メンバ / OS-400 のシステム API は実装しないと明言**
- [JaRIKo, an RPG Interpreter in Kotlin (Strumenta)](https://tomassetti.me/jariko-an-rpg-interpreter-in-kotlin/)

**CCSID**

- [IBM i: Coded character set identifier (QCCSID) system value](https://www.ibm.com/docs/en/i/7.2.0?topic=values-coded-character-set-identifier-qccsid-system-value) — 既定は 65535
- [CCSID values defined on IBM i](https://www.ibm.com/docs/en/i/7.4.0?topic=information-ccsid-values-defined-i)

**H2**

- [H2 License](http://www.h2database.com/html/license.html) — MPL 2.0 / EPL 1.0 のデュアル。「Modifications to the H2 source code must be published.」

**RPG の公開資産 (ライセンスは個別に確認が要る)**

- [SJLennon/IBM-i-RPG-Free-CLP-Code](https://github.com/SJLennon/IBM-i-RPG-Free-CLP-Code)
- [patriciocostilla/rpg-examples](https://github.com/patriciocostilla/rpg-examples)
- [richardschoen/rpgclcodingexamples](https://github.com/richardschoen/rpgclcodingexamples)
