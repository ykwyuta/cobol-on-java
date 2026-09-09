package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 中立モジュールへHTTP、Spring、Db2型を混入させないbuild-time gate。 */
@Tag("V1")
class FrameworkNeutralBoundaryTest {

    @Test
    @DisplayName("cobol-cics main sourceはframeworkとDb2固有APIをimportしない")
    void mainApiHasNoFrameworkSpecificImports() throws IOException {
        Path sources = sourceDirectory();
        List<String> forbidden = List.of(
                "import java.sql.",
                "import javax.sql.",
                "import org.springframework.",
                "import jakarta.servlet.",
                "import dev.cobolonjava.db2.");

        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String prefix : forbidden) {
                    assertFalse(source.contains(prefix),
                            () -> file + " crosses the neutral boundary with " + prefix);
                }
            }
        }
    }

    private static Path sourceDirectory() {
        Path moduleRelative = Path.of("src/main/java/dev/cobolonjava/cics");
        Path rootRelative = Path.of("cobol-cics/src/main/java/dev/cobolonjava/cics");
        Path result = Files.isDirectory(moduleRelative) ? moduleRelative : rootRelative;
        assertTrue(Files.isDirectory(result), "cannot locate cobol-cics main sources");
        return result;
    }
}
