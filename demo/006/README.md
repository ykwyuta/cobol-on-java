# デモ #006: JUnit 5 による COBOL 単体テスト & Mocking デモ

本デモは、Java 標準のテストフレームワーク **JUnit 5** を用いて、COBOL プログラムに対する自動単体テストの実行、外部サブルーチンのモック化（`expectProgram`）、および内部セクションの監視・検証（`spySection`）を行うデモです。

`cobol-on-java` の `cobol-junit` モジュール（[設計 76 (JUnit による COBOL 単体テスト)](file:///d:/workspace/cobol-on-java/docs/design/76-junit-testing.md) 準拠）を利用することで、COBOL ソースコードを変更することなく Java 側からテストダブルを挿入し、CI/CD パイプラインに組み込めるテストスイートを構築できます。

---

## 構成ファイル

* [`LOAN-APP.cbl`](file:///d:/workspace/cobol-on-java/demo/006/LOAN-APP.cbl) : 融資審査 COBOL プログラム。
  - 外部サブルーチン `SCOREAPI`（信用スコア照会）を `CALL` し、内部セクション `EVALUATE-LIMIT` で融資限度額と採否判定を行います。
* [`LoanAppTest.java`](file:///d:/workspace/cobol-on-java/demo/006/LoanAppTest.java) : JUnit 5 テストクラス。
  - `@RegisterExtension CobolExtension` を用いて、テスト実行ごとに独立した COBOL 実行環境（`CobolSession`）をプロビジョニング。
  - 未実装・外部サービスの `SCOREAPI` を `cobol.expectProgram("SCOREAPI").thenAnswer(...)` でモック化。
  - 内部 `EVALUATE-LIMIT SECTION` の呼び出しを `spySection` で追跡し、実行結果引数（限度額・採否フラグ）を JUnit の `assertEquals` で検証。
* [`TestRunner.java`](file:///d:/workspace/cobol-on-java/demo/006/TestRunner.java) : JUnit Platform Launcher を使用したテスト起動クラス。
* [`run_demo.bat`](file:///d:/workspace/cobol-on-java/demo/006/run_demo.bat) : Windows 環境用一括ビルド・実行バッチファイル。

---

## テストケースの検証内容

1. **ケース 1: 高スコア顧客 (850点)**
   - `SCOREAPI` をモック化し、信用スコア 850 点を返却。
   - `EVALUATE-LIMIT` が正常に実行され、基本限度額（500万円）の2倍である **1,000万円（`10000000`）** で承認（`A`）されることをアサート。
2. **ケース 2: 低スコア顧客 (400点)**
   - `SCOREAPI` をモック化し、信用スコア 400 点を返却。
   - 否認（`R`）かつ限度額 **0円（`00000000`）** になることをアサート。

---

## 実行方法 (Windows)

```cmd
demo\006\run_demo.bat
```

### 実行結果出力例
```text
==================================================
 [JUnit 5] Running COBOL Unit Tests (LoanAppTest) 
==================================================

Test run finished after 613 ms
[         2 containers found      ]
[         0 containers skipped    ]
[         2 containers started    ]
[         0 containers aborted    ]
[         2 containers successful ]
[         0 containers failed     ]
[         2 tests found           ]
[         0 tests skipped         ]
[         2 tests started         ]
[         0 tests aborted         ]
[         2 tests successful      ]
[         0 tests failed          ]

[SUCCESS] All COBOL unit tests passed successfully!
```
