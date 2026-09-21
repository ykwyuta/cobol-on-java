package dev.cobolonjava.verify.hlasm;

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
 * 外に置いた HLASM コーパスと、参照実装から採った期待値を読む。
 *
 * <p>コーパスは<b>同梱しない</b> (要件 NFR-042)。取得元・版・SHA-256 は別の manifest に固定する。
 *
 * <pre>
 * hlasm-corpus/
 *   decimal/
 *     add-01.asm        検査プログラム
 *     add-01.obj        期待する機械語 (16 進)。無ければ組み立ての受理だけを測る
 *     add-01.in         作業域の初期値 (16 進)。無ければゼロ
 *     add-01.out        期待する実行結果。無ければ実行しない
 * </pre>
 */
public final class HlasmSourceDirectory {

    private HlasmSourceDirectory() {
    }

    public static List<HlasmVerificationRunner.Source> read(Path root) {
        List<HlasmVerificationRunner.Source> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path source : walk.filter(Files::isRegularFile)
                    .filter(HlasmSourceDirectory::isHlasm).sorted().toList()) {
                Path relative = root.relativize(source);
                String group = relative.getNameCount() > 1
                        ? relative.getName(0).toString() : "(root)";
                out.add(new HlasmVerificationRunner.Source(
                        source.getFileName().toString(), group,
                        readText(source),
                        optional(source, ".obj"),
                        optional(source, ".in"),
                        optional(source, ".out")));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return List.copyOf(out);
    }

    private static boolean isHlasm(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".asm") || name.endsWith(".hlasm") || name.endsWith(".mlc");
    }

    private static String optional(Path source, String suffix) {
        Path sibling = source.resolveSibling(baseName(source) + suffix);
        return Files.isRegularFile(sibling) ? readText(sibling) : null;
    }

    private static String baseName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
