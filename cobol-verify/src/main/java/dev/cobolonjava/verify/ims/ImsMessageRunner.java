package dev.cobolonjava.verify.ims;

import dev.cobolonjava.ims.batch.ImsProgramRunner;
import dev.cobolonjava.ims.dli.InMemoryMessageQueue;
import dev.cobolonjava.ims.dli.InputMessage;
import dev.cobolonjava.ims.dli.OutputMessage;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * IMS のメッセージ処理プログラム (MPP) に電文を流し、応答を数える (設計 78 §4)。
 *
 * <p>置き場には {@code IMS.PSBLIB} (PSB と DBD の原文のライブラリ) と、データベースのデータセットを置く。
 * データセットは名前の最後の修飾子を DD 名として割り当てる ({@code BANK.CUSTOMER} は DD {@code CUSTOMER})。
 * 流したあと、データベースは置き場に書き戻される。
 *
 * <p>電文のファイルは 1 行 1 電文で、{@code 論理端末名|本文}。本文は EBCDIC にし、{@code {00000001}} の
 * 波括弧の中は 16 進の byte として埋める (2 進の欄を持つ電文のため)。{@code #} で始まる行は注記である。
 */
public final class ImsMessageRunner {

    private ImsMessageRunner() {
    }

    public static String run(Path base, Path classes, String program, String psb, Path messages)
            throws IOException {
        CodePage codePage = CodePages.DEFAULT;
        DataSetCatalog catalog = new DataSetCatalog(base);
        catalog = catalog.assign(ImsProgramRunner.LIBRARY, base.resolve("IMS.PSBLIB"));
        try (Stream<Path> files = Files.list(base)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot > 0 && !name.endsWith(".meta")) {
                    catalog = catalog.assign(name.substring(dot + 1), file);
                }
            }
        }

        InMemoryMessageQueue queue = new InMemoryMessageQueue();
        int count = 0;
        for (String line : Files.readAllLines(messages, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int bar = line.indexOf('|');
            if (bar < 0) {
                throw new IllegalArgumentException("a message line is 'LTERM|text': " + line);
            }
            queue.offer(new InputMessage(line.substring(0, bar), List.of(encode(line.substring(bar + 1), codePage))));
            count++;
        }

        ProgramContext context = ProgramContext.standard().withCatalog(catalog);
        String failure = null;
        int returnCode = 0;
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                ImsMessageRunner.class.getClassLoader())) {
            returnCode = ImsProgramRunner.run(context, loader, program, psb, queue);
        } catch (RuntimeException e) {
            failure = e.toString();
        } finally {
            context.closeFiles();
        }

        StringBuilder out = new StringBuilder("IMS のメッセージ処理 (設計 78 §4)\n");
        out.append("==============================\n\n");
        out.append(String.format("%s: 入力 %d 件、応答 %d 件、復帰コード %d%n", program, count, queue.sent().size(),
                returnCode));
        if (failure != null) {
            out.append("失敗: ").append(failure).append('\n');
        }
        for (OutputMessage message : queue.sent()) {
            for (byte[] segment : message.segments()) {
                out.append(message.destination()).append(" | ").append(printable(codePage, segment))
                        .append(" | ").append(HexFormat.of().withUpperCase().formatHex(segment)).append('\n');
            }
        }
        return out.toString();
    }

    /** 本文を EBCDIC にし、波括弧の中を 16 進の byte として埋める。 */
    static byte[] encode(String text, CodePage codePage) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int at = 0;
        while (at < text.length()) {
            int open = text.indexOf('{', at);
            if (open < 0) {
                out.writeBytes(codePage.encode(text.substring(at)));
                break;
            }
            int close = text.indexOf('}', open);
            if (close < 0) {
                throw new IllegalArgumentException("an unclosed { in a message: " + text);
            }
            out.writeBytes(codePage.encode(text.substring(at, open)));
            out.writeBytes(HexFormat.of().parseHex(text.substring(open + 1, close)));
            at = close + 1;
        }
        return out.toByteArray();
    }

    /** 印字できない byte は点にする。 */
    private static String printable(CodePage codePage, byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (char c : codePage.decode(bytes).toCharArray()) {
            sb.append(c >= 0x20 && c < 0x7F ? c : '.');
        }
        return sb.toString();
    }
}
