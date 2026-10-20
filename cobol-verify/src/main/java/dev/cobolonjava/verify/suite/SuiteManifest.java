package dev.cobolonjava.verify.suite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zeus の検査スイート (ZCVS / ZPVS / ZHVS) の {@code manifest.toml} (設計 87)。
 *
 * <p>スイートが自分で決めている<b>プログラムごとの流し方</b>を読む。CCSID、PARM、期待する終了コード、
 * JCL の DD、そして「翻訳が断るべきもの」かどうかである。これを読まずに流すと、道具のほうが処理系の
 * 失敗を作る (CLAUDE.md §6)。
 *
 * <p>読むのは Zeus のハーネス ({@code crates/zeus-ccvs/src/manifest.rs}) が読む部分集合だけであり、
 * 知らない鍵は断る。黙って読み飛ばすと、スイートが新しい条件を足したときに気付けない。
 *
 * @param name             スイートの名前
 * @param language         {@code cobol} / {@code pli} / {@code hlasm}。書かれていなければ cobol
 * @param requiredModules  必ず流すモジュール
 * @param optionalModules  処理系が任意で持つ機能のモジュール
 * @param programs         プログラムごとの条件
 */
public record SuiteManifest(String name, String language, List<String> requiredModules,
                            List<String> optionalModules, Map<String, Program> programs) {

    /** 期待する結末。 */
    public enum Expect {
        /** 翻訳して動かし、報告を読む。 */
        RUN,
        /** 翻訳が通ればよい。 */
        COMPILE_ONLY,
        /** 翻訳が断らなければならない。 */
        COMPILE_ERROR
    }

    /**
     * 1 本の条件。
     *
     * @param ccsid              翻訳と実行のコードページ。無ければ -1
     * @param parm               主プログラムへ渡す PARM。無ければ null
     * @param expectExitCode     期待する終了コード。無ければ null (0 を求める)
     * @param jcl                DD を割り当てる JCL の名前。無ければ null
     * @param expect             期待する結末
     * @param diagnosticContains 断るときの Zeus の診断が含む文字列。こちらの文面とは違いうる
     */
    public record Program(int ccsid, String parm, Integer expectExitCode, String jcl, Expect expect,
                          String diagnosticContains) {

        static final Program DEFAULT = new Program(-1, null, null, null, Expect.RUN, null);
    }

    /** 書かれていないプログラムの条件は既定である。 */
    public Program program(String name) {
        return programs.getOrDefault(name, Program.DEFAULT);
    }

    /** 流す順のモジュール。 */
    public List<String> modules() {
        List<String> all = new ArrayList<>(requiredModules);
        all.addAll(optionalModules);
        return all;
    }

    public static SuiteManifest load(Path suite) {
        try {
            return parse(Files.readString(suite.resolve("manifest.toml")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static SuiteManifest parse(String text) {
        String name = "";
        String language = "cobol";
        List<String> required = List.of();
        List<String> optional = List.of();
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        String section = null;
        int number = 0;
        for (String raw : text.split("\\R")) {
            number++;
            String line = withoutComment(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).strip();
                if (!section.equals("suite") && !section.startsWith("programs.")) {
                    throw new IllegalArgumentException("line " + number + ": unknown section [" + section + "]");
                }
                if (section.startsWith("programs.")) {
                    sections.putIfAbsent(unquote(section.substring("programs.".length()).strip()),
                            new LinkedHashMap<>());
                }
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0 || section == null) {
                throw new IllegalArgumentException("line " + number + ": expected key = value in a section");
            }
            String key = line.substring(0, equals).strip();
            String value = line.substring(equals + 1).strip();
            if (section.equals("suite")) {
                switch (key) {
                    case "name" -> name = unquote(value);
                    case "language" -> language = unquote(value);
                    case "required_modules" -> required = list(value);
                    case "optional_modules" -> optional = list(value);
                    default -> throw new IllegalArgumentException("line " + number + ": unknown [suite] key " + key);
                }
            } else {
                sections.get(unquote(section.substring("programs.".length()).strip())).put(key, value);
            }
        }
        Map<String, Program> programs = new LinkedHashMap<>();
        sections.forEach((program, keys) -> programs.put(program, program(program, keys)));
        return new SuiteManifest(name, language, required, optional, programs);
    }

    private static Program program(String name, Map<String, String> keys) {
        int ccsid = -1;
        String parm = null;
        Integer exit = null;
        String jcl = null;
        Expect expect = Expect.RUN;
        String diagnostic = null;
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            String value = entry.getValue();
            switch (entry.getKey()) {
                case "ccsid" -> ccsid = Integer.parseInt(value);
                case "parm" -> parm = unquote(value);
                case "expect_exit_code" -> exit = Integer.parseInt(value);
                case "jcl" -> jcl = unquote(value);
                case "expect" -> expect = switch (unquote(value)) {
                    case "run" -> Expect.RUN;
                    case "compile-only" -> Expect.COMPILE_ONLY;
                    case "compile-error" -> Expect.COMPILE_ERROR;
                    default -> throw new IllegalArgumentException(name + ": unknown expect " + value);
                };
                case "diagnostic_contains" -> diagnostic = unquote(value);
                default -> throw new IllegalArgumentException(name + ": unknown key " + entry.getKey());
            }
        }
        return new Program(ccsid, parm, exit, jcl, expect, diagnostic);
    }

    /** 引用符の外の {@code #} から後ろは注釈である。 */
    private static String withoutComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (c == '#' && !quoted) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    private static List<String> list(String value) {
        if (!value.startsWith("[") || !value.endsWith("]")) {
            throw new IllegalArgumentException("expected an array: " + value);
        }
        List<String> items = new ArrayList<>();
        for (String item : value.substring(1, value.length() - 1).split(",")) {
            if (!item.isBlank()) {
                items.add(unquote(item.strip()));
            }
        }
        return List.copyOf(items);
    }
}
