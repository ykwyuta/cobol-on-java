package dev.cobolonjava.compiler.source;

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
        values = Map.copyOf(normalized);
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

    /** 別のオプションの集まりを重ねる。あとから与えたものが優先する。 */
    public CompilerOptions merge(CompilerOptions other) {
        Map<String, String> merged = new LinkedHashMap<>(values);
        merged.putAll(other.values);
        return new CompilerOptions(merged);
    }
}
