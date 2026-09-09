package dev.cobolonjava.verify.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * ディレクトリに置かれた資産を読む (要件 NFR-042)。
 *
 * <p>OSS のコーパスはここから入る。取得スクリプトが {@code corpus/持ち主-資産/} という形で
 * 並べるので、<b>直下のディレクトリ名を区分にする</b>。どの資産がどれだけ通ったかが、
 * それで分かる。
 *
 * <p>コーパス本体はリポジトリに同梱しない (要件 NFR-042)。素性の違うソースを
 * Apache-2.0 の成果物へ取り込まないためである。だからこの道具は<b>外にある置き場</b>を
 * 指して動く。
 */
public final class SourceDirectory {

    private SourceDirectory() {
    }

    /** COBOL の原文として読む拡張子。 */
    private static final List<String> SUFFIXES =
            List.of(".cbl", ".cob", ".cobol", ".ccp", ".cpy", ".pco");

    /** 置き場の下にある資産をすべて読む。 */
    public static List<CorpusRunner.Source> read(Path root) {
        List<CorpusRunner.Source> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(SourceDirectory::named).sorted().toList();
            for (Path file : files) {
                out.add(new CorpusRunner.Source(String.valueOf(file.getFileName()),
                        group(root, file), text(file)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(out);
    }

    /** 区分は置き場の直下のディレクトリ名である。直下のファイルなら {@code (root)}。 */
    private static String group(Path root, Path file) {
        Path relative = root.relativize(file);
        return relative.getNameCount() > 1 ? relative.getName(0).toString() : "(root)";
    }

    private static boolean named(Path file) {
        String name = String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT);
        return SUFFIXES.stream().anyMatch(name::endsWith);
    }

    /**
     * 中身を読む。
     *
     * <p>符号化は決め打ちしない。資産は UTF-8 のこともラテン 1 のこともあり、<b>読めない
     * バイトがあっても止めない</b>。翻訳が通るかを見るのが目的であり、文字の選び分けは
     * 別の話である。
     */
    private static String text(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
