package dev.cobolonjava.mq;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.source.MqSystemCopyBookResolver;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 試験のための COBOL プログラム: 処理系が用意する MQ の写し句を写し、{@code CALL 'MQxxx'} を呼ぶ段落を持つ。
 * 写し句と MQI を同じ道で通すために、Java から MQI を直に呼ばず、必ず翻訳したプログラムから呼ぶ。
 */
final class MqProgram {

    private static final List<String> DATA = List.of(
            "01 HCONN PIC S9(9) BINARY VALUE 0.",
            "01 HOBJ PIC S9(9) BINARY VALUE 0.",
            "01 OPTS PIC S9(9) BINARY VALUE 0.",
            "01 CC PIC S9(9) BINARY VALUE 0.",
            "01 RC PIC S9(9) BINARY VALUE 0.",
            "01 BUFLEN PIC S9(9) BINARY VALUE 20.",
            "01 DATALEN PIC S9(9) BINARY VALUE 0.",
            "01 BUF PIC X(20).",
            "01 CC-D PIC 9(4).",
            "01 RC-D PIC 9(4).",
            "01 N-D PIC -9(9).",
            "01 OD. COPY CMQODV.",
            "01 MD. COPY CMQMDV.",
            "01 PMO. COPY CMQPMOV.",
            "01 GMO. COPY CMQGMOV.",
            "01 K. COPY CMQV.");

    /** 段落の部品。MAIN の後ろに置く。 */
    private static final List<String> PARAGRAPHS = List.of(
            "OPEN-Q.",
            "    CALL 'MQOPEN' USING HCONN OD OPTS HOBJ CC RC.",
            "PUT-MSG.",
            "    CALL 'MQPUT' USING HCONN HOBJ MD PMO BUFLEN BUF CC RC.",
            "GET-MSG.",
            "    MOVE MQMI-NONE TO MQMD-MSGID",
            "    MOVE MQCI-NONE TO MQMD-CORRELID",
            "    MOVE SPACES TO BUF",
            "    CALL 'MQGET' USING HCONN HOBJ MD GMO BUFLEN BUF DATALEN",
            "                       CC RC.",
            "SHOW.",
            "    MOVE CC TO CC-D",
            "    MOVE RC TO RC-D",
            "    DISPLAY CC-D ' ' RC-D ' ' BUF.");

    private static final class GeneratedLoader extends ClassLoader {
        private GeneratedLoader() {
            super(MqProgram.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private final String name;
    private final Class<?> type;
    private final GeneratedLoader loader;

    private MqProgram(String name, Class<?> type, GeneratedLoader loader) {
        this.name = name;
        this.type = type;
        this.loader = loader;
    }

    /** 翻訳する。{@code main} は MAIN 段落の文で、最後に GOBACK を足す。 */
    static MqProgram compile(String name, List<String> main) {
        return compile(name, List.of(), main);
    }

    static MqProgram compile(String name, List<String> data, List<String> main) {
        List<String> lines = new ArrayList<>(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. " + name + ".",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION."));
        lines.addAll(DATA);
        lines.addAll(data);
        lines.add("PROCEDURE DIVISION.");
        lines.add("MAIN.");
        lines.addAll(main);
        lines.add("    GOBACK.");
        lines.addAll(PARAGRAPHS);
        StringBuilder source = new StringBuilder();
        for (String line : lines) {
            String card = "       " + line;
            if (card.length() > 72) {
                throw new IllegalArgumentException("the test line is longer than 72 columns: " + line);
            }
            source.append(card).append('\n');
        }
        CobolCompiler.Result result = CobolCompiler.with(new MqSystemCopyBookResolver())
                .compile(name + ".cbl", source.toString());
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        GeneratedLoader loader = new GeneratedLoader();
        return new MqProgram(name, loader.define(result.className(), result.classFile()), loader);
    }

    /** 1 つの実行単位で流し、DISPLAY した行を返す。 */
    List<String> run(MqQueueManager manager) {
        ProgramCatalog catalog = Mqi.register(ProgramCatalog.builder().cobolProgram(name, () -> {
            try {
                return (CobolProgram) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }), manager).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CobolSession session = CobolRuntime.builder(catalog).classLoader(loader).build().openSession(output)) {
            session.call(name);
        }
        return output.toString(StandardCharsets.UTF_8).lines().map(String::stripTrailing).toList();
    }
}
