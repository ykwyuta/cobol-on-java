# デモ #010: Maven の標準ディレクトリ体系でのビルド

資産を[標準の置き場](../../docs/design/91-maven-build.md)に置き、`cobol-maven-plugin` で
`mvn package` 1 回で作る見本です。`exec:java` で `Main` を呼ぶ手順は要りません。

## 構成

```text
demo/010/
├── pom.xml                      2 つのモジュールをまとめる。プラグインの版と goal をここで決める
├── sales/                       COBOL と JCL のモジュール
│   ├── pom.xml
│   └── src/main/
│       ├── cobol/               GEN-SALES.cbl, PRT-SALES.cbl
│       ├── copybook/            SALESREC.cpy (2 本が共有するレコードの形)
│       ├── jcl/                 SALESJOB.jcl
│       └── proclib/             SALESRPT (目録手続き)
└── greet/                       PL/I のモジュール
    ├── pom.xml
    └── src/main/
        ├── pli/                 HELLO.pli
        └── pli-include/         GREETING.inc
```

COBOL と PL/I をモジュールに分けているのは、1 つのモジュールに置けないためです
([P-182](../../docs/decisions/provisional.md))。

## ビルドで起きること

| goal | 段 | すること |
| --- | --- | --- |
| `cobol:compile` | `compile` | `COPY SALESREC` を `copybook/` から引いて COBOL を翻訳する。PL/I は `%INCLUDE GREETING` を `pli-include/` から引く。クラスと配備カタログを `target/classes` へ |
| `cobol:jcl` | `process-classes` | `SALESJOB.jcl` を実行時と同じ読み取りで検める。`EXEC SALESRPT` は `proclib/` から展開する。JCL と手続きを `META-INF/cobol/jcl/` と `META-INF/cobol/proclib/` へ |

`sales` の jar の中身は次のとおりです。

```text
cobol/generated/GEN_SALES.class
cobol/generated/PRT_SALES.class
META-INF/cobol/programs.json
META-INF/cobol/jcl/SALESJOB.jcl
META-INF/cobol/proclib/SALESRPT
```

JCL を書き誤ると、実行するより前にビルドが止まります。

```text
[ERROR] ...\SALESJOB.jcl:2: no such procedure or member: NOSUCHPROC
[ERROR] Failed to execute goal dev.cobolonjava:cobol-maven-plugin:0.1.0-SNAPSHOT:jcl ... 1 job description(s) have errors
```

## 動かす

```cmd
demo\010\run_demo.bat
```

```sh
demo/010/run_demo.sh
```

処理系を `mvn install` し、デモを `mvn package` し、作ったものを動かします。

```text
GEN-SALES: TARGET MONTH = 202609
GEN-SALES: OUTPUT COMPLETED (3 RECORDS).
...
GRAND TOTAL  :      12,200 JPY
SALESJOB.GEN ENDED - RC=0
SALESJOB.REPORT ENDED - RC=0
HELLO FROM PL/I                      1
HELLO FROM PL/I                      2
HELLO FROM PL/I                      3
```

PL/I の `PUT LIST` は SYSPRINT を PRINT ファイルとして書くので、2 つ目の項目は tab 位置 25 桁目から
始まります。`N` は `FIXED BIN(31)` なので、幅 14 の欄に右寄せされます
([P-183](../../docs/decisions/provisional.md))。

`sales` の COBOL・JCL は [デモ #003](../003/README.md) と同じ業務で、
レコードの形を写し句へ、レポートの段を目録手続きへ切り出しています。
