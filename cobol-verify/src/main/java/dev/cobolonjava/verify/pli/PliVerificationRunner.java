package dev.cobolonjava.verify.pli;

import dev.cobolonjava.pli.PliCompiler;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** PL/I の外部コーパスを、1 本の故障で測定全体を止めずに翻訳・実行する。 */
public final class PliVerificationRunner {

    public static final long LIMIT_SECONDS = 60;

    @FunctionalInterface
    public interface Compilation {
        PliCompiler.Result compile(String fileName, String source);
    }

    public record Source(String name, String group, String text, String expectedOutput) {
    }

    private final Compilation compilation;
    private final long limitSeconds;

    public PliVerificationRunner(Compilation compilation, long limitSeconds) {
        this.compilation = compilation;
        this.limitSeconds = limitSeconds;
    }

    public static PliVerificationRunner standard() {
        return new PliVerificationRunner(PliCompiler.standard()::compile, LIMIT_SECONDS);
    }

    public PliCaseOutcome run(Source source) {
        BlockingQueue<Object> done = new ArrayBlockingQueue<>(1);
        Thread worker = new Thread(() -> {
            try {
                done.offer(runNow(source));
            } catch (RuntimeException | LinkageError | StackOverflowError | AssertionError failure) {
                done.offer(failure);
            }
        }, "pli-verify-" + source.name());
        worker.setDaemon(true);
        worker.start();
        try {
            Object value = done.poll(limitSeconds, TimeUnit.SECONDS);
            if (value == null) {
                worker.interrupt();
                return PliCaseOutcome.timedOut(source.name(), source.group(), limitSeconds);
            }
            return value instanceof Throwable failure
                    ? PliCaseOutcome.crashed(source.name(), source.group(), failure)
                    : (PliCaseOutcome) value;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            worker.interrupt();
            return PliCaseOutcome.crashed(source.name(), source.group(), interrupted);
        }
    }

    private PliCaseOutcome runNow(Source source) {
        PliCompiler.Result result = compilation.compile(source.name(), source.text());
        if (!result.succeeded()) {
            return PliCaseOutcome.rejected(source.name(), source.group(),
                    result.diagnostics().stream().map(Object::toString).toList());
        }
        if (source.expectedOutput() == null) {
            return PliCaseOutcome.compiled(source.name(), source.group());
        }
        GeneratedLoader loader = new GeneratedLoader();
        try {
            Class<?> type = loader.define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            program.runFresh(ProgramContext.capturing(output));
            String actual = normalize(output.toString(StandardCharsets.UTF_8));
            return actual.equals(normalize(source.expectedOutput()))
                    ? PliCaseOutcome.passed(source.name(), source.group())
                    : PliCaseOutcome.wrongOutput(source.name(), source.group());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("generated PL/I class cannot be loaded", failure);
        }
    }

    public PliVerificationReport run(List<Source> sources) {
        List<PliCaseOutcome> outcomes = new ArrayList<>();
        for (Source source : sources) outcomes.add(run(source));
        return new PliVerificationReport(outcomes);
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(PliVerificationRunner.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
