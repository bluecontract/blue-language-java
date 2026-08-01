package blue.language.processor;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.snapshot.FrozenNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Performs the read-only acceptance and checkpoint-newness evaluation for one
 * feeder-admitted raw source Channel.
 */
final class ExternalSourceEvaluator {

    private final DocumentProcessor owner;
    private final ProcessorInvocationState execution;
    private final DocumentProcessingRuntime runtime;
    private final ProcessingCheckpointTransaction checkpointTransaction;
    private final HandlerChannelSelector handlerSelector;

    ExternalSourceEvaluator(
            DocumentProcessor owner,
            ProcessorInvocationState execution,
            DocumentProcessingRuntime runtime,
            ProcessingCheckpointTransaction checkpointTransaction,
            HandlerChannelSelector handlerSelector) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.checkpointTransaction = Objects.requireNonNull(
                checkpointTransaction, "checkpointTransaction");
        this.handlerSelector = Objects.requireNonNull(
                handlerSelector, "handlerSelector");
    }

    ChannelRunner.ExternalClassification evaluate(
            String scopePath,
            ContractBundle bundle,
            ContractBundle.ChannelBinding channel,
            Node event) {
        if (execution.shouldStopScopeWork(scopePath)) {
            return ChannelRunner.ExternalClassification.skipped(
                    scopePath, channel.key());
        }
        runtime.chargeChannelMatchAttempt(scopePath, channel.key());
        ChannelContract contract = channel.contract();
        ProcessingObserver metrics = owner.observer();
        ProcessingObservations.record(
                metrics, ProcessingMetricId.CHANNEL_EVALUATIONS, 1L);
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
            ExternalDeliverySnapshot evidence = execution.deliveryEvidence(
                    scopePath, channel.key());
            if (evidence == null) {
                throw new IllegalStateException(
                        "External Channel classification requires verified "
                                + "delivery evidence at " + scopePath + "/"
                                + channel.key());
            }
            EffectiveContractSnapshot snapshot =
                    bundle.effectiveContractSnapshot(channel.key());
            if (snapshot == null) {
                throw new IllegalStateException(
                        "External Channel effective snapshot is absent at "
                                + scopePath + "/" + channel.key());
            }
            SubscriptionDelta.Entry activeInterval =
                    execution.activeSubscriptionInterval(
                            scopePath, channel.key());
            RuntimeWorkSession functionWork = runtime.newRuntimeWorkSession(
                    execution.blue());
            if (functionWork.hasSemanticOutputBoundary()) {
                functionWork.carryExactInput(
                        event,
                        checkpointTransaction.eventIdentity(event));
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
            frozenCheckpointSubject = evaluation.checkpointSubject();
            recomputedCheckpointSubject =
                    evaluation.checkpointSubjectBlueId();
            handlerChannelKey = evaluation.handlerChannelKey();
            logicalDeliveryKey = evaluation.logicalDeliveryKey();
            handlerChannel = handlerSelector.frozenTarget(
                    evaluation,
                    activeInterval,
                    scopePath,
                    channel.key());
            recordChannelLookups(
                    scopePath, channel.key(), evaluation);
            if (activeInterval != null
                    && !activeInterval.dependencies().equals(
                    evaluation.dependencies())) {
                throw new InvalidExecutionEvidenceException(
                        "External Channel declared dependency surface "
                                + "changed before Phase-B classification at "
                                + scopePath + "/" + channel.key());
            }
            channelProcessor = registeredProcessor(contract);
        } catch (RuntimeException exception) {
            if (isPortableFailure(exception)) {
                throw exception;
            }
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    execution.fatalCategory(
                            exception,
                            ProcessorErrorCategory.RuntimeExecutionFailure),
                    execution.fatalReason(
                            exception, "Channel execution failed"));
            return ChannelRunner.ExternalClassification.skipped(
                    scopePath, channel.key());
        } finally {
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.CHANNEL_MATCH_NANOS,
                    System.nanoTime() - channelMatchStart);
        }
        if (!matches) {
            return ChannelRunner.ExternalClassification.rejected(
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
            return ChannelRunner.ExternalClassification.skipped(
                    scopePath, channel.key());
        }
        execution.recordAcceptedDelivery(scopePath, channel.key());
        Node checkpointSubject = frozenCheckpointSubject.toNode();
        CheckpointEvaluation checkpoint = evaluateCheckpoint(
                scopePath,
                bundle,
                channel,
                event,
                checkpointSubject,
                recomputedCheckpointSubject,
                channelProcessor,
                contract,
                metrics);
        if (checkpoint == null) {
            return ChannelRunner.ExternalClassification.skipped(
                    scopePath, channel.key());
        }
        if (!checkpoint.newer) {
            execution.recordStaleDelivery();
            return ChannelRunner.ExternalClassification.stale(
                    scopePath, channel.key());
        }
        return ChannelRunner.ExternalClassification.acceptedNew(
                scopePath,
                channel.key(),
                handlerChannelKey,
                logicalDeliveryKey,
                handlerChannel,
                frozenPayload,
                checkpoint.record,
                checkpoint.eventSignature,
                checkpointSubject);
    }

    private CheckpointEvaluation evaluateCheckpoint(
            String scopePath,
            ContractBundle bundle,
            ContractBundle.ChannelBinding channel,
            Node event,
            Node checkpointSubject,
            String recomputedCheckpointSubject,
            ChannelProcessor<ChannelContract> channelProcessor,
            ChannelContract contract,
            ProcessingObserver metrics) {
        long checkpointStart = System.nanoTime();
        CheckpointManager.CheckpointRecord checkpoint;
        String eventSignature;
        try {
            long findStart = System.nanoTime();
            String checkpointDomain = execution.checkpointDomain(
                    channel, scopePath);
            checkpoint = checkpointTransaction.find(
                    bundle, channel.key(), checkpointDomain);
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.CHECKPOINT_FIND_NANOS,
                    System.nanoTime() - findStart);
            long identityStart = System.nanoTime();
            eventSignature = recomputedCheckpointSubject != null
                    ? recomputedCheckpointSubject
                    : execution.checkpointSubject(
                    scopePath, channel.key(), event);
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.CHECKPOINT_CURRENT_IDENTITY_NANOS,
                    System.nanoTime() - identityStart);
        } catch (RuntimeException exception) {
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.CHECKPOINT_UPDATE_NANOS,
                    System.nanoTime() - checkpointStart);
            if (exception instanceof GasLimitExceededException
                    || exception instanceof PortableLimitExceededException
                    || exception
                    instanceof ExecutionEvidenceUnavailableException) {
                throw exception;
            }
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    execution.fatalCategory(
                            exception,
                            ProcessorErrorCategory.CheckpointPolicyError),
                    execution.fatalReason(exception, "Checkpoint error"));
            return null;
        }
        boolean newer;
        long isNewerStart = System.nanoTime();
        try {
            checkpointTransaction.recordComparison(
                    scopePath, checkpoint, eventSignature);
            Node previousSubject = checkpoint != null
                    ? checkpoint.lastEventNode : null;
            String previousSubjectBlueId = checkpoint != null
                    ? checkpoint.lastEventSignature : null;
            if (previousSubjectBlueId == null && previousSubject != null) {
                previousSubjectBlueId = previousSubject.getBlueId();
            }
            ChannelCheckpointContext context = checkpointContext(
                    scopePath,
                    channel.key(),
                    event,
                    eventSignature,
                    checkpointSubject,
                    previousSubject,
                    previousSubjectBlueId,
                    bundle,
                    runtime.newRuntimeWorkSession(execution.blue()));
            RuntimeWorkSession work = context.runtimeWorkSession();
            try {
                newer = channelProcessor.isNewerEvent(contract, context);
                work.complete();
            } catch (ExecutionEvidenceUnavailableException unavailable) {
                work.suspend();
                throw unavailable;
            } catch (RuntimeException | Error failure) {
                work.failDeterministically();
                throw failure;
            } finally {
                work.close();
            }
        } finally {
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.CHECKPOINT_IS_NEWER_NANOS,
                    System.nanoTime() - isNewerStart);
        }
        if (!newer) {
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.CHECKPOINT_UPDATE_NANOS,
                    System.nanoTime() - checkpointStart);
            return new CheckpointEvaluation(
                    checkpoint, eventSignature, false);
        }
        boolean duplicate;
        long duplicateStart = System.nanoTime();
        try {
            duplicate = checkpointTransaction.isDuplicate(
                    checkpoint, eventSignature);
        } finally {
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.CHECKPOINT_DUPLICATE_NANOS,
                    System.nanoTime() - duplicateStart);
        }
        ProcessingObservations.record(
                metrics,
                ProcessingMetricId.CHECKPOINT_UPDATE_NANOS,
                System.nanoTime() - checkpointStart);
        return new CheckpointEvaluation(
                checkpoint, eventSignature, !duplicate);
    }

    private void recordChannelLookups(
            String scopePath,
            String channelKey,
            ExternalChannelFunctionEvaluation evaluation) {
        for (String lookup : evaluation.channelLookupResults()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put(ProcessingTraceConstants.FIELD_RESULT, lookup);
            runtime.recordTrace(
                    ProcessingTraceRecord.Kind.CHANNEL_LOOKUP,
                    scopePath,
                    channelKey,
                    null,
                    details,
                    null);
        }
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
        if (previousSubject == null || !previousSubject.isReferenceOnly()) {
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
                runtime.checkpointSubjectMaterializer(previousSubject),
                runtimeWorkSession);
    }

    private boolean isPortableFailure(RuntimeException exception) {
        return exception instanceof GasLimitExceededException
                || exception instanceof PortableLimitExceededException
                || exception instanceof SubscriptionSurfaceInvalidException
                || exception instanceof ExecutionEvidenceUnavailableException
                || exception instanceof InvalidExecutionEvidenceException
                || BlueLanguageErrorClassifier.classify(exception)
                == BlueLanguageErrorCategory.ProviderUnavailable;
    }

    @SuppressWarnings("unchecked")
    private ChannelProcessor<ChannelContract> registeredProcessor(
            ChannelContract contract) {
        return (ChannelProcessor<ChannelContract>) owner.registry()
                .lookupChannel(contract)
                .orElse(null);
    }

    private static final class CheckpointEvaluation {
        private final CheckpointManager.CheckpointRecord record;
        private final String eventSignature;
        private final boolean newer;

        private CheckpointEvaluation(
                CheckpointManager.CheckpointRecord record,
                String eventSignature,
                boolean newer) {
            this.record = record;
            this.eventSignature = eventSignature;
            this.newer = newer;
        }
    }
}
