package dev.cobolonjava.job.jcl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 目録手続きと {@code INCLUDE} の取り出し先 (要件 FR-131)。
 *
 * <p>ホストでは {@code PROCLIB} という区分データセットに、手続きが<b>メンバとして</b>入って
 * いる。ここではディレクトリの下のファイルがメンバである。
 */
public interface JclLibrary {

    /**
     * メンバの本文。
     *
     * @return 見つからなければ {@code null}
     */
    String member(String name);

    /** メンバを持たない構成。手続きを使わない JCL はこれで足りる。 */
    static JclLibrary empty() {
        return name -> null;
    }

    /**
     * ディレクトリをメンバの入れ物として使う構成。
     *
     * <p>{@code <ディレクトリ>/<名前>} を探し、なければ {@code .jcl} を付けて探す。
     */
    static JclLibrary at(Path directory) {
        return name -> {
            for (Path candidate : new Path[] {
                directory.resolve(name), directory.resolve(name + ".jcl"),
            }) {
                if (Files.isReadable(candidate)) {
                    try {
                        return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException("cannot read " + candidate, e);
                    }
                }
            }
            return null;
        };
    }

    /** 名前と本文の表をメンバの入れ物として使う構成。試験で使う。 */
    static JclLibrary of(Map<String, String> members) {
        Map<String, String> copy = new LinkedHashMap<>();
        members.forEach((name, text) -> copy.put(name.toUpperCase(Locale.ROOT), text));
        return name -> copy.get(name.toUpperCase(Locale.ROOT));
    }
}
