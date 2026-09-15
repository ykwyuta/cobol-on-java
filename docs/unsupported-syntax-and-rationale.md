# 未対応構文とその理由 (Unsupported Syntax and Rationale)

本書は、`cobol-on-java` において現時点で**サポートしていない構文（未対応構文・文脈・句・オプション）**および、それらをサポートしていない**理由・設計判断・将来の対応方針**をまとめたドキュメントです。

---

## 1. 互換性と未対応方針の基本原則

本プロジェクトでは要件定義書（[docs/requirements.md](requirements.md)）およびアーキテクチャ方針に基づき、以下の原則を厳格に順守しています。

1. **未対応構文を黙って無視しない（Fail-Closed 原則 / FR-181）**:
   - 構文解析器や意味解析器で未実装の句や文に遭遇した場合、無視して処理を継続するのではなく、**コンパイルエラー（致命的診断メッセージ）を出して明示的にコンパイルを停止**します。
   - 理由: 黙って無視すると、実行時に見当違いの計算結果や意図しないデータ破壊を招き、基幹業務移行において最も発見が困難なサイレント障害となるためです。
2. **参照実装（Enterprise COBOL for z/OS 6.x）をベースラインとする（決定 D-12）**:
   - 参照実装ですでに廃止・削除された古い構文（OS/VS COBOL 世代）は、独自拡張として復活させず原則として未対応（互換性レベル L0）とします。
3. **推測による中途半端な実装を行わない（CLAUDE.md / 決定 D-11b）**:
   - 「仕様や桁数の裏付け（Hercules 等の実機オラクルや規格）がないまま思い込みで近い値を返す」ことは行いません。仕様とオラクルが揃うまで確実にエラーとして報告します。

---

## 2. カテゴリ別未対応構文一覧

### 2.1 見出し部・環境部 (IDENTIFICATION & ENVIRONMENT DIVISION)

| 未対応構文 / 項目 | 構文例 | 未対応の理由・設計判断 | 今後の方針・暫定対応 |
| --- | --- | --- | --- |
| **複数の通貨記号 (`PICTURE SYMBOL`)** | `CURRENCY SIGN IS '€' WITH PICTURE SYMBOL 'E'` | 単一の `CURRENCY SIGN IS '文字'` にのみ対応。複数種類の通貨記号を同時にテーブル管理する字句解析・PICTURE 解析器が未実装（P-010）。 | 表形式で複数の通貨記号を保持・マッピングする拡張を予定。 |
| **`OPEN INPUT ... REVERSED`** | `OPEN INPUT F REVERSED` | 磁気テープ等のファイルを末尾から逆順に読み出す指定。現代のディスクストレージやオブジェクトストレージでは物理的意味をなさず、黙って順読みすると誤った結果となるため、安全のため明示的に拒否（P-081, C-11）。 | サポート予定なし（拒否診断を維持）。 |
| **通信節 (`COMMUNICATION SECTION`)** | `COMMUNICATION SECTION.` | ホストの TCAM 等と連携する古い通信機能。現代の Web/REST/メッセージキュー環境では使用されず、Enterprise COBOL 6.x でも廃要素（C-5, 要件定義 11 章）。 | L0（恒久的に非対応。コンパイル時エラー）。 |

---

### 2.2 データ部 (DATA DIVISION)

| 未対応構文 / 項目 | 構文例 | 未対応の理由・設計判断 | 今後の方針・暫定対応 |
| --- | --- | --- | --- |
| **19〜31 桁の 2 進項目 (16 バイトバイナリ)** | `PIC S9(20) COMP-4`, `COMP-5` | 現在の 2 進整数（`COMP`, `COMP-4`, `COMP-5`）は 2, 4, 8 バイト（最大 18 桁 / `long` 範囲）まで対応。16 バイト整数の算術・ビット操作は JVM 上で 128 ビット整数型が存在しないため未対応（P-005）。 | `BigInteger` または独自 128 ビット整数演算器を導入して対応予定。 |
| **`USAGE POINTER` / `FUNCTION-POINTER` / `PROCEDURE-POINTER`** | `01 PTR USAGE POINTER.` | メモリアドレスを保持するポインタ型。JVM 上では生のメモリアドレスが存在せず、安全な論理参照モデルとの整合設計が未完了（P-006, C-2）。 | ランタイムのアドレス空間モデル（論理参照テーブル）の設計完了後に段階的導入予定。 |
| **`USAGE NATIONAL` / `DISPLAY-1` (UTF-16 / DBCS 項目)** | `01 N-DATA PIC N(10) USAGE NATIONAL.`<br>`01 G-DATA PIC G(10) USAGE DISPLAY-1.` | ホスト固有の DBCS（シフトアウト `0x0E` / シフトイン `0x0F` を伴う固定長バイト列）および UTF-16項目のアライメントとコード変換が未実装（P-006, FR-031）。 | 日本語コードページ拡張（P-002: IBM-1390/1399）とあわせてフェーズ 2 以降で対応予定。 |
| **66 レベル (`RENAMES` 句)** | `66 NEW-NAME RENAMES A THRU B.` | 既存項目の開始位置から終了位置までをまたいで別名定義する構文。記憶域の割り付け規則が通常の階層ツリーと異なり、参照解決器で未実装（設計 50, P-006）。 | `DataLayout` におけるクロス階層オフセット計算の拡張により対応予定。 |
| **入れ子プログラム (`NESTED PROGRAM`)** | `IDENTIFICATION DIVISION.` ... `PROGRAM-ID. INNER.` ... `END PROGRAM INNER.` | 単一ソース内にプログラムが並ぶ並列配置（`PROGRAM A ... END PROGRAM A. PROGRAM B ...`）には対応しているが、プログラムの内部に別のプログラムを入れ子定義する構文はスコープ解決が未実装（P-070）。 | 静的親プログラムのシンボル探索チェーンを実装して対応予定。 |

---

### 2.3 手続き部 (PROCEDURE DIVISION) - 一般文

| 未対応構文 / 項目 | 構文例 | 未対応の理由・設計判断 | 今後の方針・暫定対応 |
| --- | --- | --- | --- |
| **`INITIALIZE ... REPLACING` にデータ項目を指定する形** | `INITIALIZE WS-GRP REPLACING NUMERIC BY WS-VAL` | `INITIALIZE ... REPLACING ... BY 定数`（リテラル）はコンパイル時に初期化バイトイメージを事前生成して高速展開できるが、受取値が実行時変数の場合は静的イメージ展開が使えないため未対応（P-033）。 | 実行時に型ごとのループ転記を行うランタイムヘルパーを生成する方式へ拡張予定。 |
| **多次元表に対する `SEARCH ALL`** | `SEARCH ALL TAB(WS-I, WS-J)` | `SEARCH ALL` は 2 分探索を行うため、探索キーの位置を自身で計算する必要がある。外側の表の添字（多次元）を受け取って内側を探索するコード生成経路が未実装（設計 60）。 | 外側添字をキャプチャして 2 分探索のインデックス計算を行う機構を追加予定。 |
| **浮動小数点項目 (`COMP-1` / `COMP-2`) の除算・べき乗・`ADD` 等の文・`ON SIZE ERROR`** | `COMPUTE WS-F = WS-F / 3` | 実機は式を浮動小数点で評価する。除算とべき乗の中間結果の精度は 10 進の厳密計算と食い違うため、近い値を返さず断る（P-127）。転記・比較・`+ - *` の `COMPUTE` は対応済み。 | 実機の結果を取り、`HexFloatArithmetic` で浮動小数点の演算として入れる。 |
| **浮動小数点項目 (`COMP-1` / `COMP-2`) の `DISPLAY`** | `DISPLAY WS-FLOAT-COMP1` | HFP (IBM 16進浮動小数点) および IEEE 浮動小数の内部バイナリから、ホスト互換の表示形式文字列（指数表記・仮数部フォーマット）への変換整形器が未実装（`ProgramGenerator.java` で診断）。 | 浮動小数点フォーマッタをランタイムに追加して対応予定。 |
| **`CALL ... BY VALUE` (COBOL 呼出)** | `CALL 'SUB' USING BY VALUE WS-ARG` | 現在の `CALL` は `BY REFERENCE`（参照渡し）および `BY CONTENT`（値コピー渡し）に対応。C 言語等のネイティブ呼出を想定したプリミティブスタック渡し（`BY VALUE`）は COBOL 相互呼出としては未対応（P-090）。 | Java 相互運用層（設計 75）の ABI 拡張にて対応予定。 |
| **動的呼出の `CANCEL` 文におけるクラスアンロード** | `CANCEL 'SUBPGM'` | `CANCEL` はプログラムの `WORKING-STORAGE` を次回呼出時に初期状態へリセットするセマンティクスを実装しているが、JVM の仕様上ロード済み `.class` を個別にアンロードすることはできないため、クラスの完全破棄・再ロードは行われない（L2 観測等価として実装）。 | 業務観測上の結果は等価であるため、現在のメモリ再初期化方式を維持。 |

---

### 2.4 報告書作成機能 (REPORT WRITER)

| 未対応構文 / 項目 | 構文例 | 未対応の理由・設計判断 | 今後の方針・暫定対応 |
| --- | --- | --- | --- |
| **報告書の制御の切れ目 (`CONTROL` / `CONTROL HEADING` / `CONTROL FOOTING`)** | `CONTROLS ARE FINAL DEPT-CD`<br>`TYPE IS CONTROL FOOTING DEPT-CD` | 報告書作成機能のうち、固定の頁制御（`PAGE LIMIT`, `HEADING`, `FIRST/LAST DETAIL`, `FOOTING`, `INITIATE`, `GENERATE DETAIL`, `TERMINATE`）は通常レコード入出力へ展開して実装済み（L1 / FR-214）。しかし、ブレークキー（`CONTROL` 項目）の変化を検知して小計・合計行を自動出力する制御ステートマシンは未実装（P-078, C-4）。 | キー値の直前比較とブレーク時フッター処理のコード生成を追加予定。 |
| **自動集計句 (`SUM` 句)** | `05 LINE PLUS 1. 10 COLUMN 20 SUM AMOUNT.` | 明細出力時に値を累積し、コントロールブレーク時に集計値を印字する自動累計機能（P-078）。 | 上記の `CONTROL` ブレーク制御とセットで段階的にサポート予定。 |
| **報告書全体の一括出力 (`GENERATE 報告書名`)** | `GENERATE RPT-SUMMARY` | `GENERATE 明細グループ名` は対応済みだが、報告書名そのものを指定して明細を出力せず集計のみを行う形式（サマリーレポート）は制御ブレークが必須となるため未対応（`ProcedureBuilder.java`）。 | 制御ブレーク実装後に対応予定。 |

---

### 2.5 サブシステム連携 (`EXEC CICS` / `EXEC SQL`)

| 未対応構文 / 項目 | 構文例 | 未対応の理由・設計判断 | 今後の方針・暫定対応 |
| --- | --- | --- | --- |
| **CICS BMS (Basic Mapping Support) 画面入出力** | `EXEC CICS SEND MAP('...') END-EXEC`<br>`EXEC CICS RECEIVE MAP('...') END-EXEC` | 3270 端末プロトコルおよび BMS データストリームは再現しない設計（C-7, ADR-0010）。BMS マクロ (DFHMSD / DFHMDI / DFHMDF) の中立モデル `BmsModel` への解析、`COPY mapset` による記号マップ写し句の生成、`DFHAID` は実装済み（P-112）。`SEND MAP` / `RECEIVE MAP` / `SEND TEXT` / `SEND CONTROL` の初期 subset は中立な画面 snapshot として実装済み（設計 79 §8、P-117〜P-119）。`DFHBMSCA`、`ERASEAUP` / `ACCUM` / `PAGING`、会話型の RECEIVE、Thymeleaf 画面は未実装。 | Web 画面アダプタとして段階実装中。 |
| **CICS file control の一部 (ESDS、`SET`、RLS / BDAM / 遠隔の option)** | `EXEC CICS READ FILE('F') SET(PTR) RIDFLD(K) END-EXEC` | KSDS / RRDS の READ / WRITE / REWRITE / DELETE / UNLOCK と browse は実装済み（設計 82 §3、P-136）。ESDS は RBA の数え方、`SET` は CICS が持つ域への pointer、`TOKEN` / `NOSUSPEND` / `CONSISTENT` / `REPEATABLE` は RLS、`DEBKEY` / `DEBREC` は BDAM、`SYSID` は遠隔の file の設計を持たないので断る。文書が条件を示さない形も失敗させる。 | 実機の振る舞いを採ってから足す。 |
| **CICS キューの一部 (遠隔・共有・区画外・ATI)** | `EXEC CICS WRITEQ TS QUEUE('Q') FROM(A) SYSID('S1') END-EXEC` | `WRITEQ` / `READQ` / `DELETEQ` の `TS` / `TD` は 1 つの JVM の中のキューとして実装済み（設計 82 §4・§5、P-137）。`SYSID`、`NOSUSPEND`、`SET`、区画外の TD、trigger level による task の開始、回復可能なキュー、`ITEM` も `NEXT` も無い `READQ TS` は断る。 | 複数の JVM で分け合う実装と回復を UOW と合わせて設計する。 |
| **CICS START の一部 (`USERID` / `CHANNEL`)** | `EXEC CICS START TRANSID('TX02') USERID('U1') END-EXEC` | 端末を持たない task を起こす `START` と、`RETRIEVE`、`CANCEL REQID` は実装済み（設計 82 §6、P-138）。`PROTECT` も実装済み（P-141）。端末へ出す `TERMID` は `JdbcCicsStarts` の構成で実装済み（設計 83 §5、P-144）。`RETRIEVE WAIT` も端末へ出す task で実装済み。別の利用者で動かす `USERID`、`CHANNEL` / `ATTACH`、POST の `CANCEL` は、利用者の認可と task の境界と合わせた設計を持たないので断る。 | `USERID` は認可の設計に合わせる。 |
| **動的 CICS オプション (`TRANSID(項目名)` 等)** | `EXEC CICS RETURN TRANSID(WS-TRN) END-EXEC` | `LINK` / `XCTL` の `PROGRAM(項目名)` は 1〜8 byte の英数字項目、`ABEND ABCODE(項目名)` は 4 byte の英数字項目に限って対応済み（設計 79 §4、P-124）。`TRANSID` / `LENGTH` のデータ名は未対応で、静的定数に限定して受理（P-097）。 | 設計 79 の順序で拡張する。 |
| **CICS チャネル・コンテナ (`CHANNEL` / `CONTAINER`)** | `EXEC CICS LINK PROGRAM('...') CHANNEL('...') END-EXEC` | 32KB を超える大容量データ受け渡し用のチャネル・コンテナ機構は初期サブセットに含まれず未対応。 | COMMAREA（32KB 上限）の検証完了後、次期マイルストーンで対応予定。 |
| **`HANDLE ABEND PROGRAM(...)`** | `EXEC CICS HANDLE ABEND PROGRAM('ERRPRG') END-EXEC` | `HANDLE ABEND LABEL(段落名)`, `CANCEL`, `RESET` は実装済みだが、異常終了時に別プログラムを起動して回復を試みる形式は未対応（設計 77）。 | CICS タスク境界でのプログラム起動回復フックを追加予定。 |
| **Db2 動的 SQL (`PREPARE`, `EXECUTE`, `DESCRIBE`)** | `EXEC SQL PREPARE STMT FROM :SQL-STR END-EXEC` | 現在の `cobol-db2` は静的 SQL（単一行 `SELECT`, `INSERT`, `UPDATE`, `DELETE`, 静的カーソル `OPEN`/`FETCH`/`CLOSE`）および Spring トランザクション連携に対応。実行時に SQL 文を組み立てる動的 SQL は未対応（設計 77）。 | 動的 SQL 実行器および SQLDA バッファの対応を検討予定。 |

---

### 2.6 削除済み旧構文 (Enterprise COBOL 6.x で廃止された構文 / 制約 C-11)

以下の構文は、IBM Enterprise COBOL for z/OS 6.x で既に言語仕様から完全に削除されています。本処理系でもこれらを受理せず、**旧世代の廃止構文であることを明示した診断メッセージを出してコンパイルをエラー停止**させます。

| 削除された構文 | 代替となる現代の COBOL 構文 | 処理系の振る舞い・診断 |
| --- | --- | --- |
| **`EXAMINE` 文** | `INSPECT` 文 | `EXAMINE is obsolete and removed in Enterprise COBOL 6.x. Use INSPECT instead.` |
| **`TRANSFORM` 文** | `INSPECT ... CONVERTING` 文 | `TRANSFORM is obsolete and removed. Use INSPECT ... CONVERTING.` |
| **`NOTE` 文** | `*` 注釈行、または `*>` 行内注釈 | 文法レベルで拒否。注釈行への書き換えを促す診断。 |
| **`ON` 文 (条件付き実行)** | カウンタ変数と `IF` 文 | `ON count AND EVERY count` などの古い構文は受理せず診断。 |
| **特殊レジスタ `CURRENT-DAY`** | 組み込み関数 `FUNCTION CURRENT-DATE` | `CURRENT-DAY is obsolete. Use FUNCTION CURRENT-DATE or ACCEPT.` |

---

## 3. 未対応構文への遭遇時の対処方法

移行対象の既存 COBOL 資産を本コンパイラで翻訳した際に未対応エラーが発生した場合は、以下のガイドラインに従って対処してください。

1. **削除済み旧構文 (`EXAMINE`, `TRANSFORM` 等) の場合**:
   - 上記 2.6 節の対応表に従い、Enterprise COBOL 6.x 準拠の標準構文（`INSPECT` 等）へ書き換えてください。
2. **未対応 USAGE (`POINTER`, `NATIONAL` 等) の場合**:
   - 英数字項目（`PIC X`）や 4 バイトバイナリ（`COMP-5`）など、現在サポートされている表現で代替可能か検討してください。
3. **報告書の制御ブレーク (`CONTROL` 句) の場合**:
   - `REPORT SECTION` を使わず、手続き部内で手動のブレーク判定（キー項目の前回値比較と合計計算・改ページ `WRITE ... AFTER ADVANCING PAGE`）を行う標準的なバッチ集計ロジックへのリファクタリングを推奨します。
4. **CICS 未対応コマンドの場合**:
   - `RESP` / `RESP2` オプションを付与した中立 Java サービス呼び出し、または Java 側でのインターセプタ処理への移行を検討してください。
