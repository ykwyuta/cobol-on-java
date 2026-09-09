package dev.cobolonjava.job;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * データセットの名前から置き場を引く目録 (要件 FR-131, FR-133、暫定判断 P-045 の解消)。
 *
 * <h2>置き場と目録は別のものである</h2>
 * <p>ホストではデータセットは<b>ボリュームの上にあり</b>、目録はその名前がどのボリュームに
 * あるかを覚えている。この 2 つが別なので「ボリュームにはあるが目録には載っていない」が
 * 起こりうる。{@code KEEP} と {@code CATLG} が違うのはそこであり、{@code UNCATLG} が
 * 消すのは目録の項目だけである。
 *
 * <p>ここでは置き場をディレクトリ 1 つとし、目録をその下の {@value #INDEX} という
 * 覚え書きにしている。{@code KEEP} で残したものは置き場にあるが目録に載らないので、
 * 次のジョブが名前だけで指しても<b>見つからない</b>。{@code VOL=SER=} を書けば
 * 目録を通さず置き場を直に見るので、そこで初めて届く。ホストと同じ形である。
 *
 * <h2>知らない名前は載っているものとして扱う</h2>
 * <p>目録が覚えるのは<b>ジョブが作ったものだけ</b>である。置き場は人がファイルを置く
 * ディレクトリでもあり、そこへ置いたものを目録に書かせるのでは道具として使えない。
 * 覚えのない名前が置き場にあれば、それは初めから目録に載っていたものとして扱う。
 * 実際の運用でも、外から持ち込まれたデータセットは目録に載っている。
 *
 * <p>ジョブが {@code NEW} で作ったものには<b>その場で「載っていない」と書き留める</b>。
 * そうしないと、次のジョブで覚えのない名前として拾われ、{@code KEEP} と {@code CATLG} の
 * 区別がまた消えてしまう。
 */
public final class SystemCatalog {

    /** 目録そのもの。置き場の下に置く。データセットの名前は点で始まらないので紛れない。 */
    static final String INDEX = ".catalog";

    /** 名前が目録に対してどうなっているか。 */
    private enum Entry {

        /** 目録に載っている。名前だけで届く。 */
        CATALOGED,

        /** 置き場にはあるが目録には載っていない。{@code VOL=SER=} を書かないと届かない。 */
        UNCATALOGED;

        static Entry of(String text) {
            return switch (text.trim().toUpperCase(Locale.ROOT)) {
                case "CATLG" -> CATALOGED;
                case "UNCAT" -> UNCATALOGED;
                default -> null;
            };
        }

        String written() {
            return this == CATALOGED ? "CATLG" : "UNCAT";
        }
    }

    private final Path volume;
    /** 覚えのある名前だけが入る。入っていない名前は載っているものとして扱う。 */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public SystemCatalog(Path volume) {
        this.volume = volume;
        read();
    }

    /** データセットの置き場。名前が決まれば場所も決まる。 */
    public Path volume() {
        return volume;
    }

    /**
     * 置き場での場所。目録を通さない。
     *
     * <p>{@code VOL=SER=} を書いたジョブが見るのがこれである。目録に載っていなくても届く。
     */
    public Path onVolume(String name) {
        return volume.resolve(name);
    }

    /**
     * その名前が目録に載っているか。
     *
     * <p>載っていなければ、名前だけを書いたジョブからは<b>無いものとして見える</b>。
     * 置き場にバイト列が残っていても関係がない。
     */
    public boolean isCataloged(String name) {
        return entries.get(key(name)) != Entry.UNCATALOGED;
    }

    /** 目録へ載せる。{@code DISP=CATLG} である。 */
    public void catalog(String name) {
        write(name, Entry.CATALOGED);
    }

    /** 目録から外す。置き場のバイト列は残る。{@code DISP=UNCATLG} と {@code NEW} である。 */
    public void uncatalog(String name) {
        write(name, Entry.UNCATALOGED);
    }

    /** 目録の項目を消す。データセットそのものを消したときに呼ぶ。 */
    public void forget(String name) {
        if (entries.remove(key(name)) != null) {
            flush();
        }
    }

    private void write(String name, Entry entry) {
        if (entries.put(key(name), entry) != entry) {
            flush();
        }
    }

    private static String key(String name) {
        return name.toUpperCase(Locale.ROOT);
    }

    private void read() {
        Path index = volume.resolve(INDEX);
        if (!Files.isReadable(index)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                List<String> parts = List.of(line.trim().split("\\s+"));
                Entry entry = parts.size() == 2 ? Entry.of(parts.get(0)) : null;
                if (entry != null) {
                    entries.put(key(parts.get(1)), entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + index, e);
        }
    }

    private void flush() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Entry> each : entries.entrySet()) {
            sb.append(each.getValue().written()).append(' ').append(each.getKey()).append('\n');
        }
        Path index = volume.resolve(INDEX);
        try {
            Files.createDirectories(volume);
            Files.writeString(index, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + index, e);
        }
    }
}
