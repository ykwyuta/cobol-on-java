# ADR-0006: サブルーチンはカタログ上書き、SECTION は明示的な PERFORM 境界で差し替える

| 項目 | 内容 |
| --- | --- |
| 状態 | 採用 |
| 日付 | 2026-09-09 |
| 関連要件 | FR-061, FR-080, FR-083, FR-194, FR-198 |
| 関連設計 | [設計 60](../design/60-procedure.md), [設計 75](../design/75-java-interop.md), [設計 76](../design/76-junit-testing.md) |

## 文脈

COBOL の外部サブルーチンは `CALL` で明確に境界を越えるため、プログラムカタログの定義を
テスト用実装へ差し替えれば隔離できる。一方 SECTION は Java のメソッドではない。現在の生成コードは
SECTION を先頭から末尾までの段落範囲として表し、private な `performRange` と `dispatch` で実行する。

SECTION への進入には、明示的な `PERFORM SECTION-NAME`、前の段落からの fall-through、`GO TO`、
宣言節や整列手続きとしての起動がある。段落メソッドを Java の継承やバイトコード置換で Mock すると、
`GO TO`、`ALTER`、`PERFORM ... THRU`、デバッグ手続きの意味まで変わり、製品コードと異なる制御系を
試験することになる。

## 決定

### 外部サブルーチン

テストセッションだけが持つ `ProgramOverrideSet` を `ProgramCatalog` の前段に置く。Mock は
`ProgramId`、本物と互換な `ProgramSignature`、セッション単位の `ProgramFactory` を明示して登録する。
製品カタログ自体は不変のままにし、ADR-0001 の同名定義禁止を緩めない。

上書きはセッション開始前に確定し、`NODYNAM` の事前束縛より先に適用する。Mock のインスタンスは
本物と同じくセッションが保持し、COBOL の `CANCEL` 後は作り直す。呼び出し履歴はテスト fixture が
保持するため `CANCEL` しても消さない。

### SECTION

コンパイラは名前付きの外部形式 `PERFORM` 呼び出しサイトへ、安定した `ProcedureId` を持つ
`ProcedureHook` を生成する。SECTION Mock が置換するのは、対象 SECTION を範囲どおり呼ぶ
**明示的な `PERFORM SECTION-NAME` 1回分**である。Mock が正常に戻ると、SECTION の末尾まで
実行して `PERFORM` から戻ったのと同じ位置から続行する。

次の制御移動は置換しない。

- 前の段落から SECTION へ自然に流れ込む fall-through
- `GO TO SECTION-NAME` または SECTION 内の段落への `GO TO`
- SECTION の一部だけを含む `PERFORM A THRU B`
- 宣言節、デバッグ節、SORT/MERGE の入出力手続きとしての暗黙起動
- inline `PERFORM`

これらまで同名だけで置換すると、どこへ制御を戻すべきかが一意に決まらない。必要なら、将来
`ProcedureRangeId` を明示する別 API と ADR を追加する。

SECTION Mock は `PROCEED`、`RETURN`、`THROW` のいずれかを返す。`PROCEED` は spy または
部分 Mock として実物を実行し、`RETURN` は Java の Mock 本体だけを実行して呼び元へ戻り、
`THROW` は指定した COBOL 実行異常を注入する。任意の段落番号への `GO TO` は Mock API に公開しない。

直接 SECTION をテストする `fixture.invokeSection` では、対象 SECTION 自身の Mock を適用せず、
その内側から明示的に `PERFORM` される別 SECTION の Mock は適用する。LINKAGE 引数と同じ
プログラムインスタンスの `WORKING-STORAGE` を使用する。

ただし直接起動には本来の caller / PERFORM stack がない。コンパイラの control-flow graph が範囲内で
完結すると判定した SECTION だけを直接起動可能とし、範囲外 `GO TO`、ALTER、宣言節への進入は
`NonLocalProcedureTransferException` で失敗させる。これらを手続き部末尾まで実行して正常復帰に
読み替えず、program-level test を要求する。

### 記録と検証

プログラム Mock と SECTION Mock は、呼び出し開始時の引数・関連領域をバイトスナップショットとして
記録する。`DataView` そのものを履歴に保持すると後続処理で値が変わるため禁止する。終了時スナップ
ショット、回数、順序、終了種別も記録し、JUnit から検証できるようにする。値は失敗メッセージへ
自動表示せず、明示的なフィールド assertion またはマスキング済み診断でだけ表示する。

## 影響

- 外部サブルーチンの Mock は本番と同じ名前解決、引数 ABI、`CANCEL` を通る。
- SECTION 呼び出しの差し替えには、コンパイラが製品生成コードへ軽量な hook 呼び出しを出す必要がある。
  製品実行では `NOOP` hook となる。
- SECTION の名前、範囲、ソース位置をマニフェストへ出し、数値の段落番号を公開 API にしない。
- fall-through を通る巨大な手続き部を SECTION Mock だけで分離できない場合は、COBOL ソース側に
  明示的な `PERFORM` 境界を作る必要がある。

## 却下した案

### 生成クラスを継承して SECTION 相当メソッドを override する

段落メソッドは private であり、SECTION は単一メソッドではない。公開・protected 化しても
`GO TO` と範囲復帰の規則を Java override へ正しく移せないため採用しない。

### Mockito 等で生成クラスの private メソッドを置換する

特定の Mock ライブラリと Java agent に依存し、JDK やバイトコード生成の変更に弱い。COBOL 名と
Java の内部メソッド名も公開契約になってしまうため採用しない。

### SECTION 名に入ったすべての経路を Mock する

fall-through と `GO TO` には通常の呼び戻り先がなく、Mock 後の継続位置を決めるだけで原プログラムの
意味を変更する。明示的な呼び出し境界だけを置換する。
