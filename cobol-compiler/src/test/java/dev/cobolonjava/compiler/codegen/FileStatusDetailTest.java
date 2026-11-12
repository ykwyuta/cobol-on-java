package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 入出力状態の細かい値。ZCVS (Zeus の検査スイート) の ZF の区分で見つかったもの。
 *
 * <p>期待値の根拠は 85 規格の入出力状態の表と Enterprise COBOL の言語解説書であり、
 * 実機とは突き合わせていない。
 */
@Tag("V1")
class FileStatusDetailTest {

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(FileStatusDetailTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static List<String> run(Path directory, String... lines) throws Exception {
        StringBuilder source = new StringBuilder();
        for (String line : lines) {
            source.append("       ").append(line).append('\n');
        }
        CobolCompiler.Result result = CobolCompiler.standard().compile("PGM.cbl",
                source.toString());
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ((CobolProgram) type.getDeclaredConstructor().newInstance()).runFresh(
                ProgramContext.capturing(sink).withCatalog(new DataSetCatalog(directory)));
        return sink.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    @DisplayName("開く前に読み書きしても、あとの OPEN は宣言どおりの様式でファイルを用意する")
    void requestsBeforeOpenDoNotFixTheFormat(@TempDir Path directory) throws Exception {
        List<String> output = run(directory,
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. PGM.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT SFILE ASSIGN TO SDATA FILE STATUS FS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  SFILE RECORDING MODE F.",
                "01  SREC PIC X(8).",
                "WORKING-STORAGE SECTION.",
                "01  FS PIC XX.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                // 開く前の要求は、文ごとに決まった状態になる。42 は CLOSE だけである
                "    READ SFILE. DISPLAY FS.",
                "    WRITE SREC. DISPLAY FS.",
                "    CLOSE SFILE. DISPLAY FS.",
                "    OPEN OUTPUT SFILE.",
                "    MOVE 'ORIGINAL' TO SREC.",
                "    WRITE SREC.",
                "    CLOSE SFILE.",
                "    OPEN INPUT SFILE.",
                "    MOVE SPACES TO SREC.",
                "    READ SFILE. DISPLAY FS SREC.",
                "    CLOSE SFILE.",
                "    STOP RUN.");

        assertEquals(List.of("47", "48", "42", "00ORIGINAL"), output);
        // 既定の様式 (80 byte) を覚えてしまうと、8 byte のレコードが 80 byte で書かれ、
        // 読み戻しが長さ違いの 04 になる
        assertEquals(8, Files.size(directory.resolve("SDATA")));
    }

    @Test
    @DisplayName("FILE STATUS の 2 つ目の項目は VSAM の戻りコードを受け、番号読みは終わりのあとも通る")
    void vsamStatusAndRandomReadAfterEnd(@TempDir Path directory) throws Exception {
        List<String> output = run(directory,
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. PGM.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT RFILE ASSIGN TO RDATA ORGANIZATION RELATIVE",
                "        ACCESS DYNAMIC RELATIVE KEY RRN",
                "        FILE STATUS FS EXT-FS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  RFILE.",
                "01  RREC PIC X(4).",
                "WORKING-STORAGE SECTION.",
                "01  FS PIC XX.",
                "01  EXT-FS.",
                "    05 EXT-RC PIC 9(4) COMP-5.",
                "    05 EXT-FN PIC 9(4) COMP-5.",
                "    05 EXT-FB PIC 9(4) COMP-5.",
                "01  RRN PIC 9(4) COMP VALUE 1.",
                "01  SHOW PIC 99.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    OPEN OUTPUT RFILE. CLOSE RFILE. OPEN I-O RFILE.",
                "    MOVE 'ONE ' TO RREC.",
                "    WRITE RREC.",
                "    DISPLAY FS. MOVE EXT-RC TO SHOW DISPLAY SHOW.",
                // 同じ番号へもう 1 度書く。22、戻りコード 8、フィードバック 8
                "    WRITE RREC.",
                "    DISPLAY FS. MOVE EXT-RC TO SHOW DISPLAY SHOW.",
                "    MOVE EXT-FB TO SHOW DISPLAY SHOW.",
                "    READ RFILE NEXT. READ RFILE NEXT. DISPLAY FS.",
                // 終わりまで読んだあとでも、番号で読むのは構わない
                "    MOVE 1 TO RRN.",
                "    READ RFILE. DISPLAY FS RREC.",
                "    CLOSE RFILE.",
                "    STOP RUN.");

        assertEquals(List.of("00", "00", "22", "08", "08", "10", "00ONE "), output);
    }
}
