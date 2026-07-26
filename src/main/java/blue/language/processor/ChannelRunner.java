package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Executes channel matching and handler invocation for a scope.
 *
 * <p>Applies checkpoint gating for external channels and feeds successful
 * matches into the registered handler processors.</p>
 */
final class ChannelRunner {

    private final DocumentProcessor owner;
    private final ProcessorEngine.Execution execution;
    private final DocumentProcessingRuntime runtime;
    private final CheckpointManager checkpointManager;
    private final Map<String, List<PendingCheckpoint>> pendingCheckpoints =
            new LinkedHashMap<>();

    ChannelRunner(DocumentProcessor owner,
                  ProcessorEngine.Execution execution,
                  DocumentProcessingRuntime runtime,
                  CheckpointManager checkpointManager) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.checkpointManager = Objects.requireNonNull(checkpointManager, "checkpointManager");
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
        if (execution.shouldStopScopeWork(scopePath)) {
            return ExternalClassification.skipped(
                    scopePath, channel.key());
        }
        runtime.chargeChannelMatchAttempt(scopePath, channel.key());
        ChannelContract contract = channel.contract();
        ProcessingMetricsSink metrics = owner.metricsSink();
        metrics.incrementChannelEvaluations();
        long channelMatchStart = System.nanoTime();
        boolean matches;
        FrozenNode frozenPayload;
        String recomputedCheckpointSubject;
        ChannelProcessor<ChannelContract> channelProcessor;
        try {
            ExternalDeliverySnapshot evidence =
                    execution.deliveryEvidence(
                            scopePath, channel.key());
            if (evidence != null) {
                EffectiveContractSnapshot snapshot =
                        bundle.effectiveContractSnapshot(
                                channel.key());
                if (snapshot == null) {
                    throw new IllegalStateException(
                            "External Channel effective snapshot is absent at "
                                    + scopePath + "/" + channel.key());
                }
                ExternalChannelFunctionEvaluation evaluation =
                        ExternalChannelFunctionEvaluation.evaluate(
                                owner.registry(),
                                owner.contractConverter(),
                                bundle,
                                snapshot,
                                event);
                matches = evaluation.accepts();
                frozenPayload = evaluation.payload();
                recomputedCheckpointSubject =
                        evaluation.checkpointSubjectBlueId();
                channelProcessor = registeredProcessor(contract);
            } else {
                /*
                 * Compatibility for the package-level runner API used without
                 * PROCESS evidence. Verified PROCESS delivery always takes the
                 * immutable-function branch above.
                 */
                ProcessorEngine.ChannelMatch legacy =
                        ProcessorEngine.evaluateChannel(
                                owner,
                                channel,
                                bundle,
                                scopePath,
                                event);
                matches = legacy.matches;
                Node payload = legacy.eventNode() != null
                        ? legacy.eventNode()
                        : event;
                frozenPayload = matches && payload != null
                        ? FrozenNode.fromResolvedNode(payload)
                        : null;
                recomputedCheckpointSubject = null;
                channelProcessor = legacy.processor;
            }
        } catch (RuntimeException ex) {
            execution.abortRuntimeFailure(scopePath,
                    bundle,
                    execution.fatalCategory(ex, ProcessorErrorCategory.InternalProcessorError),
                    execution.fatalReason(ex, "Channel execution failed"));
            return ExternalClassification.skipped(
                    scopePath, channel.key());
        } finally {
            metrics.addChannelMatchNanos(System.nanoTime() - channelMatchStart);
        }
        if (!matches) {
            return ExternalClassification.rejected(
                    scopePath, channel.key());
        }
        if (frozenPayload == null
                || channelProcessor == null) {
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    ProcessorErrorCategory.InternalProcessorError,
                    "External Channel immutable evaluation is incomplete");
            return ExternalClassification.skipped(
                    scopePath, channel.key());
        }
        execution.recordAcceptedDelivery(scopePath, channel.key());
        Node checkpointEvent = event;
        long checkpointStart = System.nanoTime();
        CheckpointManager.CheckpointRecord checkpoint;
        String eventSignature;
        try {
            long findStart = System.nanoTime();
            String checkpointDomain = execution.checkpointDomain(channel, scopePath);
            checkpoint = checkpointManager.findCheckpoint(
                    bundle, channel.key(), checkpointDomain);
            metrics.addCheckpointFindNanos(System.nanoTime() - findStart);
            long identityStart = System.nanoTime();
            eventSignature =
                    recomputedCheckpointSubject != null
                            ? recomputedCheckpointSubject
                            : execution.checkpointSubject(
                            scopePath, channel.key(), event);
            metrics.addCheckpointCurrentIdentityNanos(System.nanoTime() - identityStart);
        } catch (RuntimeException ex) {
            metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
            execution.abortRuntimeFailure(scopePath,
                    bundle,
                    execution.fatalCategory(ex, ProcessorErrorCategory.CheckpointError),
                    execution.fatalReason(ex, "Checkpoint error"));
            return ExternalClassification.skipped(
                    scopePath, channel.key());
        }
        boolean newer;
        long isNewerStart = System.nanoTime();
        try {
            checkpointManager.recordComparison(scopePath, checkpoint, eventSignature);
            ChannelCheckpointContext checkpointContext = new ChannelCheckpointContext(scopePath,
                    channel.key(),
                    checkpointEvent,
                    eventSignature,
                    checkpoint != null ? checkpoint.lastEventNode : null,
                    checkpoint != null ? checkpoint.lastEventSignature : null,
                    bundle.markers());
            newer = channelProcessor.isNewerEvent(
                    contract, checkpointContext);
        } finally {
            metrics.addCheckpointIsNewerNanos(System.nanoTime() - isNewerStart);
        }
        if (!newer) {
            execution.recordStaleDelivery();
            metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
            return ExternalClassification.stale(
                    scopePath, channel.key());
        }
        boolean duplicate;
        long duplicateStart = System.nanoTime();
        try {
            duplicate = checkpointManager.isDuplicate(checkpoint, eventSignature);
        } finally {
            metrics.addCheckpointDuplicateNanos(System.nanoTime() - duplicateStart);
        }
        if (duplicate) {
            execution.recordStaleDelivery();
            metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
            return ExternalClassification.stale(
                    scopePath, channel.key());
        }
        metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);

        return ExternalClassification.acceptedNew(
                scopePath,
                channel.key(),
                frozenPayload,
                checkpoint,
                eventSignature,
                checkpointEvent);
    }

    @SuppressWarnings("unchecked")
    private ChannelProcessor<ChannelContract> registeredProcessor(
            ChannelContract contract) {
        return (ChannelProcessor<ChannelContract>) owner.registry()
                .lookupChannel(contract)
                .orElse(null);
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
        String scopePath = classification.scopePath;
        if (execution.shouldStopScopeWork(scopePath)) {
            return;
        }
        ContractBundle executionBundle =
                execution.initializeAcceptedScope(scopePath);
        if (executionBundle == null) {
            return;
        }
        if (!runHandlers(scopePath, executionBundle,
                classification.channelKey,
                classification.payload.toNode())) {
            /*
             * A handler may successfully replace/cut off its own embedded
             * occurrence.  That ends later local work and suppresses the
             * checkpoint, but the accepted-new Root transition still
             * completed.  Deterministic failures remain noncommitting.
             */
            if (!execution.hasFailure()) {
                execution.recordCompletedDelivery();
            }
            return;
        }
        queueCheckpoint(scopePath, executionBundle,
                classification.checkpoint,
                classification.eventSignature,
                classification.checkpointEvent);
        execution.recordCompletedDelivery();
    }

    private void queueCheckpoint(String scopePath,
                                 ContractBundle bundle,
                                 CheckpointManager.CheckpointRecord checkpoint,
                                 String eventSignature,
                                 Node checkpointEvent) {
        String normalized = execution.normalizeScope(scopePath);
        pendingCheckpoints
                .computeIfAbsent(normalized, ignored -> new ArrayList<>())
                .add(new PendingCheckpoint(
                        bundle, checkpoint, eventSignature,
                        checkpointEvent != null ? checkpointEvent.clone() : null));
    }

    /**
     * Commits checkpoint state only after the caller has completed embedded
     * bridging and Triggered FIFO drain for the accepted delivery.
     */
    void persistPendingCheckpoints(String scopePath) {
        String normalized = execution.normalizeScope(scopePath);
        List<PendingCheckpoint> pending = pendingCheckpoints.remove(normalized);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        if (!execution.isScopeActive(normalized)) {
            ScopeRuntimeContext scope = runtime.existingScope(normalized);
            if (scope != null && scope.isCutOff()) {
                for (PendingCheckpoint checkpoint : pending) {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("effect", "checkpoint");
                    details.put("reason", "scope-cut-off");
                    details.put("label",
                            "checkpoint:" + checkpoint.record.channelKey);
                    runtime.recordTrace(
                            ProcessingTraceRecord.Kind.DISCARDED_EFFECT,
                            normalized,
                            checkpoint.record.channelKey,
                            null,
                            details,
                            checkpoint.event);
                }
            }
            return;
        }
        ProcessingMetricsSink metrics = owner.metricsSink();
        for (PendingCheckpoint checkpoint : pending) {
            long checkpointPersistStart = System.nanoTime();
            try {
                checkpointManager.persist(normalized,
                        checkpoint.bundle,
                        checkpoint.record,
                        checkpoint.eventSignature,
                        checkpoint.event);
            } catch (RuntimeException ex) {
                execution.abortRuntimeFailure(normalized,
                        checkpoint.bundle,
                        execution.fatalCategory(
                                ex, ProcessorErrorCategory.CheckpointError),
                        execution.fatalReason(ex, "Checkpoint error"));
                return;
            } finally {
                metrics.addCheckpointPersistNanos(
                        System.nanoTime() - checkpointPersistStart);
                metrics.addCheckpointUpdateNanos(
                        System.nanoTime() - checkpointPersistStart);
            }
        }
    }

    private static final class PendingCheckpoint {
        private final ContractBundle bundle;
        private final CheckpointManager.CheckpointRecord record;
        private final String eventSignature;
        private final Node event;

        private PendingCheckpoint(
                ContractBundle bundle,
                CheckpointManager.CheckpointRecord record,
                String eventSignature,
                Node event) {
            this.bundle = bundle;
            this.record = record;
            this.eventSignature = eventSignature;
            this.event = event;
        }
    }

    static final class ExternalClassification {
        private enum State {
            SKIPPED,
            REJECTED,
            STALE,
            ACCEPTED_NEW
        }

        private final State state;
        private final String scopePath;
        private final String channelKey;
        private final FrozenNode payload;
        private final CheckpointManager.CheckpointRecord checkpoint;
        private final String eventSignature;
        private final Node checkpointEvent;

        private ExternalClassification(
                State state,
                String scopePath,
                String channelKey,
                FrozenNode payload,
                CheckpointManager.CheckpointRecord checkpoint,
                String eventSignature,
                Node checkpointEvent) {
            this.state = Objects.requireNonNull(state, "state");
            this.scopePath = Objects.requireNonNull(
                    scopePath, "scopePath");
            this.channelKey = Objects.requireNonNull(
                    channelKey, "channelKey");
            this.payload = payload;
            this.checkpoint = checkpoint;
            this.eventSignature = eventSignature;
            this.checkpointEvent = checkpointEvent != null
                    ? checkpointEvent.clone() : null;
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
                    null);
        }

        static ExternalClassification acceptedNew(
                String scopePath,
                String channelKey,
                FrozenNode payload,
                CheckpointManager.CheckpointRecord checkpoint,
                String eventSignature,
                Node checkpointEvent) {
            return new ExternalClassification(
                    State.ACCEPTED_NEW,
                    scopePath,
                    channelKey,
                    payload,
                    checkpoint,
                    eventSignature,
                    checkpointEvent);
        }

        boolean acceptedNew() {
            return state == State.ACCEPTED_NEW;
        }

        String scopePath() {
            return scopePath;
        }

        String channelKey() {
            return channelKey;
        }
    }

    private String eventSignature(Node fallbackEvent) {
        return eventSignature(fallbackEvent, null);
    }

    private String eventSignature(Node fallbackEvent, String fallbackSignature) {
        return fallbackSignature != null ? fallbackSignature : checkpointManager.eventIdentity(fallbackEvent);
    }

    boolean runHandlers(String scopePath,
                        ContractBundle bundle,
                        String channelKey,
                        Node event) {
        return runHandlers(
                scopePath,
                bundle,
                channelKey,
                event,
                false);
    }

    boolean runHandlers(String scopePath,
                        ContractBundle bundle,
                        String channelKey,
                        Node event,
                        boolean allowTerminatingScope) {
        ProcessingMetricsSink metrics = owner.metricsSink();
        long discoveryStart = System.nanoTime();
        List<ContractBundle.HandlerBinding> handlers = bundle.handlersFor(channelKey);
        metrics.addHandlerDiscoveryNanos(System.nanoTime() - discoveryStart);
        if (handlers.isEmpty()) {
            return allowTerminatingScope
                    ? !execution.shouldStopScopeWork(scopePath)
                    : execution.isScopeActive(scopePath);
        }
        for (ContractBundle.HandlerBinding handler : handlers) {
            if (execution.shouldStopScopeWork(scopePath)
                    || (!allowTerminatingScope
                    && !execution.isScopeActive(scopePath))) {
                return false;
            }
            HandlerMatchContext matchContext = new HandlerMatchContext(scopePath,
                    handler.key(),
                    channelKey,
                    event,
                    bundle.markers(),
                    owner.matchingService());
            metrics.incrementHandlerMatchAttempts();
            runtime.chargeHandlerCandidateTested(scopePath, handler.key());
            long matchStart = System.nanoTime();
            boolean matches;
            try {
                matches = ProcessorEngine.matchesHandler(owner, handler.contract(), matchContext);
            } finally {
                metrics.addHandlerMatchNanos(System.nanoTime() - matchStart);
            }
            if (!matches) {
                continue;
            }
            ContractBundle.HandlerBinding executableHandler;
            try {
                recordSelectedExecutableBodyDemands(
                        scopePath,
                        handler);
                executableHandler =
                        owner.contractLoader()
                                .materializeSelectedExecutableBodies(
                                        handler,
                                        runtime
                                                ::materializeSelectedExecutableReference);
            } catch (RuntimeException ex) {
                ProcessorErrorCategory providerCategory =
                        ScopeIdentityErrorMapper.from(ex);
                if (providerCategory
                        == ProcessorErrorCategory.ProviderUnavailable
                        || providerCategory
                        == ProcessorErrorCategory.ProviderBlueIdMismatch) {
                    throw ex;
                }
                execution.abortRuntimeFailure(
                        scopePath,
                        bundle,
                        execution.fatalCategory(
                                ex,
                                ProcessorErrorCategory
                                        .HandlerExecutionError),
                        execution.fatalReason(
                                ex,
                                "Handler executable body materialization failed"));
                return false;
            }
            runtime.chargeHandlerOverhead(scopePath, handler.key());
            ProcessorExecutionContext context = execution.createContext(scopePath,
                    bundle,
                    event,
                    executableHandler.key(),
                    executableHandler.node(),
                    false);
            metrics.incrementHandlersExecuted();
            long executionStart = System.nanoTime();
            try (ProcessorExecutionContext ownedContext = context) {
                ProcessorEngine.executeHandler(
                        owner,
                        executableHandler.contract(),
                        ownedContext);
                ownedContext.applyBufferedEffects();
            } catch (GasLimitExceededException
                     | PortableLimitExceededException
                     | SubscriptionSurfaceInvalidException ex) {
                throw ex;
            } catch (RunTerminationException ex) {
                throw ex;
            } catch (ProcessorFatalException ex) {
                execution.abortRuntimeFailure(scopePath,
                        bundle,
                        ex.errorCategory(),
                        execution.fatalReason(ex, "Handler execution failed"));
                return false;
            } catch (RuntimeException ex) {
                execution.abortRuntimeFailure(scopePath,
                        bundle,
                        execution.fatalCategory(ex, ProcessorErrorCategory.HandlerExecutionError),
                        execution.fatalReason(ex, "Handler execution failed"));
                return false;
            } finally {
                metrics.addHandlerExecutionNanos(System.nanoTime() - executionStart);
            }
            if (execution.shouldStopScopeWork(scopePath)
                    || (!allowTerminatingScope
                    && !execution.isScopeActive(scopePath))) {
                return false;
            }
        }
        return allowTerminatingScope
                ? !execution.shouldStopScopeWork(scopePath)
                : execution.isScopeActive(scopePath);
    }

    private void recordSelectedExecutableBodyDemands(
            String scopePath,
            ContractBundle.HandlerBinding handler) {
        if (handler == null || handler.node() == null) {
            return;
        }
        for (String field : handler.executableBodyFields()) {
            List<String> path =
                    new ArrayList<>(
                            JsonPointer.split(scopePath));
            path.add("contracts");
            path.add(handler.key());
            path.add(field);
            runtime.recordSelectedExecutableBodyDemand(
                    handler.node().property(field),
                    scopePath,
                    handler.key(),
                    JsonPointer.toPointer(path));
        }
    }

    void cleanupInactiveCheckpoints(String scopePath, ContractBundle bundle) {
        Map<String, String> activeDomains = new LinkedHashMap<>();
        for (ContractBundle.ChannelBinding channel
                : bundle.channelsOfType(ChannelContract.class)) {
            if (blue.language.processor.util.ProcessorContractConstants
                    .isProcessorManagedChannel(channel.contract())) {
                continue;
            }
            activeDomains.put(
                    channel.key(),
                    execution.checkpointDomain(channel, scopePath));
        }
        checkpointManager.cleanupInactiveEntries(
                scopePath, bundle, activeDomains);
    }
}
