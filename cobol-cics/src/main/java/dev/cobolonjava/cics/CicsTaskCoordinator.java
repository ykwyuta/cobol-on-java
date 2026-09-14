package dev.cobolonjava.cics;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 一要求のclaim、program、会話変更、UOW完了、cleanupを順序付ける中立coordinator。 */
public final class CicsTaskCoordinator {

    private final CicsTransactionRegistry transactions;
    private final ConversationStorePort conversations;
    private final CicsTaskBoundaryFactory boundaries;
    private final CicsTaskProgramPort programs;
    private final CicsTaskPolicy policy;
    private final ConversationIdFactory conversationIds;
    private final Clock clock;

    public CicsTaskCoordinator(
            CicsTransactionRegistry transactions,
            ConversationStorePort conversations,
            CicsTaskBoundaryFactory boundaries,
            CicsTaskProgramPort programs,
            CicsTaskPolicy policy,
            ConversationIdFactory conversationIds,
            Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.boundaries = Objects.requireNonNull(boundaries, "boundaries");
        this.programs = Objects.requireNonNull(programs, "programs");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.conversationIds = Objects.requireNonNull(conversationIds, "conversationIds");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CicsTaskReply launch(CicsTaskRequest request) {
        Objects.requireNonNull(request, "request");
        Instant startedAt = clock.instant();
        CicsTransactionDefinition definition = transactions.resolve(request.transactionId());
        definition.validate(request.payload());
        CicsTaskId taskId = CicsTaskId.create();
        // 直前の画面は会話にある。claim してから task 文脈へ入れる
        Optional<ConversationLease> lease = claim(request, definition, startedAt);
        CicsTaskContext task = new CicsTaskContext(
                taskId, definition.transId(), request.owner(), startedAt)
                .withTerminal(request.terminalInput(),
                        lease.flatMap(claimed -> claimed.envelope().screen()))
                .withTerminalId(request.terminalId());
        CicsTaskBoundary boundary = null;
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        boolean commitStarted = false;
        try {
            boundary = boundaries.open(task, definition);
            if (boundary == null) {
                throw new IllegalStateException("task boundary factory returned null");
            }
            TaskCompletion completion = Objects.requireNonNull(
                    programs.execute(definition, request.payload(), task, boundary),
                    "program result");
            CompletionPlan plan = planCompletion(request, taskId, lease, completion, clock.instant());
            commitStarted = true;
            boundary.commit(plan.mutation, clock.instant());
            return new CicsTaskReply(taskId, definition.transId(), completion.payload(), plan.next,
                    completion.immediate(), completion.screen());
        } catch (RuntimeException failure) {
            runtimeFailure = failure;
            cleanupFailure(boundary, lease, failure, commitStarted);
            throw failure;
        } catch (Error failure) {
            errorFailure = failure;
            cleanupFailure(boundary, lease, failure, commitStarted);
            throw failure;
        } finally {
            if (boundary != null) {
                try {
                    boundary.close();
                } catch (RuntimeException | Error closeFailure) {
                    if (runtimeFailure != null) {
                        runtimeFailure.addSuppressed(closeFailure);
                    } else if (errorFailure != null) {
                        errorFailure.addSuppressed(closeFailure);
                    } else {
                        throw closeFailure;
                    }
                }
            }
        }
    }

    private Optional<ConversationLease> claim(
            CicsTaskRequest request, CicsTransactionDefinition definition, Instant now) {
        if (request.conversation().isEmpty()) {
            return Optional.empty();
        }
        if (policy.leaseDuration().compareTo(definition.taskTimeout()) <= 0) {
            throw new IllegalStateException(
                    "conversation leaseDuration must be greater than taskTimeout");
        }
        ConversationReference reference = request.conversation().orElseThrow();
        ConversationClaimResult claimed = conversations.claim(
                reference.id(), reference.expectedVersion(), request.owner(),
                policy.leaseDuration(), now);
        if (claimed.status() != ConversationClaimStatus.CLAIMED) {
            throw new ConversationConflictException(claimed.status());
        }
        ConversationLease lease = claimed.lease().orElseThrow();
        if (!lease.envelope().nextTransaction().equals(definition.transId())) {
            CicsTaskStateException mismatch = new CicsTaskStateException(
                    "requested TRANSID disagrees with the claimed conversation");
            releaseBeforeBoundary(lease, mismatch);
            throw mismatch;
        }
        return Optional.of(lease);
    }

    /** 会話へ残すのは map の画面だけである。文字だけの画面は RECEIVE MAP と照合できない。 */
    private static Optional<dev.cobolonjava.cics.bms.BmsScreenSnapshot> mapScreenOf(
            TaskCompletion completion) {
        return completion.screen()
                .filter(CicsTerminalScreen.MapScreen.class::isInstance)
                .map(screen -> ((CicsTerminalScreen.MapScreen) screen).snapshot());
    }

    private CompletionPlan planCompletion(
            CicsTaskRequest request,
            CicsTaskId taskId,
            Optional<ConversationLease> lease,
            TaskCompletion completion,
            Instant now) {
        Objects.requireNonNull(completion.payload(), "completion payload");
        if (completion.nextTransaction().isEmpty()) {
            ConversationMutation mutation = lease.<ConversationMutation>map(
                    ConversationMutation.Complete::new).orElseGet(ConversationMutation.None::new);
            return new CompletionPlan(mutation, Optional.empty());
        }

        TransId nextTransaction = completion.nextTransaction().orElseThrow();
        CicsTransactionDefinition nextDefinition = transactions.resolve(nextTransaction.value());
        nextDefinition.validate(completion.payload());
        Instant expiresAt;
        try {
            expiresAt = now.plus(policy.conversationTtl());
        } catch (DateTimeException overflow) {
            throw new IllegalStateException("conversation expiry is out of range", overflow);
        }
        Optional<String> outcome = Optional.of(taskId.value());
        if (lease.isPresent()) {
            ConversationLease claimed = lease.orElseThrow();
            ConversationEnvelope next = claimed.envelope().next(
                    nextTransaction, completion.payload(), expiresAt,
                    request.idempotencyKey(), outcome).withScreen(mapScreenOf(completion));
            return new CompletionPlan(
                    new ConversationMutation.Save(claimed, next), Optional.of(next));
        }
        ConversationEnvelope initial = new ConversationEnvelope(
                Objects.requireNonNull(conversationIds.create(), "conversation ID"),
                0, request.owner(), nextTransaction, completion.payload(), expiresAt,
                request.idempotencyKey(), outcome).withScreen(mapScreenOf(completion));
        return new CompletionPlan(
                new ConversationMutation.Create(initial), Optional.of(initial));
    }

    private void cleanupFailure(
            CicsTaskBoundary boundary,
            Optional<ConversationLease> lease,
            Throwable failure,
            boolean commitStarted) {
        if (boundary == null) {
            lease.ifPresent(value -> releaseBeforeBoundary(value, failure));
            return;
        }
        boolean safelyNotCommitted = !commitStarted
                || failure instanceof CicsTaskCommitException commitFailure
                && commitFailure.state() == CommitFailureState.NOT_COMMITTED;
        if (!safelyNotCommitted) {
            return;
        }
        try {
            boundary.abort(lease, failure, clock.instant());
        } catch (RuntimeException | Error abortFailure) {
            failure.addSuppressed(abortFailure);
        }
    }

    private void releaseBeforeBoundary(ConversationLease lease, Throwable failure) {
        try {
            ConversationMutationResult result = conversations.release(
                    lease, clock.instant());
            if (result != ConversationMutationResult.RELEASED) {
                failure.addSuppressed(new CicsTaskStateException(
                        "conversation lease release failed: " + result));
            }
        } catch (RuntimeException | Error releaseFailure) {
            failure.addSuppressed(releaseFailure);
        }
    }

    private record CompletionPlan(
            ConversationMutation mutation,
            Optional<ConversationEnvelope> next) {
    }
}
