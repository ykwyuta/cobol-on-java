# 検討報告: MFS (Message Format Service) を BMS と類似方針 (中立モデル + Web UI) で扱う設計検討

| 項目 | 内容 |
| :--- | :--- |
| **作成日** | 2026-09-11 |
| **対象サブシステム** | IMS TM (Message Format Service: MFS) / 端末画面・電文マッピング層 |
| **参考設計** | [ADR-0010 (BMS の中立モデル + Thymeleaf/JS/CSS 再現)](decisions/0010-bms-thymeleaf-terminal-ui.md), [設計文書 77 (Spring Boot CICS/BMS)](design/77-spring-cics-db2.md) |
| **関連文書** | [docs/ims-overview-and-support-scope.md](ims-overview-and-support-scope.md), [docs/ims-tm-and-mq-architecture.md](ims-tm-and-mq-architecture.md) |

---

## 1. はじめに：MFS と BMS の本質的な違い

CICS の画面制御である **BMS (Basic Mapping Support)** については、すでに [ADR-0010](decisions/0010-bms-thymeleaf-terminal-ui.md) において **「中立な `BmsScreenModel` を構築し、Spring Boot (Thymeleaf + CSS Grid + 端末エミュレーション JS) でブラウザ上に 3270 画面を再現する」** という方針が決定されています。

MFS（Message Format Service）に対しても同様のアプローチを適用することを検討しますが、そのためには **BMS と MFS の根本的な設計思想の違い** を正しく認識する必要があります。

### 1.1 BMS と MFS の比較

| 観点 | CICS: BMS | IMS TM: MFS |
| :--- | :--- | :--- |
| **主な目的** | 画面のレイアウト定義と COBOL レコードの相互マッピング | 端末画面だけでなく、**「外部電文（メッセージ）」と「COBOL 入出力領域」の完全なデータフォーマッティング** |
| **定義の構造** | **1対1**<br>`DFHMSD` (マップセット) ⊃ `DFHMDI` (マップ) ⊃ `DFHMDF` (フィールド) | **2層構造（メッセージ層とデバイス層の分離）**<br>- **MSG 側**: `MSG` / `LPAGE` / `MFLD` (COBOL I/O レコード定義)<br>- **DEV 側**: `DEV` / `DPAGE` / `DFLD` (3270 物理画面定義) |
| **COBOL 側の記述** | `EXEC CICS SEND MAP(...)` / `RECEIVE MAP(...)` | **プログラム側には画面操作コマンドが一切現れない**。<br>単に `CALL 'CBLTDLI' USING 'GU  ', IO-PCB, IN-BUFFER` を呼ぶだけ。 |
| **画面制御のトリガー** | COBOL プログラムが能動的に「今からこのマップを表示する」と指示 | **IMS 制御領域（MFS エンジン）が背後で自動フォーマット**。<br>端末から Enter された電文を MFS が COBOL のバッファ形式に変換してキューに入れ、COBOL が出力したバッファを MFS が画面形式に変換して端末へ描画。 |
| **ページネーション** | アプリケーションが `SEND MAP ACCUM` や PAGING を制御 | MFS 自身が **オペレータ論理ページング（Operator Logical Paging）** を持ち、次ページ送り（`/FOR` や PA2 キー）を自動処理 |

---

## 2. BMS 類似方針（ADR-0010 方針）を MFS に適用したアーキテクチャ

BMS と同様に **「MFS 定義から中立スクリーンモデル（`MfsScreenModel`）を生成し、共通の Web UI (Thymeleaf / CSS Grid / JS) でレンダリングする」** という方針を適用すると、以下のようなアーキテクチャになります。

```
[ ブラウザ (3270 端末エミュレーション UI: CSS Grid + JS) ]
          |
          | ① HTTP POST (JSON: 入力フィールド値, AIDキー, カーソル位置)
          v
+-------------------------------------------------------------------------+
| `cobol-ims-mfs` (MFS 中立フォーマッタ層)                                |
|                                                                         |
|   1. 入力メッセージフォーマット (Device Input Format: DIF/MID)          |
|      - HTTP 入力値 (DFLD) を MFS ルールに従ってマッピング               |
|      - MFLD への転記、充填文字（FILL）、パディング、シフト処理          |
|      - COBOL 用の生バイトバッファ (`IN-BUFFER`) を生成                   |
|                                                                         |
|   2. キューへの電文格納                                                 |
|      - トランザクションキュー（RabbitMQ 等）へ投入                       |
+-------------------------------------------------------------------------+
          |
          | ② CALL 'CBLTDLI' (GU) でデキュー
          v
+-------------------------------------------------------------------------+
| COBOL 業務プログラム (MPP)                                              |
|   - CALL 'CBLTDLI' USING GU, IO-PCB, IN-BUFFER.                         |
|   - (プログラムは MFS や画面の存在を意識せず純粋な電文として処理)       |
|   - CALL 'CBLTDLI' USING ISRT, IO-PCB, OUT-BUFFER.                     |
+-------------------------------------------------------------------------+
          |
          | ③ CALL 'CBLTDLI' (ISRT) でエンキュー
          v
+-------------------------------------------------------------------------+
| `cobol-ims-mfs`                                                         |
|                                                                         |
|   3. 出力メッセージフォーマット (Device Output Format: DOF/MOD)         |
|      - COBOL の `OUT-BUFFER` (MFLD) を 物理画面フィールド (DFLD) へ展開 |
|      - 色・輝度・保護属性・カーソル初期位置の決定                       |
|      - 中立な `MfsScreenModel` (BMS の `BmsScreenModel` とほぼ同一) 構築 |
+-------------------------------------------------------------------------+
          |
          | ④ Thymeleaf 共通テンプレート + CSS Grid で HTML レンダリング
          v
[ ブラウザ (3270 端末エミュレーション UI) ]
```

---

## 3. BMS との共通化できる部分（再利用可能な資産）

ADR-0010 で設計・実装されるコンポーネントの多くが、そのまま、あるいは微小な拡張で流用可能です。

1. **共通端末 UI レイヤ (ブラウザ側)**:
   - **CSS Grid 固定セル配置**: 3270 の 24行×80桁（または 32×80、43×80）の固定グリッド描画は完全に同一。
   - **JavaScript 端末ステートマシン**: カーソル移動、Tab/Backtab、英数字/漢字入力、Insert/Overwrite、MDT（修正データタグ）、PF/PA キー、Enter キーハンドリングは **BMS と 100% 共通の JavaScript module** を再利用可能。
2. **中立画面モデル (`ScreenModel`) の共通化**:
   - 行・桁、属性（PROTECTED, NUMERIC, BRT, NORM, DRK）、色、下線、初期カーソル位置などを保持する中立データ構造は、`BmsScreenModel` と `MfsScreenModel` で共通の基盤インタフェース（例: `TerminalScreenModel`）に統合可能。
3. **Thymeleaf 共通テンプレート**:
   - 画面の HTML レンダリング部分は BMS 向けに作成する template をそのまま共用可能。

---

## 4. MFS 固有の難しさと追加で必要になる実装

BMS と類似方針をとる場合でも、MFS 特有の仕様に対応するための追加実装・検討課題が存在します。

### 4.1 MFS マクロ構文のパース（DIF / DOF / MID / MOD）
BMS が単一のマップ定義（`DFHMDF POS=(row,col),LENGTH=len...`）であるのに対し、MFS は **メッセージ定義（MID/MOD）** と **デバイス定義（DIF/DOF）** の2系統をパースする必要があります。

- **MFS ソースの例**:
  ```assembler
  * --- デバイス側定義 (DIF / DOF: 3270画面上の位置) ---
  CUSTDEV  DEV   TYPE=(3270,2),FEAT=IGNORE
  CUSTDPG  DPAGE
  DF1      DFLD  'CUSTOMER ID:',POS=(3,5)
  DF2      DFLD  POS=(3,20),LTH=8,ATTR=(NUM,PROT)
  
  * --- メッセージ側定義 (MID / MOD: COBOLバッファ上の位置) ---
  CUSTMID  MSG   TYPE=INPUT,SOR=(CUSTDEV,START)
  CUSTSEG  SEG
  MF1      MFLD  DF2,LTH=8               <-- 画面の DF2 を COBOL の先頭8バイトへマップ
           MSGEND
  ```
- **対応**: MFS マクロのパーサを実装し、**「画面上の `DFLD`」と「COBOL バッファ上の `MFLD`」の間の双方向バイトオフセット変換マップ** をビルド時に生成しておく必要があります。

### 4.2 画面遷移トリガー（MOD 名の解決）
CICS ではプログラムが明示的に `EXEC CICS SEND MAP('CUSTMAP')` と記述するため次に表示すべき画面が明白です。
しかし IMS TM では、COBOL 側は単に `ISRT` で電文を吐き出すだけです。

- **画面決定の仕組み**:
  - COBOL プログラムが `IO-PCB` の `MOD-NAME` 領域（8バイト）に「出力に使用する MOD 名（例: `CUSTMOD1`）」をセットして `ISRT` を呼ぶ。
  - または、入力時の MID 定義に指定された `NXT=MODNAME`（次画面の既定 MOD）が採用される。
- **対応**: ランタイム側で `IO-PCB` の `MOD-NAME` 欄を監視し、指定された MFS 定義を動的にロードして画面を組み立てるディスパッチャが必要です。

### 4.3 複数論理ページ（Logical Paging）
MFS の高度な機能として「プログラムが一度の `ISRT` で何ページ分もの明細データを出力し、MFS がそれを分割して端末へ送り、ユーザーが PA2 キー（または `/FOR` コマンド）で次ページ・前ページをめくる」機能があります。

- **対応**: この論理ページングを再現する場合、ブラウザ側またはサーバ側セッションに「ページバッファ」を保持し、PA2 キーの押下を検知してクライアント側（または Thymeleaf 側）でページ切り替えを行う状態管理が必要になります。

---

## 5. 推奨されるロードマップと段階的アプローチ

もし MFS（画面）のサポートを進める場合、以下のステップで進めることを推奨します。

| フェーズ | スコープ | 実装内容 |
| :--- | :--- | :--- |
| **Phase 0**<br>(現状) | **MFS バイパス (電文直結)** | MFS を通さない純粋な電文（MQ / REST 連携）のみを対象とする。COBOL プログラムは電文の直読み書きとして動作（※前回の RabbitMQ+JMS 方式）。 |
| **Phase 1**<br>(BMS 連携後) | **単一画面 MFS のエミュレーション** | - BMS 向けに開発した `cobol-spring-boot-4-bms-thymeleaf` の UI/CSS/JS をそのまま流用。<br>- 基本的な MFS 定義（MID/MOD/DIF/DOF）のパーサを実装。<br>- 1画面・1ページの入力・出力マッピングを中立 `MfsScreenModel` 経由でブラウザ表示。 |
| **Phase 2**<br>(必要時) | **高度な MFS 機能の拡充** | - 複数論理ページング（Operator Logical Paging）。<br>- 属性動的変更（Attr byte の動的オーバレイ）。 |

---

## 6. まとめ

- **BMS 類似方針（中立モデル + Thymeleaf/CSS Grid/JS）の適用は「大いに可能」であり、合理的です。**
- ブラウザ上の 3270 画面描画 CSS やカーソル制御 JavaScript は、**BMS と全く同一のフロントエンド資産を 100% 流用** できます。
- 一方で、BMS と違って **「COBOL 側には SEND MAP がなく、キュー電文の途中に MFS マッパーが透過的に割り込む」** というアーキテクチャ上の差があるため、バックエンド側では「電文（MFLD）⇔ 画面（DFLD）の自動フォーマット変換エンジン」を IMS TM 制御層に組み込む設計となります。
