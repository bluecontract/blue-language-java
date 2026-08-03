package blue.language.processor;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.processor.util.ProcessorContractConstants;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Re-evaluates registered subscription functions and their exact selectors. */
final class ExternalSubscriptionSelection {

    private final ProcessingSnapshotManager snapshotManager;
    private final ContractProcessorRegistry registry;
    private final NodeToObjectConverter converter;

    ExternalSubscriptionSelection(
            ProcessingSnapshotManager snapshotManager,
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter) {
        this.snapshotManager = snapshotManager;
        this.registry = registry;
        this.converter = converter;
    }

    boolean configured() {
        return registry != null && converter != null;
    }

    ExternalSubscriptionEvaluation evaluate(
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot,
            blue.language.model.Node event,
            List<String> effectiveContractKeys) {
        return immutableEvaluation(
                ExternalChannelFunctionEvaluation.evaluate(
                        registry,
                        converter,
                        ExternalChannelFunctionEvaluation
                                .verifiedMatcherSessions(
                                        snapshotManager),
                        bundle,
                        snapshot,
                        event,
                        effectiveContractKeys));
    }

    ExternalSubscriptionEvaluation evaluate(
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot,
            blue.language.model.Node event,
            List<String> effectiveContractKeys,
            RuntimeWorkSession runtimeWorkSession) {
        return immutableEvaluation(
                ExternalChannelFunctionEvaluation.evaluate(
                        registry,
                        converter,
                        ExternalChannelFunctionEvaluation
                                .verifiedMatcherSessions(
                                        snapshotManager),
                        bundle,
                        snapshot,
                        event,
                        effectiveContractKeys,
                        runtimeWorkSession));
    }

    private ExternalSubscriptionEvaluation immutableEvaluation(
            ExternalChannelFunctionEvaluation evaluation) {
        return new ExternalSubscriptionEvaluation(
                evaluation.channelKeys(),
                evaluation.eventKeys(),
                evaluation.preselects(),
                evaluation.accepts(),
                evaluation.checkpointDomainBlueId(),
                evaluation.checkpointSubjectBlueId(),
                evaluation.dependencies(),
                evaluation.payloadBlueId(),
                evaluation.handlerChannelKey(),
                evaluation.logicalDeliveryKey());
    }

    boolean intersects(List<String> left, List<String> right) {
        Set<String> rightSet = new LinkedHashSet<>(right);
        for (String value : left) {
            if (rightSet.contains(value)) {
                return true;
            }
        }
        return false;
    }

    Set<String> subscriptionContractKeys(
            SubscriptionDelta.Entry interval,
            Map<String, String> selectorTypes) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(interval.channelKey());
        for (ExternalChannelDependencySnapshot.Entry dependency
                : interval.dependencies().entries()) {
            keys.add(dependency.channelKey());
        }
        for (ExternalChannelDependencySnapshot.TypeFamily family
                : interval.dependencies().typeFamilies()) {
            /*
             * Retain the claimed family too, so retyping/removal is visible
             * when the exact dependency snapshot is re-derived.
             */
            for (ExternalChannelDependencySnapshot.Member member
                    : family.members()) {
                keys.add(member.channelKey());
            }
        }
        for (ExternalChannelDependencySnapshot.ChannelEntry channel
                : interval.dependencies().channelEntries()) {
            keys.add(channel.channelKey());
        }
        if (selectorTypes != null) {
            for (Map.Entry<String, String> candidate
                    : selectorTypes.entrySet()) {
                boolean channelCatalog =
                        interval.dependencies()
                                .wholeSameScopeChannelCatalog();
                if (channelCatalog
                        ? !isChannelType(candidate.getValue())
                        : !isExternalChannelType(
                        candidate.getValue())) {
                    continue;
                }
                if (channelCatalog
                        || interval.dependencies()
                        .wholeSameScopeExternalSurface()
                        || selectsEffectiveType(
                        interval.dependencies(),
                        candidate.getValue())) {
                    keys.add(candidate.getKey());
                }
            }
        }
        return keys;
    }

    boolean hasEnumerationSelector(
            SubscriptionDelta.Entry interval) {
        return interval.dependencies()
                .wholeSameScopeExternalSurface()
                || interval.dependencies()
                .wholeSameScopeChannelCatalog()
                || !interval.dependencies().typeFamilies().isEmpty();
    }

    boolean isExternalChannelType(String typeBlueId) {
        ChannelProcessor<?> processor = typeBlueId != null
                ? registry.lookupChannel(typeBlueId).orElse(null)
                : null;
        if (processor == null) {
            return false;
        }
        Class<?> contractType = processor.contractType();
        for (Class<?> managed : ProcessorManagedChannelTypes.TYPES) {
            if (managed.isAssignableFrom(contractType)) {
                return false;
            }
        }
        return true;
    }

    boolean isChannelType(String typeBlueId) {
        return typeBlueId != null
                && registry.lookupChannel(typeBlueId).isPresent();
    }

    private boolean selectsEffectiveType(
            ExternalChannelDependencySnapshot dependencies,
            String effectiveTypeBlueId) {
        ExternalChannelFunctionEvaluation.MatcherSession matcher =
                null;
        try {
            for (ExternalChannelDependencySnapshot.TypeFamily family
                    : dependencies.typeFamilies()) {
                if (family.effectiveTypeBlueId().equals(
                        effectiveTypeBlueId)) {
                    return true;
                }
                if (!family.includesSubtypes()) {
                    continue;
                }
                if (matcher == null) {
                    matcher = ExternalChannelFunctionEvaluation
                            .verifiedMatcherSessions(snapshotManager)
                            .open();
                }
                if (matcher.isAssignableToType(
                        effectiveTypeBlueId,
                        family.baseTypeBlueId())) {
                    return true;
                }
            }
            return false;
        } finally {
            if (matcher != null) {
                matcher.close();
            }
        }
    }
}

/** Immutable result of one registered subscription-function evaluation. */
final class ExternalSubscriptionEvaluation {
    final List<String> channelKeys;
    final List<String> eventKeys;
    final boolean preselects;
    final boolean accepts;
    final String checkpointDomainBlueId;
    final String checkpointSubjectBlueId;
    final ExternalChannelDependencySnapshot dependencies;
    final String payloadBlueId;
    final String handlerChannelKey;
    final String logicalDeliveryKey;

    ExternalSubscriptionEvaluation(
            List<String> channelKeys,
            List<String> eventKeys,
            boolean preselects,
            boolean accepts,
            String checkpointDomainBlueId,
            String checkpointSubjectBlueId,
            ExternalChannelDependencySnapshot dependencies,
            String payloadBlueId,
            String handlerChannelKey,
            String logicalDeliveryKey) {
        this.channelKeys = channelKeys;
        this.eventKeys = eventKeys;
        this.preselects = preselects;
        this.accepts = accepts;
        this.checkpointDomainBlueId = Objects.requireNonNull(
                checkpointDomainBlueId,
                "checkpointDomainBlueId");
        this.checkpointSubjectBlueId = checkpointSubjectBlueId;
        this.dependencies = Objects.requireNonNull(
                dependencies, "dependencies");
        this.payloadBlueId = payloadBlueId;
        this.handlerChannelKey = handlerChannelKey;
        this.logicalDeliveryKey = logicalDeliveryKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ExternalSubscriptionEvaluation)) {
            return false;
        }
        ExternalSubscriptionEvaluation evaluation =
                (ExternalSubscriptionEvaluation) other;
        return channelKeys.equals(evaluation.channelKeys)
                && eventKeys.equals(evaluation.eventKeys)
                && preselects == evaluation.preselects
                && accepts == evaluation.accepts
                && checkpointDomainBlueId.equals(
                evaluation.checkpointDomainBlueId)
                && Objects.equals(
                checkpointSubjectBlueId,
                evaluation.checkpointSubjectBlueId)
                && dependencies.equals(evaluation.dependencies)
                && Objects.equals(payloadBlueId, evaluation.payloadBlueId)
                && Objects.equals(
                handlerChannelKey,
                evaluation.handlerChannelKey)
                && Objects.equals(
                logicalDeliveryKey,
                evaluation.logicalDeliveryKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                channelKeys,
                eventKeys,
                preselects,
                accepts,
                checkpointDomainBlueId,
                checkpointSubjectBlueId,
                dependencies,
                payloadBlueId,
                handlerChannelKey,
                logicalDeliveryKey);
    }
}
