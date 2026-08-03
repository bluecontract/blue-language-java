package blue.language.processor;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Independently re-evaluates the retained external subscription surface. */
final class ExternalPreselectionVerifier {

    /** Opens one isolated admission session for one subscription evaluation. */
    interface RuntimeWorkSessionFactory {
        RuntimeWorkSession open();
    }

    private static final Comparator<String> CANONICAL_TEXT_ORDER =
            ExternalOrderKey::compareTextCodePoints;
    private static final Comparator<EvaluatedOccurrence> CANONICAL_ORDER =
            Comparator
                    .comparingInt((EvaluatedOccurrence occurrence) ->
                            ExternalEvidenceVerificationSupport.depth(
                                    occurrence.scopePath))
                    .reversed()
                    .thenComparing(
                            occurrence -> occurrence.scopePath,
                            CANONICAL_TEXT_ORDER)
                    .thenComparingInt(occurrence -> occurrence.order)
                    .thenComparing(
                            occurrence -> occurrence.channelKey,
                            CANONICAL_TEXT_ORDER)
                    .thenComparing(
                            occurrence -> occurrence.effectiveTypeBlueId,
                            CANONICAL_TEXT_ORDER);

    private final ExternalSubscriptionSelection selection;
    private final ExternalSubscriptionProjectionBuilder projectionBuilder;

    ExternalPreselectionVerifier(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter) {
        this.selection = new ExternalSubscriptionSelection(
                snapshotManager, registry, converter);
        this.projectionBuilder =
                new ExternalSubscriptionProjectionBuilder(
                        contractLoader, snapshotManager, selection);
    }

    /**
     * The default can prove only a genuinely empty effective External Channel
     * surface. It never guesses subscription or activation state.
     */
    ExternalDeliveryPlan deriveProvablyEmptyPlan(Node root) {
        Set<ExternalSubscriptionOccurrenceKey> occurrences =
                exactExternalOccurrences(root);
        if (!occurrences.isEmpty()) {
            throw ExternalEvidenceVerificationSupport.unavailable(
                    "Exact external delivery subscription and activation "
                            + "state is unavailable",
                    ExternalEvidenceVerificationSupport
                            .referencedBlueIds(root));
        }
        return ExternalDeliveryPlan.builder()
                .revisions(0L, 0L)
                .eventOrderKey(ExternalOrderKey.of(
                        Collections.emptyList()))
                .activeSubscriptionIntervals(
                        Collections.<SubscriptionDelta.Entry>emptyList())
                .exactRuntimeState()
                .build();
    }

    /**
     * Enumerates the exact effective External Channel occurrence surface.
     * This is intentionally independent of feeder-supplied interval keys so an
     * omitted interval cannot make its own absence look complete.
     */
    private Set<ExternalSubscriptionOccurrenceKey> exactExternalOccurrences(
            Node root) {
        Set<ExternalSubscriptionOccurrenceKey> occurrences =
                new LinkedHashSet<>();
        try (ExternalDeliveryResolution resolution =
                     projectionBuilder.resolution(root)) {
            Deque<String> pending = new ArrayDeque<>();
            Set<String> visited = new LinkedHashSet<>();
            pending.add(JsonPointer.ROOT);
            while (!pending.isEmpty()) {
                String scopePath = pending.removeFirst();
                if (!visited.add(scopePath)) {
                    throw ExternalEvidenceVerificationSupport.invalid(
                            "Process Embedded surface contains a repeated scope: "
                                    + scopePath);
                }
                Node selectedScope = resolution.selectedNodeAt(scopePath);
                Node effectiveScope = resolution.effectiveNodeAt(scopePath);
                if (!ExternalEvidenceVerificationSupport.isValidScope(
                        scopePath, selectedScope)
                        || !ExternalEvidenceVerificationSupport.isValidScope(
                        scopePath, effectiveScope)) {
                    throw ExternalEvidenceVerificationSupport.invalid(
                            "Process Embedded scope is absent or not an object: "
                                    + scopePath);
                }
                if (ExternalEvidenceVerificationSupport
                        .hasDirectTerminatedMarker(selectedScope)) {
                    continue;
                }
                ContractBundle bundle =
                        resolution.subscriptionBundleAt(scopePath);
                for (EffectiveContractSnapshot snapshot
                        : bundle.effectiveContractSnapshots()) {
                    if (EffectiveContractSnapshotConstants
                            .Role.EXTERNAL_CHANNEL.equals(snapshot.role())) {
                        ExternalSubscriptionOccurrenceKey occurrence =
                                ExternalSubscriptionOccurrenceKey.of(
                                        scopePath, snapshot.key());
                        if (!occurrences.add(occurrence)) {
                            throw ExternalEvidenceVerificationSupport.invalid(
                                    "Effective External Channel surface contains "
                                            + "a repeated occurrence: "
                                            + occurrence);
                        }
                    }
                }
                EmbeddedScopePlan embeddedPlan =
                        resolution.embeddedScopePlanAt(scopePath, bundle);
                for (String child : embeddedPlan != null
                        ? embeddedPlan.concreteChildPaths()
                        : Collections.<String>emptyList()) {
                    if (child.equals(scopePath)
                            || !PointerUtils.descendantOrEqual(
                            child, scopePath)) {
                        throw ExternalEvidenceVerificationSupport.invalid(
                                "Process Embedded path escapes its scope at "
                                        + scopePath + ": " + child);
                    }
                    if (visited.contains(child) || pending.contains(child)) {
                        throw ExternalEvidenceVerificationSupport.invalid(
                                "Ambiguous Process Embedded scope: " + child);
                    }
                    pending.addLast(child);
                }
            }
        }
        return occurrences;
    }

    void verify(
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence) {
        verify(
                root,
                event,
                evidence,
                defaultRuntimeWorkSessions());
    }

    void verify(
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence,
            RuntimeWorkSessionFactory runtimeWorkSessions) {
        if (!evidence.hasActiveSubscriptionIntervals()) {
            throw ExternalEvidenceVerificationSupport.unavailable(
                    "Complete retained external subscription and activation "
                            + "evidence is unavailable",
                    ExternalEvidenceVerificationSupport.referencedBlueIds(
                            root, event));
        }
        EvaluationResult evaluated = evaluate(
                root,
                event,
                evidence.indexedRootRevision(),
                evidence.eventOrderKey(),
                evidence.activeSubscriptionIntervals(),
                runtimeWorkSessions);
        verify(evidence, evaluated);
    }

    /** Verifies already replayed evaluation products against bound evidence. */
    void verify(
            VerifiedExecutionEvidence evidence,
            EvaluationResult evaluated) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(evaluated, "evaluated");
        if (!evidence.hasActiveSubscriptionIntervals()) {
            throw ExternalEvidenceVerificationSupport.unavailable(
                    "Complete retained external subscription and activation "
                            + "evidence is unavailable",
                    Collections.<String>emptySet());
        }
        verifyEvaluatedDeliveries(
                evidence.deliveries(), evaluated);
    }

    /**
     * Evaluates the complete retained interval surface through the same
     * projection, resolution, and registered selection kernel used by core
     * evidence verification.
     */
    EvaluationResult evaluate(
            Node root,
            Node event,
            long indexedRootRevision,
            ExternalOrderKey eventOrderKey,
            List<SubscriptionDelta.Entry> activeIntervals,
            RuntimeWorkSessionFactory runtimeWorkSessions) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(eventOrderKey, "eventOrderKey");
        Objects.requireNonNull(activeIntervals, "activeIntervals");
        Objects.requireNonNull(runtimeWorkSessions, "runtimeWorkSessions");
        if (indexedRootRevision < 0L) {
            throw new IllegalArgumentException(
                    "indexedRootRevision must be non-negative");
        }
        if (!selection.configured()) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Registered External Channel subscription functions are "
                            + "unavailable");
        }

        final String eventBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);
        final List<EvaluatedOccurrence> occurrences = new ArrayList<>();
        final Set<ExternalSubscriptionOccurrenceKey> uniqueOccurrences =
                new LinkedHashSet<>();
        try {
            ExternalSubscriptionProjection projected =
                    projectionBuilder.subscriptionIndexProjection(
                            root, activeIntervals);
            try (ExternalDeliveryResolution resolution =
                         projectionBuilder.subscriptionResolution(projected)) {
                for (SubscriptionDelta.Entry activeInterval
                        : activeIntervals) {
                    String scopePath = PointerUtils.normalizeScope(
                            activeInterval.scopePath());
                    ExternalSubscriptionOccurrenceKey occurrenceKey =
                            ExternalSubscriptionOccurrenceKey.of(
                                    scopePath,
                                    activeInterval.channelKey());
                    if (!uniqueOccurrences.add(occurrenceKey)) {
                        throw ExternalEvidenceVerificationSupport.invalid(
                                "Duplicate retained External Channel occurrence at "
                                        + scopePath + "/"
                                        + activeInterval.channelKey());
                    }
                    Node selected = resolution.selectedNodeAt(scopePath);
                    Node effective = resolution.effectiveNodeAt(scopePath);
                    verifyScope(
                            resolution,
                            scopePath,
                            activeInterval.channelKey(),
                            selected,
                            effective);

                    Map<String, String> selectorTypes =
                            selection.hasEnumerationSelector(activeInterval)
                                    ? projected.selectorTypes(scopePath)
                                    : null;
                    ContractBundle bundle =
                            resolution.subscriptionBundleAt(
                                    scopePath,
                                    selection.subscriptionContractKeys(
                                            activeInterval, selectorTypes),
                                    false);
                    EffectiveContractSnapshot snapshot =
                            bundle.effectiveContractSnapshot(
                                    activeInterval.channelKey());
                    if (snapshot == null
                            || !EffectiveContractSnapshotConstants
                            .Role.EXTERNAL_CHANNEL.equals(snapshot.role())) {
                        throw ExternalEvidenceVerificationSupport.invalid(
                                "Retained active subscription channel is absent "
                                        + "or not external at " + scopePath + "/"
                                        + activeInterval.channelKey());
                    }
                    FrozenNode effectiveContract =
                            bundle.contractNode(snapshot.key());
                    if (effectiveContract == null) {
                        throw ExternalEvidenceVerificationSupport.invalid(
                                "External delivery effective contract content is "
                                        + "absent at " + scopePath + "/"
                                        + snapshot.key());
                    }

                    RuntimeWorkSession runtimeWorkSession = Objects.requireNonNull(
                            runtimeWorkSessions.open(),
                            "runtimeWorkSession");
                    ExternalSubscriptionEvaluation evaluation =
                            selection.evaluate(
                                    bundle,
                                    snapshot,
                                    event,
                                    activeInterval.dependencies()
                                            .wholeSameScopeChannelCatalog()
                                            ? projected.contractKeys(scopePath)
                                            : null,
                                    runtimeWorkSession);
                    verifySubscriptionLaws(
                            evaluation, scopePath, snapshot.key());
                    verifyActiveInterval(
                            snapshot,
                            activeInterval,
                            evaluation,
                            scopePath,
                            indexedRootRevision);

                    boolean eligibleAtEvent =
                            activeInterval.startAfterExternalOrderKey() == null
                                    || eventOrderKey.compareTo(
                                    activeInterval
                                            .startAfterExternalOrderKey()) > 0;
                    boolean intersects = selection.intersects(
                            evaluation.channelKeys,
                            evaluation.eventKeys);
                    boolean physicalCandidate =
                            eligibleAtEvent && intersects;
                    String plannedCheckpointSubject = checkpointSubject(
                            evaluation,
                            eventBlueId,
                            scopePath,
                            snapshot.key());
                    ExternalDeliverySnapshot delivery =
                            eligibleAtEvent && evaluation.preselects
                                    ? delivery(
                                            snapshot,
                                            activeInterval,
                                            evaluation,
                                            scopePath,
                                            plannedCheckpointSubject)
                                    : null;
                    IndexedDeliveryDiagnostic diagnostic =
                            new IndexedDeliveryDiagnostic(
                                    occurrenceKey,
                                    eligibleAtEvent,
                                    physicalCandidate,
                                    evaluation.preselects,
                                    evaluation.accepts,
                                    evaluation.channelKeys,
                                    evaluation.eventKeys,
                                    evaluation.dependencies,
                                    evaluation.checkpointDomainBlueId,
                                    plannedCheckpointSubject,
                                    evaluation.payloadBlueId,
                                    evaluation.handlerChannelKey,
                                    evaluation.logicalDeliveryKey);
                    occurrences.add(new EvaluatedOccurrence(
                            scopePath,
                            snapshot.key(),
                            snapshot.order(),
                            snapshot.effectiveTypeBlueId(),
                            diagnostic,
                            delivery));
                }
            }
        } catch (ExecutionEvidenceUnavailableException exception) {
            throw exception;
        } catch (InvalidExecutionEvidenceException exception) {
            throw exception;
        } catch (SubscriptionSurfaceInvalidException exception) {
            throw exception;
        } catch (PortableLimitExceededException exception) {
            throw exception;
        } catch (GasLimitExceededException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (BlueLanguageErrorClassifier.classify(exception)
                    == BlueLanguageErrorCategory.ProviderUnavailable) {
                throw ExternalEvidenceVerificationSupport.unavailable(
                        "External subscription surface acquisition failed: "
                                + ProcessorEngine.deterministicMessage(
                                exception, "provider unavailable"),
                        ExternalEvidenceVerificationSupport.referencedBlueIds(
                                root, event));
            }
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External subscription surface verification failed: "
                            + ProcessorEngine.deterministicMessage(
                            exception, "invalid subscription surface"));
        }

        occurrences.sort(CANONICAL_ORDER);
        List<ExternalDeliverySnapshot> deliveries = new ArrayList<>();
        List<IndexedDeliveryDiagnostic> diagnostics = new ArrayList<>();
        List<ExternalSubscriptionOccurrenceKey> candidates =
                new ArrayList<>();
        for (EvaluatedOccurrence occurrence : occurrences) {
            diagnostics.add(occurrence.diagnostic);
            if (occurrence.diagnostic.physicalCandidate()) {
                candidates.add(occurrence.diagnostic.occurrenceKey());
            }
            if (occurrence.delivery != null) {
                deliveries.add(occurrence.delivery);
            }
        }
        return new EvaluationResult(
                deliveries, diagnostics, candidates);
    }

    /** Proves a host-supplied active interval surface against the exact Root. */
    void verifyCompleteActiveSurface(
            Node root,
            List<SubscriptionDelta.Entry> activeIntervals) {
        Set<ExternalSubscriptionOccurrenceKey> supplied =
                new LinkedHashSet<>();
        for (SubscriptionDelta.Entry interval : activeIntervals) {
            SubscriptionDelta.Entry exactInterval =
                    Objects.requireNonNull(
                            interval, "active subscription interval");
            ExternalSubscriptionOccurrenceKey occurrence =
                    ExternalSubscriptionOccurrenceKey.of(
                            exactInterval.scopePath(),
                            exactInterval.channelKey());
            if (!supplied.add(occurrence)) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "Duplicate retained External Channel occurrence at "
                                + occurrence);
            }
        }

        Set<ExternalSubscriptionOccurrenceKey> exact =
                exactExternalOccurrences(root);
        if (exact.equals(supplied)) {
            return;
        }
        Set<ExternalSubscriptionOccurrenceKey> omitted =
                new LinkedHashSet<>(exact);
        omitted.removeAll(supplied);
        Set<ExternalSubscriptionOccurrenceKey> extra =
                new LinkedHashSet<>(supplied);
        extra.removeAll(exact);
        throw ExternalEvidenceVerificationSupport.invalid(
                "Retained active External Channel surface does not match the "
                        + "exact Root (omitted=" + omitted.size()
                        + ", extra=" + extra.size() + ")");
    }

    private void verifyScope(
            ExternalDeliveryResolution resolution,
            String scopePath,
            String channelKey,
            Node selected,
            Node effective) {
        if (selected == null || effective == null) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Retained active subscription scope is absent: "
                            + scopePath);
        }
        if (!ExternalEvidenceVerificationSupport.isValidScope(
                scopePath, selected)
                || !ExternalEvidenceVerificationSupport.isValidScope(
                scopePath, effective)) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Process Embedded scope is not an object: "
                            + scopePath);
        }
        if (ExternalEvidenceVerificationSupport
                .hasDirectTerminatedMarker(selected)) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Retained active subscription is under a direct "
                            + "terminated scope: " + scopePath + "/"
                            + channelKey);
        }
        if (!reachableScope(resolution, scopePath)) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Retained active subscription scope is not reachable "
                            + "through Process Embedded: " + scopePath);
        }
    }

    private void verifyEvaluatedDeliveries(
            List<ExternalDeliverySnapshot> actualDeliveries,
            EvaluationResult evaluated) {
        Map<ExternalSubscriptionOccurrenceKey, ExternalDeliverySnapshot>
                expectedByOccurrence = new LinkedHashMap<>();
        for (ExternalDeliverySnapshot expected : evaluated.deliveries()) {
            expectedByOccurrence.put(
                    ExternalSubscriptionOccurrenceKey.of(
                            expected.scopePath(), expected.channelKey()),
                    expected);
        }
        Map<ExternalSubscriptionOccurrenceKey, ExternalDeliverySnapshot>
                actualByOccurrence = new LinkedHashMap<>();
        for (ExternalDeliverySnapshot actual : actualDeliveries) {
            ExternalSubscriptionOccurrenceKey key =
                    ExternalSubscriptionOccurrenceKey.of(
                            actual.scopePath(), actual.channelKey());
            if (actualByOccurrence.put(key, actual) != null) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "Duplicate External Channel occurrence at " + key);
            }
        }
        for (IndexedDeliveryDiagnostic diagnostic
                : evaluated.diagnostics()) {
            ExternalSubscriptionOccurrenceKey key =
                    diagnostic.occurrenceKey();
            ExternalDeliverySnapshot expected =
                    expectedByOccurrence.get(key);
            ExternalDeliverySnapshot actual =
                    actualByOccurrence.remove(key);
            if ((expected != null) != (actual != null)) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        expected != null
                                ? "External delivery plan omitted a true "
                                + "preselection at " + key
                                : "External delivery plan contains an inactive "
                                + "or false preselection at " + key);
            }
            if (actual != null) {
                verifyDerivedDelivery(expected, actual);
            }
        }
        if (!actualByOccurrence.isEmpty()) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery plan contains an occurrence outside "
                            + "the retained active subscription surface");
        }
        ExternalDeliveryPlanVerifier.verifyExactDeliveries(
                actualDeliveries, evaluated.deliveries());
    }

    /** Rejects any value-level disagreement between two complete evaluations. */
    void verifyExactEvaluation(
            EvaluationResult expected,
            EvaluationResult actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        if (!expected.candidates().equals(actual.candidates())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Indexed physical candidate set changed during independent "
                            + "verification");
        }
        ExternalDeliveryPlanVerifier.verifyExactDeliveries(
                actual.deliveries(), expected.deliveries());
        if (expected.diagnostics().size()
                != actual.diagnostics().size()) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Indexed delivery diagnostic occurrence set changed during "
                            + "independent verification");
        }
        for (int index = 0;
             index < expected.diagnostics().size();
             index++) {
            if (!sameDiagnostic(
                    expected.diagnostics().get(index),
                    actual.diagnostics().get(index))) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "Indexed delivery diagnostic changed during independent "
                                + "verification at index " + index);
            }
        }
    }

    private boolean sameDiagnostic(
            IndexedDeliveryDiagnostic left,
            IndexedDeliveryDiagnostic right) {
        return left.occurrenceKey().equals(right.occurrenceKey())
                && left.eligibleAtEvent() == right.eligibleAtEvent()
                && left.physicalCandidate() == right.physicalCandidate()
                && left.preselects() == right.preselects()
                && left.accepts() == right.accepts()
                && left.channelKeys().equals(right.channelKeys())
                && left.eventKeys().equals(right.eventKeys())
                && left.dependencies().equals(right.dependencies())
                && left.checkpointDomainBlueId().equals(
                right.checkpointDomainBlueId())
                && Objects.equals(
                left.checkpointSubjectBlueId(),
                right.checkpointSubjectBlueId())
                && Objects.equals(
                left.payloadBlueId(), right.payloadBlueId())
                && Objects.equals(
                left.handlerChannelKey(), right.handlerChannelKey())
                && Objects.equals(
                left.logicalDeliveryKey(), right.logicalDeliveryKey());
    }

    private void verifyDerivedDelivery(
            ExternalDeliverySnapshot expected,
            ExternalDeliverySnapshot actual) {
        String location = actual.scopePath() + "/" + actual.channelKey();
        if (!expected.subscriptionKeys().equals(
                actual.subscriptionKeys())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery subscription keys mismatch at "
                            + location);
        }
        if (!expected.checkpointDomainBlueId().equals(
                actual.checkpointDomainBlueId())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery checkpoint domain mismatch at "
                            + location);
        }
        if (!expected.checkpointSubjectBlueId().equals(
                actual.checkpointSubjectBlueId())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery checkpoint subject mismatch at "
                            + location);
        }
        if (!Objects.equals(
                expected.activationStartExclusive(),
                actual.activationStartExclusive())
                || !Objects.equals(
                expected.activationEndInclusive(),
                actual.activationEndInclusive())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery activation interval mismatch at "
                            + location);
        }
        if (!expected.effectiveTypeBlueId().equals(
                actual.effectiveTypeBlueId())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery effective type mismatch at "
                            + location);
        }
        if (expected.order() != actual.order()) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery order mismatch at " + location);
        }
        if (!expected.sourceContributionNodeBlueIds().equals(
                actual.sourceContributionNodeBlueIds())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery ordered Source contributions mismatch at "
                            + location);
        }
    }

    private void verifySubscriptionLaws(
            ExternalSubscriptionEvaluation evaluation,
            String scopePath,
            String channelKey) {
        if (evaluation.accepts && !evaluation.preselects) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External subscription law violated "
                            + "(ACCEPTS => PRESELECTS) at "
                            + scopePath + "/" + channelKey);
        }
        if (evaluation.preselects
                && !selection.intersects(
                evaluation.channelKeys, evaluation.eventKeys)) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External subscription law violated "
                            + "(PRESELECTS => key intersection) at "
                            + scopePath + "/" + channelKey);
        }
    }

    private String checkpointSubject(
            ExternalSubscriptionEvaluation evaluation,
            String eventBlueId,
            String scopePath,
            String channelKey) {
        if (evaluation.accepts) {
            if (evaluation.checkpointSubjectBlueId == null
                    || evaluation.checkpointSubjectBlueId.isEmpty()) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "Accepted External Channel has no checkpoint subject at "
                                + scopePath + "/" + channelKey);
            }
            return evaluation.checkpointSubjectBlueId;
        }
        return evaluation.preselects ? eventBlueId : null;
    }

    private ExternalDeliverySnapshot delivery(
            EffectiveContractSnapshot snapshot,
            SubscriptionDelta.Entry interval,
            ExternalSubscriptionEvaluation evaluation,
            String scopePath,
            String checkpointSubjectBlueId) {
        ExternalDeliverySnapshot.Builder builder =
                ExternalDeliverySnapshot.builder(
                                scopePath, snapshot.key())
                        .order(snapshot.order())
                        .effectiveTypeBlueId(
                                snapshot.effectiveTypeBlueId())
                        .checkpointDomainBlueId(
                                evaluation.checkpointDomainBlueId)
                        .checkpointSubjectBlueId(
                                checkpointSubjectBlueId)
                        .activationStartExclusive(
                                interval.startAfterExternalOrderKey())
                        .activationEndInclusive(null);
        for (String contribution
                : snapshot.sourceContributionNodeBlueIds()) {
            builder.sourceContribution(contribution);
        }
        for (String key : evaluation.channelKeys) {
            builder.subscriptionKey(key);
        }
        return builder.build();
    }

    private void verifyActiveInterval(
            EffectiveContractSnapshot snapshot,
            SubscriptionDelta.Entry interval,
            ExternalSubscriptionEvaluation evaluation,
            String scopePath,
            long indexedRootRevision) {
        if (!scopePath.equals(interval.scopePath())
                || !snapshot.key().equals(interval.channelKey())
                || !snapshot.effectiveTypeBlueId().equals(
                interval.effectiveTypeBlueId())
                || !snapshot.sourceContributionNodeBlueIds().equals(
                interval.sourceContributionNodeBlueIds())
                || snapshot.order() != interval.order()
                || !evaluation.channelKeys.equals(
                interval.subscriptionKeys())
                || !evaluation.checkpointDomainBlueId.equals(
                interval.checkpointDomainBlueId())
                || !evaluation.dependencies.equals(
                interval.dependencies())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Retained active subscription interval header mismatch "
                            + "at " + scopePath + "/" + snapshot.key());
        }
        if (interval.activationRootRevision() == null
                || interval.activationRootRevision() > indexedRootRevision
                || interval.endAtRootRevision() != null) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Retained subscription interval is not active at indexed "
                            + "Root revision " + indexedRootRevision + " at "
                            + scopePath + "/" + snapshot.key());
        }
    }

    private boolean reachableScope(
            ExternalDeliveryResolution resolution,
            String targetPath) {
        String target = PointerUtils.normalizeScope(targetPath);
        String current = JsonPointer.ROOT;
        Set<String> visited = new LinkedHashSet<>();
        while (!current.equals(target)) {
            if (!visited.add(current)) {
                return false;
            }
            Node selected = resolution.selectedNodeAt(current);
            if (ExternalEvidenceVerificationSupport
                    .hasDirectTerminatedMarker(selected)) {
                return false;
            }
            ContractBundle bundle = resolution.subscriptionBundleAt(
                    current, (String) null, true);
            EmbeddedScopePlan embeddedPlan =
                    resolution.embeddedScopePlanAt(current, bundle);
            String selectedChild = null;
            int selectedDepth = -1;
            for (String candidate : embeddedPlan != null
                    ? embeddedPlan.concreteChildPaths()
                    : Collections.<String>emptyList()) {
                if (candidate.equals(current)
                        || !PointerUtils.descendantOrEqual(
                        target, candidate)) {
                    continue;
                }
                int depth = ExternalEvidenceVerificationSupport.depth(
                        candidate);
                if (depth > selectedDepth) {
                    selectedChild = candidate;
                    selectedDepth = depth;
                } else if (depth == selectedDepth
                        && !candidate.equals(selectedChild)) {
                    throw ExternalEvidenceVerificationSupport.invalid(
                            "Ambiguous Process Embedded route to " + target);
                }
            }
            if (selectedChild == null) {
                return false;
            }
            current = selectedChild;
        }
        return true;
    }

    private RuntimeWorkSessionFactory defaultRuntimeWorkSessions() {
        return () -> new RuntimeWorkSession(
                new GasMeter(), RuntimeWorkSession.Mode.ADMISSION);
    }

    /** Immutable products of one complete surface evaluation. */
    static final class EvaluationResult {
        private final List<ExternalDeliverySnapshot> deliveries;
        private final List<IndexedDeliveryDiagnostic> diagnostics;
        private final List<ExternalSubscriptionOccurrenceKey> candidates;

        private EvaluationResult(
                List<ExternalDeliverySnapshot> deliveries,
                List<IndexedDeliveryDiagnostic> diagnostics,
                List<ExternalSubscriptionOccurrenceKey> candidates) {
            this.deliveries = immutable(deliveries);
            this.diagnostics = immutable(diagnostics);
            this.candidates = immutable(candidates);
        }

        List<ExternalDeliverySnapshot> deliveries() {
            return deliveries;
        }

        List<IndexedDeliveryDiagnostic> diagnostics() {
            return diagnostics;
        }

        List<ExternalSubscriptionOccurrenceKey> candidates() {
            return candidates;
        }

        private static <T> List<T> immutable(List<T> values) {
            return Collections.unmodifiableList(
                    new ArrayList<>(values));
        }
    }

    /** Evaluation metadata retained until canonical ordering is established. */
    private static final class EvaluatedOccurrence {
        private final String scopePath;
        private final String channelKey;
        private final int order;
        private final String effectiveTypeBlueId;
        private final IndexedDeliveryDiagnostic diagnostic;
        private final ExternalDeliverySnapshot delivery;

        private EvaluatedOccurrence(
                String scopePath,
                String channelKey,
                int order,
                String effectiveTypeBlueId,
                IndexedDeliveryDiagnostic diagnostic,
                ExternalDeliverySnapshot delivery) {
            this.scopePath = scopePath;
            this.channelKey = channelKey;
            this.order = order;
            this.effectiveTypeBlueId = effectiveTypeBlueId;
            this.diagnostic = diagnostic;
            this.delivery = delivery;
        }
    }
}
