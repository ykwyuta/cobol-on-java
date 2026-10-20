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
 * <p>名前をそのまま、および決まった拡張子を付けて探す。大文字と小文字は区別しない
 * ({@link MemberFiles})。{@code .dcl} は DCLGEN の出力を置く慣習の拡張子である
 * (CardDemo は {@code COPY DCLTRTYP.} を {@code dcl/DCLTRTYP.dcl} に置く)。
 */
public final class DirectoryCopyBookResolver implements CopyBookResolver {

    /** 名前に付けて試す拡張子。 */
    private static final List<String> SUFFIXES = List.of("", ".cpy", ".cbl", ".dcl");

    private final Path directory;

    public DirectoryCopyBookResolver(Path directory) {
        this.directory = directory;
    }

    @Override
    public Optional<CopyBook> resolve(String textName, String libraryName) {
        Path base = libraryName == null ? directory : directory.resolve(libraryName);
        return MemberFiles.find(base, textName, SUFFIXES)
                .map(found -> new CopyBook(found.getFileName().toString(), read(found)));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read copybook " + path, e);
        }
    }
}
