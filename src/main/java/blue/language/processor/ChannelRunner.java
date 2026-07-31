package blue.language.processor;

import blue.language.BlueLanguageErrorCategory;
import blue.language.BlueLanguageErrorClassifier;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;

import java.util.ArrayList;
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

    private final DocumentProcessor owner;
    private final ProcessorEngine.Execution execution;
    private final DocumentProcessingRuntime runtime;
    private final CheckpointManager checkpointManager;
    private final Map<String, Map<PendingCheckpointKey, PendingCheckpoint>>
            pendingCheckpoints =
            new LinkedHashMap<>();
    private final Map<String, PendingCheckpointCleanup>
            pendingCheckpointCleanup =
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
        FrozenNode frozenCheckpointSubject;
        String recomputedCheckpointSubject;
        String handlerChannelKey;
        String logicalDeliveryKey;
        ChannelMemberSnapshot handlerChannel;
        ChannelProcessor<ChannelContract> channelProcessor;
        try {
            ExternalDeliverySnapshot evidence =
                    execution.deliveryEvidence(
                            scopePath, channel.key());
            if (evidence == null) {
                throw new IllegalStateException(
                        "External Channel classification requires verified "
                                + "delivery evidence at " + scopePath + "/"
                                + channel.key());
            }
            EffectiveContractSnapshot snapshot =
                    bundle.effectiveContractSnapshot(
                            channel.key());
            if (snapshot == null) {
                throw new IllegalStateException(
                        "External Channel effective snapshot is absent at "
                                + scopePath + "/" + channel.key());
            }
            SubscriptionDelta.Entry activeInterval =
                    execution.activeSubscriptionInterval(
                            scopePath, channel.key());
            RuntimeWorkSession functionWork =
                    runtime.newRuntimeWorkSession(
                            execution.blue());
            if (functionWork
                    .hasSemanticOutputBoundary()) {
                functionWork.carryExactInput(
                        event,
                        checkpointManager.eventIdentity(
                                event));
            }
            ExternalChannelFunctionEvaluation evaluation =
                    ExternalChannelFunctionEvaluation.evaluate(
                            owner.registry(),
                            owner.contractConverter(),
                            runtime.externalChannelMatcherSessions(),
                            bundle,
                            snapshot,
                            event,
                            activeInterval != null
                                    && activeInterval.dependencies()
                                    .wholeSameScopeChannelCatalog()
                                    ? activeInterval.dependencies()
                                    .channelCatalogContractKeys()
                                    : null,
                            functionWork);
            matches = evaluation.accepts();
            frozenPayload = evaluation.payload();
            frozenCheckpointSubject =
                    evaluation.checkpointSubject();
            recomputedCheckpointSubject =
                    evaluation.checkpointSubjectBlueId();
            handlerChannelKey =
                    evaluation.handlerChannelKey();
            logicalDeliveryKey =
                    evaluation.logicalDeliveryKey();
            handlerChannel =
                    evaluation.handlerChannel();
            for (String lookup
                    : evaluation.channelLookupResults()) {
                Map<String, Object> details =
                        new LinkedHashMap<>();
                details.put(
                        ProcessingTraceConstants.FIELD_RESULT,
                        lookup);
                runtime.recordTrace(
                        ProcessingTraceRecord.Kind.CHANNEL_LOOKUP,
                        scopePath,
                        channel.key(),
                        null,
                        details,
                        null);
            }
            if (activeInterval != null
                    && !activeInterval.dependencies().equals(
                    evaluation.dependencies())) {
                throw new InvalidExecutionEvidenceException(
                        "External Channel declared dependency surface "
                                + "changed before Phase-B classification at "
                                + scopePath + "/" + channel.key());
            }
            if (evaluation.accepts()
                    && activeInterval != null
                    && handlerChannel == null) {
                throw new InvalidExecutionEvidenceException(
                        "External Channel handler target was not frozen by "
                                + "the retained Phase-B dependency surface at "
                                + scopePath + "/" + channel.key());
            }
            channelProcessor = registeredProcessor(contract);
        } catch (RuntimeException ex) {
            if (ex instanceof GasLimitExceededException
                    || ex instanceof PortableLimitExceededException
                    || ex instanceof SubscriptionSurfaceInvalidException
                    || ex instanceof ExecutionEvidenceUnavailableException
                    || ex instanceof InvalidExecutionEvidenceException
                    || BlueLanguageErrorClassifier.classify(ex)
                    == BlueLanguageErrorCategory.ProviderUnavailable) {
                throw ex;
            }
            execution.abortRuntimeFailure(scopePath,
                    bundle,
                    execution.fatalCategory(ex, ProcessorErrorCategory.RuntimeExecutionFailure),
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
                || frozenCheckpointSubject == null
                || handlerChannelKey == null
                || logicalDeliveryKey == null
                || channelProcessor == null) {
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    ProcessorErrorCategory.RuntimeExecutionFailure,
                    "External Channel immutable evaluation is incomplete");
            return ExternalClassification.skipped(
                    scopePath, channel.key());
        }
        execution.recordAcceptedDelivery(scopePath, channel.key());
        Node checkpointSubject =
                frozenCheckpointSubject.toNode();
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
            if (ex instanceof GasLimitExceededException
                    || ex instanceof PortableLimitExceededException
                    || ex instanceof ExecutionEvidenceUnavailableException) {
                throw ex;
            }
            execution.abortRuntimeFailure(scopePath,
                    bundle,
                    execution.fatalCategory(ex, ProcessorErrorCategory.CheckpointPolicyError),
                    execution.fatalReason(ex, "Checkpoint error"));
            return ExternalClassification.skipped(
                    scopePath, channel.key());
        }
        boolean newer;
        long isNewerStart = System.nanoTime();
        try {
            checkpointManager.recordComparison(scopePath, checkpoint, eventSignature);
            Node previousSubject =
                    checkpoint != null
                            ? checkpoint.lastEventNode
                            : null;
            String previousSubjectBlueId =
                    checkpoint != null
                            ? checkpoint.lastEventSignature
                            : null;
            if (previousSubjectBlueId == null
                    && previousSubject != null) {
                previousSubjectBlueId =
                        previousSubject.getBlueId();
            }
            ChannelCheckpointContext checkpointContext =
                    checkpointContext(
                            scopePath,
                            channel.key(),
                            event,
                            eventSignature,
                            checkpointSubject,
                            previousSubject,
                            previousSubjectBlueId,
                            bundle,
                            runtime.newRuntimeWorkSession(
                                    execution.blue()));
            RuntimeWorkSession checkpointWork =
                    checkpointContext.runtimeWorkSession();
            try {
                newer = channelProcessor.isNewerEvent(
                        contract, checkpointContext);
                checkpointWork.complete();
            } catch (ExecutionEvidenceUnavailableException unavailable) {
                checkpointWork.suspend();
                throw unavailable;
            } catch (RuntimeException | Error failure) {
                checkpointWork.failDeterministically();
                throw failure;
            } finally {
                checkpointWork.close();
            }
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
                handlerChannelKey,
                logicalDeliveryKey,
                handlerChannel,
                frozenPayload,
                checkpoint,
                eventSignature,
                checkpointSubject);
    }

    private ChannelCheckpointContext checkpointContext(
            String scopePath,
            String channelKey,
            Node event,
            String eventSignature,
            Node currentSubject,
            Node previousSubject,
            String previousSubjectBlueId,
            ContractBundle bundle,
            RuntimeWorkSession runtimeWorkSession) {
        if (previousSubject == null
                || !previousSubject.isReferenceOnly()) {
            return ChannelCheckpointContext.withRuntimeWorkSession(
                    scopePath,
                    channelKey,
                    event,
                    eventSignature,
                    currentSubject,
                    previousSubject,
                    previousSubjectBlueId,
                    bundle.markers(),
                    null,
                    runtimeWorkSession);
        }
        return ChannelCheckpointContext.withRuntimeWorkSession(
                scopePath,
                channelKey,
                event,
                eventSignature,
                currentSubject,
                null,
                previousSubjectBlueId,
                bundle.markers(),
                runtime.checkpointSubjectMaterializer(
                        previousSubject),
                runtimeWorkSession);
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
        ExternalClassification first =
                classifications.get(0);
        requireCoherentGroup(classifications, first);
        String scopePath = first.scopePath;
        if (execution.shouldStopScopeWork(scopePath)) {
            return null;
        }
        ContractBundle executionBundle =
                execution.initializeAcceptedScope(scopePath);
        if (executionBundle == null) {
            /*
             * Initialization may successfully replace or terminate an
             * ancestor/target occurrence before the external Channel's local
             * handlers begin. The admitted accepted-new transition still
             * owns those lifecycle effects; only deterministic failures roll
             * them back.
             */
            if (!execution.hasFailure()) {
                execution.recordCompletedDelivery();
            }
            return null;
        }
        requireSameScopeHandlerTarget(
                scopePath,
                executionBundle,
                first.handlerChannelKey);
        if (!runHandlers(scopePath, executionBundle,
                first.handlerChannelKey,
                first.payload.toNode())) {
            /*
             * A handler may successfully replace/cut off its own embedded
             * occurrence.  That ends later local work and suppresses the
             * checkpoint, but the accepted-new Root transition still
             * completed.  Deterministic failures remain noncommitting.
             */
            if (!execution.hasFailure()) {
                execution.recordCompletedDelivery();
            }
            return null;
        }
        execution.recordCompletedDelivery();
        return executionBundle;
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
                classifications.get(0);
        requireCoherentGroup(classifications, first);
        for (ExternalClassification classification
                : classifications) {
            queueCheckpoint(
                    first.scopePath,
                    executionBundle,
                    classification.sourceChannelKey,
                    classification.checkpoint,
                    classification.eventSignature,
                    classification.checkpointSubject);
        }
    }

    private void requireCoherentGroup(
            List<ExternalClassification> classifications,
            ExternalClassification first) {
        if (first == null || !first.acceptedNew()) {
            throw new IllegalArgumentException(
                    "Logical delivery group requires accepted-new "
                            + "classifications");
        }
        for (ExternalClassification classification
                : classifications) {
            if (classification == null
                    || !classification.acceptedNew()
                    || !first.scopePath.equals(
                    classification.scopePath)
                    || !first.logicalDeliveryKey.equals(
                    classification.logicalDeliveryKey)
                    || !first.handlerChannelKey.equals(
                    classification.handlerChannelKey)
                    || !first.payload.blueId().equals(
                    classification.payload.blueId())) {
                throw new IllegalArgumentException(
                        "Logical delivery group is inconsistent at "
                                + first.scopePath + "/"
                                + first.logicalDeliveryKey);
            }
        }
    }

    private void requireSameScopeHandlerTarget(
            String scopePath,
            ContractBundle bundle,
            String handlerChannelKey) {
        if (bundle == null
                || bundle.channelBinding(
                handlerChannelKey) == null) {
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    ProcessorErrorCategory.RuntimeExecutionFailure,
                    "External Channel handler target is not an existing "
                            + "same-scope Channel at "
                            + scopePath + "/"
                            + handlerChannelKey);
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
        ProcessingMetricsSink metrics = owner.metricsSink();
        long checkpointPersistStart = System.nanoTime();
        try {
            if (pending != null) {
                for (PendingCheckpoint checkpoint : pending.values()) {
                    checkpointManager.persist(normalized,
                            mutationBundle,
                            checkpoint.record,
                            checkpoint.eventSignature,
                            checkpoint.subject);
                }
            }
            if (cleanup != null) {
                checkpointManager.cleanupInactiveEntries(
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
            metrics.addCheckpointPersistNanos(
                    System.nanoTime() - checkpointPersistStart);
            metrics.addCheckpointUpdateNanos(
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
                event,
                false);
    }

    boolean runHandlers(String scopePath,
                        ContractBundle bundle,
                        String channelKey,
                        Node event,
                        boolean allowTerminatingScope) {
        return runHandlers(
                scopePath,
                bundle,
                channelKey,
                event,
                event,
                allowTerminatingScope);
    }

    boolean runHandlers(String scopePath,
                        ContractBundle bundle,
                        String channelKey,
                        Node event,
                        Node occurrenceEvent) {
        return runHandlers(
                scopePath,
                bundle,
                channelKey,
                event,
                occurrenceEvent,
                false);
    }

    private boolean runHandlers(String scopePath,
                        ContractBundle bundle,
                        String channelKey,
                        Node event,
                        Node occurrenceEvent,
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
            RuntimeWorkSession matchWork =
                    runtime.newRuntimeWorkSession(
                            execution.blue());
            ExternalChannelFunctionEvaluation.MatcherSession
                    matcherSession =
                    runtime.externalChannelMatcherSessions()
                            .open();
            HandlerMatchContext matchContext = new HandlerMatchContext(scopePath,
                    handler.key(),
                    channelKey,
                    event,
                    occurrenceEvent,
                    bundle.markers(),
                    owner.matchingService(),
                    matchWork,
                    matcherSession);
            metrics.incrementHandlerMatchAttempts();
            runtime.chargeHandlerCandidateTested(scopePath, handler.key());
            long matchStart = System.nanoTime();
            boolean matches;
            try {
                matches = ProcessorEngine.matchesHandler(owner, handler.contract(), matchContext);
                matchWork.complete();
            } catch (ExecutionEvidenceUnavailableException unavailable) {
                matchWork.suspend();
                throw unavailable;
            } catch (RuntimeException | Error failure) {
                matchWork.failDeterministically();
                throw failure;
            } finally {
                matcherSession.close();
                matchWork.close();
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
                if (ex instanceof GasLimitExceededException
                        || ex instanceof PortableLimitExceededException
                        || ex instanceof ExecutionEvidenceUnavailableException
                        || ex instanceof InvalidExecutionEvidenceException
                        || ScopeIdentityErrorMapper
                        .isProviderIdentityFailure(ex)) {
                    throw ex;
                }
                execution.abortRuntimeFailure(
                        scopePath,
                        bundle,
                        execution.fatalCategory(
                                ex,
                                ProcessorErrorCategory
                                        .RuntimeExecutionFailure),
                        execution.fatalReason(
                                ex,
                                "Handler executable body materialization failed"));
                return false;
            }
            runtime.chargeHandlerOverhead(scopePath, handler.key());
            ProcessorExecutionContext context = execution.createContext(scopePath,
                    bundle,
                    event,
                    occurrenceEvent,
                    executableHandler.key(),
                    executableHandler.node(),
                    false);
            context.bindSelectedExecutableBodies(
                    executableHandler.executableBodyFields(),
                    selectedExecutableBodyBlueIds(
                            handler));
            metrics.incrementHandlersExecuted();
            long executionStart = System.nanoTime();
            try (ProcessorExecutionContext ownedContext = context) {
                try {
                    Map<String, Object> details =
                            new LinkedHashMap<>();
                    details.put(
                            ProcessingTraceConstants.FIELD_CHANNEL_KEY,
                            channelKey);
                    runtime.recordTrace(
                            ProcessingTraceRecord.Kind.HANDLER_EXECUTION,
                            scopePath,
                            executableHandler.key(),
                            null,
                            details,
                            event);
                    ProcessorEngine.executeHandler(
                            owner,
                            executableHandler.contract(),
                            ownedContext);
                    ownedContext.applyBufferedEffects();
                } catch (ExecutionEvidenceUnavailableException unavailable) {
                    /*
                     * This attempt did not establish portable execution work.
                     * Discard staged child ledgers before try-with-resources
                     * closes the context.
                     */
                    ownedContext.suspendRuntimeWork();
                    throw unavailable;
                }
            } catch (GasLimitExceededException
                     | PortableLimitExceededException
                     | SubscriptionSurfaceInvalidException
                     | InvalidExecutionEvidenceException ex) {
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
                        execution.fatalCategory(ex, ProcessorErrorCategory.RuntimeExecutionFailure),
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
            path.add(ProcessorContractConstants.KEY_CONTRACTS);
            path.add(handler.key());
            path.add(field);
            runtime.recordSelectedExecutableBodyDemand(
                    handler.node().property(field),
                    scopePath,
                    handler.key(),
                    JsonPointer.toPointer(path));
        }
    }

    private Map<String, String>
    selectedExecutableBodyBlueIds(
            ContractBundle.HandlerBinding binding) {
        Map<String, String> identities =
                new LinkedHashMap<>();
        FrozenNode contract =
                binding != null ? binding.node() : null;
        Map<String, FrozenNode> properties =
                contract != null
                        ? contract.getProperties()
                        : null;
        if (properties == null) {
            return identities;
        }
        for (String field :
                binding.executableBodyFields()) {
            FrozenNode body =
                    properties.get(field);
            if (body != null) {
                identities.put(
                        field,
                        body.isReferenceOnly()
                                ? body.getReferenceBlueId()
                                : body.blueId());
            }
        }
        return identities;
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
        String normalized = execution.normalizeScope(scopePath);
        pendingCheckpointCleanup.put(
                normalized,
                new PendingCheckpointCleanup(
                        bundle,
                        activeDomains));
    }
}
