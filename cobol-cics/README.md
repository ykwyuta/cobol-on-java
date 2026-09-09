# cobol-cics

Spring、Servlet、Db2 JDBCへ依存しないCICS実行境界のexperimental実装である。

現在の増分は次を提供する。

- 許可リスト型`CicsTransactionRegistry`と、正規化済み1〜4文字`TransId`
- task定義ごとのCOMMAREA、container件数、単体長、合計長の検査
- copy-in/copy-outの`CicsPayload`
- `LINK`、`XCTL`、`RETURN`、`SYNCPOINT`の閉じたcommand/controlモデル
- 同一thread・同一`CobolSession`で`LINK`する`DefaultCicsGateway`
- version、owner、期限、冪等keyを持つ`ConversationEnvelope`
- COBOL起動前の排他claimと、lease付きsave/complete/releaseを持つ`ConversationStorePort`
- 単一JVM用`InMemoryConversationStore` reference adapter

`ConversationStorePort.load`は表示・診断用の読み取りであり、task実行権を与えない。会話を使って
COBOLを実行する入口は、必ず期待versionとownerを指定して`claim`し、成功したleaseだけを
`save`、`complete`または`release`へ渡す。task timeoutはlease期限より短く設定し、長時間実行では
将来のrenewal契約を追加するまでleaseを自動延長しない。

`InMemoryConversationStore`はcluster、process再起動、Spring Session連携、業務Db2との原子commitを
保証しない。本番用ではない。Spring Boot 4.1 adapter、STRICT会話表、NON_ATOMIC outcome journal、
task coordinator、EIB、BMS、CICS compiler translationは後続増分である。
