# 敵対的設計レビュー: Java / JUnit / CICS / Db2 / BMS

| 項目 | 内容 |
| --- | --- |
| 日付 | 2026-09-09 |
| 対象 | [設計 75](../design/75-java-interop.md)、[設計 76](../design/76-junit-testing.md)、[設計 77](../design/77-spring-cics-db2.md)、ADR-0001〜0011。`WITH HOLD` 改善決定は ADR-0012 |
| 方法 | 要件逆引き、障害注入、信頼境界、意味論差、更新・並行・容量の破綻条件から反証 |
| 結論 | 方向性は妥当だが、現状のまま公開 API と互換性を確定してはならない。P0 指摘を実装開始ゲートとする |

## 重大度

- **P0**: 未解消のまま実装すると要件未達、データ不整合、誤った互換性保証、公開 API の作り直しになる。
- **P1**: 初期実装は可能でも、並行実行、運用、更新、セキュリティで高確率に障害になる。
- **P2**: 品質・保守性を下げる。該当機能の一般提供前に解消する。

## 総括

良い判断は、COBOL storage を正本にすること、Spring 型を中核へ漏らさないこと、CICS task を同期
imperative UOW として扱うこと、ブラウザ入力をサーバで再検証することである。ただし「型を依存させない」
ことを「意味論もフレームワーク非依存」と取り違えており、いくつかの近似実装を互換実装として扱っている。

最も危険なのは次の四点である。

1. FR-171 の OO COBOL / Java 連携が未設計なのに、Java 双方向連携が完了したように読める。
2. `WITH HOLD` spool は cursor の可視性、lock、エラー発生時点を変えるため安全な既定値ではない。
3. JDBC から SQLCA 全項目を復元できるという暗黙前提と、SQLWarning を既定で無視する `JdbcTemplate` の
   組み合わせが矛盾している。
4. Hercules は CICS / Db2 / 3270 の oracle にならず、「忠実」の合格基準がまだ存在しない。

## 指摘と改善案

<a id="ar-01"></a>

### AR-01 [P0] FR-171 と Java 連携設計が一致していない

**反証:** 設計 75 は通常の `CALL 'CUSTLOOK'` を登録 alias の `JavaCallable` へ解決する。IBM 非 OO
連携の `CALL 'Java.class.method'` が持つ型変換、RETURNING、Java 例外用 `ON EXCEPTION` も設計していない。
さらに FR-171 は `INVOKE`、
`CLASS-ID`、`METHOD-ID`、`REPOSITORY` と Java object の相互運用を要求しており、class / instance method、
object reference、overload、return type、Java exception の規則が存在しない。

**改善:** [ADR-0011](../decisions/0011-separate-procedural-and-oo-java-interop.md)のとおり、登録 alias、
IBM 非 OO static call、OO `INVOKE` を別設計にする。設計 75 は FR-086、FR-170、FR-172、FR-173 の
登録 alias 経路に限定する。後二者は専用 catalog、generation 付き object handle、method descriptor、
型変換表、例外 special register の詳細設計と IBM 実機試験が終わるまで未充足と表示する。

### AR-02 [P0] 呼び出し ABI が実資産を表現し切れない

**反証:** `ProgramSignature` の個数、最小 byte 数、passing mode だけでは `OMITTED` / `OPTIONAL`、
`CALL ... RETURNING`、`BY VALUE` の machine representation、`ENTRY` ごとの異なる USING、pointer、
dynamic length を検証できない。IBM COBOL では省略引数と暗黙の末尾 OMITTED があり、RETURNING を使う
CALL は RETURN-CODE の扱いも異なる。

**改善:** signature を program ではなく entry point 単位にし、parameter に presence、data category、
usage、encoded length、addressability、passing convention を、call result に RETURNING descriptor を持たせる。
ABI compatibility matrix と negative test を先に作り、`minimumBytes` だけの API を凍結しない。

**対応状況（2026-09-10）:** 主entryの必須・固定長`BY REFERENCE`に限定したsignature生成と、
Java入口・COBOL `CALL`・JUnit直接SECTION・ジョブの実行前個数／長さ検査を実装した。内部レイアウトの
hashも生成し、signatureと手続きmanifestは生成classへ埋め込む。単一の独立deploy catalogをJSONで
生成・読込みし、class内metadataと照合する経路も実装した。呼出し側ビューとの照合、複数catalog合成、
配備物の暗号学的真正性検証は未実装である。
`OMITTED`等を表現できない現recordを最終互換APIとして凍結せず、残りはP-090で追跡する。

### AR-03 [P1] program 名正規化と配備 revision が曖昧である

**反証:** decode 後の Unicode upper-case は文字展開や別 byte 列の衝突を起こし得る。immutable catalog
だけでは rolling deployment 中に DYNAM call、CANCEL、疑似会話の次 task がどの revision を使うか決まらない。

**改善:** dialect / code page ごとの `ProgramNameCodec` で許可 byte、長さ、正規化、衝突を検査する。
各 session / CICS task は `CatalogRevision` を pin し、同一 task 内で混在させない。会話 envelope に必要な
program / map ABI revision を保存し、次 task は互換 revision へ route するか明示的に停止する。

### AR-04 [P0] Java adapter の thread hop と失敗時書込みが未定義である

**反証:** 設計 75 は別 thread で処理して戻る余地を残すが、`CobolSession` と Spring transaction は
thread-bound である。また `BY REFERENCE` を Java が更新してから例外を投げると、DB rollback しても
COBOL storage は戻らない。

**改善:** `JavaCallable` は同一 thread 同期完了を必須にする。非同期連携は immutable copy-in / copy-out と
明示的な task suspension protocol を持つ別機能にする。失敗時の storage は自動 rollback すると決めず、
IBM oracle で可視性を確定するまで「部分更新あり得る」とし、テストで固定する。

### AR-05 [P0] SECTION 直接テストが存在しない制御フローを正常化する

**反証:** `invokeSection` 中の `GO TO` が SECTION 外へ出た後も program 末尾まで dispatch して
`GOBACK` とするのは、本来の caller、PERFORM stack、fall-through を持たない合成実行である。テストが
成功しても本番経路の正しさを示さない。

**改善:** 既定を strict にし、SECTION 範囲外への control transfer、ALTER 対象、宣言節進入を
`NonLocalProcedureTransferException` で失敗させる。コンパイラの control-flow graph で直接試験可能性を
manifest に出し、非適格 SECTION は program-level test を要求する。明示 `PERFORM SECTION` の Mock は維持する。

**対応状況（2026-09-10）:** 段落範囲、source位置、直接起動適格性をmanifestへ追加し、適格SECTIONだけを
既存PERFORM範囲実行器で起動する入口を実装した。第1増分は安全側に倒し、非構造化transfer文を含む
SECTIONを遷移先によらず拒否する。正確な範囲外判定と安全なローカル`GO TO`の許可は
[P-093](../decisions/provisional.md#p-093-section直接起動は非構造化transferを保守的に拒否する)で追跡する。

### AR-06 [P1] JUnit extension の失敗集約、並列性、cache が過小設計である

**反証:** `AfterEachCallback` だけで常に元の test failure へ suppressed を付けられるとは限らない。
`PER_CLASS`、static extension、parameterized / repeated test、並列実行で mutable fixture が共有され得る。
compile cache key に transitive copybook、compiler option、dialect、code page、runtime ABI が明記されておらず、
classloader の強参照 cache は leak と Windows file lock を生む。

**改善:** JUnit unique test ID を key に `ExtensionContext.Store` へ状態を置き、invocation interception と
cleanup の組合せを extension contract test で検証する。共有可能なのは immutable compile artifact だけとし、
完全な入力 Merkle hash を cache key にする。bounded / weak cache と classloader close を設計する。
`PER_CLASS` と static registration は拒否するか、明示 opt-in と thread-safety test を要求する。

### AR-07 [P1] 「別フレームワークへ交換可能」を過大に表現している

**反証:** core API に Spring 型がなくても、設計全体は同一 thread、同期 JDBC、request 内で完了する
imperative transaction を前提にする。reactive framework へ adapter だけ交換しても成立しない。

**改善:** 保証範囲を「同期 imperative host 間で交換可能」に限定する。reactive / actor / distributed task
host は continuation、context propagation、cancellation、R2DBC transaction を持つ別 coordinator と ADR を要求する。
port contract には type だけでなく thread ownership と UOW lifecycle を含める。

### AR-08 [P0] 任意の `PlatformTransactionManager` を受け入れられない

**反証:** 同じ interface でも `JdbcTransactionManager`、`JpaTransactionManager`、JTA、独自 manager は
JDBC Connection の参加方法が異なる。JPA persistence context は COBOL の途中 `SYNCPOINT` 後に stale entity を
保持し得る。低レベル cursor callback は transaction timeout を Statement へ適用しない可能性がある。

**改善:** 初期 `SPRING_MANAGED` local capability は同じ DataSource の `JdbcTransactionManager` または
`DataSourceTransactionManager` に限定する。JPA / arbitrary manager は capability test 合格まで fail-fast とし、
明示 SYNCPOINT をまたぐ EntityManager 利用を初期非対応にする。全低レベル Statement に transaction timeout、
query timeout、cancel policy を適用する。JTA は別 profile と障害試験を持つ。`WITH HOLD` 必須 task は
[ADR-0012](../decisions/0012-db2-driver-managed-uow-for-required-hold-cursors.md)の独立 profile とし、
Spring transaction manager を利用する task と混在させない。

### AR-09 [P0] SQLCA fidelity を JDBC 例外写像だけでは保証できない

**反証:** SQLCA の SQLERRD / SQLWARN には row count、reason code、cursor capability、truncation 等があり、
SQLException の error code / SQLState だけでは全て得られない。Spring `JdbcTemplate` は既定で SQLWarning を
無視する。pure dynamic JDBC では static package 前提の -805 を通常経路で再現できない。

**改善:** SQLCA field ごとに `EXACT`、`DERIVED`、`UNAVAILABLE` の fidelity matrix を作る。adapter 専用
JdbcOperations で warning を捨てず、Statement / ResultSet / Connection の chain と update count を close 前に
採取する。得られない値を推測せず deterministic な未提供値と診断にする。-805 は package mode の統合試験と
dynamic mode の非適用を分ける。

### AR-10 [P0] `WITH HOLD` の spool 化は安全な既定ではない

**反証:** commit 前に残行を全 FETCH すると、Db2 native held cursor と比べて lock、可視性、network I/O、
fetch 時の例外発生時点、LOB locator、scroll sensitivity が変わる。容量上限到達が commit を失敗させるという
新しい障害も作る。

**改善:** 未分類 cursor の既定を `REJECT_UNVERIFIED` にする。意味差を承認した cursor だけ
`PORTABLE_SPOOL` を選べる。`WITH HOLD` 必須 entry は
[ADR-0012](../decisions/0012-db2-driver-managed-uow-for-required-hold-cursors.md)の
`DB2_DRIVER_MANAGED_HOLD` を task 全体へ適用し、Spring 管理 JDBC と混在させない。同じ物理 connection を
task 内の commit 間で専有し、疑似会話の次 task へ持ち越さない。実 Db2 crash 試験が通るまで一般提供しない。

### AR-11 [P0] `NON_ATOMIC` 会話回復が名前だけである

**反証:** 「outcome journal を設ける」だけでは、Db2 commit 後かつ Spring Session 保存前の crash、
応答喪失、同じ nonce / idempotency key の再送、Session expiry との競合を解決できない。

**改善:** Db2 UOW 内で `COMMITTED_PENDING_SESSION` outcome と次 envelope を idempotency key 付きで記録し、
commit 後に Session CAS 保存、成功後に `PUBLISHED` とする state machine を定義する。再送は journal の
結果を返すか publish を再開し、同じ業務処理を再実行しない。screen nonce は受理済み outcome と一体で
消費する。expiry / logout と孤児 journal の回収順も試験する。

### AR-12 [P0] BMS renderer の実現方式を prototype 前に固定している

**反証:** 通常の HTML input を field 単位で置くだけでは、cell 単位 cursor、overwrite、画面端をまたぐ
field、DBCS / combining character、3270 の field attribute cell を同時に忠実再現できない可能性が高い。

**改善:** representative な難画面で time-boxed spike を行い、(a) native input overlay、(b) per-cell DOM と
隠れた native input、(c) visual canvas + semantic form の hybrid を比較する。Canvas-only は不採用のまま、
hybrid は許容する。cell 座標、keyboard trace、IME、screen reader、性能の exit criteria を満たした方式だけを
`WEB_3270_STRICT` とする。

### AR-13 [P1] BMS snapshot と browser security の残余リスクがある

**反証:** `BmsScreenSnapshot` は画面値を保存しないと refresh / retry を再現できず、保存すれば DRK、個人情報、
大量 field による漏えい・容量リスクになる。`application/json` 埋込みは `</script>` 等の escape を誤ると XSS
になる。password manager / back-forward cache が DRK 値を残す可能性もある。

**改善:** snapshot schema に field-level sensitivity、保存可否、暗号化 key version、最大 bytes、TTL を持たせる。
DRK 入力は応答へ echo せず submit 後に client state を消去し、autocomplete と cache-control を制御する。
JSON serializer の HTML-safe escape、CSP、back-forward navigation を browser security test に追加する。

### AR-14 [P0] CICS / Db2 / BMS の互換性 oracle がない

**反証:** Hercules V2 は OS を介さない命令・ランタイム検証であり、CICS task、Db2 SQLCA、BMS screen の
基準にはならない。golden screenshot も見た目しか検証せず、AID / MDT / cursor / byte map を証明しない。

**改善:** 証拠レベルを機能別に表示する。IBM CICS / Db2 実環境で採取した sanitized command / SQLCA /
screen trace を `V2-SUBSYSTEM`、IBM 公開仕様だけの試験を `V1`、推測を `V0` とする。要件→test vector→oracle→
対象 adapter version の traceability matrix を release artifact にし、V0 / V1 の項目を「忠実」と広告しない。

### AR-15 [P1] 容量と backpressure が設定例に留まる

**反証:** Servlet thread、CICS session storage、Db2 pool、cursor spool、Spring Session payload の上限が
相互に独立しており、`max-concurrent-tasks=200` の根拠がない。pool 枯渇時に thread が全て待機すると health
endpoint と shutdown も進まない。

**改善:** deployment ごとに task memory、WORKING-STORAGE、screen snapshot、cursor、connection wait の
budget を算定する。admission queue を bounded にし、Db2 pool と servlet executor を別途 reserved capacity
込みで設計する。query / task / queue timeout と overload response を統一し、load / soak / crash test の
閾値を受入条件にする。

## 直ちに設計へ反映する補正

- FR-171 を設計 75 の未充足事項として分離し、ADR-0011 を追加する。
- `JavaCallable` の別 thread 実行を禁止し、catalog revision pin と ABI 完全性 gate を追加する。
- SECTION 直接実行は non-local transfer を既定で失敗させる。
- Spring adapter の初期 local transaction manager を JDBC 系へ限定する。
- SQLWarning の明示採取と SQLCA fidelity matrix を必須にする。
- `WITH HOLD` の既定を `REJECT_UNVERIFIED` とし、必須 entry は task 全体を Db2 driver 管理 UOW にする。
- NON_ATOMIC 会話の journal state machine、BMS renderer spike、subsystem oracle を実装開始ゲートへ追加する。

## 実装開始ゲート

| Gate | 合格条件 |
| --- | --- |
| G-AR1 Java ABI | OMITTED / OPTIONAL / RETURNING / BY VALUE / ENTRY の署名と test vector が確定 |
| G-AR2 Java OO | FR-171 を別 milestone とし、未対応診断または詳細設計が承認済み |
| G-AR3 JUnit seam | non-local SECTION と primary failure 保存を extension contract test で実証 |
| G-AR4 Spring UOW | manager capability、timeout、JPA 非対応範囲、crash point が確定 |
| G-AR5 SQLCA / cursor | field fidelity matrix と native task profile の commit 間 FETCH、混在拒否、task 終了 close が実 Db2 で合格 |
| G-AR6 Conversation | STRICT と NON_ATOMIC の crash / retry / expiry state machine が合格 |
| G-AR7 BMS UI | 難画面 spike が cell、AID、MDT、IME、アクセシビリティ基準を合格 |
| G-AR8 Oracle | CICS / Db2 / BMS の traceability matrix と証拠レベルを公開 |
| G-AR9 Capacity | bounded queue、memory / pool / spool budget、load / soak test 閾値が確定 |

P0 gate が未合格の機能は experimental と表示し、互換保証の対象に含めない。

## 根拠資料

- [IBM COBOL: CALL statement](https://www.ibm.com/docs/en/cobol-zos/6.4.0?topic=statements-call-statement)
- [IBM COBOL: OMITTED arguments](https://www.ibm.com/docs/en/cobol-zos/6.5.0?topic=data-omitted-arguments)
- [IBM COBOL: Invoking methods](https://www.ibm.com/docs/en/cobol-zos/6.3?topic=client-invoking-methods-invoke)
- [IBM COBOL: Non-OO COBOL / Java interoperability](https://www.ibm.com/docs/en/cobol-zos/6.4.0?topic=pg-coboljava-interoperability-outside-object-oriented-oo-cobol-framework)
- [Spring Framework: Declarative transaction implementation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-decl-explained.html)
- [Spring Framework: JDBC connection and transaction management](https://docs.spring.io/spring-framework/reference/data-access/jdbc/connections.html)
- [Spring Framework: JdbcTemplate warnings](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/core/JdbcTemplate.html)
- [IBM Db2: SQLCA fields](https://www.ibm.com/docs/en/db2-for-zos/13.0.0?topic=sqlca-description-fields)
- [IBM Db2: Held and non-held cursors](https://www.ibm.com/docs/en/db2-for-zos/13.0.0?topic=cursors-held-non-held)
- [IBM CICS: Synchronization points](https://www.ibm.com/docs/en/cics-ts/6.x?topic=work-synchronization-points)
- [IBM CICS: BMS DFHMDF](https://www.ibm.com/docs/en/cics-ts/6.x?topic=macros-dfhmdf)
