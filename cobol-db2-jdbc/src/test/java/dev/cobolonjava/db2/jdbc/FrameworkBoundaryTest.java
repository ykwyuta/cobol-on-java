package dev.cobolonjava.db2.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class FrameworkBoundaryTest {

    @Test
    @DisplayName("direct JDBC adapterのmain sourceはSpring型へ依存しない")
    void mainSourcesDoNotImportSpring() throws IOException {
        Path root = Path.of("src", "main", "java");
        try (var files = Files.walk(root)) {
            assertFalse(files.filter(path -> path.toString().endsWith(".java"))
                    .map(FrameworkBoundaryTest::read)
                    .anyMatch(source -> source.contains("org.springframework")));
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
