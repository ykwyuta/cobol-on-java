package dev.cobolonjava.verify.ccvs85;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * NIST CCVS85 の配布物 ({@code newcob.val}) を部品へ切り分ける (要件 NFR-040)。
 *
 * <p>配布物は<b>1 つの本文ファイル</b>である。中には 459 本の検査プログラム、51 本の
 * 写し句、2 本のデータが、区切りの行を挟んで並んでいる。
 *
 * <pre>
 * *HEADER,COBOL,NC101A     ← ここから 1 本
 * 000100 IDENTIFICATION DIVISION.                          NC1014.2
 * ...
 * *END-OF,NC101A           ← ここまで
 * </pre>
 *
 * <p>種別は 3 つある。{@code COBOL} が検査プログラム、{@code CLBRY} が写し句
 * ({@code COPY} で取り込まれる)、{@code DATA*} が入力データである。
 *
 * <h2>なぜ同梱しないか</h2>
 * <p>配布物は解いて 28 MB あり、素性は NIST (パブリックドメイン) である。要件 NFR-042 は
 * コーパス本体を同梱せず<b>取得スクリプトで持ってくる</b>ことを求めている。ここでも同じ
 * 扱いにした。Apache-2.0 の成果物へ素性の違うものを取り込まないためであり、Hercules を
 * 外から呼ぶのと同じ考え方である。
 */
public record Ccvs85Archive(List<Member> members) {

    /** 部品の種別。 */
    public enum Kind {
        /** 検査プログラム。 */
        COBOL,
        /** 写し句。{@code COPY} で取り込まれる。 */
        COPYBOOK,
        /** 入力データ。 */
        DATA;

        /**
         * 区切りの行に書かれた綴りから読む。
         *
         * @return 知らない綴りなら {@code null}
         */
        static Kind of(String written) {
            return switch (written) {
                case "COBOL" -> COBOL;
                case "CLBRY" -> COPYBOOK;
                case "DATA*", "DATA" -> DATA;
                default -> null;
            };
        }
    }

    /**
     * 部品 1 つ。
     *
     * @param lines 区切りの行を除いた中身
     */
    public record Member(Kind kind, String name, List<String> lines) {

        /**
         * 属する検査モジュール。名前の頭 2 文字である ({@code NC101A} なら {@code NC})。
         *
         * <p>モジュールは検査の区分であり、{@code NC} は基本機能、{@code SQ} は順編成、
         * {@code IX} は索引編成である。合格率はモジュールごとに数える。要件 13 章が
         * 受け入れ基準に置いているのは<b>入出力以外のモジュール</b>だからである。
         */
        public String module() {
            return name.length() >= 2 ? name.substring(0, 2) : name;
        }

        /** 中身を 1 つの文字列へ戻す。 */
        public String text() {
            return String.join("\n", lines) + "\n";
        }
    }

    private static final String HEADER = "*HEADER,";
    private static final String END = "*END-OF,";

    /** 配布物を読む。 */
    public static Ccvs85Archive read(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            return read(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 配布物を読む。
     *
     * <p>区切りの行の外にある行は捨てる。先頭の題名の行がそれにあたる。
     */
    public static Ccvs85Archive read(BufferedReader reader) throws IOException {
        List<Member> members = new ArrayList<>();
        Kind kind = null;
        String name = null;
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith(HEADER)) {
                String[] parts = line.substring(HEADER.length()).trim().split(",", 2);
                kind = Kind.of(parts[0].trim());
                name = parts.length > 1 ? parts[1].trim() : "";
                lines = new ArrayList<>();
                continue;
            }
            // *END-OF-POP のような似た綴りに引っかからないよう、コンマまで見る
            if (line.startsWith(END)) {
                if (kind != null) {
                    members.add(new Member(kind, name, List.copyOf(lines)));
                }
                kind = null;
                continue;
            }
            if (kind != null) {
                lines.add(line);
            }
        }
        if (kind != null) {
            // 終わりの区切りが無いまま尽きた。読めたところまでは部品として扱う
            members.add(new Member(kind, name, List.copyOf(lines)));
        }
        return new Ccvs85Archive(List.copyOf(members));
    }

    /** 検査プログラムだけを取り出す。 */
    public List<Member> programs() {
        return members.stream().filter(m -> m.kind() == Kind.COBOL).toList();
    }

}
