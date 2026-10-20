# ADR-0016: Zeus の実装を cobol-on-java へ移すときの権利と手順

| 項目 | 内容 |
| :--- | :--- |
| 状態 | **提案** (権利者の明示の承認を待つ) |
| 日付 | 2026-10-20 |
| 関連 | [Zeus との機能差と改善計画](../report/20261020-zeus-gap-and-improvement-plan.md) 段階 0-7 |

## 文脈

Zeus (Rust、GPL-3.0-or-later) と cobol-on-java (Java、Apache-2.0) は同じ権利者のプロジェクトである。
Zeus は cobol-on-java に無い機能を多く持つ (動的 SQL、物理ブロック、MQ、報告書の制御の切れ目、54 のコードページなど)。
改善計画はそれらを移植元として挙げている。

権利者は 2026-10-20 に「Zeus の中を見て比較してよい」と許した。これで Zeus のソースを読むこと、Zeus を
ビルドして同じ原文を流すこと (`tools/zos-probe/run-zeus.sh`) はできる。一方、Zeus の GPL のコードから
作ったものを Apache-2.0 の cobol-on-java に入れるには、権利者がその部分を Apache-2.0 でも出すと
決める必要がある。本 ADR はその決定と手順を提案する。

Zeus の側の決まり (Zeus `CONTRIBUTING.md`「Clean-room rules」) は cobol-on-java と揃っている:
Hercules のソースを読まず外から動かすだけ、IBM の配布物 (マクロ、写し句、BMS / CICS の定義) を写さない、
GnuCOBOL は比較に使うだけ、検査スイートと OS の像はリポジトリに入れない。したがって Zeus の
<b>権利者自身が書いた部分</b>は、cobol-on-java の NOTICE (公開仕様と観測できる振る舞いだけに拠る) と矛盾しない。

## 決定 (提案)

1. **権利者が書いた Zeus の部分は、cobol-on-java へ移すときに Apache-2.0 で出す。** 権利者は自分の著作物を
   別のライセンスでも出せる。Zeus 自体は GPL のまま変えない
2. **第三者の権利のものは、この決定に含めない。** 元のライセンスに従う
   - コードページの表 (`crates/zeus-cobol/codepages/`): ICU の UCM から生成したもので Unicode License V3。
     移すなら表とともに `LICENSE-ICU` を置き、NOTICE に書く。Unicode License V3 は Apache-2.0 と両立する。
     なお cobol-on-java はいまコードページを JDK の charset で持っており、表を移さずに済むならそのほうがよい
   - `crates/zeus-cobol/src/vm/unicode_case.rs` (Unicode のデータ由来、`UNICODE-LICENSE.txt`) も同じ扱い
   - Zeus が取得して使う検査スイート、OS の像、Hercules は移さない (どちらのプロジェクトも入れていない)
3. **移すときの手順**
   - 写すのではなく Java で書き直す。言語が違ううえ、cobol-on-java には ABI (ADR-0002)、カタログ (ADR-0001)、
     中立ポート (ADR-0007) の決まりがある。移すのは<b>仕様の読み方</b>と<b>試験の事例</b>である
   - コミットメッセージに移植元を書く (`Zeus <commit> の <path> から移した`)。どの読みがどこから来たかを
     あとで辿れるようにする
   - Zeus の読みが cobol-on-java の暫定判断と食い違うときは、移す前に暫定判断へ書き足す。<b>Zeus は実機ではない</b>
     (改善計画 §3.1。Zeus の不具合を 5 件見つけて Zeus #24〜#28 に起票した)
4. **NOTICE** に「権利者の別のプロジェクト Zeus から、権利者の判断で Apache-2.0 で移した部分を含む」と書く。
   最初の移植のコミットで足す

## 採らなかった案

- **Zeus を外部の道具として呼ぶだけにする** (Hercules と同じ扱い): 測るにはこれで足り、`run-zeus.sh` は
  この形である。しかし機能の移植は書き直しになり、Zeus の読み方を参照できないと同じ調べ物を 2 度することになる
- **cobol-on-java を GPL にする**: 利用者が Java のアプリケーションに組み込む前提 (Spring、Maven プラグイン) と合わない

## 結果

- 承認されれば、改善計画の段階 1 以降で Zeus の該当ファイルを読みながら書き直せる
- 承認されるまでは、Zeus のソースは比較のために読むだけにし、コードは移さない
