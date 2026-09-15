# 設計 81: BMS 画面の Web 描画 (Thymeleaf adapter) と renderer spike

| 項目 | 内容 |
| --- | --- |
| 状態 | 第 1 増分を実装 (experimental)。HTTP 入口は未構成 |
| 対応要件 | 設計 77 §3.1 (`cobol-spring-boot-4-bms-thymeleaf`)、§4.5.2〜4.5.5 |
| 検証レベル | V1。ブラウザでの配置は in-app browser (Chromium) で Bank-of-Z の 2 画面を測った |

## 1. 位置づけ

設計 77 §4.5.2 は、DOM / CSS の方式を決める前に代表的な難画面で time-boxed spike を行うことを求める。
本書はその spike の記録と、spike の結果に基づいて決めた第 1 増分の形である。

## 2. spike の方法

- 画面: Bank-of-Z の `BNK1CAM` (44 field、保護・非保護・数字・FSET・色・下線) と `BNK1DCM`
  (52 field、server の cursor が画面の途中)。どちらも `BmsParser` と `BmsScreenComposer.send` (MAPONLY) で
  作った `BmsScreenSnapshot` を、実際の template で HTML にした
- 表示: in-app browser (Chromium) に local HTTP server から読ませた
- 測ったもの: 各 field の左端と幅を等幅の 1 cell の幅で割った行・桁と、`data-row` / `data-column` /
  `data-length` の一致、はみ出し、初期 focus の field と caret、console の error

比較した方式:

| 方式 | spike での扱い | 判断 |
| --- | --- | --- |
| native input overlay (field ごとに 1 つの input / span を等幅の cell に置く) | 試作して測った | 採用 |
| per-cell DOM + 隠れた native input | 試作していない。1920 要素と IME / focus の往復が重い | 保留 |
| visual canvas + semantic form | 試作していない。支援技術の正本を DOM に残す二重管理になる | 保留 |

試作は 1 方式だけであり、3 方式を同じ条件で測った比較ではない。IME、DBCS、overwrite の評価は
次の spike の対象として残す (§6)。

## 3. spike の結果と、それで変えたこと

### 3.1 JavaScript で grid に置く形 (最初の試作)

- field を CSS grid の 1ch の cell に置き、位置は JavaScript が `data-*` から `grid-row` / `grid-column` を
  設定した。template には style を書かない
- 測定: 44 field すべてが期待した桁と幅に一致し、はみ出し無し。初期 focus は `CUSTNO`。
  `BNK1DCM` では server の cursor (5 行 18 桁) を含む field に focus と caret が入った。console の error 無し
- **問題**: JavaScript の module が読めないと (server が `text/plain` で返した最初の試行で起きた)、
  全 field が 1 行目の 1ch の cell に詰まり、画面として読めなくなった。配置を JavaScript に頼ると、
  script の読み込み失敗や CSP の設定誤りがそのまま業務画面の破損になる

### 3.2 server が行を並べる形 (採用)

- 画面の各行を「空白の並び」と「field」の列にして server が渡す。行は flex で並べ、要素の間の
  空白の文字は flex が捨てるので template の改行が桁をずらさない
- 出力 field は長さぶんの文字を `white-space: pre` で描くので等幅で幅が決まる。入力 field は
  列挙した class `bms-len-1` 〜 `bms-len-132` で幅を `N ch` にする。style 属性も任意 class も使わない
- JavaScript は配置をしない。MDT、初期 cursor、PF キー、送信する field の選別、二重送信の抑止だけを持つ。
  読めなくても画面は崩れず、Enter / Clear / PF の button で送信できる (MDT は送れないので全入力 field を送る)
- 測定: JavaScript あり・なしの両方で、`BNK1CAM` の 44 field のうち 43 が期待した行・桁・幅に一致した。
  一致しなかった 1 つは 24 行 80 桁の `DUMMY` (`ATTRB=(DRK,PROT,FSET)`) で、幅が 0 だった。DRK の値を出さない
  ために文字を空にしたので、出力の span が縮んでいた。行の最後の cell だったので他の field は動かなかったが、
  行の途中なら後ろの field がすべて左へずれる。DRK の出力 field は長さぶんの空白を置いて cell を保つように直した
  (入力 field の幅は `bms-len-N` が決めるので値は空のまま)。自分の単体テストは DRK の入力 field しか見ておらず、
  ブラウザで幅を測って初めて分かった
- 直したあとの測定: `BNK1CAM` は JavaScript あり・なしとも 44 field すべてが行・桁・幅に一致し、24 行とも
  幅 750px (80 桁)。`BNK1DCM` は描いた 44 field すべてが一致し、初期 focus と caret は server の cursor
  (5 行 18 桁) の `CUSTNO` に入った。console の error は無い

## 4. 実装した部品 (第 1 増分)

| 部品 | 役割 |
| --- | --- |
| `BmsScreenView` / `BmsScreenViewFactory` | snapshot から表示モデルを作る。属性は列挙した class へだけ写す。DRK の値は持たない。画面端をまたぐ field と重なる field は断る |
| `templates/cobol/bms/screen.html` | 共通 template。`th:text` / `th:value` / `th:attr` だけを使い、`th:utext` と style を使わない |
| `static/cobol/bms/bms.css` / `terminal.js` | theme と端末操作。外部 file なので CSP の `script-src 'self'` で動く |
| `BmsTerminalInputBinder` | form (`aid`、`cursor`、`bms.NAME.occurrence`) を `BmsTerminalInput` にする。形だけを確かめ、画面との照合は `BmsInputDecoder` に任せる |
| `BmsThymeleafAutoConfiguration` | 上の 2 つの部品を bean にする |

## 5. ブラウザの入口 (第 2 増分)

`CicsBrowserController` を `CicsBrowserAutoConfiguration` が構成する。coordinator、会話ストア、task の境界は
base の `cobol-spring-boot-4-autoconfigure` の `CicsTaskAutoConfiguration` が作り、JSON の入口 (P-135) と分け合う。
設計 77 §4.2 の条件をこう満たす。

| 条件 | 扱い |
| --- | --- |
| 認証 | Spring Security が classpath に無ければ入口を構成しない。principal が無い要求は 401 |
| CSRF | task は POST だけで動かす。Spring Security の CSRF filter が token の無い POST を 403 にする。GET は開始の画面だけ |
| 会話 | HTTP session に置くのは会話の ID、版、次の TRANSID だけ。COMMAREA と直前の画面は会話ストアから読む。版が合わなければ 409 で task を動かさない |
| 会話ストア | 既定は `InMemoryConversationStore` (1 つの JVM の中だけ)。Spring Session は HTTP session を外へ置く形で使え、会話ストアは利用者が bean で替える |
| UOW | 既定の境界は回復可能な資源を持たない `NonRecoverableTaskBoundaryFactory`。Db2 を使う transaction は UOW を持つ境界を bean で置く |
| 端末 | HTTP session ごとに `W` + base36 3 文字の端末名を振る。user ID は principal 名が 8 文字の CICS の形に収まるときだけ |
| IMMEDIATE | 端末入力なしで次の task を続け、8 回を越えれば失敗させる |
| 二重送信 | 画面ごとに冪等キーと会話の ID・版を hidden で載せる。同じ画面の再送は task を動かさず覚えた結果を返し、同じキーで違う値は 409 (P-142) |
| 失敗 | 応答へ入力の内容や例外の文面を出さない。ABEND だけは code を示す |

設計 77 §3.1 は MVC の入口を `cobol-spring-boot-4-autoconfigure` に置く。ブラウザの入口は画面の描画と切り離せず、
JSON 専用の構成でこの module を外せるよう、BMS Thymeleaf adapter の側に置いた。JSON API の入口は autoconfigure の
増分として残す。

## 6. 未検証の項目 (次の spike)

- IME、全角文字、DBCS の SO / SI と cell 幅
- insert / overwrite、erase EOF、Tab / Backtab を field 単位で扱う 3270 の操作意味論 (今はブラウザ既定)
- 画面端で折り返す field の visual segment
- font の読み込み前後で cell 幅が変わらないことの測定と fallback
- 27x132 などの端末 profile、DRK 入力の password 以外の扱い
- golden image と操作 trace の比較 (`WEB_3270_STRICT`)
