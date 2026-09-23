# 設計 91: Maven による資産のビルドと標準ディレクトリ体系

| 項目 | 内容 |
| --- | --- |
| 対応要件 | FR-180 (翻訳の起動口)、FR-090 (写し句)、FR-131 / FR-132 (ジョブ記述)、FR-162 (BMS の記号マップ) |
| ステータス | `cobol-maven-plugin` の `compile` / `jcl` を実装済。見本は [デモ 010](../../demo/010/README.md) |

## 1. 何を決めるか

これまで資産を翻訳する入口は、各モジュールの `Main` (`cobolc` / `plic` / `cobolj`) だけだった。
デモは `mvn exec:java` で `Main` を呼び、置き場も出力先もデモごとに違っていた。

利用者のプロジェクトで COBOL・PL/I・JCL を<b>`mvn package` 1 回で</b>作れるようにし、
そのための<b>標準の置き場</b>を決める。

## 2. 標準ディレクトリ体系

Maven の `src/main/java` に倣い、<b>言語と役割ごとに 1 つの置き場</b>を持つ。ホストでは原文・
写し句・手続きがそれぞれ別の区分データセット (ライブラリ) に入っている。その
<b>1 ライブラリを 1 ディレクトリ</b>へ写す。

```text
my-batch/
├── pom.xml
└── src/
    └── main/
        ├── cobol/          COBOL の原文        .cbl .cob .cobol        (ホストの SRCLIB)
        ├── copybook/       COPY の写し句       .cpy または拡張子なし   (SYSLIB)
        ├── bms/            BMS の mapset       .bms                     (記号マップの元。原文も配る)
        ├── pli/            PL/I の原文         .pli .pl1
        ├── pli-include/    %INCLUDE のメンバ   .inc .pli または拡張子なし
        ├── jcl/            ジョブ記述          .jcl (JCL) / .job (宣言的形式)
        ├── proclib/        目録手続きと JCL の INCLUDE メンバ          (PROCLIB / JCLLIB)
        ├── java/           Java (CobolSession で呼ぶ側、JavaCallable 等)
        └── resources/      そのほかの資源
```

| 決まり | 理由 |
| --- | --- |
| 置き場の下の<b>下位ディレクトリは整理のためだけ</b>にある | プログラムの名前は `PROGRAM-ID` が決める。ロードライブラリに階層が無いのと同じである |
| `copybook/` の下位ディレクトリは `COPY 名前 OF ライブラリ` のライブラリ名になる | `DirectoryCopyBookResolver` の既存の規則 |
| 拡張子の大文字小文字は問わない | ホストから落とした資産は `.CBL` であることが多い |
| `cobol/` の `.cpy` は原文として拾わない | 写し句を原文と同じ置き場に置く資産があっても、単独で翻訳して誤りにしない |
| 並びは名前順に固定する | ファイル体系の列挙順で翻訳の順や診断の順が変わらないようにする |

写し句は `copybook/` (複数書ける。書いた順) → `bms/` → CICS・Db2・LE が配る写し句の順に探す。
`COPY TODOSET.` は、同じ名前の写し句が無ければ `bms/TODOSET.bms` から作った記号マップになる。
手書きの写し句を置けばそちらが勝つ。ホストで生成済みの記号マップを SYSLIB に置いた資産と
同じ振る舞いである。

### 成果物 (jar) の中身

| 置き場 | 中身 |
| --- | --- |
| `cobol/generated/*.class` | COBOL の生成クラス |
| `pli/generated/*.class` | PL/I の生成クラス |
| `META-INF/cobol/programs.json` | 配備カタログ (設計 75 / 76) |
| `/*.bms` | BMS の原文。実行時に `/名前.bms` で読む既存の使い方 (デモ 009) に合わせて classpath の根に置く |
| `META-INF/cobol/jcl/` | 検めたジョブ記述 (下位ディレクトリごと) |
| `META-INF/cobol/proclib/` | 目録手続き |

## 3. `cobol:compile` — プログラムを作る

`compile` の段で動き、次を行う。

1. `bms/` の mapset を<b>1 本ずつ</b>検め、classpath の根へ写す。`COPY` されない mapset は
   翻訳の途中で読まれないので、ここで読まないと誤りが実行時まで残る。根に置くので名前が
   重なれば断る
2. `cobol/` を翻訳する (`CobolBuild`)
3. `pli/` を翻訳する (`PliBuild`)

1 本が失敗しても<b>残りは翻訳し、診断をすべて出してから</b>ビルドを止める。1 本目で止めると、
直して流し直すたびに次の 1 本しか見えない。

### 翻訳の手順はコマンドラインと共有する

プラグインは `Main` を呼ばない。`Main` は `System.exit` するので Maven ごと落ちる。
かといってプラグインの中に同じ手順を書き写すと、<b>`cobolc` で通るのに `mvn` で通らない</b>
食い違いが生まれうる。

そこで手順を `CobolBuild` / `PliBuild` / `JobDescription` へ切り出し、`Main` とプラグインの
両方がそれを呼ぶ形にした。写し句の探索順、配備カタログの書き方、ジョブ記述の形式の見分け方は
それぞれ 1 か所にしかない。

### 増分翻訳はしない

毎回すべてを翻訳し直す。写し句 1 本の変更がどのプログラムに及ぶかを追うには `COPY` の
依存を記録する必要があり、配備カタログは 1 回の翻訳で作ったプログラムすべてから作る
(revision が全クラスの hash である)。部分的に翻訳するとカタログが古いクラスを指しうる。
翻訳の速さが問題になったら、依存の記録と合わせて考える。

### 設定

| パラメータ | 既定 | 意味 |
| --- | --- | --- |
| `cobolSourceDirectory` | `src/main/cobol` | |
| `copybookDirectories` | `src/main/copybook` | 複数書ける。書いた順に探す |
| `bmsDirectory` | `src/main/bms` | |
| `pliSourceDirectory` | `src/main/pli` | |
| `pliIncludeDirectories` | `src/main/pli-include` | 複数書ける |
| `freeFormat` | `false` | COBOL を自由形式で読む |
| `compilerOptions` | なし | `CBL` 文と同じ綴り。`SSRANGE,ARITH(EXTEND)` |
| `skip` | `false` | `-Dcobol.skip` |

## 4. `cobol:jcl` — ジョブ記述を検める

JCL に「翻訳」は無い。そのかわり、実行時に `cobolj` が読むのと<b>同じ読み取り</b>
(`JobDescription`) をビルドの時点で通す。目録手続きの展開も通すので、`EXEC 手続き名` の
書き誤りや `proclib/` に無い手続きも<b>実行より前</b>に分かる。知らない書き方を誤りにする
方針 (設計 90) はそのまま効く。

`process-classes` の段で動く。

### 検めないこと

`PGM=` がこのモジュールで作ったプログラムを指しているかは見ない。ユーティリティ
(`IEBGENER`、`SORT` など) や、依存する別の jar のプログラムを呼ぶのは普通のことであり、
ビルドの時点では揃っているかを決められない。

## 5. 利用者の pom

```xml
<dependencies>
  <!-- 生成クラスが実行時に要る。JCL を動かすなら cobol-job、PL/I なら pli-compiler -->
  <dependency>
    <groupId>dev.cobolonjava</groupId>
    <artifactId>cobol-job</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>dev.cobolonjava</groupId>
      <artifactId>cobol-maven-plugin</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <executions>
        <execution>
          <goals>
            <goal>compile</goal>
            <goal>jcl</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

プラグインの接頭辞は `cobol` である (`mvn cobol:compile`。`pluginGroups` に
`dev.cobolonjava` を足した場合)。

## 6. COBOL と PL/I を 1 つのモジュールに置けない (P-182)

配備カタログは 1 つの classpath に 1 つで、載せられる package も 1 つである
(`DeployCatalogManifest.allowedPackage`)。COBOL は `cobol.generated`、PL/I は
`pli.generated` へ出すので、同じ `target/classes` に書くと<b>後から書いたほうのカタログが
先のものを黙って消す</b>。

黙って片方を落とすより断る。両方の原文があれば、何も書かずにビルドを止める。当面は
言語ごとにモジュールを分ける (デモ 010 の `sales` と `greet`)。

解消条件は [P-182](../decisions/provisional.md#p-182-cobol-と-pli-を-1-つのモジュールで作れない) に書いた。

## 7. まだ無いもの

| 項目 | 扱い |
| --- | --- |
| `src/test/cobol` | 置かない。`cobol-junit` は classpath の配備カタログを<b>1 つだけ</b>読む。test 用に 2 つ目を作ると読めなくなる。試験する COBOL は `src/main/cobol` に置き、JUnit から呼ぶ |
| HLASM (`src/main/asm`) | まだ入れていない。`hlasm-assembler` は配備カタログを書かないので、ここに載せる前にカタログの扱いを決める |
| ジョブを動かす goal | 無い。実行はビルドではなく運用の側であり、`cobolj` (`dev.cobolonjava.job.Main`) で動かす |
| 原文の文字コード | UTF-8 固定。コマンドラインと同じ。EBCDIC の原文は取り込むときに変換する |
