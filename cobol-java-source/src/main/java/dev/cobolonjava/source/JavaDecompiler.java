package dev.cobolonjava.source;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleFileSaver;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

final class JavaDecompiler {

    private JavaDecompiler() {
    }

    /** 一つの原文から生成したクラス群をまとめて復元する。 */
    static Map<String, String> decompile(Map<String, byte[]> classes) throws IOException {
        Map<String, byte[]> bytecode = new LinkedHashMap<>();
        classes.forEach((name, bytes) -> bytecode.put(name.replace('.', '/'), bytes));
        GeneratedClasses source = new GeneratedClasses(bytecode, new LinkedHashMap<>());
        ErrorLog errors = new ErrorLog();
        // GeneratedClasses supplies the output sink. Vineflower still closes its saver.
        Fernflower decompiler = new Fernflower(new ConsoleFileSaver(null), Map.of(
                IFernflowerPreferences.REMOVE_SYNTHETIC, "0",
                IFernflowerPreferences.DECOMPILE_PREVIEW, "0",
                IFernflowerPreferences.USE_METHOD_PARAMETERS, "1",
                IFernflowerPreferences.THREADS, "1"), errors);
        try {
            decompiler.addSource(source);
            decompiler.addLibrary(new RuntimeClasses());
            decompiler.decompileContext();
        } catch (RuntimeException e) {
            throw new IOException("cannot decompile " + classes.keySet(), e);
        } finally {
            decompiler.clearContext();
        }
        if (errors.failure != null) {
            throw new IOException("cannot decompile " + classes.keySet(), errors.failure);
        }
        if (!source.sources().keySet().equals(bytecode.keySet())) {
            throw new IOException("incomplete Java output for " + classes.keySet());
        }
        return source.sources();
    }

    private record GeneratedClasses(Map<String, byte[]> bytecode, Map<String, String> sources)
            implements IContextSource {

        @Override
        public String getName() {
            return "generated programs";
        }

        @Override
        public Entries getEntries() {
            return new Entries(bytecode.keySet().stream().map(Entry::atBase).toList(),
                    List.of(), List.of());
        }

        @Override
        public InputStream getInputStream(String path) {
            byte[] bytes = bytecode.get(path.substring(0, path.length() - CLASS_SUFFIX.length()));
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }

        @Override
        public IOutputSink createOutputSink(IResultSaver saver) {
            return new IOutputSink() {
                @Override public void begin() {}
                @Override public void acceptDirectory(String directory) {}
                @Override public void acceptOther(String path) {}
                @Override public void close() {}

                @Override
                public void acceptClass(String name, String file, String content, int[] mapping) {
                    if (content != null && !content.isBlank()) {
                        sources.put(name, content);
                    }
                }
            };
        }
    }

    private static final class RuntimeClasses implements IContextSource {
        @Override public String getName() { return "runtime classpath"; }
        @Override public Entries getEntries() { return Entries.EMPTY; }
        @Override public boolean isLazy() { return true; }

        @Override
        public InputStream getInputStream(String path) {
            return JavaDecompiler.class.getClassLoader().getResourceAsStream(path);
        }
    }

    private static final class ErrorLog extends IFernflowerLogger {
        private IOException failure;

        @Override
        public void writeMessage(String message, Severity severity) {
            writeMessage(message, severity, null);
        }

        @Override
        public void writeMessage(String message, Severity severity, Throwable cause) {
            if (severity == Severity.ERROR && failure == null) {
                failure = new IOException(message, cause);
            }
        }
    }
}
