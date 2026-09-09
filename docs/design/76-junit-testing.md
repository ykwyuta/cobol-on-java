# 設計 76: JUnit による COBOL 単体テスト

| 項目 | 内容 |
| --- | --- |
| 対応要件 | FR-061, FR-080〜FR-084, FR-194, FR-195, FR-197, FR-198, FR-204, NFR-043 |
| ステータス | 敵対的レビュー済み。外部プログラムMock、明示的PERFORM SECTION Mock/spy、手続きmanifest、制限付き直接SECTION実行を実装 |

## 目的

業務 COBOL のテストを JUnit 5 の通常のテストスイートへ組み込み、Java から次を行えるようにする。

- COBOL プログラムを主プログラムまたはサブルーチンとして実行する
- LINKAGE、`WORKING-STORAGE`、特殊レジスタ、入力、ファイルを設定する
- COBOL の型を保ったまま結果、出力、復帰コード、異常終了を検証する
- `CALL` 先の COBOL / Java サブルーチンを Java の stub、Mock、spy へ差し替える
- 明示的に `PERFORM` される SECTION を Java 実装へ差し替える
- SECTION を単独で起動し、下位の SECTION と外部呼び出しを隔離して検証する
- 呼び出し回数、順序、入力時と出力時の引数を検証する

対象は JUnit 5 (Jupiter) である。JUnit 4 runner、Mockito 等の特定 Mock ライブラリ、ホスト上の
ネイティブ COBOL 実行は対象外とする。Mockito と併用することは妨げないが、COBOL の差し替えに
Java agent や private メソッド Mock を必要としない。

関連する決定は [ADR-0005](../decisions/0005-junit-adapter-module.md) と
[ADR-0006](../decisions/0006-program-and-section-test-seams.md) に記録する。呼び出し ABI と
セッションの基本は[設計 75](75-java-interop.md)に従う。残余リスクと実装開始条件は
[敵対的レビュー](../reviews/2026-09-09-interop-adversarial-review.md)に従う。

## 実装状況（2026-09-10）

独立した `cobol-junit` モジュールを追加し、`CobolExtension`、`CobolTestContext`、
`CobolProgramFixture`、`CobolTestResult` を実装した。JUnitテストごとに出力とセッションを分離し、
COBOLソースの遅延コンパイル、`call` / `runMain`、`DataView` 引数、作業場所、`CANCEL` を扱える。
外部サブルーチンは `stubProgram` / `expectProgram` で Java 実装へ上書きでき、期待回数と呼出し前後の
引数バイトスナップショットを検証する。`mockSection` / `spySection` は通常SECTIONへの明示的な
`PERFORM SECTION-NAME`だけを差し替え、作業場所とLINKAGEの前後像、正常・Mock・GOBACK・STOP RUN・
ABEND・例外の終了種別を記録する。段落、`THRU`、`GO TO`、fall-throughは差し替えない。
program MockとSECTION Mock/spyの記録にはテストセッション共通の単調増加通番を付ける。
コンパイラ生成の主entry `ProgramSignature`をテストcatalogへ登録し、program実行とSECTION直接起動の
前にLINKAGE引数の個数と固定バイト長を検査する。
コンパイラはSECTION・段落・宣言部分フラグ、段落範囲、source位置、直接起動適格性と
SHA-256 `procedureHash`を`Compiled`結果へ出し、`sourceText` / `source`で翻訳した対象は
Mock/spy登録時と直接起動時に通常SECTIONの実在性を検査する。
`fixture.invokeSection`は適格な通常SECTIONを既存のPERFORM範囲実行器で起動する。対象SECTION自身の
Mockは適用せず、その内側から明示的にPERFORMされる別SECTIONのMock / spyは適用する。
LINKAGEは参照渡し、WORKING-STORAGEは通常のprogram instanceと同じ寿命を持ち、`GOBACK`と
`STOP RUN`はprogram実行と同じ終了種別で返す。

現実装の適格性判定は意図的に保守的である。通常SECTION内の任意の深さに`GO TO`、
`GO TO ... DEPENDING ON`、`ALTER`、`NEXT SENTENCE`が一つでもあれば、実際にはSECTION内だけを
移動する安全な制御であっても直接起動を拒否する。宣言SECTIONも拒否する。これは範囲外遷移を
正常終了に見せないための暫定gateであり、正確なcontrol-flow graph解析を導入するまで、該当処理は
program-level testで検証する。
最初の実行後の上書き変更と `PER_CLASS` lifecycle は拒否する。

現時点では低レベル API であり、統合済み`CobolInvocationLog`、開始・終了時刻と深度、
copybook resolver、内容ハッシュ共有cache、完全なentry署名と呼出し側layout hash照合、program spy、複数deploy catalogの合成、
正確なcontrol-flow graph判定、型付きfixture、全失敗経路のlifecycle contract testは未実装である。
この差分を [P-091](../decisions/provisional.md#p-091-cobol-junit-初期版は低レベルプログラム境界に限定する) に記録する。

## テストの境界

テスト対象を3段階に分ける。

| 段階 | 起動単位 | 主な用途 | 差し替え可能な依存 |
| --- | --- | --- | --- |
| プログラム | `CobolSession.call` / `runMain` | 公開された COBOL 入出力契約 | 下位 `CALL`、明示的 `PERFORM SECTION` |
| SECTION | `fixture.invokeSection` | 計算や編集等の局所ロジック | 下位 `CALL`、別 SECTION の明示的 `PERFORM` |
| 実行シナリオ | 同じセッションで複数プログラムを実行 | 状態保持、`CANCEL`、ファイル、ジョブ境界 | 必要な外部境界だけ |

SECTION テストは COBOL 言語が定める独立したプログラム呼び出しではなく、処理系が提供する
テスト用入口である。SECTION の前段で本来行われる初期化は自動実行しない。必要な
`WORKING-STORAGE` と LINKAGE はテストが明示して準備する。

## モジュール構成

新しい Maven モジュール `cobol-junit` を追加する。

```text
application tests
    └── cobol-junit        JUnit Extension、fixture、Mock、assertion
          ├── cobol-compiler  テスト時コンパイル、手続きメタデータ
          └── cobol-runtime   CobolRuntime / CobolSession / DataView
```

`cobol-junit` だけが `org.junit.jupiter:junit-jupiter-api` に依存する。`cobol-runtime` へ
JUnit の型を持ち込まない。SECTION hook と手続きメタデータはデバッガやトレースでも使える
一般的な runtime SPI とし、JUnit 固有の fixture と検証 DSL は `cobol-junit` に閉じ込める。

利用側の Maven 依存は `test` scope とする。

```xml
<dependency>
  <groupId>dev.cobolonjava</groupId>
  <artifactId>cobol-junit</artifactId>
  <version>${cobol-on-java.version}</version>
  <scope>test</scope>
</dependency>
```

## JUnit ライフサイクル

`CobolExtension` は JUnit 5 の `BeforeEachCallback`、`AfterEachCallback`、
`ParameterResolver` を実装する。

```java
class CalculateTaxTest {

    @RegisterExtension
    final CobolExtension cobol = CobolExtension.builder()
            .source("src/test/cobol/CALCTAX.cbl")
            .copybookDirectory("src/main/copybook")
            .build();

    @Test
    void calculatesTax() {
        // fixture を使用する
    }
}
```

ライフサイクルは次のとおりである。

1. 最初の利用時にソースと copybook をコンパイルし、不変のカタログを構築する
2. `beforeEach` で捕捉出力、テスト用データセット領域、Mock 履歴を初期化する
3. テスト本体が stub / Mock と入力を登録する
4. 最初の COBOL 実行直前に上書き定義を固定し、新しい `CobolSession` を開く
5. テスト中は同じセッションを使用する
6. `afterEach` で期待呼び出しを検証し、セッションとファイルを閉じる
7. 失敗時だけ、マスキング済み呼び出し履歴と診断 ID を JUnit レポートへ添付する

セッションを最初の実行まで遅延生成するのは、テストメソッドごとに違う Mock を登録でき、かつ
`NODYNAM` の事前束縛より前に上書きを確定するためである。一度実行したあとの Mock 追加・削除は
`IllegalTestStateException` とする。

JUnit の既定ライフサイクルであるテストメソッドごとのインスタンスを前提にする。コンパイル結果は
内容ハッシュをキーに安全に共有するが、セッション、記憶域、出力、Mock 履歴は共有しないので、
JUnit の並列実行でも別テストの値が混ざらない。

テスト本体がすでに失敗している場合、`afterEach` の期待回数違反や close 失敗で元の失敗を
置き換えない。後処理の失敗は元の例外へ suppressed として追加し、すべての資源の close を続ける。

この失敗集約は `AfterEachCallback` だけで実現できると仮定しない。JUnit unique test ID を key にした
`ExtensionContext.Store`、test invocation の interception、cleanup callback を組み合わせ、通常失敗、
assertion、timeout、parameterized / repeated test、before / after failure の全経路を extension 自身の
contract test で固定する。元例外へ安全に suppressed を追加できない経路では、元例外を優先して
`publishReportEntry` に後処理失敗を記録する。

JUnit の `PER_CLASS` lifecycle と static `@RegisterExtension` は mutable session を共有し得るため初期版では
拒否する。対応する場合は method unique ID ごとに session / Mock / output を完全分離し、parallel execution
試験に合格した明示 opt-in とする。

## COBOL プログラムの実行

### 生成した型付きビューを使う

[設計 75](75-java-interop.md)の生成ビューを標準の入力・検証方法とする。

```java
@Test
void calculatesTax() {
    TaxParameters parameters = TaxParameters.allocate(cobol.codePage());
    parameters.setAmount(new BigDecimal("1200.00"));
    parameters.setCategory("STANDARD");

    CobolTestResult result = cobol.program("CALCTAX")
            .byReference(parameters.view())
            .call();

    assertEquals(new BigDecimal("120.00"), parameters.tax());
    assertEquals("00", parameters.status());
    assertEquals(0, result.returnCode());
    assertEquals(Termination.RETURNED, result.termination());
}
```

生成ファサードを使える場合は次の短縮形も提供する。

```java
CobolTestResult result = CalcTaxTestFacade.call(cobol.session(), parameters);
```

低レベル API では `Storage` と `DataView` を直接渡せる。`byte[]` の所有権を曖昧にしないため、
生の配列だけを受け取って暗黙に copy / wrap する API は作らない。

### 主プログラムを起動する

```java
CobolTestResult result = cobol.program("DAILYJOB")
        .runMain();

assertEquals(Termination.STOP_RUN, result.termination());
assertEquals(4, result.returnCode());
```

`runMain` と `call` は `EXIT PROGRAM` の意味が違うため、fixture が推測して選ばない。

### 同じ実行単位で繰り返す

```java
CobolProgramFixture counter = cobol.program("COUNTER");

counter.call();
counter.call();
assertEquals("002", counter.workingStorage(CounterWs::over).countText());

counter.cancel();
counter.call();
assertEquals("001", counter.workingStorage(CounterWs::over).countText());
```

同じテストメソッド内では同じセッションとプログラムインスタンスを使うため、
`WORKING-STORAGE` の保持と `CANCEL` を検証できる。

## SECTION の直接テスト

コンパイラは、名前付き SECTION と段落について次のメタデータを生成物マニフェストへ出す。

- 正規化済み `ProcedureId`
- 種別 `SECTION` / `PARAGRAPH`
- 先頭・末尾段落の内部番号
- 宣言部分か通常部分か
- ソースファイルと位置
- 直接起動適格性と不適格理由
- 手続き一覧のハッシュ `procedureHash`

数値の段落番号や `paragraph$3` のような生成メソッド名は公開しない。Java API は
`ProgramId + ProcedureKind + 正規化済み名前` からなる `ProcedureId` を使い、
`procedureHash` の不一致を実行前に検出する。

```java
@Test
void calculatesNetAmountInSection() {
    CobolProgramFixture fixture = cobol.program("CALCTAX")
            .byReference(parameters.view());

    TaxWorkingStorage ws = fixture.workingStorage(TaxWorkingStorage::over);
    ws.setGross(new BigDecimal("100.00"));
    ws.setDiscount(new BigDecimal("12.50"));

    CobolTestResult result = fixture.invokeSection("CALCULATE-NET");

    assertEquals(new BigDecimal("87.50"), ws.net());
    assertEquals(Termination.RETURNED, result.termination());
}
```

直接起動は対象 SECTION を合成的に `PERFORM` した範囲規則を使う。ただし本来の caller と PERFORM stack
が存在しないため、SECTION 内から範囲外への `GO TO`、ALTER 対象、宣言節への進入を正常な `GOBACK` に
読み替えない。初期版は `NonLocalProcedureTransferException` で失敗し、program-level test を要求する。
現実装は非構造化transfer文が一つでもあれば拒否する保守的な構文走査から、直接起動の適格性と理由を
manifestに出す。将来はcontrol-flow graphで範囲外遷移だけを識別する。対象 SECTION 自身に登録された
Mock は無視し、その内側から明示的に `PERFORM` する別 SECTION の Mock は有効とする。

宣言節とデバッグ節は通常の SECTION API から直接起動できない。専用の異常条件やデバッグ条件を
作って実行することで試験する。これは、本来存在しない呼び出し方を単体テストが固定するのを防ぐ
ためである。

## 外部サブルーチンの stub / Mock / spy

### stub

stub は戻り値や記憶域変更だけを与え、呼び出し回数を自動検証しない。

```java
cobol.stubProgram("RATEAPI", RateApiContract.SIGNATURE)
        .thenAnswer(call -> {
            RateRequest request = RateRequest.over(call.argument(0));
            RateResponse response = RateResponse.over(call.argument(1));
            assertEquals("JPY", request.currency());
            response.setRate(new BigDecimal("0.10"));
            response.setStatus("00");
        });
```

`BY REFERENCE` の引数は同じ `DataView` なので書き込みが COBOL へ届く。`BY CONTENT` の場合は
独立した写しに書くため呼び出し元へ届かない。同じ領域が複数引数に渡された場合も別名関係を保つ。

### Mock と期待呼び出し

```java
cobol.expectProgram("AUDIT", AuditContract.SIGNATURE)
        .times(1)
        .withArgument(0, bytes -> AuditRecord.over(bytes).event().equals("TAX-CALCULATED"))
        .thenReturn();

cobol.program("CALCTAX").byReference(parameters.view()).call();
```

`expectProgram` は既定で1回を期待し、`afterEach` で未達・過剰呼び出しを報告する。
単なる振る舞い差し替えには `stubProgram` を使う。呼ばれなかった stub を失敗にしないことで、
条件分岐の複数テストに同じ fixture 構成を再利用できる。

連続する呼び出しには `thenAnswer` を複数並べられる。定義していない回数へ達したときは、最後の
応答を繰り返さず `UnexpectedCobolCallException` にする。明示すれば `thenRepeatLast()` を使える。

### spy

```java
cobol.spyProgram("ROUNDING");

cobol.program("CALCTAX").byReference(parameters.view()).call();

assertEquals(2, cobol.invocations().program("ROUNDING").count());
```

spy は呼び出しを記録して本物を実行する。入力を書き換えてから本物へ進む spy はテストを読みにくく
するため標準 API にせず、必要なら明示的な `thenAnswer(...).thenCallRealProgram()` を使う。

### 失敗を注入する

```java
cobol.stubProgram("OPTIONALPGM", OptionalContract.SIGNATURE)
        .thenNotFound();

cobol.stubProgram("REMOTEAPI", RemoteContract.SIGNATURE)
        .thenAbend("U0042");
```

`thenNotFound` は解決失敗として COBOL の `ON EXCEPTION` 対象になる。`thenAbend` と Mock 本体の
未処理例外は実行開始後の異常なので `ON EXCEPTION` では捕まらない。[ADR-0004](../decisions/0004-call-failure-and-control-flow.md)
の分類をテストでも変えない。

### `CANCEL`

Mock プログラムもセッション単位のファクトリから作る。`CANCEL` 後は Mock インスタンスの状態を
初期化する一方、fixture が持つ呼び出し履歴と期待回数は残す。状態を持つ stub は singleton ではなく
`.thenAnswerFactory(...)` で新しい実装を作る。

## SECTION の Mock と spy

SECTION の差し替えは、対象名への**明示的な外部形式 `PERFORM`**だけに作用する。

```cobol
       MAIN-LOGIC.
           PERFORM READ-RATE
           PERFORM CALCULATE-TAX.

       READ-RATE SECTION.
           CALL 'RATEAPI' USING LK-RATE.
```

```java
cobol.mockSection("CALCTAX", "READ-RATE")
        .thenAnswer(section -> {
            TaxWorkingStorage ws = TaxWorkingStorage.over(section.workingStorage());
            ws.setRate(new BigDecimal("0.10"));
        });

cobol.program("CALCTAX").byReference(parameters.view()).call();
```

Mock が正常に返ると `READ-RATE` SECTION の末尾まで実行したものとして `MAIN-LOGIC` の
`PERFORM` の次へ進む。SECTION 本体は実行しない。

次の経路は Mock しない。

```cobol
           GO TO READ-RATE
           *> または、直前の段落から READ-RATE へそのまま流れ込む
```

これらには `PERFORM` の戻り先がなく、Mock 後にどこへ進めるかを決めると COBOL の制御フローを
変えてしまうためである。また `PERFORM READ-RATE THRU OTHER-END` のように登録 SECTION と異なる
範囲を指定した呼び出しも置換しない。

SECTION spy は呼び出しを記録して本物の範囲を実行する。

```java
cobol.spySection("CALCTAX", "CALCULATE-TAX");

cobol.program("CALCTAX").byReference(parameters.view()).call();

assertEquals(1, cobol.invocations()
        .section("CALCTAX", "CALCULATE-TAX").count());
```

Mock / spy 本体が使える `ProcedureInvocation` は次を持つ。

- プログラム ID、SECTION の `ProcedureId`、呼び出し元手続き、ソース位置
- 現在の `WORKING-STORAGE` と LINKAGE 引数
- コードページと特殊レジスタへの制限されたアクセス
- 呼び出し順を表すセッション内通番

Mock は `RETURN`、spy は `PROCEED` を hook へ返す。異常系は `THROW` で ABEND を注入する。
任意の段落番号を返して `GO TO` を模倣する API は設けない。

## Procedure hook

製品生成コードには JUnit を知らない軽量な `ProcedureHook` 呼び出しを置く。

```java
public interface ProcedureHook {
    ProcedureDecision before(ProcedureInvocation invocation);

    default void after(ProcedureInvocation invocation,
                       ProcedureOutcome outcome) {
    }
}
```

通常実行の `ProgramContext` は `ProcedureHook.NOOP` を持ち、常に `PROCEED` を返す。コンパイラは
名前付き外部形式 `PERFORM` の呼び出しサイトで次の形を生成する。

```text
invocation = beforeProcedure(program, procedureId, storage, context, arguments)
if decision == PROCEED:
    既存の performRange(from, through, ...) を実行
afterProcedure(invocation, outcome)
```

hook は段落の `dispatch` 自体には置かない。そこへ置くと fall-through と `GO TO` まで Mock され、
既存の `ALTER`、独立段、`USE FOR DEBUGGING` の意味が変わるためである。繰返し `PERFORM` では
実際に SECTION を呼ぶ各回に hook を通し、呼び出し回数も各回を1回と数える。

`after` は正常、`GOBACK`、`STOP RUN`、ABEND のすべてで呼ぶ。元の制御例外を置き換えないよう
`finally` 相当で記録したあと再送出する。hook 自身が失敗した場合は
`CobolTestInfrastructureException` とし、COBOL の ABEND と区別する。

## 呼び出し履歴と検証

`CobolInvocationLog` はプログラムと SECTION の呼び出しを1本の順序で記録する。

```text
1  PROGRAM CALCTAX        enter
2  SECTION READ-RATE      mocked return
3  SECTION CALCULATE-TAX  real return
4  PROGRAM AUDIT          stub return
5  PROGRAM CALCTAX        return
```

各記録は次を保持する。

- セッション内通番、開始・終了時刻、呼び出し深度
- プログラム / 手続き ID、実物・stub・Mock・spy の区分
- 開始時と終了時の引数バイトスナップショット
- 正常復帰、`STOP RUN`、未解決、ABEND 等の終了種別
- 診断 ID

履歴には `DataView` を保持しない。後続処理が同じ領域を書き換えると過去の引数まで変わるため、
呼び出し境界で必要な範囲をコピーする。大きな領域向けに、値スナップショットを無効化して
ハッシュと長さだけを記録する設定も持つ。

順序検証は ID で行う。

```java
cobol.verifyOrder(
        program("RATEAPI"),
        section("CALCTAX", "CALCULATE-TAX"),
        program("AUDIT"));
```

引数値の失敗メッセージは既定で長さとハッシュだけを出す。生成ビューを使った明示的な assertion は
対象フィールドの値を表示できる。業務データ全体を自動ダンプしない。

## 入出力と非決定値

テスト fixture は次を差し替えられる。

| 対象 | API | 既定 |
| --- | --- | --- |
| `ACCEPT` | `inputLines(...)` | 入力なし |
| `DISPLAY` 標準出力 | `stdout()` / `stdoutBytes()` | テストごとの捕捉領域 |
| `DISPLAY` 標準エラー | `stderr()` / `stderrBytes()` | テストごとの捕捉領域 |
| 日付・時刻 | `fixedClock(...)` | ランタイム既定時計 |
| 乱数 | `randomSeed(...)` | ランタイム既定値 |
| 外部スイッチ | `switchState(...)` | すべて off |
| データセット | `dataSet(ddName, records)` | テストごとの一時目録 |
| 出力データセット | `outputDataSet(ddName)` | テストごとの一時領域 |

文字列出力はセッションのコードページから復号する。`stdout()` は改行と末尾空白を保存し、勝手に
trim しない。比較を簡単にする `stdoutLines()` は改行だけを構造化し、空行を保存する。

データセットは JUnit の一時ディレクトリ配下へ置き、テスト終了時に閉じる。レコード様式、LRECL、
コードページを明示できる。入力レコードを Java 文字列から暗黙生成する便宜 API だけに依存せず、
ホストから採取した生バイト列も設定・検証できる。

## コンパイルとクラスロード

ソースの用意は2方式を提供する。

### テスト時コンパイル

`source` と `copybookDirectory` を extension に指定し、最初のテスト前にコンパイルする。
診断は COBOL ファイル名、行・桁、メッセージを含む `ExtensionConfigurationException` として
JUnit へ報告する。ソース、展開した copybook、オプション、コンパイラバージョンのハッシュが
同じなら不変の生成クラスをキャッシュする。

cache key は直接ソースだけでなく、推移的に展開した全 copybook の内容と解決順、`COPY REPLACING`、
compiler option、dialect、code page、runtime ABI、生成器版を Merkle hash に含める。cache は件数・byte 数の
上限と eviction を持ち、専用 classloader を close して強参照を残さない。異なるテスト成果物は一意な生成
namespace を使い、parent classpath 上の同名 class を誤って優先しない。

### 事前コンパイル済み成果物

本番と同じ JAR のプログラムマニフェストを `ProgramCatalog` へ読み込む。配備物との一致を重視する
統合テストではこちらを推奨する。`layoutHash` と `procedureHash` が生成 Java fixture と違えば、
テスト開始前に失敗させる。

生成クラスはテストクラスローダの子となる専用ローダへ置く。テストごとにローダを作らず、同じ
不変コンパイル結果を共有する。プログラムインスタンスと静的でない COBOL 状態はセッションごとに
作る。生成コードへ業務テストクラスのクラスローダを直接渡して任意クラスを探索させない。

## エラーの扱い

```java
CobolExecutionException error = assertThrows(
        CobolExecutionException.class,
        () -> cobol.program("CALCTAX").call());

assertEquals("S0C7", error.abendCode());
assertEquals("CALCULATE-TAX", error.cobolStack().getFirst().procedure());
```

JUnit assertion の失敗、Mock 本体の例外、COBOL の異常終了、テスト基盤の失敗を区別する。

| 種別 | JUnit への表現 |
| --- | --- |
| COBOL ABEND / 実行異常 | `CobolExecutionException` |
| 未解決プログラム | `CobolProgramNotFoundException` |
| Mock の期待回数・引数不一致 | `AssertionFailedError` |
| Mock Java 本体の例外 | `CobolMockException`。原因を保持 |
| コンパイル、hook、fixture の不具合 | `CobolTestInfrastructureException` |

Mock 本体で投げた JUnit の `AssertionFailedError` は包まず、そのままテスト失敗として伝える。
`CobolMockException` に包むのは Mock 本体から出た通常の `Exception` だけである。

`STOP RUN` は異常ではなく `CobolTestResult` の終了種別である。`STOP RUN` 後はセッションが終了するため、
同じテスト内で続けて呼ぶと `ClosedCobolSessionException` になる。

## 制約

- SECTION Mock は外部形式の明示的 `PERFORM SECTION-NAME` だけを置換する。
- fall-through、`GO TO`、異なる `THRU` 範囲、宣言節、デバッグ節、inline `PERFORM` は置換しない。
- SECTION 単独テストは前段の初期化を実行しない。入力状態はテストが準備する。
- Mock は同一スレッドで同期的に完了させる。`DataView` を非同期処理へ保持できない。
- COBOL の制御フローを Mock から任意の段落へ移すことはできない。
- 本番クラスと違う「テスト専用翻訳モード」は作らない。常設の `NOOP` hook を使う。
- SECTION direct invocation は control-flow graph が適格と判定した範囲に限定し、non-local transfer を
  program-level test の代替として正常化しない。

## 敵対的レビューで追加した実装開始ゲート

- `invokeSection` の non-local `GO TO`、ALTER、declarative 進入が deterministic に拒否されること。
- test 本体と after / close の二重失敗で primary failure が失われないこと。
- `PER_CLASS`、static extension、parallel、parameterized / repeated test の支持・拒否が明確であること。
- transitive copybook または compiler option の一文字変更で compile cache が確実に miss すること。
- cache eviction 後に classloader と一時生成物が解放されること。

## 実装変更点

### `cobol-runtime`

- `ProcedureId`, `ProcedureMetadata`, `ProcedureInvocation`, `ProcedureHook` を追加する
- `ProgramContext` に既定 `NOOP` の hook と呼び出し通番を持たせる
- プログラム定義のテスト用上書きをセッション構築時に受け取れるようにする
- SECTION を実物の段落範囲で起動する内部 SPI を追加する

JUnit の型、Mock DSL、assertion は追加しない。

### `cobol-compiler`

- SECTION / 段落メタデータと `procedureHash` をマニフェストへ出す
- 名前付き外部形式 `PERFORM` の前後に `ProcedureHook` 呼び出しを生成する
- SECTION 直接実行用の名前ベース bridge を生成する
- 既存の private `performRange` / `dispatch` と段落番号は内部のまま保つ

### `cobol-junit`

- `CobolExtension`, `CobolTestContext`, `CobolProgramFixture`, `CobolTestResult`
- source / copybook コンパイルと不変クラスキャッシュ
- プログラムと SECTION の stub / Mock / spy
- 呼び出し履歴、回数・順序・引数検証
- 入出力、時計、乱数、スイッチ、テスト用データセット設定
- JUnit 失敗時の診断添付と確実な資源解放

## 段階導入

### 第1段階: JUnit からプログラムを実行する

- `cobol-junit` モジュールと `CobolExtension` を追加する
- ソースのコンパイル、プログラム `call` / `runMain`、出力捕捉を提供する
- `Storage` / `DataView` の低レベル fixture を提供する
- テストメソッドごとのセッション分離と並列実行を検証する

### 第2段階: 外部サブルーチン Mock

- `ProgramOverrideSet` と stub / Mock / spy を追加する
- `BY REFERENCE`、`BY CONTENT`、別名領域、`CANCEL`、`ON EXCEPTION` を検証する
- 回数、順序、開始・終了スナップショットを提供する

### 第3段階: SECTION hook と直接実行

- SECTION・段落・宣言部分フラグ、範囲、source位置、直接起動適格性と `procedureHash` をコンパイル結果へ生成する（実装済み）
- signatureと手続きmanifestを生成classへ埋め込む（実装済み）
- program一覧・revisionを持つ独立deploy catalog manifestを生成する（単一catalogを実装済み）
- 明示的 `PERFORM SECTION` の `ProcedureHook` を追加する（実装済み）
- SECTION Mock / spy を追加する（実装済み）
- ソース翻訳対象のSECTION登録をmanifestで検査する（実装済み）
- `invokeSection` と保守的な非構造化control transfer拒否を追加する（実装済み）
- control-flow graphで範囲外transferだけを正確に拒否し、安全なローカル`GO TO`を許可する（未実装）
- `GO TO`、fall-through、`THRU` が誤って置換されないことを固定する（実装済み）

### 第4段階: 型付き fixture と運用統合

- copybook 生成ビューとテストファサードを統合する
- データセット fixture、失敗時診断、OpenTelemetry テスト出力を追加する
- `program(...)`で登録した現行生成classの埋込みmetadataを使う統合テスト経路（実装済み）
- JAR単位のdeploy catalog manifestを読み込む統合テスト経路（単一catalogを実装済み）
- 複数JARのcatalogを明示的な重複・revision規則で合成する（未実装）

## 受け入れ条件

- JUnit 5 の1テストメソッドから COBOL の `call` と `runMain` を実行できる。
- LINKAGE と `WORKING-STORAGE` を生成ビューまたは `DataView` で設定・検証できる。
- 出力、データセット、復帰コード、`GOBACK`、`STOP RUN`、ABEND を検証できる。
- `CALL` 先の COBOL / Java プログラムをテストごとに Mock / spy できる。
- 明示的に `PERFORM` する SECTION を Mock / spy し、SECTION 単独でも実行できる。
- SECTION 単独実行で範囲外 control transfer を正常復帰に見せず、非適格なら program-level test を要求する。
- fall-through、`GO TO`、異なる `THRU` 範囲は SECTION Mock によって変化しない。
- 呼び出し回数、順序、入力時・出力時引数を安定した ID とスナップショットで検証できる。
- テストごとの状態と一時資源が並列テスト間で混ざらず、終了時に解放される。
- 製品の `cobol-runtime` が JUnit または特定 Mock ライブラリへ依存しない。
