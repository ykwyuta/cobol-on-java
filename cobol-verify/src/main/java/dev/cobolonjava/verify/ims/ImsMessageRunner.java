package dev.cobolonjava.verify.ims;

import dev.cobolonjava.ims.batch.ImsProgramRunner;
import dev.cobolonjava.ims.dli.InMemoryMessageQueue;
import dev.cobolonjava.ims.dli.InputMessage;
import dev.cobolonjava.ims.dli.MessageQueue;
import dev.cobolonjava.ims.dli.MessageQueues;
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
import java.util.ArrayList;
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
 *
 * <p>キューは既定ではこの JVM の中のメモリである。{@code -Dcobol.ims.jms.factory=...} を指定すると
 * {@code cobol-ims-jms} が差し込むブローカのキューになり、電文はブローカを経由して届く (P-165)。
 * どちらの場合も、応答は領域が送った時点で数える (送った先がメモリかブローカかによらず同じ数を出すため)。
 */
public final class ImsMessageRunner {

    private ImsMessageRunner() {
    }

    /** 領域が送った応答を控える。ブローカへ送っても測れるようにするための覆いである。 */
    private static final class Recording implements MessageQueue, AutoCloseable {

        private final MessageQueue delegate;
        private final List<OutputMessage> sent = new ArrayList<>();

        Recording(MessageQueue delegate) {
            this.delegate = delegate;
        }

        @Override
        public InputMessage next() {
            return delegate.next();
        }

        @Override
        public void send(OutputMessage message) {
            sent.add(message);
            delegate.send(message);
        }

        @Override
        public void commit() {
            delegate.commit();
        }

        @Override
        public void rollback() {
            // 巻き戻された応答は送られない。控えからも落とす
            sent.clear();
            delegate.rollback();
        }

        @Override
        public boolean enqueue(InputMessage message) {
            return delegate.enqueue(message);
        }

        List<OutputMessage> sent() {
            return List.copyOf(sent);
        }

        @Override
        public void close() throws Exception {
            if (delegate instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
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

        List<InputMessage> input = new ArrayList<>();
        for (String line : Files.readAllLines(messages, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int bar = line.indexOf('|');
            if (bar < 0) {
                throw new IllegalArgumentException("a message line is 'LTERM|text': " + line);
            }
            input.add(new InputMessage(line.substring(0, bar),
                    List.of(encode(line.substring(bar + 1), codePage))));
        }

        // 差し込みのキュー (JMS) が構成されていればそちら、無ければメモリのキュー (P-165)
        MessageQueue configured = MessageQueues.open(program);
        String transport = configured == null ? "メモリ" : configured.getClass().getName();
        ProgramContext context = ProgramContext.standard().withCatalog(catalog);
        String failure = null;
        int returnCode = 0;
        List<OutputMessage> sent = List.of();
        Recording queue = new Recording(configured != null ? configured : new InMemoryMessageQueue());
        try {
            for (InputMessage message : input) {
                if (!queue.enqueue(message)) {
                    throw new IllegalStateException("the queue " + transport + " cannot be given input messages");
                }
            }
            try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                    ImsMessageRunner.class.getClassLoader())) {
                returnCode = ImsProgramRunner.run(context, loader, program, psb, queue);
            } catch (RuntimeException e) {
                failure = e.toString();
            } finally {
                context.closeFiles();
            }
            sent = queue.sent();
        } finally {
            try {
                queue.close();
            } catch (Exception e) {
                failure = failure == null ? e.toString() : failure;
            }
        }

        StringBuilder out = new StringBuilder("IMS のメッセージ処理 (設計 78 §4)\n");
        out.append("==============================\n\n");
        out.append("キュー: ").append(transport).append('\n');
        out.append(String.format("%s: 入力 %d 件、応答 %d 件、復帰コード %d%n", program, input.size(), sent.size(),
                returnCode));
        if (failure != null) {
            out.append("失敗: ").append(failure).append('\n');
        }
        for (OutputMessage message : sent) {
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
