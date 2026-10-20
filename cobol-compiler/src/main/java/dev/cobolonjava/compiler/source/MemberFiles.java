package dev.cobolonjava.compiler.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 置き場のファイルを、ホストの区分データセットのメンバーとして引く。
 *
 * <p><b>大文字と小文字を区別しない</b>。COPY の text-name は利用者語であり、COBOL の語は大文字と小文字を
 * 区別しない。ホストのメンバー名はすべて大文字だが、手元へ持ってきた資産は小文字のファイル名のことが多い
 * (GenApp は {@code lgcmarea.cpy} と書き、原文は {@code COPY LGCMAREA.} と書く)。
 *
 * <p>以前はこれをファイル体系に任せていた。すると Windows では見つかり Linux では見つからず、
 * 避けたかった「環境で結果が変わる」がかえって起きていた。ここではディレクトリの中身を自分で読んで
 * 照合するので、どの OS でも同じ結果になる。大文字と小文字だけが違うファイルが 2 つあれば、
 * どちらかを黙って選ばずに断る。
 */
final class MemberFiles {

    private MemberFiles() {
    }

    /**
     * {@code name} に拡張子を順に付けて探し、最初に当たったファイルを返す。拡張子の順が優先の順である。
     *
     * @throws SourceFormatException 大文字と小文字だけが違うファイルが 2 つ以上ある
     */
    static Optional<Path> find(Path directory, String name, List<String> suffixes) {
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        List<Path> entries;
        try (Stream<Path> listing = Files.list(directory)) {
            entries = listing.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + directory, e);
        }
        for (String suffix : suffixes) {
            String wanted = (name + suffix).toUpperCase(Locale.ROOT);
            List<Path> matches = new ArrayList<>();
            for (Path entry : entries) {
                if (entry.getFileName().toString().toUpperCase(Locale.ROOT).equals(wanted)) {
                    matches.add(entry);
                }
            }
            if (matches.size() > 1) {
                throw new SourceFormatException("COPY " + name + " matches more than one file that differ only in"
                        + " case: " + matches.stream().map(path -> path.getFileName().toString()).sorted().toList());
            }
            if (matches.size() == 1) {
                return Optional.of(matches.get(0));
            }
        }
        return Optional.empty();
    }
}
