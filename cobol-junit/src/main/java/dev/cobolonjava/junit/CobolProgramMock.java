package dev.cobolonjava.junit;

import dev.cobolonjava.runtime.interop.JavaCallable;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.storage.DataView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 外部プログラム境界を Java で差し替える、ライブラリ非依存の低レベル Mock。 */
public final class CobolProgramMock {

    private final ProgramId id;
    private final LongSupplier sequence;
    private final List<JavaCallable> answers = new ArrayList<>();
    private final List<ProgramInvocation> invocations = new ArrayList<>();
    private Integer expectedCalls;
    private boolean repeatLast;

    CobolProgramMock(String name, LongSupplier sequence, boolean expectation) {
        this.id = ProgramId.of(name);
        this.sequence = sequence;
        this.expectedCalls = expectation ? 1 : null;
        this.repeatLast = !expectation;
    }

    public CobolProgramMock thenAnswer(JavaCallable answer) {
        answers.add(Objects.requireNonNull(answer, "answer"));
        return this;
    }

    public CobolProgramMock thenReturn() {
        return thenAnswer((context, arguments) -> { });
    }

    public CobolProgramMock thenRepeatLast() {
        repeatLast = true;
        return this;
    }

    public CobolProgramMock times(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("expected call count must not be negative");
        }
        expectedCalls = count;
        return this;
    }

    public synchronized int count() {
        return invocations.size();
    }

    public synchronized List<ProgramInvocation> invocations() {
        return List.copyOf(invocations);
    }

    ProgramId id() {
        return id;
    }

    JavaCallable callable() {
        return (context, arguments) -> {
            JavaCallable answer;
            int index;
            synchronized (this) {
                index = invocations.size();
                answer = answerAt(index);
            }
            List<byte[]> before = snapshot(arguments);
            try {
                answer.invoke(context, arguments);
            } finally {
                ProgramInvocation invocation = new ProgramInvocation(sequence.getAsLong(), id,
                        before, snapshot(arguments));
                synchronized (this) {
                    invocations.add(invocation);
                }
            }
        };
    }

    synchronized void verify() {
        if (expectedCalls != null && invocations.size() != expectedCalls) {
            throw new AssertionError("expected " + id.value() + " to be called "
                    + expectedCalls + " time(s), but was " + invocations.size());
        }
    }

    private JavaCallable answerAt(int index) {
        if (answers.isEmpty()) {
            if (expectedCalls != null && index >= expectedCalls) {
                return unexpectedAnswer(index);
            }
            return (context, arguments) -> { };
        }
        if (index < answers.size()) {
            return answers.get(index);
        }
        if (repeatLast) {
            return answers.get(answers.size() - 1);
        }
        return unexpectedAnswer(index);
    }

    private JavaCallable unexpectedAnswer(int index) {
        return (context, arguments) -> {
            throw new UnexpectedCobolCallException(
                    "unexpected call " + (index + 1) + " to " + id.value());
        };
    }

    private static List<byte[]> snapshot(List<DataView> arguments) {
        return arguments.stream().map(DataView::toByteArray).toList();
    }
}
