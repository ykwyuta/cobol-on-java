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
    private final Optional<CicsOutcomeStorePort> outcomes;
    private final CicsSecurityPort security;

    public CicsTaskCoordinator(
            CicsTransactionRegistry transactions,
            ConversationStorePort conversations,
            CicsTaskBoundaryFactory boundaries,
            CicsTaskProgramPort programs,
            CicsTaskPolicy policy,
            ConversationIdFactory conversationIds,
            Clock clock) {
        this(transactions, conversations, boundaries, programs, policy, conversationIds, clock, Optional.empty(),
                CicsSecurityPort.derived());
    }

    /**
     * 冪等キーの再送に覚えた結果を返す coordinator (設計 77 §4.3、暫定判断 P-142)。
     *
     * <p>境界は {@link CicsTaskBoundary#commit(TaskCommit, Instant)} で結果を業務の UOW と一緒に確定できなければならない。
     */
    public CicsTaskCoordinator(
            CicsTransactionRegistry transactions,
            ConversationStorePort conversations,
            CicsTaskBoundaryFactory boundaries,
            CicsTaskProgramPort programs,
            CicsTaskPolicy policy,
            ConversationIdFactory conversationIds,
            Clock clock,
            CicsOutcomeStorePort outcomes) {
        this(transactions, conversations, boundaries, programs, policy, conversationIds, clock,
                Optional.of(outcomes), CicsSecurityPort.derived());
    }

    /**
     * 利用者の user ID と transaction の attach の権限を確かめる coordinator (設計 84、暫定判断 P-145)。
     *
     * <p>要求が user ID を持たなければ owner (principal) から {@link CicsSecurityPort#userIdOf} で決め、どの入口の task も
     * 起こす前に {@link CicsSecurityPort#mayAttach} を確かめる。
     */
    public CicsTaskCoordinator(
            CicsTransactionRegistry transactions,
            ConversationStorePort conversations,
            CicsTaskBoundaryFactory boundaries,
            CicsTaskProgramPort programs,
            CicsTaskPolicy policy,
            ConversationIdFactory conversationIds,
            Clock clock,
            CicsOutcomeStorePort outcomes,
            CicsSecurityPort security) {
        this(transactions, conversations, boundaries, programs, policy, conversationIds, clock,
                Optional.of(outcomes), security);
    }

    private CicsTaskCoordinator(
            CicsTransactionRegistry transactions,
            ConversationStorePort conversations,
            CicsTaskBoundaryFactory boundaries,
            CicsTaskProgramPort programs,
            CicsTaskPolicy policy,
            ConversationIdFactory conversationIds,
            Clock clock,
            Optional<CicsOutcomeStorePort> outcomes,
            CicsSecurityPort security) {
        this.security = Objects.requireNonNull(security, "security");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.boundaries = Objects.requireNonNull(boundaries, "boundaries");
        this.programs = Objects.requireNonNull(programs, "programs");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.conversationIds = Objects.requireNonNull(conversationIds, "conversationIds");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    }

    /**
     * task を動かす。
     *
     * <p>冪等キーの置き場があれば、先に冪等キーを予約する。同じ要求がもう commit していれば task を動かさず覚えた結果を返し、
     * 動いている最中か違う要求なら {@link IdempotencyConflictException}。commit しなかった task の予約は外す。
     * commit の状態が分からない失敗 (UNKNOWN) では予約を残し、期限まで再送を動かさない。
     */
    public CicsTaskReply launch(CicsTaskRequest request) {
        Objects.requireNonNull(request, "request");
        Instant startedAt = clock.instant();
        CicsTransactionDefinition definition = transactions.resolve(request.transactionId());
        definition.validate(request.payload());
        // 冪等キーを予約する前に確かめる。権限の無い要求は結果を覚えず、task も起こさない
        request = authorize(request, definition);
        if (outcomes.isEmpty()) {
            return run(request, definition, startedAt, false);
        }
        CicsOutcomeStorePort store = outcomes.orElseThrow();
        CicsOutcomeStorePort.Reservation reservation = store.reserve(request.owner(), request.idempotencyKey(),
                CicsRequestFingerprint.of(request), policy.leaseDuration(), startedAt);
        switch (reservation.status()) {
            case REPLAY -> {
                return reservation.reply().orElseThrow();
            }
            case IN_PROGRESS, MISMATCH -> throw new IdempotencyConflictException(reservation.status());
            case RESERVED -> {
            }
        }
        try {
            return run(request, definition, startedAt, true);
        } catch (RuntimeException | Error failure) {
            boolean unknown = failure instanceof CicsTaskCommitException commitFailure
                    && commitFailure.state() != CommitFailureState.NOT_COMMITTED;
            if (!unknown) {
                try {
                    store.release(request.owner(), request.idempotencyKey(), clock.instant());
                } catch (RuntimeException | Error releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
            }
            throw failure;
        }
    }

    /**
     * 要求の user ID を決めて attach の権限を確かめる。user ID を持つ要求 (START の USERID、ATI の USERID) はそれを使い、
     * 持たなければ owner (principal) から決める。
     */
    private CicsTaskRequest authorize(CicsTaskRequest request, CicsTransactionDefinition definition) {
        Optional<String> userId = request.userId().isPresent()
                ? request.userId() : security.userIdOf(request.owner());
        if (!security.mayAttach(userId, definition.transId())) {
            throw new TransactionNotAuthorizedException(definition.transId());
        }
        return userId.equals(request.userId()) ? request
                : new CicsTaskRequest(request.transactionId(), request.owner(), request.payload(),
                        request.conversation(), request.idempotencyKey(), request.terminalInput(),
                        request.terminalId(), userId, request.start());
    }

    private CicsTaskReply run(CicsTaskRequest request, CicsTransactionDefinition definition, Instant startedAt,
                              boolean recordOutcome) {
        CicsTaskId taskId = CicsTaskId.create();
        // 直前の画面は会話にある。claim してから task 文脈へ入れる
        Optional<ConversationLease> lease = claim(request, definition, startedAt);
        CicsTaskContext task = new CicsTaskContext(
                taskId, definition.transId(), request.owner(), startedAt)
                .withTerminal(request.terminalInput(),
                        lease.flatMap(claimed -> claimed.envelope().screen()))
                .withTerminalId(request.terminalId())
                .withUserId(request.userId())
                .withStart(request.start());
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
            Instant completedAt = clock.instant();
            CompletionPlan plan = planCompletion(request, taskId, lease, completion, completedAt);
            CicsTaskReply reply = new CicsTaskReply(taskId, definition.transId(), completion.payload(), plan.next,
                    completion.immediate(), completion.screen());
            Optional<TaskCommit.RecordedOutcome> outcome = recordOutcome
                    ? Optional.of(new TaskCommit.RecordedOutcome(request.owner(), request.idempotencyKey(), reply,
                            completedAt.plus(policy.conversationTtl())))
                    : Optional.empty();
            commitStarted = true;
            boundary.commit(new TaskCommit(plan.mutation, outcome), clock.instant());
            runAfterCommit(completion);
            return reply;
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

    private static final System.Logger LOG = System.getLogger(CicsTaskCoordinator.class.getName());

    /**
     * 暗黙の同期点のあとに行うこと (PROTECT の START、暫定判断 P-141)。
     *
     * <p>task はもう commit したので、ここでの失敗は task の結果を変えず記録だけする。
     */
    private static void runAfterCommit(TaskCompletion completion) {
        for (Runnable action : completion.afterCommit()) {
            try {
                action.run();
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "an action after the task commit failed", failure);
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
