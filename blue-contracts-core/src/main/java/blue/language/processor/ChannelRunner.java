package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Executes channel matching and handler invocation for a scope.
 *
 * <p>Applies checkpoint gating for external channels and feeds successful
 * matches into the registered handler processors.</p>
 */
final class ChannelRunner {

    private final ProcessorInvocationServices owner;
    private final ProcessorInvocationState execution;
    private final DocumentProcessingRuntime runtime;
    private final ProcessingCheckpointTransaction checkpointTransaction;
    private final ExternalSourceEvaluator sourceEvaluator;
    private final ScopeHandlerDispatcher handlerDispatcher;
    private final ExternalDeliveryExecutor deliveryExecutor;
    private final LogicalDeliveryGrouper deliveryGrouper;
    private final Map<String, Map<PendingCheckpointKey, PendingCheckpoint>>
            pendingCheckpoints =
            new LinkedHashMap<>();
    private final Map<String, PendingCheckpointCleanup>
            pendingCheckpointCleanup =
            new LinkedHashMap<>();

    ChannelRunner(DocumentProcessor owner,
                  ProcessorInvocationState execution,
                  DocumentProcessingRuntime runtime,
                  ProcessingCheckpointTransaction checkpointTransaction) {
        this(ProcessorInvocationServices.configured(owner),
                execution,
                runtime,
                checkpointTransaction);
    }

    ChannelRunner(ProcessorInvocationServices owner,
                  ProcessorInvocationState execution,
                  DocumentProcessingRuntime runtime,
                  ProcessingCheckpointTransaction checkpointTransaction) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.checkpointTransaction = Objects.requireNonNull(
                checkpointTransaction, "checkpointTransaction");
        HandlerChannelSelector handlerSelector =
                new HandlerChannelSelector(execution);
        this.handlerDispatcher = new ScopeHandlerDispatcher(
                owner, execution, runtime);
        this.deliveryGrouper = new LogicalDeliveryGrouper();
        this.sourceEvaluator = new ExternalSourceEvaluator(
                owner,
                execution,
                runtime,
                checkpointTransaction,
                handlerSelector);
        this.deliveryExecutor = new ExternalDeliveryExecutor(
                execution,
                handlerDispatcher,
                handlerSelector,
                deliveryGrouper);
    }

    ChannelRunner(DocumentProcessor owner,
                  ProcessorInvocationState execution,
                  DocumentProcessingRuntime runtime,
                  CheckpointManager checkpointManager) {
        this(ProcessorInvocationServices.configured(owner),
                execution,
                runtime,
                checkpointManager);
    }

    ChannelRunner(ProcessorInvocationServices owner,
                  ProcessorInvocationState execution,
                  DocumentProcessingRuntime runtime,
                  CheckpointManager checkpointManager) {
        this(owner,
                execution,
                runtime,
                new ProcessingCheckpointTransaction(checkpointManager));
    }

    void runExternalChannel(String scopePath,
                            ContractBundle bundle,
                            ContractBundle.ChannelBinding channel,
                            Node event) {
        ExternalClassification classification =
                classifyExternalChannel(
                        scopePath, bundle, channel, event);
        runClassifiedExternalChannel(classification);
    }

    /**
     * Performs Contracts 1.0 Phase-B candidate classification.  This method
     * is intentionally read-only with respect to the Processing Document:
     * acceptance, payload and checkpoint newness are frozen before any
     * participating-scope preflight or initialization.
     */
    ExternalClassification classifyExternalChannel(
            String scopePath,
            ContractBundle bundle,
            ContractBundle.ChannelBinding channel,
            Node event) {
        return sourceEvaluator.evaluate(
                scopePath, bundle, channel, event);
    }

    /**
     * Executes one already-classified accepted-new occurrence after the
     * complete accepted-new participating closure has passed preflight.
     */
    void runClassifiedExternalChannel(
            ExternalClassification classification) {
        if (classification == null
                || !classification.acceptedNew()) {
            return;
        }
        ContractBundle checkpointBundle =
                runClassifiedExternalGroup(
                        Collections.singletonList(
                                classification));
        if (checkpointBundle != null) {
            queueClassifiedCheckpoints(
                    Collections.singletonList(
                            classification),
                    checkpointBundle);
        }
    }

    /**
     * Executes one logical accepted-new delivery group. All members retain
     * their raw-source checkpoint ownership, but handlers run once through the
     * group's immutable handler target.
     *
     * @return the exact execution bundle when handler work completed and the
     *         caller may stage checkpoints after internal FIFO drain
     */
    ContractBundle runClassifiedExternalGroup(
            List<ExternalClassification> classifications) {
        if (classifications == null
                || classifications.isEmpty()) {
            return null;
        }
        return deliveryExecutor.execute(classifications);
    }

    /**
     * Stages every raw-source checkpoint only after the group's handler and
     * synchronous internal work have completed successfully.
     */
    void queueClassifiedCheckpoints(
            List<ExternalClassification> classifications,
            ContractBundle executionBundle) {
        if (classifications == null
                || classifications.isEmpty()
                || executionBundle == null) {
            return;
        }
        ExternalClassification first =
                deliveryGrouper.requireCoherent(classifications);
        for (ExternalClassification classification
                : classifications) {
            queueCheckpoint(
                    first.scopePath(),
                    executionBundle,
                    classification.sourceChannelKey(),
                    classification.checkpoint(),
                    classification.eventSignature(),
                    classification.checkpointSubject());
        }
    }

    private void queueCheckpoint(String scopePath,
                                 ContractBundle bundle,
                                 String sourceChannelKey,
                                 CheckpointManager.CheckpointRecord checkpoint,
                                 String eventSignature,
                                 Node checkpointSubject) {
        if (checkpoint == null
                || !Objects.equals(
                sourceChannelKey,
                checkpoint.channelKey)) {
            throw new InvalidExecutionEvidenceException(
                    "Checkpoint ownership changed from raw source Channel "
                            + sourceChannelKey);
        }
        String normalized = execution.normalizeScope(scopePath);
        pendingCheckpoints
                .computeIfAbsent(
                        normalized,
                        ignored -> new TreeMap<>())
                .put(new PendingCheckpointKey(
                                checkpoint.channelKey,
                                checkpoint.checkpointDomainBlueId),
                        new PendingCheckpoint(
                        bundle, checkpoint, eventSignature,
                        checkpointSubject != null
                                ? checkpointSubject.clone()
                                : null));
    }

    /**
     * Commits checkpoint state only after the caller has completed embedded
     * bridging and Triggered FIFO drain for the accepted delivery.
     */
    void persistPendingCheckpoints(String scopePath) {
        String normalized = execution.normalizeScope(scopePath);
        Map<PendingCheckpointKey, PendingCheckpoint> pending =
                pendingCheckpoints.remove(normalized);
        PendingCheckpointCleanup cleanup =
                pendingCheckpointCleanup.remove(normalized);
        if ((pending == null || pending.isEmpty())
                && cleanup == null) {
            return;
        }
        if (!execution.isScopeActive(normalized)) {
            ScopeRuntimeContext scope = runtime.existingScope(normalized);
            if (scope != null
                    && scope.isCutOff()
                    && pending != null) {
                for (PendingCheckpoint checkpoint : pending.values()) {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put(
                            ProcessingTraceConstants.FIELD_EFFECT,
                            ProcessingTraceConstants.EFFECT_CHECKPOINT);
                    details.put(
                            ProcessingTraceConstants.FIELD_REASON,
                            ProcessingTraceConstants.REASON_SCOPE_CUT_OFF);
                    details.put(
                            ProcessingTraceConstants.FIELD_LABEL,
                            ProcessingTraceConstants
                                    .LABEL_PREFIX_CHECKPOINT
                                    + checkpoint.record.channelKey);
                    runtime.recordTrace(
                            ProcessingTraceRecord.Kind.DISCARDED_EFFECT,
                            normalized,
                            checkpoint.record.channelKey,
                            null,
                            details,
                            checkpoint.subject);
                }
            }
            return;
        }
        ContractBundle mutationBundle =
                cleanup != null
                        ? cleanup.bundle
                        : pending.values().iterator().next().bundle;
        ProcessingObserver metrics = owner.observer();
        long checkpointPersistStart = System.nanoTime();
        try {
            if (pending != null) {
                for (PendingCheckpoint checkpoint : pending.values()) {
                    checkpointTransaction.persist(normalized,
                            mutationBundle,
                            checkpoint.record,
                            checkpoint.eventSignature,
                            checkpoint.subject);
                }
            }
            if (cleanup != null) {
                checkpointTransaction.cleanupInactiveEntries(
                        normalized,
                        mutationBundle,
                        cleanup.activeDomains);
            }
        } catch (GasLimitExceededException
                 | PortableLimitExceededException
                 | SubscriptionSurfaceInvalidException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            execution.abortRuntimeFailure(normalized,
                    mutationBundle,
                    execution.fatalCategory(
                            ex, ProcessorErrorCategory.CheckpointPolicyError),
                    execution.fatalReason(ex, "Checkpoint error"));
        } finally {
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.CHECKPOINT_PERSIST_NANOS,
                    System.nanoTime() - checkpointPersistStart);
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.CHECKPOINT_UPDATE_NANOS,
                    System.nanoTime() - checkpointPersistStart);
        }
    }

    /**
     * Commits every scope's tentative checkpoint mutation in deterministic
     * scope order after the invocation has completed all logical deliveries
     * and internal FIFO work.
     */
    void persistAllPendingCheckpoints() {
        Set<String> scopes = new TreeSet<>(
                ExternalOrderKey::compareTextCodePoints);
        scopes.addAll(pendingCheckpoints.keySet());
        scopes.addAll(pendingCheckpointCleanup.keySet());
        for (String scopePath : scopes) {
            if (execution.hasFailure()) {
                pendingCheckpoints.clear();
                pendingCheckpointCleanup.clear();
                return;
            }
            persistPendingCheckpoints(scopePath);
        }
    }

    /**
     * Deferred checkpoint write captured during external-channel
     * classification and committed only after delivery succeeds.
     */
    private static final class PendingCheckpoint {
        private final ContractBundle bundle;
        private final CheckpointManager.CheckpointRecord record;
        private final String eventSignature;
        private final Node subject;

        private PendingCheckpoint(
                ContractBundle bundle,
                CheckpointManager.CheckpointRecord record,
                String eventSignature,
                Node subject) {
            this.bundle = bundle;
            this.record = record;
            this.eventSignature = eventSignature;
            this.subject =
                    subject != null ? subject.clone() : null;
        }
    }

    /**
     * Deterministic identity of one tentative raw-source checkpoint update.
     */
    private static final class PendingCheckpointKey
            implements Comparable<PendingCheckpointKey> {
        private final String rawChannelKey;
        private final String checkpointDomainBlueId;

        private PendingCheckpointKey(
                String rawChannelKey,
                String checkpointDomainBlueId) {
            this.rawChannelKey = Objects.requireNonNull(
                    rawChannelKey,
                    "rawChannelKey");
            this.checkpointDomainBlueId =
                    Objects.requireNonNull(
                            checkpointDomainBlueId,
                            "checkpointDomainBlueId");
        }

        @Override
        public int compareTo(PendingCheckpointKey other) {
            int rawKeyOrder =
                    ExternalOrderKey.compareTextCodePoints(
                            rawChannelKey,
                            other.rawChannelKey);
            return rawKeyOrder != 0
                    ? rawKeyOrder
                    : ExternalOrderKey.compareTextCodePoints(
                            checkpointDomainBlueId,
                            other.checkpointDomainBlueId);
        }
    }

    /**
     * Invocation-local cleanup request composed with pending source updates.
     */
    private static final class PendingCheckpointCleanup {
        private final ContractBundle bundle;
        private final Map<String, String> activeDomains;

        private PendingCheckpointCleanup(
                ContractBundle bundle,
                Map<String, String> activeDomains) {
            this.bundle = Objects.requireNonNull(
                    bundle,
                    "bundle");
            this.activeDomains = Collections.unmodifiableMap(
                    new LinkedHashMap<>(
                            activeDomains));
        }
    }

    /**
     * Complete immutable outcome of classifying one external channel.
     *
     * <p>The state distinguishes skipped, rejected, stale, and newly accepted
     * sources while retaining the exact routing, payload, and checkpoint
     * evidence needed by the later delivery phase.</p>
     */
    static final class ExternalClassification {
        private enum State {
            SKIPPED,
            REJECTED,
            STALE,
            ACCEPTED_NEW
        }

        private final State state;
        private final String scopePath;
        private final String sourceChannelKey;
        private final String handlerChannelKey;
        private final String logicalDeliveryKey;
        private final ChannelMemberSnapshot handlerChannel;
        private final FrozenNode payload;
        private final CheckpointManager.CheckpointRecord checkpoint;
        private final String eventSignature;
        private final Node checkpointSubject;

        private ExternalClassification(
                State state,
                String scopePath,
                String sourceChannelKey,
                String handlerChannelKey,
                String logicalDeliveryKey,
                ChannelMemberSnapshot handlerChannel,
                FrozenNode payload,
                CheckpointManager.CheckpointRecord checkpoint,
                String eventSignature,
                Node checkpointSubject) {
            this.state = Objects.requireNonNull(state, "state");
            this.scopePath = Objects.requireNonNull(
                    scopePath, "scopePath");
            this.sourceChannelKey = Objects.requireNonNull(
                    sourceChannelKey, "sourceChannelKey");
            this.handlerChannelKey = handlerChannelKey;
            this.logicalDeliveryKey = logicalDeliveryKey;
            this.handlerChannel = handlerChannel;
            this.payload = payload;
            this.checkpoint = checkpoint;
            this.eventSignature = eventSignature;
            this.checkpointSubject =
                    checkpointSubject != null
                            ? checkpointSubject.clone()
                            : null;
        }

        static ExternalClassification skipped(
                String scopePath, String channelKey) {
            return terminal(
                    State.SKIPPED, scopePath, channelKey);
        }

        static ExternalClassification rejected(
                String scopePath, String channelKey) {
            return terminal(
                    State.REJECTED, scopePath, channelKey);
        }

        static ExternalClassification stale(
                String scopePath, String channelKey) {
            return terminal(
                    State.STALE, scopePath, channelKey);
        }

        private static ExternalClassification terminal(
                State state,
                String scopePath,
                String channelKey) {
            return new ExternalClassification(
                    state,
                    scopePath,
                    channelKey,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        static ExternalClassification acceptedNew(
                String scopePath,
                String sourceChannelKey,
                String handlerChannelKey,
                String logicalDeliveryKey,
                ChannelMemberSnapshot handlerChannel,
                FrozenNode payload,
                CheckpointManager.CheckpointRecord checkpoint,
                String eventSignature,
                Node checkpointSubject) {
            return new ExternalClassification(
                    State.ACCEPTED_NEW,
                    scopePath,
                    sourceChannelKey,
                    Objects.requireNonNull(
                            handlerChannelKey,
                            "handlerChannelKey"),
                    Objects.requireNonNull(
                            logicalDeliveryKey,
                            "logicalDeliveryKey"),
                    handlerChannel,
                    payload,
                    checkpoint,
                    eventSignature,
                    checkpointSubject);
        }

        boolean acceptedNew() {
            return state == State.ACCEPTED_NEW;
        }

        String scopePath() {
            return scopePath;
        }

        String channelKey() {
            return sourceChannelKey;
        }

        String sourceChannelKey() {
            return sourceChannelKey;
        }

        String handlerChannelKey() {
            return handlerChannelKey;
        }

        String logicalDeliveryKey() {
            return logicalDeliveryKey;
        }

        ChannelMemberSnapshot handlerChannel() {
            return handlerChannel;
        }

        String payloadBlueId() {
            return payload != null ? payload.blueId() : null;
        }

        Node payloadNode() {
            return payload != null ? payload.toNode() : null;
        }

        CheckpointManager.CheckpointRecord checkpoint() {
            return checkpoint;
        }

        String eventSignature() {
            return eventSignature;
        }

        Node checkpointSubject() {
            return checkpointSubject != null
                    ? checkpointSubject.clone()
                    : null;
        }
    }

    boolean runHandlers(String scopePath,
                        ContractBundle bundle,
                        String channelKey,
                        Node event) {
        return handlerDispatcher.dispatch(
                scopePath, bundle, channelKey, event);
    }

    boolean runHandlers(String scopePath,
                        ContractBundle bundle,
                        String channelKey,
                        Node event,
                        boolean allowTerminatingScope) {
        return handlerDispatcher.dispatch(
                scopePath,
                bundle,
                channelKey,
                event,
                allowTerminatingScope);
    }

    boolean runHandlers(String scopePath,
                        ContractBundle bundle,
                        String channelKey,
                        Node event,
                        Node occurrenceEvent) {
        return handlerDispatcher.dispatch(
                scopePath,
                bundle,
                channelKey,
                event,
                occurrenceEvent);
    }

    void cleanupInactiveCheckpoints(String scopePath, ContractBundle bundle) {
        Map<String, String> activeDomains = new LinkedHashMap<>();
        for (ContractBundle.ChannelBinding channel
                : bundle.channelsOfType(ChannelContract.class)) {
            if (ProcessorManagedChannelTypes.contains(channel.contract())) {
                continue;
            }
            activeDomains.put(
                    channel.key(),
                    execution.checkpointDomain(channel, scopePath));
        }
        String normalized = execution.normalizeScope(scopePath);
        pendingCheckpointCleanup.put(
                normalized,
                new PendingCheckpointCleanup(
                        bundle,
                        activeDomains));
    }
}
