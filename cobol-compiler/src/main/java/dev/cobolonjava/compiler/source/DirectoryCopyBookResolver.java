package dev.cobolonjava.compiler.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * ディレクトリからコピー句を探す (要件 FR-090)。
 *
 * <p>名前をそのまま、および決まった拡張子を付けて探す。<b>大文字と小文字は
 * ファイル体系に任せる</b> — 探索の規則を自前で持つと、環境によって見つかったり
 * 見つからなかったりする理由が増える。
 */
public final class DirectoryCopyBookResolver implements CopyBookResolver {

    /** 名前に付けて試す拡張子。 */
    private static final List<String> SUFFIXES = List.of("", ".cpy", ".CPY", ".cbl", ".CBL");

    private final Path directory;

    public DirectoryCopyBookResolver(Path directory) {
        this.directory = directory;
    }

    @Override
    public Optional<CopyBook> resolve(String textName, String libraryName) {
        Path base = libraryName == null ? directory : directory.resolve(libraryName);
        for (String suffix : SUFFIXES) {
            Path candidate = base.resolve(textName + suffix);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(new CopyBook(candidate.getFileName().toString(),
                        read(candidate)));
            }
        }
        return Optional.empty();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read copybook " + path, e);
        }
    }
}
