package dev.cobolonjava.verify.ccvs85;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * CCVS85 の差し込み札 (X-card) (要件 NFR-040)。
 *
 * <p>検査プログラムは処理系ごとに違うところを空欄にしてある。装置の名前、ファイルの
 * 結び付け、行の幅などである。空欄には番号が振ってあり、処理系の側がその番号に文字を
 * 割り当てる。これが差し込み札である。
 *
 * <p>大事なのは 4 つで、ほかは入出力のモジュールが使うファイル名である。
 *
 * <ul>
 *   <li>{@code 082} — {@code SOURCE-COMPUTER} に書く名前
 *   <li>{@code 083} — {@code OBJECT-COMPUTER} に書く名前
 *   <li>{@code 055} — 印字するファイルの結び付け
 *   <li>{@code 084} — {@code SPECIAL-NAMES} 段落。書くことが無ければ {@code OMITTED}
 * </ul>
 *
 * <p>既定は {@code x-cards.properties} に置いてある。実行するときに差し替えられるよう、
 * ファイルからも読める。処理系が育って入出力のモジュールへ手が届いたら、そこで札を
 * 足すことになる。
 */
public record XCards(Map<Integer, String> texts) {

    private static final String DEFAULTS = "x-cards.properties";

    /** 同梱の既定の札。 */
    public static XCards defaults() {
        try (InputStream in = XCards.class.getResourceAsStream(DEFAULTS)) {
            if (in == null) {
                throw new IllegalStateException("x-cards.properties is missing from the jar");
            }
            return read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 既定の札に、書かれたものを重ねる。 */
    public XCards and(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Map<Integer, String> merged = new LinkedHashMap<>(texts);
            merged.putAll(read(in).texts());
            return new XCards(Map.copyOf(merged));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static XCards read(InputStream in) throws IOException {
        Properties properties = new Properties();
        properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        Map<Integer, String> texts = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            try {
                texts.put(Integer.parseInt(name.trim()), properties.getProperty(name).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("x-card name is not a number: " + name, e);
            }
        }
        return new XCards(Map.copyOf(texts));
    }

    /**
     * 番号の札。
     *
     * @return 用意していなければ {@code null}
     */
    public String text(int number) {
        return texts.get(number);
    }
}
