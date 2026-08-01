package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.utils.JsonPointer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Executes the evidence-bound external-delivery phases for one invocation.
 *
 * <p>The service preserves the specification order: admit immutable evidence,
 * classify every candidate from a read-only projection, preflight the complete
 * accepted closure, freeze logical groups, then register routes and execute.
 * No mutation occurs before classification and closure preflight complete.</p>
 */
final class EvidenceDeliveryOrchestrator {

    private final ProcessorInvocationState execution;
    private final DocumentProcessingRuntime runtime;
    private final ScopeExecutor scopeExecutor;
    private final Map<String, ContractBundle> bundles;
    private final ContractRecognitionMeter contractRecognitionMeter;
    private final EvidenceClassificationView classificationView;
    private final Map<String, List<String>> initializationPaths =
            new LinkedHashMap<>();
    private final Set<String> consumedCheckpointDomainProofs =
            new LinkedHashSet<>();
    private List<ChannelRunner.ExternalClassification> acceptedDeliveries =
            Collections.emptyList();
    private Map<String, List<EvidenceRouteStep>> deliveryRoutes =
            Collections.emptyMap();
    private List<List<ChannelRunner.ExternalClassification>> logicalDeliveries =
            Collections.emptyList();

    EvidenceDeliveryOrchestrator(
            ProcessorInvocationState execution,
            DocumentProcessingRuntime runtime,
            ScopeExecutor scopeExecutor,
            Map<String, ContractBundle> bundles,
            ContractRecognitionMeter contractRecognitionMeter,
            EvidenceClassificationView classificationView) {
        this.execution = execution;
        this.runtime = runtime;
        this.scopeExecutor = scopeExecutor;
        this.bundles = bundles;
        this.contractRecognitionMeter = contractRecognitionMeter;
        this.classificationView = classificationView;
    }

    void admitEvidence() {
        VerifiedExecutionEvidence evidence = execution.executionEvidence();
        if (evidence == null) {
            return;
        }
        for (ExternalDeliverySnapshot delivery : evidence.deliveries()) {
            runtime.chargeDeliverySnapshotEntry(
                    delivery.scopePath(),
                    delivery.channelKey());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put(
                    ProcessingTraceConstants.FIELD_ORDER,
                    delivery.order());
            details.put(
                    ProcessingTraceConstants.FIELD_EFFECTIVE_TYPE_BLUE_ID,
                    delivery.effectiveTypeBlueId());
            details.put(
                    ProcessingTraceConstants.FIELD_CHECKPOINT_DOMAIN_BLUE_ID,
                    delivery.checkpointDomainBlueId());
            details.put(
                    ProcessingTraceConstants.FIELD_CHECKPOINT_SUBJECT_BLUE_ID,
                    delivery.checkpointSubjectBlueId());
            runtime.recordTrace(
                    ProcessingTraceRecord.Kind.EXTERNAL_DELIVERY,
                    delivery.scopePath(),
                    delivery.channelKey(),
                    null,
                    details,
                    null);
        }
    }

    void classify(Node event) {
        VerifiedExecutionEvidence evidence = execution.executionEvidence();
        if (evidence == null) {
            throw new IllegalStateException("No execution evidence admitted");
        }
        runtime.recordSemanticDemand(JsonPointer.ROOT);

        List<ChannelRunner.ExternalClassification> acceptedNew =
                new ArrayList<>();
        Map<String, List<EvidenceRouteStep>> routes = new LinkedHashMap<>();
        Set<String> openedScopes = new LinkedHashSet<>();
        Map<String, List<EvidenceRouteStep>> plannedRoutes =
                new LinkedHashMap<>();
        for (ExternalDeliverySnapshot delivery : evidence.deliveries()) {
            int openedBefore = openedScopes.size();
            contractRecognitionMeter.beginCanonicalClassificationBatch();
            try {
                String normalizedScope = ProcessorEngine.normalizeScope(
                        delivery.scopePath());
                List<EvidenceRouteStep> route =
                        plannedRoutes.get(normalizedScope);
                if (route == null) {
                    route = routeTo(delivery.scopePath(), openedScopes);
                    plannedRoutes.put(normalizedScope, route);
                }

                openedScopes.add(normalizedScope);
                SubscriptionDelta.Entry activeInterval =
                        classificationView.activeSubscriptionInterval(
                                delivery.scopePath(),
                                delivery.channelKey());
                ContractBundle classificationBundle = scopeExecutor
                        .externalClassificationBundle(
                                delivery.scopePath(),
                                delivery.channelKey(),
                                false,
                                activeInterval != null
                                        ? activeInterval.dependencies()
                                        : ExternalChannelDependencySnapshot.none());
                validateDeliveryBinding(
                        delivery,
                        classificationBundle,
                        "classification");
                recordClassificationDemands(delivery, route, event);

                int newlyOpened = openedScopes.size() - openedBefore;
                if (newlyOpened > 0) {
                    runtime.chargeParticipatingClosure(newlyOpened);
                }
                contractRecognitionMeter.flushCanonicalClassificationBatch();

                ChannelRunner.ExternalClassification classification =
                        scopeExecutor.classifyEvidenceDelivery(
                                delivery.scopePath(),
                                delivery.channelKey(),
                                event,
                                classificationBundle);
                if (classification.acceptedNew()) {
                    acceptedNew.add(classification);
                    routes.put(
                            occurrenceKey(
                                    delivery.scopePath(),
                                    delivery.channelKey()),
                            route);
                }
            } finally {
                contractRecognitionMeter.cancelCanonicalClassificationBatch();
            }
        }
        acceptedDeliveries = Collections.unmodifiableList(
                new ArrayList<>(acceptedNew));
        deliveryRoutes = Collections.unmodifiableMap(
                new LinkedHashMap<>(routes));
    }

    void preflightParticipatingClosure() {
        if (acceptedDeliveries.isEmpty()) {
            return;
        }
        Set<String> participatingScopes = new LinkedHashSet<>();
        participatingScopes.add(JsonPointer.ROOT);
        for (ChannelRunner.ExternalClassification classification
                : acceptedDeliveries) {
            List<EvidenceRouteStep> route = deliveryRoutes.getOrDefault(
                    occurrenceKey(
                            classification.scopePath(),
                            classification.channelKey()),
                    Collections.emptyList());
            List<String> initializationPath = new ArrayList<>();
            initializationPath.add(JsonPointer.ROOT);
            for (EvidenceRouteStep step : route) {
                participatingScopes.add(step.targetScope);
                initializationPath.add(step.targetScope);
            }
            participatingScopes.add(classification.scopePath());
            initializationPaths.put(
                    classification.scopePath(),
                    Collections.unmodifiableList(initializationPath));
        }

        for (String scopePath : participatingScopes) {
            scopeExecutor.preflightSelectedHeaders(scopePath);
        }
        for (String scopePath : participatingScopes) {
            scopeExecutor.preflightEvidenceScopeAfterSelectedHeaders(scopePath);
        }
    }

    void prepareLogicalDeliveries() {
        if (acceptedDeliveries.isEmpty()) {
            logicalDeliveries = Collections.emptyList();
            return;
        }
        List<List<ChannelRunner.ExternalClassification>> groups =
                groupLogicalDeliveries(acceptedDeliveries);
        validateLogicalDeliveryGroups(groups);
        recordLogicalDeliveryGroups(groups);
        logicalDeliveries = groups;
    }

    void executeLogicalDeliveries() {
        for (List<ChannelRunner.ExternalClassification> group
                : logicalDeliveries) {
            ChannelRunner.ExternalClassification classification = group.get(0);
            registerRoute(deliveryRoutes.getOrDefault(
                    occurrenceKey(
                            classification.scopePath(),
                            classification.sourceChannelKey()),
                    Collections.emptyList()));
            scopeExecutor.processClassifiedEvidenceDeliveryGroup(group);
            if (execution.shouldStopScopeWork(classification.scopePath())) {
                return;
            }
        }
    }

    List<String> frozenScopeChain(String scopePath) {
        String normalized = ProcessorEngine.normalizeScope(scopePath);
        List<String> path = initializationPaths.get(normalized);
        return path != null
                ? path
                : Collections.singletonList(normalized);
    }

    ExternalDeliverySnapshot deliveryEvidence(
            String scopePath,
            String channelKey) {
        VerifiedExecutionEvidence evidence = execution.executionEvidence();
        if (evidence == null) {
            return null;
        }
        String normalized = ProcessorEngine.normalizeScope(scopePath);
        for (ExternalDeliverySnapshot snapshot : evidence.deliveries()) {
            if (snapshot.scopePath().equals(normalized)
                    && snapshot.channelKey().equals(channelKey)) {
                return snapshot;
            }
        }
        return null;
    }

    String checkpointSubject(
            String scopePath,
            String channelKey,
            Node event) {
        ExternalDeliverySnapshot evidence = deliveryEvidence(
                scopePath,
                channelKey);
        return evidence != null
                ? evidence.checkpointSubjectBlueId()
                : CheckpointIdentityCalculator.identity(
                        event,
                        execution.blue());
    }

    String checkpointDomain(
            ContractBundle.ChannelBinding channel,
            String scopePath) {
        ExternalDeliverySnapshot evidence = deliveryEvidence(
                scopePath,
                channel.key());
        if (evidence != null) {
            String occurrence = ProcessorEngine.normalizeScope(scopePath)
                    + ProcessorIdentityConstants.SELECTOR_COMPONENT_DELIMITER
                    + channel.key();
            if (consumedCheckpointDomainProofs.add(occurrence)) {
                useExternalContributionProof(evidence, "checkpoint-domain");
            }
            return evidence.checkpointDomainBlueId();
        }
        List<String> contributions = channel.node() != null
                ? sourceContributions(scopePath, channel.key())
                : Collections.emptyList();
        return CheckpointDomain.derive(
                channel.contract().getTypeBlueId(),
                contributions,
                null);
    }

    void recordAcceptanceProof(
            String scopePath,
            String channelKey) {
        ExternalDeliverySnapshot evidence = deliveryEvidence(
                scopePath,
                channelKey);
        if (evidence != null) {
            useExternalContributionProof(
                    evidence,
                    "external-channel-acceptance");
        }
    }

    private void recordClassificationDemands(
            ExternalDeliverySnapshot delivery,
            List<EvidenceRouteStep> route,
            Node event) {
        if (JsonPointer.ROOT.equals(delivery.scopePath())
                && route.isEmpty()) {
            runtime.recordSemanticDemand(
                    ProcessorPointerConstants.RELATIVE_CONTRACTS);
        }
        if (!JsonPointer.ROOT.equals(delivery.scopePath())) {
            runtime.recordSemanticDemand(delivery.scopePath());
        }
        runtime.recordSemanticDemand(contractDemand(
                delivery.scopePath(),
                delivery.channelKey()));
        if (event != null
                && event.getProperties() != null
                && event.getProperties().containsKey(
                ProcessorContractConstants.KEY_SUBSCRIPTION_KEY)) {
            runtime.recordSemanticDemand(
                    ProcessorPointerConstants.PROCESS_EVENT_SUBSCRIPTION_KEY);
        }
    }

    private void useExternalContributionProof(
            ExternalDeliverySnapshot delivery,
            String reason) {
        SemanticGasMeter semantic = runtime.semanticGas();
        String effectiveConstraintIdentity =
                delivery.effectiveTypeBlueId();
        for (String contribution
                : delivery.sourceContributionNodeBlueIds()) {
            GasChargeContext context = GasChargeContext.of(
                    delivery.scopePath(),
                    delivery.channelKey(),
                    contribution,
                    reason);
            semantic.openNodeManifest(contribution, context);
            semantic.useValidationProof(
                    contribution,
                    delivery.effectiveTypeBlueId(),
                    effectiveConstraintIdentity,
                    context);
        }
    }

    private List<String> sourceContributions(
            String scopePath,
            String contractKey) {
        ContractBundle bundle = bundles.get(
                ProcessorEngine.normalizeScope(scopePath));
        EffectiveContractSnapshot snapshot = bundle != null
                ? bundle.effectiveContractSnapshot(contractKey)
                : null;
        return snapshot != null
                ? snapshot.sourceContributionNodeBlueIds()
                : Collections.emptyList();
    }

    private List<List<ChannelRunner.ExternalClassification>>
    groupLogicalDeliveries(
            List<ChannelRunner.ExternalClassification> acceptedNew) {
        Map<LogicalDeliveryGroupKey,
                List<ChannelRunner.ExternalClassification>> grouped =
                new LinkedHashMap<>();
        for (ChannelRunner.ExternalClassification classification
                : acceptedNew) {
            LogicalDeliveryGroupKey key = new LogicalDeliveryGroupKey(
                    ProcessorEngine.normalizeScope(
                            classification.scopePath()),
                    classification.logicalDeliveryKey());
            grouped.computeIfAbsent(
                    key,
                    ignored -> new ArrayList<>()).add(classification);
        }
        List<List<ChannelRunner.ExternalClassification>> result =
                new ArrayList<>(grouped.size());
        for (List<ChannelRunner.ExternalClassification> group
                : grouped.values()) {
            result.add(Collections.unmodifiableList(
                    new ArrayList<>(group)));
        }
        return Collections.unmodifiableList(result);
    }

    private void validateLogicalDeliveryGroups(
            List<List<ChannelRunner.ExternalClassification>> groups) {
        for (List<ChannelRunner.ExternalClassification> group : groups) {
            if (group == null || group.isEmpty()) {
                throw new IllegalStateException(
                        "Logical delivery group is empty");
            }
            ChannelRunner.ExternalClassification first = group.get(0);
            String scopePath = ProcessorEngine.normalizeScope(
                    first.scopePath());
            String handlerChannelKey = ExternalChannelFunctionResolver
                    .immutableRoutingKey(
                            first.handlerChannelKey(),
                            "handler Channel");
            String logicalDeliveryKey = ExternalChannelFunctionResolver
                    .immutableRoutingKey(
                            first.logicalDeliveryKey(),
                            "logical delivery");
            String payloadBlueId = first.payloadBlueId();
            for (ChannelRunner.ExternalClassification classification
                    : group) {
                if (classification == null
                        || !classification.acceptedNew()
                        || !scopePath.equals(ProcessorEngine.normalizeScope(
                        classification.scopePath()))
                        || !logicalDeliveryKey.equals(
                        classification.logicalDeliveryKey())
                        || !handlerChannelKey.equals(
                        classification.handlerChannelKey())
                        || !sameChannelMember(
                        first.handlerChannel(),
                        classification.handlerChannel())
                        || !Objects.equals(
                        payloadBlueId,
                        classification.payloadBlueId())) {
                    throw new ProcessorFailureException(
                            ProcessorErrorCategory.InconsistentLogicalDelivery,
                            "Accepted External Channels disagree on logical "
                                    + "delivery at " + scopePath + "/"
                                    + logicalDeliveryKey);
                }
            }
            ContractBundle bundle = bundles.get(scopePath);
            EffectiveContractSnapshot target = bundle != null
                    ? bundle.effectiveContractSnapshot(handlerChannelKey)
                    : null;
            ChannelMemberSnapshot finalTarget = target != null
                    ? ChannelMemberSnapshot.from(target)
                    : null;
            if (bundle == null
                    || bundle.channelBinding(handlerChannelKey) == null
                    || target == null
                    || first.handlerChannel() != null
                    && !sameChannelMember(
                    first.handlerChannel(),
                    finalTarget)) {
                throw new IllegalStateException(
                        "External Channel handler target is not an unchanged "
                                + "existing same-scope Channel at "
                                + scopePath + "/" + handlerChannelKey
                                + " (classified="
                                + channelMemberDiagnostic(
                                first.handlerChannel())
                                + ", preflight="
                                + channelMemberDiagnostic(finalTarget)
                                + ")");
            }
        }
    }

    private void recordLogicalDeliveryGroups(
            List<List<ChannelRunner.ExternalClassification>> groups) {
        for (List<ChannelRunner.ExternalClassification> group : groups) {
            ChannelRunner.ExternalClassification first = group.get(0);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put(
                    ProcessingTraceConstants.FIELD_HANDLER_CHANNEL_KEY,
                    first.handlerChannelKey());
            details.put(
                    ProcessingTraceConstants.FIELD_LOGICAL_DELIVERY_KEY,
                    first.logicalDeliveryKey());
            details.put(
                    ProcessingTraceConstants.FIELD_SOURCE_COUNT,
                    group.size());
            for (int index = 0; index < group.size(); index++) {
                details.put(
                        ProcessingTraceConstants.sourceField(index),
                        group.get(index).sourceChannelKey());
            }
            runtime.recordTrace(
                    ProcessingTraceRecord.Kind.LOGICAL_DELIVERY_GROUP,
                    first.scopePath(),
                    first.handlerChannelKey(),
                    first.logicalDeliveryKey(),
                    details,
                    null);
        }
    }

    private void validateDeliveryBinding(
            ExternalDeliverySnapshot delivery,
            ContractBundle bundle,
            String phase) {
        ContractBundle.ChannelBinding binding = bundle != null
                ? bundle.channelBinding(delivery.channelKey())
                : null;
        EffectiveContractSnapshot snapshot = bundle != null
                ? bundle.effectiveContractSnapshot(delivery.channelKey())
                : null;
        if (binding == null
                || ProcessorContractConstants.isProcessorManagedChannel(
                binding.contract())
                || snapshot == null
                || !delivery.effectiveTypeBlueId().equals(
                snapshot.effectiveTypeBlueId())
                || delivery.order() != snapshot.order()
                || !delivery.sourceContributionNodeBlueIds().equals(
                snapshot.sourceContributionNodeBlueIds())) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery changed during " + phase + " at "
                            + delivery.scopePath() + "/"
                            + delivery.channelKey());
        }
    }

    private List<EvidenceRouteStep> routeTo(
            String targetScope,
            Set<String> openedScopes) {
        String target = ProcessorEngine.normalizeScope(targetScope);
        if (JsonPointer.ROOT.equals(target)) {
            return Collections.emptyList();
        }
        List<EvidenceRouteStep> result = new ArrayList<>();
        String currentScope = JsonPointer.ROOT;
        Set<String> visited = new LinkedHashSet<>();
        while (!currentScope.equals(target)) {
            if (!visited.add(currentScope)) {
                throw new InvalidExecutionEvidenceException(
                        "Cyclic Process Embedded route to " + target);
            }
            openedScopes.add(currentScope);
            EvidenceRouteStep selected = null;
            ContractBundle bundle = scopeExecutor
                    .externalClassificationBundle(
                            currentScope,
                            null,
                            true);
            EffectiveContractSnapshot embeddedSnapshot = null;
            for (EffectiveContractSnapshot snapshot
                    : bundle.effectiveContractSnapshots()) {
                if (EffectiveContractSnapshotConstants.Role.PROCESS_EMBEDDED
                        .equals(snapshot.role())) {
                    embeddedSnapshot = snapshot;
                    break;
                }
            }
            if (embeddedSnapshot != null) {
                runtime.recordSemanticDemand(contractDemand(
                        currentScope,
                        embeddedSnapshot.key()));
                for (String raw : bundle.embeddedPaths()) {
                    String candidate = ProcessorEngine.resolvePointer(
                            currentScope,
                            raw);
                    if (candidate.equals(currentScope)
                            || !PointerUtils.descendantOrEqual(
                            target,
                            candidate)) {
                        continue;
                    }
                    int segments = JsonPointer.split(
                            ProcessorEngine.relativizePointer(
                                    currentScope,
                                    candidate)).size();
                    EvidenceRouteStep next = new EvidenceRouteStep(
                            currentScope,
                            embeddedSnapshot.key(),
                            candidate,
                            segments,
                            embeddedSnapshot
                                    .sourceContributionNodeBlueIds());
                    if (selected == null
                            || JsonPointer.split(candidate).size()
                            > JsonPointer.split(selected.targetScope).size()) {
                        selected = next;
                    }
                }
            }
            if (selected == null) {
                throw new InvalidExecutionEvidenceException(
                        "No Process Embedded route to " + target);
            }
            result.add(selected);
            currentScope = selected.targetScope;
        }
        return Collections.unmodifiableList(result);
    }

    private void registerRoute(List<EvidenceRouteStep> route) {
        for (EvidenceRouteStep step : route) {
            ScopeRuntimeContext declaringScope = runtime.scope(
                    step.declaringScope);
            runtime.attachScopeOccurrence(
                    step.declaringScope,
                    step.targetScope);
            if (!declaringScope.processedEmbeddedPaths()
                    .contains(step.targetScope)) {
                declaringScope.recordProcessedEmbeddedPath(step.targetScope);
            }
            runtime.setScopeEmbeddedDepth(
                    step.targetScope,
                    runtime.scopeEmbeddedDepth(step.declaringScope) + 1);
        }
    }

    private String contractDemand(String scopePath, String key) {
        return ProcessorEngine.resolvePointer(
                scopePath,
                ProcessorPointerConstants.RELATIVE_CONTRACTS
                        + "/"
                        + JsonPointer.escape(key));
    }

    private String occurrenceKey(String scopePath, String channelKey) {
        return ProcessorEngine.normalizeScope(scopePath)
                + ProcessorIdentityConstants.SELECTOR_COMPONENT_DELIMITER
                + channelKey;
    }

    private String channelMemberDiagnostic(ChannelMemberSnapshot snapshot) {
        if (snapshot == null) {
            return "absent";
        }
        return snapshot.role()
                + ":" + snapshot.effectiveTypeBlueId()
                + ":" + snapshot.order()
                + ":" + snapshot.sourceContributionNodeBlueIds()
                + ":" + snapshot.deterministicDependencyNodeBlueIds()
                + ":" + snapshot.headerIdentityBlueId();
    }

    private boolean sameChannelMember(
            ChannelMemberSnapshot left,
            ChannelMemberSnapshot right) {
        return left == right
                || left != null
                && right != null
                && left.channelKey().equals(right.channelKey())
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

    /** Immutable route selected during read-only evidence classification. */
    private static final class EvidenceRouteStep {
        private final String declaringScope;
        private final String contractKey;
        private final String targetScope;
        private final int relativeSegmentCount;
        private final List<String> orderedContributionBlueIds;

        private EvidenceRouteStep(
                String declaringScope,
                String contractKey,
                String targetScope,
                int relativeSegmentCount,
                List<String> orderedContributionBlueIds) {
            this.declaringScope = declaringScope;
            this.contractKey = contractKey;
            this.targetScope = targetScope;
            this.relativeSegmentCount = relativeSegmentCount;
            this.orderedContributionBlueIds = Collections.unmodifiableList(
                    new ArrayList<>(orderedContributionBlueIds));
        }
    }

    /** Key preserving first occurrence order without strategy-controlled sort. */
    private static final class LogicalDeliveryGroupKey {
        private final String scopePath;
        private final String logicalDeliveryKey;

        private LogicalDeliveryGroupKey(
                String scopePath,
                String logicalDeliveryKey) {
            this.scopePath = Objects.requireNonNull(
                    scopePath,
                    "scopePath");
            this.logicalDeliveryKey = Objects.requireNonNull(
                    logicalDeliveryKey,
                    "logicalDeliveryKey");
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof LogicalDeliveryGroupKey)) {
                return false;
            }
            LogicalDeliveryGroupKey that =
                    (LogicalDeliveryGroupKey) other;
            return scopePath.equals(that.scopePath)
                    && logicalDeliveryKey.equals(that.logicalDeliveryKey);
        }

        @Override
        public int hashCode() {
            return 31 * scopePath.hashCode() + logicalDeliveryKey.hashCode();
        }
    }
}
