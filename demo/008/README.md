# デモ #008: Spring Boot & Db2 SQL 連携デモ

本デモは、モダンなエンタープライズ Java 基盤である **Spring Boot 4.x / Spring Framework 7** のトランザクション管理（`PlatformTransactionManager`）と協調し、Db2 互換 SQL（`SELECT`, `UPDATE`）および COBOL ビジネスロジックを同一の作業単位（Unit of Work）内で実行・コミット・ロールバックする動作デモです。

`cobol-on-java` の `cobol-spring-boot-4-autoconfigure` および `cobol-db2` モジュール（[設計 77 (Spring Boot / CICS / Db2 連携)](file:///d:/workspace/cobol-on-java/docs/design/77-spring-cics-db2.md) 準拠）を利用することで、COBOL のホスト変数型（パック10進数、2進整数）と JDBC / リレーショナル DB 間の正確なエンコード・デコードをゼロコピーで担保しつつ、Spring 管理トランザクションによるロールバックの安全性を実現します。

---

## 構成ファイル

* [`ACC-PROCESS.cbl`](file:///d:/workspace/cobol-on-java/demo/008/ACC-PROCESS.cbl) : 口座残高計算 COBOL プログラム。
  - 口座番号、取引種別（`D`: 預金, `W`: 引出）、取引金額を受け取り、残高更新後の新残高およびステータス（`OK` または `NS`: 残高不足）を返却します。
* [`SpringDb2DemoMain.java`](file:///d:/workspace/cobol-on-java/demo/008/SpringDb2DemoMain.java) : Spring トランザクション & Db2 SQL 連携ランナー。
  - H2 インメモリ DB 上に口座テーブルを作成。
  - `SpringManagedUnitOfWorkPort` と `SpringManagedSqlExecutor` を使用して `Db2TaskRuntime` を起動。
  - COBOL ホスト変数マッピングによる SQL 実行と COBOL プログラム呼び出しを結合。
* [`run_demo.bat`](file:///d:/workspace/cobol-on-java/demo/008/run_demo.bat) : Windows 環境用一括ビルド・実行バッチファイル。

---

## トランザクションシナリオの検証内容

1. **トランザクション 1: 預金取引 (Deposit $500.00)**
   - SQL `SELECT balance INTO :balanceView` で初期残高 $1,500.00 を取得。
   - COBOL `ACC-PROCESS` を実行して新残高 $2,000.00 を算出（ステータス `OK`）。
   - SQL `UPDATE account SET balance = ?` を実行し、`task.commit()` で DB へ確定反映。
2. **トランザクション 2: 引出取引 ($5,000.00 引出・残高不足)**
   - SQL `SELECT balance` で現在の残高 $2,000.00 を取得。
   - COBOL `ACC-PROCESS` が残高不足を検知しステータス `NS` を返却。
   - `task.rollback(...)` がトリガーされ、取引が安全に取り消されることを確認。
3. **最終 DB 状態検証**
   - トランザクション 1 のコミット結果（$2,000.00）が維持され、トランザクション 2 の変更が確実に破棄されていることを検証。

---

## 実行方法 (Windows)

```cmd
demo\008\run_demo.bat
```

### 実行結果出力例
```text
==================================================
 [Spring & Db2] cobol-on-java DEMO #008           
==================================================
[DB Setup] Initial Account (ID=1001) Balance: 1500.00

--- [Transaction 1] Deposit $500.00 (Commit Expected) ---
  [SQL SELECT] Current Balance in DB: 1500.00
--------------------------------------------------
[COBOL:ACC-PROCESS] Processing Account ID: 1001
[COBOL:ACC-PROCESS] New Balance Calculated:   2,000.00
--------------------------------------------------
  [COBOL Result] Status: OK, ReturnCode: 0
  [SQL UPDATE] Balance updated to: 2000.00
  [Transaction] COMMIT completed successfully.

--- [Transaction 2] Withdraw $5000.00 (Insufficient Funds -> Rollback) ---
  [SQL SELECT] Current Balance in DB: 2000.00
--------------------------------------------------
[COBOL:ACC-PROCESS] Processing Account ID: 1001
[COBOL:ACC-PROCESS] INSUFFICIENT FUNDS!
[COBOL:ACC-PROCESS] New Balance Calculated:   2,000.00
--------------------------------------------------
  [COBOL Result] Status: NS, ReturnCode: 0
  [Transaction] ROLLBACK triggered due to status: NS
==================================================
[Final Verification]
  - Expected Account Balance: 2000.00
  - Actual Account Balance  : 2000.0
==================================================
```
