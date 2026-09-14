package dev.cobolonjava.compiler.source;

import dev.cobolonjava.cics.bms.BmsDefinitionException;
import dev.cobolonjava.cics.bms.BmsParser;
import dev.cobolonjava.cics.bms.BmsSymbolicMapWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * BMSマクロの原文から、COBOLの記号マップ写し句をその場で作って返す (要件 FR-162)。
 *
 * <p>hostでは組立て工程が記号マップを別に作り、COBOLはそれを{@code COPY mapset}で取り込む。
 * 生成物を置き場へ書き出して二重に管理すると、BMSを直したのに写し句が古いまま、という
 * ずれが起きる。原文を正本にして、引かれるたびに作る。
 */
public final class BmsCopyBookResolver implements CopyBookResolver {

    private static final List<String> SUFFIXES = List.of(".bms", ".BMS");

    private final Path directory;

    public BmsCopyBookResolver(Path directory) {
        this.directory = directory;
    }

    @Override
    public Optional<CopyBook> resolve(String textName, String libraryName) {
        Path base = libraryName == null ? directory : directory.resolve(libraryName);
        for (String suffix : SUFFIXES) {
            Path candidate = base.resolve(textName + suffix);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(generate(candidate));
            }
        }
        return Optional.empty();
    }

    private static CopyBook generate(Path source) {
        String fileName = source.getFileName().toString();
        try {
            String text = Files.readString(source, StandardCharsets.ISO_8859_1);
            return new CopyBook(fileName, BmsSymbolicMapWriter.cobol(BmsParser.parse(text)));
        } catch (BmsDefinitionException invalid) {
            // COPY文の位置は写し句展開の側が付ける。ここでは原文の行を残す
            throw new SourceFormatException(fileName + " " + invalid.getMessage());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read BMS source " + source, e);
        }
    }
}
