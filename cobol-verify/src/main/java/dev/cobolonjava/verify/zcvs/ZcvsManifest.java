package dev.cobolonjava.verify.zcvs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ZCVS の {@code manifest.toml} のうち、プログラムごとの流し方を読む。
 *
 * <p>ZCVS はプログラムごとに<b>どう流すか</b>を別の表に書いている。翻訳が断られることを
 * 期待する本 ({@code expect = "compile-error"})、翻訳が通ることだけを見る本
 * ({@code compile-only})、CCSID、PARM、期待する復帰コードである。これを読まずに流すと、
 * 断るべきものを断って「翻訳できない」と数える。<b>道具が処理系の失敗を作る</b>
 * (覚え書き 6)。
 *
 * <p>TOML 全体は読まない。使われているのは {@code [programs.名前]} の節と
 * {@code 鍵 = 値} の行だけであり、依存を足すほどのものではない。知らない鍵は捨てる。
 */
public final class ZcvsManifest {

    /** 期待する結末。 */
    public enum Expect {
        /** 動かして報告を読む。 */
        RUN,
        /** 翻訳が通ることだけを見る。 */
        COMPILE_ONLY,
        /** 翻訳が断られることを見る。 */
        COMPILE_ERROR
    }

    /**
     * 1 本の流し方。
     *
     * @param expect     期待する結末
     * @param ccsid      翻訳と実行の CCSID。書かれていなければ 0
     * @param parm       主プログラムへ渡す PARM。無ければ {@code null}
     * @param exitCode   期待する復帰コード。書かれていなければ 0
     * @param diagnostic 断る診断に含まれるはずの語 (Zeus の文面)。無ければ {@code null}
     */
    public record Entry(Expect expect, int ccsid, String parm, int exitCode, String diagnostic) {

        static final Entry PLAIN = new Entry(Expect.RUN, 0, null, 0, null);
    }

    private final Map<String, Entry> entries;

    private ZcvsManifest(Map<String, Entry> entries) {
        this.entries = entries;
    }

    public Entry of(String program) {
        return entries.getOrDefault(program, Entry.PLAIN);
    }

    public static ZcvsManifest read(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ZcvsManifest parse(String text) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        String program = null;
        Entry entry = Entry.PLAIN;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("[")) {
                if (program != null) {
                    entries.put(program, entry);
                }
                program = line.startsWith("[programs.")
                        ? line.substring("[programs.".length(), line.length() - 1) : null;
                entry = Entry.PLAIN;
                continue;
            }
            int equals = line.indexOf('=');
            if (program == null || equals < 0 || line.startsWith("#")) {
                continue;
            }
            String key = line.substring(0, equals).strip();
            String value = unquote(line.substring(equals + 1).strip());
            entry = switch (key) {
                case "expect" -> new Entry(switch (value) {
                    case "compile-error" -> Expect.COMPILE_ERROR;
                    case "compile-only" -> Expect.COMPILE_ONLY;
                    default -> Expect.RUN;
                }, entry.ccsid(), entry.parm(), entry.exitCode(), entry.diagnostic());
                case "ccsid" -> new Entry(entry.expect(), Integer.parseInt(value), entry.parm(),
                        entry.exitCode(), entry.diagnostic());
                case "parm" -> new Entry(entry.expect(), entry.ccsid(), value, entry.exitCode(),
                        entry.diagnostic());
                case "expect_exit_code" -> new Entry(entry.expect(), entry.ccsid(), entry.parm(),
                        Integer.parseInt(value), entry.diagnostic());
                case "diagnostic_contains" -> new Entry(entry.expect(), entry.ccsid(),
                        entry.parm(), entry.exitCode(), value);
                default -> entry;
            };
        }
        if (program != null) {
            entries.put(program, entry);
        }
        return new ZcvsManifest(entries);
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }
}
