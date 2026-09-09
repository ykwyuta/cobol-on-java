# cobol-junit

JUnit 5 のテストメソッドから、テスト時に翻訳した COBOL を同一 JVM 内で実行する。
製品の `cobol-runtime` は JUnit に依存せず、このモジュールだけが Jupiter API を参照する。

```java
@RegisterExtension
final CobolExtension cobol = CobolExtension.builder()
        .source("src/test/cobol/CALLRATE.cbl")
        .build();

@Test
void callsRateProgram() {
    CobolProgramMock rate = cobol.expectProgram("RATEAPI")
            .thenAnswer((context, arguments) -> {
                arguments.get(0).setBytes(context.codePage().encode("010"));
            });

    CobolTestResult result = cobol.program("CALLRATE").call();

    assertEquals(0, result.returnCode());
    assertEquals(1, rate.count());
}
```

`byReference(DataView...)`、`call()`、`runMain()`、`workingStorage()`、`cancel()`、出力捕捉を
低レベル API として提供する。外部プログラムの上書きは最初の COBOL 実行より前に登録する。
ソースから翻訳したプログラムでは`PROCEDURE DIVISION USING`から生成したsignatureにより、実行前に
LINKAGE引数の個数と固定バイト長を検査する。不一致ならCOBOLへ入る前に
`ProgramSignatureMismatchException`となる。

明示的なSECTION呼び出しは次のように差し替えられる。

```java
cobol.mockSection("CALCTAX", "READ-RATE")
        .thenAnswer(invocation -> {
            TaxWorkingStorage.over(invocation.workingStorage()).setRate("010");
        });
cobol.spySection("CALCTAX", "CALCULATE-TAX");
```

対象は`PERFORM READ-RATE`のような通常SECTIONの明示的PERFORMだけである。段落PERFORM、
`PERFORM ... THRU ...`、`GO TO`、fall-throughの制御フローは変更しない。
extensionがソースから翻訳したプログラムでは、SECTION名を手続きmanifestと照合し、誤記や
宣言節の指定をCOBOL実行前に拒否する。現在のコンパイラで生成した事前コンパイル済みclassも
`program(...)`登録時に埋込みmanifestを読み、同じ早期検査を行う。手書き`CobolProgram`と旧生成classは
metadataを持たないため、SECTION名検査と直接起動を利用できない。

本番向けに生成済みの単一JAR／出力ディレクトリは、標準資源
`META-INF/cobol/programs.json`を持つクラスローダから登録できる。

```java
@RegisterExtension
final CobolExtension cobol = CobolExtension.builder()
        .deployCatalog(applicationClassLoader)
        .build();
```

この経路でも生成class内のABI署名と手続きmanifestを再照合する。標準資源が複数見つかる場合は
暗黙に一つを選ばず拒否するため、複数JARの合成は未対応である。

適格な通常SECTIONは単独でも起動できる。

```java
CobolProgramFixture fixture = cobol.program("CALCTAX")
        .byReference(linkageArgument);
fixture.workingStorage().setInt("RATE", 10);

CobolTestResult result = fixture.invokeSection("CALCULATE-TAX");
```

直接起動では対象SECTION自身に登録したMockは適用せず、その内部から明示的にPERFORMされる
別SECTIONのMock / spyは適用する。LINKAGEは参照渡しであり、WORKING-STORAGEは同じprogram
instanceに保持される。現段階では宣言SECTION、および`GO TO`、`GO TO ... DEPENDING ON`、
`ALTER`、`NEXT SENTENCE`を含むSECTIONを保守的に拒否する。安全なローカル`GO TO`も拒否対象なので、
該当処理はprogram-level testを使う。現在のコンパイラが生成した事前コンパイル済みclassは直接起動
できるが、手書き`CobolProgram`と旧生成classは埋込みmanifestがないため直接起動できない。

現時点の実装範囲と暫定制約は
[設計 76](../docs/design/76-junit-testing.md)および
[P-091](../docs/decisions/provisional.md#p-091-cobol-junit-初期版は低レベルプログラム境界に限定する)を参照する。
