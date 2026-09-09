# ADR-0004: 制御終了、解決失敗、実行失敗を別の結果・例外として扱う

| 項目 | 内容 |
| --- | --- |
| 状態 | 採用 |
| 日付 | 2026-09-09 |
| 関連要件 | FR-067, FR-082, FR-084, FR-142, NFR-052, NFR-060 |
| 関連設計 | [設計 60](../design/60-procedure.md), [設計 75](../design/75-java-interop.md) |

## 文脈

JVM 上では、COBOL の `GOBACK` と `STOP RUN`、未解決 `CALL`、データ例外、Java アダプタが
投げた例外のすべてを Java の例外で表現できる。しかし、それらは COBOL から見た意味と
受け止める場所が異なる。すべてを `RuntimeException` として公開すると、正常な `GOBACK` が
障害に見えたり、`CALL ... ON EXCEPTION` が業務処理内の例外まで隠したりする。

## 決定

ランタイム内部と Java 公開境界で、次の分類を維持する。

| 分類 | 例 | COBOL 内部での扱い | Java 呼び出し側への扱い |
| --- | --- | --- | --- |
| 正常復帰 | `GOBACK`、手続き部末尾 | 直前の呼び出しへ戻る | `CobolCallResult` |
| 実行単位の正常終了 | `STOP RUN` | 最外の境界まで抜ける | 終了種別 `STOP_RUN` の結果。セッションは終了 |
| 解決失敗 | 未登録名、ロード不能 | `ON EXCEPTION` の対象。なければ異常終了 | `CobolProgramNotFoundException` |
| 契約違反 | 引数個数・長さ・レイアウト不一致 | 呼び出し元の不備として異常終了 | `CobolCallContractException` |
| COBOL 実行失敗 | データ例外、ファイル異常、明示 ABEND | 規定の宣言節等がなければ異常終了 | `CobolExecutionException` |
| Java 実装失敗 | 登録アダプタが投げた `Exception` | Java 呼び出し異常として異常終了 | `CobolExecutionException`。元例外を cause に保持 |

`CALL ... ON EXCEPTION` が受け止めるのは、呼び先を開始できない「解決失敗」だけとする。
引数契約違反や開始後の Java 業務例外を同じ分岐へ流さない。`NOT ON EXCEPTION` は呼び先が
正常に開始され、正常復帰した場合だけ実行する。

Java アダプタ境界では `Exception` を捕捉してプログラム名、呼び出し履歴、原因を付け直す。
`Error`、`ProgramStop`、`ProgramReturn` はアダプタ障害として包まない。Java アダプタが
COBOL へ業務エラーを返す場合は、例外ではなく契約に定義した状態コードまたは応答項目へ書く。

Java 公開例外は、COBOL の呼び出し履歴と診断 ID を持つ。記憶域ダンプは例外メッセージへ
自動展開せず、既存の `DumpLevel` とマスキング設定を通した診断出力にだけ載せる。
`CobolCallResult` は少なくとも終了種別と `RETURN-CODE` を返す。

## 影響

- Java ホストは通常復帰、`STOP RUN`、障害を安定した型で区別できる。
- Java アダプタは、予想される業務上の不成立を状態項目として設計する必要がある。
- 現在の `runFresh` が `GOBACK` と `STOP RUN` をともに飲み込む実装は、公開境界で終了種別を
  保持できるように分離する必要がある。
- 例外ログに引数の `toString` や生バイトを無条件で含めないため、NFR-052 のマスキング方針と
  矛盾しない。

## 却下した案

### すべての失敗を `CALL ... ON EXCEPTION` へ渡す

Java 実装の不具合や契約不一致を COBOL の通常分岐が隠し、部分更新された引数で処理を続ける
おそれがあるため採用しない。

### COBOL の RETURN-CODE だけで全結果を返す

`RETURN-CODE` は業務上の終了値であり、呼び先未解決や JVM の障害を一意に表さない。
診断情報と cause も失われるため採用しない。

### Java アダプタの例外をそのまま COBOL 呼び出し元へ投げる

COBOL プログラム名と呼び出し履歴を付けられず、公開 API が各アダプタの例外型へ依存するため
採用しない。
