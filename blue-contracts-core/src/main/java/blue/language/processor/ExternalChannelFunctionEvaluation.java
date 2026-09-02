package blue.language.processor;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.identity.BlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.matching.FrozenTypeMatcher;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Run-local result of the registered immutable External Channel functions.
 *
 * <p>Every evaluation is repeated from a fresh conversion of the frozen
 * effective contract. This makes function nondeterminism observable without
 * trusting either feeder-derived payload data or mutable converted contract
 * instances.</p>
 */
final class ExternalChannelFunctionEvaluation {

    /**
     * Pass-local frozen matching boundary backed only by captured verified
     * processing-snapshot evidence.
     */
    interface MatcherSession {

        /** Fails when the owning processing-snapshot session is no longer active. */
        void requireActive();

        /** Returns whether the candidate matches the supplied frozen pattern. */
        boolean matches(
                FrozenNode candidate,
                FrozenNode pattern);

        /** Returns whether the candidate type is equal to or below the base type. */
        boolean isAssignableToType(
                String candidateTypeBlueId,
                String baseTypeBlueId);

        /** Resolves one exact reference through the captured verified boundary. */
        FrozenNode materializeExactReference(
                FrozenNode reference);

        /** Releases all pass-local matcher state. */
        void close();
    }

    /** Opens an independent matcher session for one deterministic evaluation pass. */
    @FunctionalInterface
    interface MatcherSessionFactory {

        /** @return a fresh active matcher session */
        MatcherSession open();
    }

    private final List<String> channelKeys;
    private final List<String> eventKeys;
    private final boolean preselects;
    private final boolean accepts;
    private final String checkpointDomainBlueId;
    private final FrozenNode payload;
    private final String payloadBlueId;
    private final FrozenNode checkpointSubject;
    private final String checkpointSubjectBlueId;
    private final String handlerChannelKey;
    private final String logicalDeliveryKey;
    private final ChannelMemberSnapshot handlerChannel;
    private final ExternalChannelDependencySnapshot dependencies;
    private final String runtimeDiscriminator;
    private final List<String> channelLookupResults;
    private final List<ExactBlueValue> carriedExactValues;

    private ExternalChannelFunctionEvaluation(
            List<String> channelKeys,
            List<String> eventKeys,
            boolean preselects,
            boolean accepts,
            String checkpointDomainBlueId,
            FrozenNode payload,
            String payloadBlueId,
            FrozenNode checkpointSubject,
            String checkpointSubjectBlueId,
            String handlerChannelKey,
            String logicalDeliveryKey,
            ChannelMemberSnapshot handlerChannel,
            ExternalChannelDependencySnapshot dependencies,
            String runtimeDiscriminator,
            List<String> channelLookupResults,
            List<ExactBlueValue> carriedExactValues) {
        this.channelKeys = channelKeys;
        this.eventKeys = eventKeys;
        this.preselects = preselects;
        this.accepts = accepts;
        this.checkpointDomainBlueId = checkpointDomainBlueId;
        this.payload = payload;
        this.payloadBlueId = payloadBlueId;
        this.checkpointSubject = checkpointSubject;
        this.checkpointSubjectBlueId = checkpointSubjectBlueId;
        this.handlerChannelKey = handlerChannelKey;
        this.logicalDeliveryKey = logicalDeliveryKey;
        this.handlerChannel = handlerChannel;
        this.dependencies = dependencies;
        this.runtimeDiscriminator = runtimeDiscriminator;
        this.channelLookupResults =
                Collections.unmodifiableList(
                        Objects.requireNonNull(
                                channelLookupResults,
                                "channelLookupResults"));
        this.carriedExactValues =
                Collections.unmodifiableList(
                        new ArrayList<>(Objects.requireNonNull(
                                carriedExactValues,
                                "carriedExactValues")));
    }

    static ExternalChannelFunctionEvaluation evaluate(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            MatcherSessionFactory matcherSessions,
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot,
            Node exactEvent,
            List<String> effectiveContractKeys,
            RuntimeWorkSession runtimeWorkSession) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(converter, "converter");
        Objects.requireNonNull(
                matcherSessions,
                "matcherSessions");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(exactEvent, "exactEvent");
        RuntimeWorkSession authoritative =
                Objects.requireNonNull(
                        runtimeWorkSession,
                        "runtimeWorkSession");
        RuntimeWorkSession comparison = null;
        ExternalChannelFunctionEvaluation result = null;
        Throwable failure = null;
        boolean evidenceUnavailable = false;
        try {
            comparison = authoritative.diagnosticTwin();
            ExternalChannelFunctionEvaluation first = evaluateOnce(
                    registry,
                    converter,
                    matcherSessions,
                    bundle,
                    snapshot,
                    exactEvent,
                    effectiveContractKeys,
                    authoritative);
            ExternalChannelFunctionEvaluation second = evaluateOnce(
                    registry,
                    converter,
                    matcherSessions,
                    bundle,
                    snapshot,
                    exactEvent,
                    effectiveContractKeys,
                    comparison);
            if (!first.sameResult(second)
                    || !sameRuntimeTrace(
                            authoritative.stagedTrace(),
                            comparison.stagedTrace())) {
                throw new IllegalStateException(
                        "External Channel functions are not deterministic at "
                                + snapshot.scopePath() + "/" + snapshot.key());
            }
            List<ExactBlueValue> exactValues =
                    authoritative.exactValuesSnapshot();
            authoritative.complete();
            comparison.suspend();
            result = first.withCarriedExactValues(exactValues);
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            failure = unavailable;
            evidenceUnavailable = true;
        } catch (RuntimeException | Error caught) {
            failure = caught;
        } finally {
            if (failure != null) {
                failure = evidenceUnavailable
                        ? RuntimeWorkSession.suspendIfOpenPreserving(
                                authoritative, failure)
                        : RuntimeWorkSession.failIfOpenPreserving(
                                authoritative, failure);
                failure = RuntimeWorkSession.suspendIfOpenPreserving(
                        comparison, failure);
            }
            failure = RuntimeWorkSession.closePreserving(comparison, failure);
        }
        RuntimeWorkSession.rethrow(failure);
        return Objects.requireNonNull(result, "functionEvaluation");
    }

    /** Evaluates one exact event and owns the supplied work-session lifecycle. */
    static ExternalChannelFunctionEvaluation evaluateWithExactInput(
            ProcessorInvocationServices owner,
            DocumentProcessingRuntime runtime,
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot,
            ExactEventIdentityEvidence eventEvidence,
            List<String> effectiveContractKeys) {
        ExactEventIdentityEvidence evidence = Objects.requireNonNull(
                eventEvidence, "eventEvidence");
        return evaluateWithExactInput(
                owner.registry(),
                owner.contractConverter(),
                runtime.externalChannelMatcherSessions(),
                bundle,
                snapshot,
                evidence.event(),
                effectiveContractKeys,
                evidence.frozenEvent(),
                evidence.eventBlueId(),
                runtime.newRuntimeWorkSession(owner.languageRuntimeAccess()));
    }

    /** Evaluates one exact event and owns the supplied work-session lifecycle. */
    static ExternalChannelFunctionEvaluation evaluateWithExactInput(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            MatcherSessionFactory matcherSessions,
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot,
            Node exactEvent,
            List<String> effectiveContractKeys,
            FrozenNode admittedEvent,
            String eventBlueId,
            RuntimeWorkSession runtimeWorkSession) {
        RuntimeWorkSession authoritative = Objects.requireNonNull(
                runtimeWorkSession, "runtimeWorkSession");
        ExternalChannelFunctionEvaluation result = null;
        Throwable failure = null;
        boolean evidenceUnavailable = false;
        try {
            authoritative.carryExactInput(admittedEvent, eventBlueId);
            result = evaluate(
                    registry,
                    converter,
                    matcherSessions,
                    bundle,
                    snapshot,
                    exactEvent,
                    effectiveContractKeys,
                    authoritative);
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            failure = unavailable;
            evidenceUnavailable = true;
        } catch (RuntimeException | Error caught) {
            failure = caught;
        } finally {
            if (failure != null) {
                failure = evidenceUnavailable
                        ? RuntimeWorkSession.suspendIfOpenPreserving(
                                authoritative, failure)
                        : RuntimeWorkSession.failIfOpenPreserving(
                                authoritative, failure);
            }
            failure = RuntimeWorkSession.closePreserving(
                    authoritative, failure);
        }
        RuntimeWorkSession.rethrow(failure);
        return Objects.requireNonNull(result, "functionEvaluation");
    }

    private static ExternalChannelFunctionEvaluation evaluateOnce(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            MatcherSessionFactory matcherSessions,
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot,
            Node exactEvent,
            List<String> effectiveContractKeys,
            RuntimeWorkSession runtimeWorkSession) {
        MatcherSession matcher = null;
        ExternalChannelFunctionEvaluation result = null;
        Throwable failure = null;
        try {
            matcher = Objects.requireNonNull(
                    matcherSessions.open(),
                    "matcherSession");
            ExternalChannelFunctionResolver.Evaluation resolved =
                    new ExternalChannelFunctionResolver(
                            registry,
                            converter,
                            matcher,
                            bundle,
                            effectiveContractKeys,
                            runtimeWorkSession)
                            .evaluate(snapshot, exactEvent);
            FrozenNode checkpointSubject =
                    resolved.checkpointSubject();
            String checkpointSubjectBlueId =
                    resolved.checkpointSubjectBlueId();

            result = new ExternalChannelFunctionEvaluation(
                    resolved.channelKeys(),
                    resolved.eventKeys(),
                    resolved.preselects(),
                    resolved.accepts(),
                    resolved.checkpointDomainBlueId(),
                    resolved.payload(),
                    resolved.payloadBlueId(),
                    checkpointSubject,
                    checkpointSubjectBlueId,
                    resolved.handlerChannelKey(),
                    resolved.logicalDeliveryKey(),
                    resolved.handlerChannel(),
                    resolved.dependencies(),
                    resolved.runtimeDiscriminator(),
                    resolved.channelLookupResults(),
                    Collections.<ExactBlueValue>emptyList());
        } catch (RuntimeException | Error caught) {
            failure = caught;
        } finally {
            failure = RuntimeWorkSession.closePreserving(
                    matcher == null ? null : matcher::close,
                    failure);
        }
        RuntimeWorkSession.rethrow(failure);
        return Objects.requireNonNull(result, "functionEvaluation");
    }

    private ExternalChannelFunctionEvaluation withCarriedExactValues(
            List<ExactBlueValue> exactValues) {
        return new ExternalChannelFunctionEvaluation(
                channelKeys,
                eventKeys,
                preselects,
                accepts,
                checkpointDomainBlueId,
                payload,
                payloadBlueId,
                checkpointSubject,
                checkpointSubjectBlueId,
                handlerChannelKey,
                logicalDeliveryKey,
                handlerChannel,
                dependencies,
                runtimeDiscriminator,
                channelLookupResults,
                exactValues);
    }

    private static boolean sameRuntimeTrace(
            List<GasTraceEntry> left,
            List<GasTraceEntry> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            GasTraceEntry a = left.get(index);
            GasTraceEntry b = right.get(index);
            if (!a.namespace().equals(b.namespace())
                    || !a.counter().equals(b.counter())
                    || a.quantity() != b.quantity()
                    || a.weight() != b.weight()
                    || a.subtotal() != b.subtotal()
                    || !Objects.equals(
                            a.scopePath(), b.scopePath())
                    || !Objects.equals(
                            a.contractKey(),
                            b.contractKey())
                    || !Objects.equals(
                            a.logicalPath(),
                            b.logicalPath())
                    || !Objects.equals(
                            a.reason(), b.reason())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Captures one snapshot-manager boundary and creates a new cache-isolated
     * matcher for each deterministic evaluation pass. A missing manager is
     * tolerated only until matching demands a non-core reference.
     */
    static MatcherSessionFactory verifiedMatcherSessions(
            ProcessingSnapshotManager snapshotManager) {
        final ProcessingSnapshotManager captured =
                snapshotManager;
        return () -> new VerifiedMatcherSession(captured);
    }

    /**
     * A static wrapper prevents a retained function context from acquiring an
     * implicit reference to the factory that captured the snapshot manager.
     * Closing severs the only remaining matcher/materializer reference.
     */
    private static final class VerifiedMatcherSession
            implements MatcherSession {
        private FrozenTypeMatcher matcher;
        private Function<FrozenNode, FrozenNode>
                exactReferenceMaterializer;

        private VerifiedMatcherSession(
                ProcessingSnapshotManager snapshotManager) {
            final ProcessingSnapshotManager captured =
                    snapshotManager;
            this.exactReferenceMaterializer =
                    reference ->
                            materializeVerifiedExactReference(
                                    captured,
                                    reference,
                                    "event fragment");
            this.matcher =
                    FrozenTypeMatcher
                            .withVerifiedReferenceMaterializer(
                                    reference ->
                                            materializeVerifiedTypeReference(
                                                    captured,
                                                    reference,
                                                    "reference matching"));
        }

        @Override
        public synchronized void requireActive() {
            if (matcher == null) {
                throw new IllegalStateException(
                        "External Channel pattern matcher session "
                                + "is no longer active");
            }
        }

        @Override
        public synchronized boolean matches(
                FrozenNode candidate,
                FrozenNode pattern) {
            requireActive();
            if (pattern == null) {
                return true;
            }
            if (candidate == null) {
                return false;
            }
            return matcher.matchesType(
                    candidate,
                    pattern);
        }

        @Override
        public synchronized boolean isAssignableToType(
                String candidateTypeBlueId,
                String baseTypeBlueId) {
            requireActive();
            if (candidateTypeBlueId == null
                    || candidateTypeBlueId.isEmpty()
                    || baseTypeBlueId == null
                    || baseTypeBlueId.isEmpty()) {
                throw new IllegalArgumentException(
                        "Subtype comparison requires non-empty exact "
                                + "type BlueIds");
            }
            return matcher.isSubtypeOrSame(
                    FrozenNode.fromNode(
                            new Node().blueId(
                                    candidateTypeBlueId)),
                    FrozenNode.fromNode(
                            new Node().blueId(
                                    baseTypeBlueId)),
                    GasSchedule.contracts10()
                            .portableLimit(GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES));
        }

        @Override
        public synchronized FrozenNode materializeExactReference(
                FrozenNode reference) {
            requireActive();
            FrozenNode exactReference =
                    Objects.requireNonNull(
                            reference, "reference");
            if (!exactReference.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "External Channel event fragment must be an exact "
                                + "pure reference");
            }
            Function<FrozenNode, FrozenNode> materializer =
                    exactReferenceMaterializer;
            if (materializer == null) {
                throw new IllegalStateException(
                        "External Channel event fragment materializer "
                                + "session is no longer active");
            }
            FrozenNode materialized =
                    Objects.requireNonNull(
                            materializer.apply(
                                    exactReference),
                            "materializedExactReference");
            if (materialized.isReferenceOnly()) {
                throw new IllegalStateException(
                        "External Channel event fragment provider returned "
                                + "a reference instead of exact content for "
                                + exactReference
                                .getReferenceBlueId());
            }
            if (BlueIds.hasCyclicMemberSeparator(
                    exactReference.getReferenceBlueId())) {
                /*
                 * The snapshot manager has established complete cyclic-set
                 * proof. A member cannot be independently rehashed as an
                 * ordinary node.
                 */
                return materialized;
            }
            Node exact = materialized.toNode();
            final String actualBlueId;
            try {
                actualBlueId =
                        DirectBlueIdCalculator.calculateBlueId(
                                exact);
            } catch (RuntimeException invalidContent) {
                throw new IllegalStateException(
                        "External Channel event fragment provider content is "
                                + "not exact canonical content for "
                                + exactReference
                                .getReferenceBlueId(),
                        invalidContent);
            }
            if (!exactReference.getReferenceBlueId()
                    .equals(actualBlueId)) {
                throw new IllegalStateException(
                        "External Channel event fragment provider content "
                                + "BlueId mismatch: expected "
                                + exactReference
                                .getReferenceBlueId()
                                + " but calculated "
                                + actualBlueId);
            }
            return FrozenNode.fromNode(exact);
        }

        @Override
        public synchronized void close() {
            FrozenTypeMatcher active = matcher;
            if (active == null) {
                return;
            }
            matcher = null;
            exactReferenceMaterializer = null;
            active.clearCaches();
        }
    }

    private static FrozenNode materializeVerifiedExactReference(
            ProcessingSnapshotManager snapshotManager,
            FrozenNode reference,
            String purpose) {
        if (snapshotManager == null) {
            throw new IllegalStateException(
                    "External Channel " + purpose
                            + " requires a verified "
                            + "ProcessingSnapshotManager");
        }
        try {
            return snapshotManager
                    .materializeVerifiedExactReference(
                            reference);
        } catch (ExecutionEvidenceUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (BlueLanguageErrorClassifier.classify(
                    exception)
                    == BlueLanguageErrorCategory
                    .ProviderUnavailable) {
                throw new ExecutionEvidenceUnavailableException(
                        "External Channel " + purpose
                                + " exact content is unavailable for "
                                + reference.getReferenceBlueId(),
                        Collections.singleton(
                                reference.getReferenceBlueId()));
            }
            throw exception;
        }
    }

    private static TypeEvidenceResolution materializeVerifiedTypeReference(
            ProcessingSnapshotManager snapshotManager,
            FrozenNode reference,
            String purpose) {
        if (snapshotManager == null) {
            throw new IllegalStateException(
                    "External Channel " + purpose
                            + " requires a verified "
                            + "ProcessingSnapshotManager");
        }
        try {
            TypeEvidenceResolution materialization =
                    snapshotManager.materializeVerifiedTypeReference(
                            reference);
            if (materialization == null
                    || materialization.resolvedRoot().isReferenceOnly()) {
                throw new ExecutionEvidenceUnavailableException(
                        "External Channel " + purpose
                                + " exact type content is unavailable for "
                                + reference.getReferenceBlueId(),
                        Collections.singleton(
                                reference.getReferenceBlueId()));
            }
            return materialization;
        } catch (ExecutionEvidenceUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (BlueLanguageErrorClassifier.classify(exception)
                    == BlueLanguageErrorCategory.ProviderUnavailable) {
                throw new ExecutionEvidenceUnavailableException(
                        "External Channel " + purpose
                                + " exact content is unavailable for "
                                + reference.getReferenceBlueId(),
                        Collections.singleton(
                                reference.getReferenceBlueId()));
            }
            throw exception;
        }
    }

    private boolean sameResult(
            ExternalChannelFunctionEvaluation other) {
        return channelKeys.equals(other.channelKeys)
                && eventKeys.equals(other.eventKeys)
                && preselects == other.preselects
                && accepts == other.accepts
                && checkpointDomainBlueId.equals(
                other.checkpointDomainBlueId)
                && Objects.equals(payloadBlueId(), other.payloadBlueId())
                && Objects.equals(
                checkpointSubjectBlueId,
                other.checkpointSubjectBlueId)
                && Objects.equals(
                handlerChannelKey,
                other.handlerChannelKey)
                && Objects.equals(
                logicalDeliveryKey,
                other.logicalDeliveryKey)
                && sameHandlerChannel(
                handlerChannel,
                other.handlerChannel)
                && sameCheckpointSubject(
                checkpointSubject,
                other.checkpointSubject)
                && dependencies.equals(other.dependencies)
                && Objects.equals(
                runtimeDiscriminator,
                other.runtimeDiscriminator)
                && channelLookupResults.equals(
                other.channelLookupResults);
    }

    private static boolean sameCheckpointSubject(
            FrozenNode left,
            FrozenNode right) {
        return left == right
                || left != null
                && right != null
                && left.sameResolvedStructure(right);
    }

    private static boolean sameHandlerChannel(
            ChannelMemberSnapshot left,
            ChannelMemberSnapshot right) {
        return left == right
                || left != null
                && right != null
                && left.channelKey().equals(
                right.channelKey())
                && left.order() == right.order()
                && left.effectiveTypeBlueId().equals(
                right.effectiveTypeBlueId())
                && left.role().equals(right.role())
                && left.sourceContributionNodeBlueIds().equals(
                right.sourceContributionNodeBlueIds())
                && left.deterministicDependencyNodeBlueIds().equals(
                right.deterministicDependencyNodeBlueIds())
                && left.headerIdentityBlueId().equals(
                right.headerIdentityBlueId());
    }

    String payloadBlueId() {
        return payloadBlueId;
    }

    List<String> channelKeys() {
        return channelKeys;
    }

    List<String> eventKeys() {
        return eventKeys;
    }

    boolean preselects() {
        return preselects;
    }

    boolean accepts() {
        return accepts;
    }

    String checkpointDomainBlueId() {
        return checkpointDomainBlueId;
    }

    FrozenNode payload() {
        return payload;
    }

    FrozenNode checkpointSubject() {
        return checkpointSubject;
    }

    String checkpointSubjectBlueId() {
        return checkpointSubjectBlueId;
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

    ExternalChannelDependencySnapshot dependencies() {
        return dependencies;
    }

    String runtimeDiscriminator() {
        return runtimeDiscriminator;
    }

    List<String> channelLookupResults() {
        return channelLookupResults;
    }

    List<ExactBlueValue> carriedExactValues() {
        return carriedExactValues;
    }
}
