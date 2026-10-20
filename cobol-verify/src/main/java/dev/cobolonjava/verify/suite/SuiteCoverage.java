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
 * スイートの {@code coverage.json}。プログラムごとに、報告に出るはずの検査の名前を<b>出る順に</b>並べた
 * 固定の一覧である (設計 87)。
 *
 * <p>報告の「n OF m」だけを数えると、途中で止まって書かれなかった検査が見えない。一覧と突き合わせれば、
 * 消えた検査を「消えた」と数えられる。一覧はスイートの側がレビューして固定したもので、実行結果から
 * 作り直さない。
 *
 * <p>形は {@code {"名前": {"expect": "...", "tests": ["...", ...]}, ...}} だけなので、JSON の部分集合を
 * ここで読む。依存を増やさないためであり、読めない形が来れば断る。
 */
public record SuiteCoverage(Map<String, List<String>> tests) {

    /** そのプログラムが書くはずの検査の名前。一覧に無ければ空。 */
    public List<String> testsOf(String program) {
        return tests.getOrDefault(program, List.of());
    }

    public static SuiteCoverage load(Path suite) {
        try {
            return parse(Files.readString(suite.resolve("coverage.json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static SuiteCoverage parse(String text) {
        Object root = new Reader(text).value();
        Map<String, List<String>> tests = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) root).entrySet()) {
            Map<String, Object> program = (Map<String, Object>) entry.getValue();
            List<String> names = new ArrayList<>();
            Object listed = program.get("tests");
            if (listed != null) {
                for (Object name : (List<Object>) listed) {
                    names.add((String) name);
                }
            }
            tests.put(entry.getKey(), List.copyOf(names));
        }
        return new SuiteCoverage(tests);
    }

    /** 文字列・配列・オブジェクトだけを読む。 */
    private static final class Reader {

        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        Object value() {
            skip();
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                default -> throw new IllegalArgumentException("unexpected '" + c + "' at " + at);
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            at++;
            skip();
            if (text.charAt(at) == '}') {
                at++;
                return map;
            }
            while (true) {
                skip();
                String key = string();
                skip();
                expect(':');
                map.put(key, value());
                skip();
                if (text.charAt(at) == ',') {
                    at++;
                    continue;
                }
                expect('}');
                return map;
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            at++;
            skip();
            if (text.charAt(at) == ']') {
                at++;
                return list;
            }
            while (true) {
                list.add(value());
                skip();
                if (text.charAt(at) == ',') {
                    at++;
                    continue;
                }
                expect(']');
                return list;
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (text.charAt(at) != '"') {
                char c = text.charAt(at++);
                if (c == '\\') {
                    char escaped = text.charAt(at++);
                    out.append(switch (escaped) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> escaped;
                    });
                } else {
                    out.append(c);
                }
            }
            at++;
            return out.toString();
        }

        private void expect(char c) {
            if (text.charAt(at) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + at);
            }
            at++;
        }

        private void skip() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }
    }
}
