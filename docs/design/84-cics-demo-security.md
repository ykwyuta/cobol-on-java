# 設計 84: デモ環境の簡易認証 (principal と CICS の user ID、transaction と START USERID の権限)

| 項目 | 内容 |
| --- | --- |
| 状態 | 実装 (2026-09-15)。デモ環境の仕様であり、本番の利用者管理 (RACF 等) の代わりにはならない |
| 対応要件 | 設計 77 §4.2、設計 82 §6、設計 83 |
| 検証レベル | V1。実機の CICS / RACF と突き合わせていない |
| 暫定判断 | P-145 |

## 1. 位置づけ

これまで CICS の user ID は、入口 (ブラウザ、JSON API、端末へ出す task) が principal 名を大文字にして作っていた。
transaction の権限は確かめず、START の `USERID` は断っていた。

デモ環境で「利用者ごとに使える transaction が違う」「バッチ用の user ID で START する」を見せられるよう、
**設定ファイルだけで閉じる簡易認証**を置く。本番では利用者が `CicsSecurityPort` と Spring Security を自分で構成する。

## 2. 公開文書から決まること

| 項目 | 文書の記述 | 出典 |
| --- | --- | --- |
| START の USERID | TERMID を書かず USERID を書けば、起こす task はその user ID で動く。どちらも書かなければ START を出した task の代理の user ID | START の頁 |
| NOTAUTH | RESP 70。RESP2 7 は TRANSID の資源の権限の確かめに失敗、RESP2 9 は USERID の代理の権限の確かめに失敗 | START の頁 |

## 3. 仕様

### 3.1 何を確かめるか

`CicsSecurityPort` (cobol-cics) が 3 つだけを持つ。

| 確かめること | どこで | 失敗したとき |
| --- | --- | --- |
| principal → CICS の user ID (`userIdOf`) | coordinator。要求が user ID を持たないとき | user ID を持たない task になる (demo では attach で断られる) |
| transaction の attach (`mayAttach`) | coordinator。どの入口の task も起こす前 (ブラウザ、JSON、START、ATI、疑似会話の続き、IMMEDIATE の連鎖)。冪等キーを予約する前 | `TransactionNotAuthorizedException`。ブラウザと JSON は 403。応答に user ID や権限の構成を出さない |
| START の `TRANSID` | START の命令の時点で、起こす task の user ID (USERID か、書かなければ START を出した task の user ID) | NOTAUTH 70 / RESP2 7 |
| START の `USERID` の代理 (`maySurrogate`) | START の命令の時点で、START を出した task の user ID | NOTAUTH 70 / RESP2 9 |

- 要求が user ID を持つとき (START の USERID で起こす task、ATI の USERID) は、principal から決めない
- TERMID の START の user ID は端末の利用者なので、START の時点では TRANSID を確かめず、task を起こすときに coordinator が確かめる
- `USERID` と `TERMID` の併記は、どちらの user ID で動くかを確かめていないので翻訳で断る
- file、一時記憶、program の LINK / XCTL などの資源ごとの権限、SIGNON、`QUERY SECURITY` は持たない

### 3.2 既定 (構成しないとき)

`CicsSecurityPort.derived()`。これまでと同じく、principal 名が CICS の user ID の形 (1〜8 文字の英大文字・数字・国別文字) に
収まれば大文字にして user ID とし、どの transaction も起こせる。START の USERID の代理は**同じ user ID に限る**
(構成が無いのに他人の user ID で task を起こさせない)。

### 3.3 デモの構成 (`cobol.cics.security.mode=demo`)

```yaml
cobol:
  cics:
    security:
      mode: demo
      users:
        - username: alice
          password: "{bcrypt}$2a$10$..."
          user-id: ALICE01
          transactions: [BNK1, INQ1]
          surrogates: [BATCH01]
        - username: admin
          password: "{noop}admin-pass"
          user-id: ADMIN01
          transactions: ["*"]
        - user-id: BATCH01
          transactions: [BTCH]
```

| 項目 | 規則 |
| --- | --- |
| `username` | Spring Security のログイン名 (principal)。書かなければログインできない user ID (START USERID や ATI の USERID で使う) |
| `password` | `{bcrypt}` / `{noop}` などの encoder の接頭が必須。照合は Spring Security の既定の `DelegatingPasswordEncoder` |
| `user-id` | CICS の user ID。1〜8 文字の英大文字・数字・国別文字。重複は断る |
| `transactions` | 起こせる TRANSID。`*` はすべて。書かなければどれも起こせない |
| `surrogates` | START USERID で代理できる user ID。同じ user ID は書かなくても代理できる |

- 一覧に無い principal は user ID を持たず、どの transaction も起こせない
- 構成の誤り (user ID の形、重複、接頭の無いパスワード、TRANSID の形、ログイン名の無いパスワード) は起動の時点で断る。
  例外の文面にパスワードを出さない
- Spring Security が classpath にあれば、一覧の利用者の `InMemoryUserDetailsManager` と、すべての要求に認証を求める
  form login の `SecurityFilterChain` (CSRF は既定のまま有効) を作る。利用者が bean を置けばそれを使う
- 起動の記録に「デモ環境のための構成」と警告を出す
- START の USERID / TRANSID の権限は region の構成 (`CicsEnvironment.withSecurity`) に同じ bean を入れて使う

## 4. 実装

| 部品 | 役割 |
| --- | --- |
| `CicsSecurityPort` / `TransactionNotAuthorizedException` (cobol-cics) | 中立の権限の port と、attach を断った例外 |
| `CicsTaskCoordinator` | user ID を決めて attach を確かめる |
| `CicsRuntimeOps.startCondition` | START の USERID の代理と TRANSID の権限。NOTAUTH 70 / 9・7 |
| 翻訳 (`CicsBlockParser` ほか) | START の `USERID` を定数か 8 byte の英数字項目で受ける。`DFHRESP(NOTAUTH)` と `HANDLE CONDITION NOTAUTH` |
| `CicsSecurityProperties` / `DemoCicsSecurity` / `CicsDemoSecurityAutoConfiguration` (autoconfigure) | デモの構成 |
| ブラウザ / JSON の入口 | user ID を自分で作らず coordinator に任せ、`TransactionNotAuthorizedException` を 403 にする |

## 5. 確かめていないこと

- RACF の TCICSTRN / SURROGAT の判定の細部 (UACC、グループ、警告 mode)。ここは一覧に書いたかだけで決める
- NOTAUTH の EIBRCODE の byte (binary zero とした)
- 起こせない transaction を端末から入れたときの実機のメッセージ (DFHAC2033 等) と、ABEND の有無。ここは 403 で task を起こさない
- RUN TRANSID の USERID (RESP2 の値を確かめていないので断ったまま)
