package blue.language.processor;

import blue.language.BlueLanguageErrorCategory;
import blue.language.BlueLanguageErrorClassifier;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
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

    interface MatcherSession {
        void requireActive();

        boolean matches(
                FrozenNode candidate,
                FrozenNode pattern);

        FrozenNode materializeExactReference(
                FrozenNode reference);

        void close();
    }

    @FunctionalInterface
    interface MatcherSessionFactory {
        MatcherSession open();
    }

    private final List<String> channelKeys;
    private final List<String> eventKeys;
    private final boolean preselects;
    private final boolean accepts;
    private final String checkpointDomainBlueId;
    private final FrozenNode payload;
    private final FrozenNode checkpointSubject;
    private final String checkpointSubjectBlueId;
    private final String handlerChannelKey;
    private final String logicalDeliveryKey;
    private final ChannelMemberSnapshot handlerChannel;
    private final ExternalChannelDependencySnapshot dependencies;

    private ExternalChannelFunctionEvaluation(
            List<String> channelKeys,
            List<String> eventKeys,
            boolean preselects,
            boolean accepts,
            String checkpointDomainBlueId,
            FrozenNode payload,
            FrozenNode checkpointSubject,
            String checkpointSubjectBlueId,
            String handlerChannelKey,
            String logicalDeliveryKey,
            ChannelMemberSnapshot handlerChannel,
            ExternalChannelDependencySnapshot dependencies) {
        this.channelKeys = channelKeys;
        this.eventKeys = eventKeys;
        this.preselects = preselects;
        this.accepts = accepts;
        this.checkpointDomainBlueId = checkpointDomainBlueId;
        this.payload = payload;
        this.checkpointSubject = checkpointSubject;
        this.checkpointSubjectBlueId = checkpointSubjectBlueId;
        this.handlerChannelKey = handlerChannelKey;
        this.logicalDeliveryKey = logicalDeliveryKey;
        this.handlerChannel = handlerChannel;
        this.dependencies = dependencies;
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
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(converter, "converter");
        Objects.requireNonNull(
                matcherSessions,
                "matcherSessions");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(exactEvent, "exactEvent");

        ExternalChannelFunctionEvaluation first =
                evaluateOnce(
                        registry,
                        converter,
                        matcherSessions,
                        bundle,
                        snapshot,
                        exactEvent,
                        effectiveContractKeys);
        ExternalChannelFunctionEvaluation second =
                evaluateOnce(
                        registry,
                        converter,
                        matcherSessions,
                        bundle,
                        snapshot,
                        exactEvent,
                        effectiveContractKeys);
        if (!first.sameResult(second)) {
            throw new IllegalStateException(
                    "External Channel functions are not deterministic at "
                            + snapshot.scopePath() + "/" + snapshot.key());
        }
        return first;
    }

    private static ExternalChannelFunctionEvaluation evaluateOnce(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            MatcherSessionFactory matcherSessions,
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot,
            Node exactEvent,
            List<String> effectiveContractKeys) {
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
                            effectiveContractKeys)
                            .evaluate(snapshot, exactEvent);
            FrozenNode checkpointSubject =
                    resolved.checkpointSubject();
            String checkpointSubjectBlueId =
                    checkpointSubject != null
                            ? checkpointSubject.blueId()
                            : null;

            return new ExternalChannelFunctionEvaluation(
                    resolved.channelKeys(),
                    resolved.eventKeys(),
                    resolved.preselects(),
                    resolved.accepts(),
                    resolved.checkpointDomainBlueId(),
                    resolved.payload(),
                    checkpointSubject,
                    checkpointSubjectBlueId,
                    resolved.handlerChannelKey(),
                    resolved.logicalDeliveryKey(),
                    resolved.handlerChannel(),
                    resolved.dependencies());
        } finally {
            matcher.close();
        }
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
                && dependencies.equals(other.dependencies);
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
        return payload != null ? payload.blueId() : null;
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
}
