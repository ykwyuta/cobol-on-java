# cobol-cics

Spring、Servlet、Db2 JDBCへ依存しないCICS実行境界のexperimental実装である。

現在の増分は次を提供する。

- 許可リスト型`CicsTransactionRegistry`と、正規化済み1〜4文字`TransId`
- task定義ごとのCOMMAREA、container件数、単体長、合計長の検査
- copy-in/copy-outの`CicsPayload`
- `LINK`、`XCTL`、`RETURN`、`SYNCPOINT`の閉じたcommand/controlモデル
- 同一thread・同一`CobolSession`で`LINK`する`DefaultCicsGateway`
- 初期programを起動し、Java stackを増やさず`XCTL`を反復する`CobolCicsTaskProgram`
- task-scopedな`CicsExecution`を生成COBOLへ渡す型付きruntime service境界
- version、owner、期限、冪等keyを持つ`ConversationEnvelope`
- COBOL起動前の排他claimと、lease付きsave/complete/releaseを持つ`ConversationStorePort`
- claim、program実行、RETURN会話変更、UOW完了、abort、cleanupを順序付ける`CicsTaskCoordinator`
- 会話変更と業務UOWを各整合性profileで確定する`CicsTaskBoundary`
- 単一JVM用`InMemoryConversationStore` reference adapter

`ConversationStorePort.load`は表示・診断用の読み取りであり、task実行権を与えない。会話を使って
COBOLを実行する入口は、必ず期待versionとownerを指定して`claim`し、成功したleaseだけを
`save`、`complete`または`release`へ渡す。task timeoutはlease期限より短く設定し、長時間実行では
将来のrenewal契約を追加するまでleaseを自動延長しない。

`CicsTaskBoundary.commit`の実装は、失敗時に`NOT_COMMITTED`または`UNKNOWN`を必ず明示する。
`UNKNOWN`ではcoordinatorはrollbackもlease解放も行わず、冪等keyとoutcome journalによる照会へ委ねる。
通常の例外を返したadapterも安全側で`UNKNOWN`相当として扱う。

`cobol-compiler`の初期`EXEC CICS`変換は、静的`PROGRAM` / `TRANSID`、単純データ名の`COMMAREA`、
正の数値定数`LENGTH`を使う`LINK` / `XCTL` / `RETURN` / `SYNCPOINT [ROLLBACK]`と、静的`ABCODE`、
`CANCEL`、`NODUMP`を使う`ABEND`を対象とする。task-local EIBの初期subsetとして`EIBTRNID`、
`EIBCALEN`、`EIBRESP`、`EIBRESP2`を公開し、コマンド単位の`RESP` / `RESP2` / `NOHANDLE`を扱う。
`RESP`と`NOHANDLE`は既定処理をそのコマンドだけ抑止し、非正常結果をEIBへ残す。`RESP` / `RESP2`の
受取項目は4byte binary整数に限定する。`DFHRESP`は現在結果を生成できる`NORMAL`と`PGMIDERR`を
翻訳時の数値定数へ変換し、未分類のcondition名は拒否する。ABENDはtask ID、正規化済みcode、
handler取消し、dump要求を持つ`CicsAbend`としてtask boundaryのabortへ渡す。
未知optionを無視せず翻訳エラーにする。動的target / length、`HANDLE CONDITION` / `IGNORE CONDITION`、
channel / container、残りのEIB fieldは未対応である。`CANCEL`は記録するが、`HANDLE ABEND`自体が
未実装のため取消すhandlerはまだ存在しない。`dumpRequested`も後続adapterへ渡す要求情報であり、
現増分はtransaction dumpの採取・永続化を行わない。

`InMemoryConversationStore`はcluster、process再起動、Spring Session連携、業務Db2との原子commitを
保証しない。本番用ではない。Spring Boot 4.1の`CicsTaskBoundary` adapter、STRICT会話表、
NON_ATOMIC outcome journal、lease renewal、残りのEIB field、BMSは後続増分である。
