package dev.cobolonjava.junit;

import dev.cobolonjava.runtime.procedure.ProcedureDecision;
import dev.cobolonjava.runtime.procedure.ProcedureId;
import dev.cobolonjava.runtime.procedure.ProcedureInvocation;
import dev.cobolonjava.runtime.procedure.ProcedureOutcome;
import dev.cobolonjava.runtime.storage.DataView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 明示的なPERFORM SECTIONを置換または監視するテスト境界。 */
public final class CobolSectionMock {

    private static final ProcedureAnswer NOOP = invocation -> { };

    private final ProcedureId id;
    private final boolean spy;
    private final LongSupplier sequence;
    private final List<SectionInvocation> invocations = new ArrayList<>();
    private final ThreadLocal<Deque<Pending>> pending =
            ThreadLocal.withInitial(ArrayDeque::new);
    private ProcedureAnswer answer = NOOP;
    private Integer expectedCalls;

    CobolSectionMock(ProcedureId id, boolean spy, LongSupplier sequence) {
        this.id = Objects.requireNonNull(id, "id");
        this.spy = spy;
        this.sequence = Objects.requireNonNull(sequence, "sequence");
        this.expectedCalls = spy ? null : 1;
    }

    public CobolSectionMock thenAnswer(ProcedureAnswer value) {
        if (spy) {
            throw new IllegalTestStateException(
                    "spySection observes the real SECTION and cannot replace its input");
        }
        answer = Objects.requireNonNull(value, "value");
        return this;
    }

    public CobolSectionMock times(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("expected call count must not be negative");
        }
        expectedCalls = count;
        return this;
    }

    public synchronized int count() {
        return invocations.size();
    }

    public synchronized List<SectionInvocation> invocations() {
        return List.copyOf(invocations);
    }

    ProcedureId id() {
        return id;
    }

    ProcedureDecision before(ProcedureInvocation invocation) {
        Pending call = new Pending(sequence.getAsLong(),
                invocation.workingStorage().array().clone(), snapshot(invocation));
        try {
            answer.answer(invocation);
        } catch (RuntimeException | Error failure) {
            record(call, invocation, ProcedureOutcome.THREW);
            throw failure;
        } catch (Exception failure) {
            record(call, invocation, ProcedureOutcome.THREW);
            throw new CobolMockException("SECTION mock failed: " + id, failure);
        }
        pending.get().push(call);
        return spy ? ProcedureDecision.PROCEED : ProcedureDecision.RETURN;
    }

    void after(ProcedureInvocation invocation, ProcedureOutcome outcome) {
        Deque<Pending> stack = pending.get();
        Pending call = stack.poll();
        if (call == null) {
            throw new IllegalTestStateException("missing SECTION invocation state: " + id);
        }
        record(call, invocation, outcome);
        if (stack.isEmpty()) {
            pending.remove();
        }
    }

    synchronized void verify() {
        if (expectedCalls != null && invocations.size() != expectedCalls) {
            throw new AssertionError("expected " + id + " to be performed "
                    + expectedCalls + " time(s), but was " + invocations.size());
        }
    }

    private void record(Pending call, ProcedureInvocation invocation, ProcedureOutcome outcome) {
        SectionInvocation completed = new SectionInvocation(call.sequence, id,
                call.workingStorageBefore, invocation.workingStorage().array().clone(),
                call.argumentsBefore, snapshot(invocation), outcome);
        synchronized (this) {
            invocations.add(completed);
        }
    }

    private static List<byte[]> snapshot(ProcedureInvocation invocation) {
        return invocation.arguments().stream().map(DataView::toByteArray).toList();
    }

    private record Pending(long sequence, byte[] workingStorageBefore,
                           List<byte[]> argumentsBefore) {
    }
}
