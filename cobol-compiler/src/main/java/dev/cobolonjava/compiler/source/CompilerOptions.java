package dev.cobolonjava.compiler.source;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 翻訳時オプションの集まり (要件 FR-093)。
 *
 * <p>プロセス文 ({@code CBL} / {@code PROCESS}) でソースに書かれたもの、および
 * 処理系の起動時に与えられたものを同じ形で保持する。値のないオプション
 * ({@code APOST} など) は値を空文字とする。
 *
 * <p>名前は大文字に正規化する。COBOL の語と同じく大文字と小文字を区別しないためである。
 */
public record CompilerOptions(Map<String, String> values) {

    /** オプションのないもの。 */
    public static final CompilerOptions NONE = new CompilerOptions(Map.of());

    public CompilerOptions {
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((name, value) -> normalized.put(name.toUpperCase(Locale.ROOT), value));
        // 書かれた順を保つ。ON / OFF の対はあとに書いたものが効くためである
        values = Collections.unmodifiableMap(normalized);
    }

    /** 指定した名前のオプションが与えられているか。 */
    public boolean has(String name) {
        return values.containsKey(name.toUpperCase(Locale.ROOT));
    }

    /** 指定した名前のオプションの値。括弧の中身であり、値がなければ空文字。 */
    public Optional<String> value(String name) {
        return Optional.ofNullable(values.get(name.toUpperCase(Locale.ROOT)));
    }

    /**
     * {@code SOURCEFORMAT} が指定していた参照形式。指定がなければ空。
     *
     * <p>指定は<b>読み取り器の選択に跳ね返る</b>。プロセス文そのものは固定形式の
     * カラムに従わない位置に書けるため、読み取りより前に取り出す必要がある。
     */
    public Optional<SourceFormat> sourceFormat() {
        return value("SOURCEFORMAT").map(text -> switch (text.toUpperCase(Locale.ROOT)) {
            case "FREE" -> SourceFormat.FREE;
            case "FIXED" -> SourceFormat.FIXED;
            default -> throw new SourceFormatException(
                    "unknown SOURCEFORMAT value: " + text);
        });
    }

    /**
     * {@code SSRANGE} が効いているか (要件 FR-024, FR-026)。
     *
     * <p>指定があれば、実行時に決まる添字と部分参照の位置を<b>実行時に検査する</b>。
     * 既定は検査しない。参照実装の既定 ({@code NOSSRANGE}) に合わせている。
     */
    public boolean subscriptRangeChecks() {
        return toggle("SSRANGE");
    }

    /**
     * {@code 名前} と {@code NO名前} の対で指定するオプションの状態。
     *
     * <p><b>あとに書いたものが効く</b>。{@code CBL NOSSRANGE,SSRANGE} は検査する。
     * 参照実装の規則であり、コピー句や既定の指定を局所的に打ち消す書き方が成り立つ。
     */
    public boolean toggle(String name) {
        String on = name.toUpperCase(Locale.ROOT);
        String off = "NO" + on;
        boolean enabled = false;
        for (String key : values.keySet()) {
            if (key.equals(on)) {
                enabled = true;
            } else if (key.equals(off)) {
                enabled = false;
            }
        }
        return enabled;
    }

    /** 別のオプションの集まりを重ねる。あとから与えたものが優先する。 */
    public CompilerOptions merge(CompilerOptions other) {
        Map<String, String> merged = new LinkedHashMap<>(values);
        merged.putAll(other.values);
        return new CompilerOptions(merged);
    }
}
