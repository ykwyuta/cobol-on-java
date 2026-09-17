package dev.cobolonjava.verify.pli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** 外に置いた PL/I コーパスと、参照処理系から採取した期待出力を読む。 */
public final class PliSourceDirectory {

    private PliSourceDirectory() {
    }

    public static List<PliVerificationRunner.Source> read(Path root) {
        List<PliVerificationRunner.Source> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path source : walk.filter(Files::isRegularFile)
                    .filter(PliSourceDirectory::isPli).sorted().toList()) {
                Path relative = root.relativize(source);
                String group = relative.getNameCount() > 1
                        ? relative.getName(0).toString() : "(root)";
                Path oracle = source.resolveSibling(baseName(source) + ".out");
                String expected = Files.isRegularFile(oracle) ? readText(oracle) : null;
                out.add(new PliVerificationRunner.Source(source.getFileName().toString(), group,
                        readText(source), expected));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return List.copyOf(out);
    }

    private static boolean isPli(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pli") || name.endsWith(".pl1");
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
