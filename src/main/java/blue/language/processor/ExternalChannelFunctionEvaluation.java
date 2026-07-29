package blue.language.processor;

import blue.language.BlueLanguageErrorCategory;
import blue.language.BlueLanguageErrorClassifier;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.FrozenTypeMatcher;

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
    private final List<String> channelLookupResults;

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
            List<String> channelLookupResults) {
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
        this.channelLookupResults =
                Collections.unmodifiableList(
                        Objects.requireNonNull(
                                channelLookupResults,
                                "channelLookupResults"));
    }

    static ExternalChannelFunctionEvaluation evaluate(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            MatcherSessionFactory matcherSessions,
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot,
            Node exactEvent) {
        return evaluate(
                registry,
                converter,
                matcherSessions,
                bundle,
                snapshot,
                exactEvent,
                null);
    }

    static ExternalChannelFunctionEvaluation evaluate(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            MatcherSessionFactory matcherSessions,
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot,
            Node exactEvent,
            List<String> effectiveContractKeys) {
        RuntimeWorkSession admission =
                new RuntimeWorkSession(
                        new GasMeter(),
                        RuntimeWorkSession.Mode.ADMISSION);
        try {
            return evaluate(
                    registry,
                    converter,
                    matcherSessions,
                    bundle,
                    snapshot,
                    exactEvent,
                    effectiveContractKeys,
                    admission);
        } finally {
            if (admission.isOpen()) {
                admission.suspend();
            }
        }
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
        RuntimeWorkSession comparison =
                authoritative.diagnosticTwin();

        final ExternalChannelFunctionEvaluation first;
        try {
            first = evaluateOnce(
                        registry,
                        converter,
                        matcherSessions,
                        bundle,
                        snapshot,
                        exactEvent,
                        effectiveContractKeys,
                        authoritative);
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            suspendIfOpen(authoritative);
            suspendIfOpen(comparison);
            throw unavailable;
        } catch (RuntimeException | Error failure) {
            failIfOpen(authoritative);
            suspendIfOpen(comparison);
            throw failure;
        }

        final ExternalChannelFunctionEvaluation second;
        try {
            second = evaluateOnce(
                        registry,
                        converter,
                        matcherSessions,
                        bundle,
                        snapshot,
                        exactEvent,
                        effectiveContractKeys,
                        comparison);
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            suspendIfOpen(authoritative);
            suspendIfOpen(comparison);
            throw unavailable;
        } catch (RuntimeException | Error failure) {
            failIfOpen(authoritative);
            suspendIfOpen(comparison);
            throw failure;
        }
        if (!first.sameResult(second)
                || !sameRuntimeTrace(
                        authoritative.stagedTrace(),
                        comparison.stagedTrace())) {
            failIfOpen(authoritative);
            suspendIfOpen(comparison);
            throw new IllegalStateException(
                    "External Channel functions are not deterministic at "
                            + snapshot.scopePath() + "/" + snapshot.key());
        }
        authoritative.complete();
        comparison.suspend();
        return first;
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
        MatcherSession matcher = Objects.requireNonNull(
                matcherSessions.open(),
                "matcherSession");
        try {
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

            return new ExternalChannelFunctionEvaluation(
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
                    resolved.channelLookupResults());
        } finally {
            matcher.close();
        }
    }

    private static void failIfOpen(
            RuntimeWorkSession session) {
        if (session.isOpen()) {
            session.failDeterministically();
        }
    }

    private static void suspendIfOpen(
            RuntimeWorkSession session) {
        if (session.isOpen()) {
            session.suspend();
        }
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
                                            materializeVerifiedExactReference(
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
                        BlueIdCalculator.calculateBlueId(
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

    private String payloadBlueId() {
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

    List<String> channelLookupResults() {
        return channelLookupResults;
    }
}
