# cobol-cics

Spring、Servlet、Db2 JDBCへ依存しないCICS実行境界のexperimental実装である。

現在の増分は次を提供する。

- 許可リスト型`CicsTransactionRegistry`と、正規化済み1〜4文字`TransId`
- task定義ごとのCOMMAREA、container件数、単体長、合計長の検査
- copy-in/copy-outの`CicsPayload`
- `LINK`、`XCTL`、`RETURN` (`IMMEDIATE`を含む)、`SYNCPOINT`の閉じたcommand/controlモデル
- 同一thread・同一`CobolSession`で`LINK`する`DefaultCicsGateway`
- 初期programを起動し、Java stackを増やさず`XCTL`を反復する`CobolCicsTaskProgram`
- task-scopedな`CicsExecution`を生成COBOLへ渡す型付きruntime service境界
- version、owner、期限、冪等keyを持つ`ConversationEnvelope`
- COBOL起動前の排他claimと、lease付きsave/complete/releaseを持つ`ConversationStorePort`
- claim、program実行、RETURN会話変更、UOW完了、abort、cleanupを順序付ける`CicsTaskCoordinator`
- 会話変更と業務UOWを各整合性profileで確定する`CicsTaskBoundary`
- 単一JVM用`InMemoryConversationStore` reference adapter
- BMSマクロ (DFHMSD / DFHMDI / DFHMDF) を読むfail-closedの`BmsParser`と、Thymeleaf等に依存しない
  中立画面定義`BmsModel`
- COBOL記号マップ写し句を作る`BmsSymbolicMapWriter`と、公開仕様のAID値から作る`DFHAID`
  (`CicsSystemCopybooks`)。形の根拠と未確認点は暫定判断P-112

`ConversationStorePort.load`は表示・診断用の読み取りであり、task実行権を与えない。会話を使って
COBOLを実行する入口は、必ず期待versionとownerを指定して`claim`し、成功したleaseだけを
`save`、`complete`または`release`へ渡す。task timeoutはlease期限より短く設定し、長時間実行では
将来のrenewal契約を追加するまでleaseを自動延長しない。

`CicsTaskBoundary.commit`の実装は、失敗時に`NOT_COMMITTED`または`UNKNOWN`を必ず明示する。
`UNKNOWN`ではcoordinatorはrollbackもlease解放も行わず、冪等keyとoutcome journalによる照会へ委ねる。
通常の例外を返したadapterも安全側で`UNKNOWN`相当として扱う。

`cobol-compiler`の初期`EXEC CICS`変換は、静的`PROGRAM` / `TRANSID`、単純データ名の`COMMAREA`、
正の数値定数または省略した`LENGTH` (省略時はCOMMAREA項目の長さ)、データ名の`PROGRAM`を使う`LINK` / `XCTL` / `RETURN` / `SYNCPOINT [ROLLBACK]`と、静的`ABCODE`、
`CANCEL`、`NODUMP`を使う`ABEND`を対象とする。task-local EIBの初期subsetとして`EIBTRNID`、
`EIBCALEN`、`EIBFN`、`EIBRCODE`、`EIBRESP`、`EIBRESP2`、`EIBTIME`、`EIBDATE`、`EIBTASKN`、`EIBTRMID`、
`EIBCPOSN`、`EIBAID`を公開し (日時・task番号・端末fieldの値の出どころは暫定判断P-113)、コマンド単位の
`RESP` / `RESP2` / `NOHANDLE`を扱う。
`RESP`と`NOHANDLE`は既定処理をそのコマンドだけ抑止し、非正常結果をEIBへ残す。`RESP` / `RESP2`の
受取項目は4byte binary整数に限定する。`DFHRESP`は現在結果を生成できる`NORMAL`と`PGMIDERR`を
翻訳時の数値定数へ変換し、未分類のcondition名は拒否する。`PGMIDERR`とgeneralized `ERROR`について、
`HANDLE CONDITION condition(paragraph)`、handler省略による既定処置への復帰、
`IGNORE CONDITION`を扱う。個別conditionの明示処置を`ERROR`より優先し、個別handlerを省略した場合は
`ERROR`へfallbackせずCICS既定処置を選ぶ。handler tableはCICS LINK levelごとに分離し、`RESP` / `NOHANDLE`は
登録済みhandlerもそのcommandだけ迂回する。handlerへの移動は生成programの段落制御結果で表し、
Javaの業務例外とは混同しない。同じLINK levelで通常COBOL `CALL`を重ねた先からでも、subsystem共通の
所有program付き制御結果をCALL境界で伝播し、登録元programの段落state machineへ戻す。
標準gatewayは未登録のLINK / XCTL targetを起動前に`PGMIDERR(27), RESP2=1`へ変換する。
XCTLのprobeは固定catalogへ副作用なしで照会し、target factoryやWORKING-STORAGEを先行生成しない。
完了した対応commandは`EIBFN`を更新し、プログラム制御PGMIDERRを
`EIBRCODE=X'010000000000'`へ変換する。未分類の非正常EIBRCODEは推測せず拒否する。
ABENDはtask ID、正規化済みcode、
handler取消し、dump要求を持つ`CicsAbend`としてtask boundaryのabortへ渡す。
COBOL向け`HANDLE ABEND LABEL(paragraph)`、`CANCEL`（option省略時の既定）、`RESET`を扱い、
明示ABEND時は現在levelから上位levelへ最初の有効なexitを探す。選択したexitは再入防止のため無効化し、
RESETで再有効化する。`ABEND CANCEL`は全levelのexitを迂回して必ずtaskを異常終了する。
一つのHANDLE / IGNOREには空白区切りで最大16 conditionを列挙でき、同じconditionの重複を拒否する。
`PUSH HANDLE`は同じCICS LINK level内のhandler table全体をLIFOで退避して効果を一時停止し、
`POP HANDLE`は直前の状態を復元する。
LINK先へ退避stackを継承しない。対応するPUSHのないPOPは実行時に拒否する。
`ASSIGN ABCODE(data-area)`は書込み可能な4byte英数字領域に限定し、abend exit内では現在の
abend codeを右側space paddingして返す。abend未発生時はspace 4byteを返し、完了時の
`EIBFN`は`X'0208'`になる。
未知optionを無視せず翻訳エラーにする。動的target / length、`HANDLE ABEND PROGRAM`、
channel / container、残りのEIB fieldは未対応である。ABENDコードはEIBRCODEではなく
`ASSIGN ABCODE`で公開する。`dumpRequested`は後続adapterへ渡す要求情報であり、
現増分はtransaction dumpの採取・永続化を行わない。

`InMemoryConversationStore`はcluster、process再起動、Spring Session連携、業務Db2との原子commitを
保証しない。本番用ではない。Spring Boot 4.1の`CicsTaskBoundary` adapter、STRICT会話表、
NON_ATOMIC outcome journal、lease renewal、残りのEIB field、BMSは後続増分である。
