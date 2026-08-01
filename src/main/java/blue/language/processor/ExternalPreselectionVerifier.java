package blue.language.processor;

import blue.language.BlueLanguageErrorCategory;
import blue.language.BlueLanguageErrorClassifier;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Independently re-evaluates the retained external subscription surface. */
final class ExternalPreselectionVerifier {

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
                        throw ExternalEvidenceVerificationSupport.unavailable(
                                "Exact external delivery subscription and "
                                        + "activation state is unavailable",
                                ExternalEvidenceVerificationSupport
                                        .referencedBlueIds(root));
                    }
                }
                for (String embedded : bundle.embeddedPaths()) {
                    String child = PointerUtils.resolvePointer(
                            scopePath, embedded);
                    if (child.equals(scopePath)
                            || !PointerUtils.descendantOrEqual(
                            child, scopePath)) {
                        throw ExternalEvidenceVerificationSupport.invalid(
                                "Process Embedded path escapes its scope at "
                                        + scopePath + ": " + embedded);
                    }
                    if (visited.contains(child) || pending.contains(child)) {
                        throw ExternalEvidenceVerificationSupport.invalid(
                                "Ambiguous Process Embedded scope: " + child);
                    }
                    pending.addLast(child);
                }
            }
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

    void verify(
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence) {
        if (!selection.configured()) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Registered External Channel subscription functions are "
                            + "unavailable");
        }
        if (!evidence.hasActiveSubscriptionIntervals()) {
            throw ExternalEvidenceVerificationSupport.unavailable(
                    "Complete retained external subscription and activation "
                            + "evidence is unavailable",
                    ExternalEvidenceVerificationSupport.referencedBlueIds(
                            root, event));
        }
        Map<String, ExternalDeliverySnapshot> remaining =
                new LinkedHashMap<>();
        for (ExternalDeliverySnapshot delivery : evidence.deliveries()) {
            remaining.put(
                    ExternalEvidenceVerificationSupport.occurrenceKey(
                            delivery.scopePath(), delivery.channelKey()),
                    delivery);
        }
        ExternalSubscriptionProjection projected =
                projectionBuilder.subscriptionIndexProjection(
                        root, evidence.activeSubscriptionIntervals());
        try (ExternalDeliveryResolution resolution =
                     projectionBuilder.subscriptionResolution(projected)) {
            for (SubscriptionDelta.Entry activeInterval
                    : evidence.activeSubscriptionIntervals()) {
                String scopePath = PointerUtils.normalizeScope(
                        activeInterval.scopePath());
                Node selected = resolution.selectedNodeAt(scopePath);
                Node effective = resolution.effectiveNodeAt(scopePath);
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
                                    + activeInterval.channelKey());
                }
                if (!reachableScope(resolution, scopePath)) {
                    throw ExternalEvidenceVerificationSupport.invalid(
                            "Retained active subscription scope is not "
                                    + "reachable through Process Embedded: "
                                    + scopePath);
                }
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
                ExternalSubscriptionEvaluation evaluation =
                        selection.evaluate(
                                bundle,
                                snapshot,
                                event,
                                activeInterval.dependencies()
                                        .wholeSameScopeChannelCatalog()
                                        ? projected.contractKeys(scopePath)
                                        : null);
                if (evaluation.accepts && !evaluation.preselects) {
                    throw ExternalEvidenceVerificationSupport.invalid(
                            "External subscription law violated "
                                    + "(ACCEPTS => PRESELECTS) at "
                                    + scopePath + "/" + snapshot.key());
                }
                if (evaluation.preselects
                        && !selection.intersects(
                        evaluation.channelKeys, evaluation.eventKeys)) {
                    throw ExternalEvidenceVerificationSupport.invalid(
                            "External subscription law violated "
                                    + "(PRESELECTS => key intersection) at "
                                    + scopePath + "/" + snapshot.key());
                }
                verifyActiveInterval(
                        snapshot,
                        activeInterval,
                        evaluation,
                        scopePath,
                        evidence.indexedRootRevision());
                String key =
                        ExternalEvidenceVerificationSupport.occurrenceKey(
                                scopePath, snapshot.key());
                ExternalDeliverySnapshot delivery = remaining.remove(key);
                boolean eligibleAtEvent =
                        activeInterval.startAfterExternalOrderKey() == null
                                || evidence.eventOrderKey().compareTo(
                                activeInterval
                                        .startAfterExternalOrderKey()) > 0;
                boolean expected = eligibleAtEvent && evaluation.preselects;
                if (expected != (delivery != null)) {
                    throw ExternalEvidenceVerificationSupport.invalid(
                            expected
                                    ? "External delivery plan omitted a true "
                                    + "preselection at " + scopePath + "/"
                                    + snapshot.key()
                                    : "External delivery plan contains an "
                                    + "inactive or false preselection at "
                                    + scopePath + "/" + snapshot.key());
                }
                if (delivery != null) {
                    verifySubscriptionHeader(
                            snapshot, delivery, evaluation, scopePath);
                    verifyDeliveryActivation(activeInterval, delivery);
                    verifyDelivery(resolution, delivery, activeInterval);
                }
            }
        } catch (ExecutionEvidenceUnavailableException exception) {
            throw exception;
        } catch (InvalidExecutionEvidenceException exception) {
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
        if (!remaining.isEmpty()) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery plan contains an occurrence outside "
                            + "the retained active subscription surface");
        }
    }

    private void verifySubscriptionHeader(
            EffectiveContractSnapshot snapshot,
            ExternalDeliverySnapshot delivery,
            ExternalSubscriptionEvaluation evaluation,
            String scopePath) {
        if (!evaluation.channelKeys.equals(delivery.subscriptionKeys())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery subscription keys mismatch at "
                            + scopePath + "/" + snapshot.key());
        }
        if (!evaluation.checkpointDomainBlueId.equals(
                delivery.checkpointDomainBlueId())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery checkpoint domain mismatch at "
                            + scopePath + "/" + snapshot.key());
        }
        if (evaluation.accepts
                && !evaluation.checkpointSubjectBlueId.equals(
                delivery.checkpointSubjectBlueId())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery checkpoint subject mismatch at "
                            + scopePath + "/" + snapshot.key());
        }
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

    private void verifyDeliveryActivation(
            SubscriptionDelta.Entry interval,
            ExternalDeliverySnapshot delivery) {
        if (!Objects.equals(
                interval.startAfterExternalOrderKey(),
                delivery.activationStartExclusive())
                || delivery.activationEndInclusive() != null) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery activation interval mismatch at "
                            + delivery.scopePath() + "/"
                            + delivery.channelKey());
        }
    }

    private void verifyDelivery(
            ExternalDeliveryResolution resolution,
            ExternalDeliverySnapshot delivery,
            SubscriptionDelta.Entry interval) {
        if (!reachableScope(resolution, delivery.scopePath())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery scope is not reachable through the "
                            + "effective Process Embedded surface: "
                            + delivery.scopePath());
        }
        Node selectedScope = resolution.selectedNodeAt(
                delivery.scopePath());
        Node effectiveScope = resolution.effectiveNodeAt(
                delivery.scopePath());
        if (!ExternalEvidenceVerificationSupport.isValidScope(
                delivery.scopePath(), selectedScope)
                || !ExternalEvidenceVerificationSupport.isValidScope(
                delivery.scopePath(), effectiveScope)) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery scope is absent or not an object: "
                            + delivery.scopePath());
        }
        if (ExternalEvidenceVerificationSupport
                .hasDirectTerminatedMarker(selectedScope)) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery scope is directly terminated: "
                            + delivery.scopePath());
        }
        Map<String, String> selectorTypes =
                selection.hasEnumerationSelector(interval)
                        ? projectionBuilder.selectorEffectiveContractTypes(
                        resolution, delivery.scopePath())
                        : null;
        ContractBundle bundle = resolution.subscriptionBundleAt(
                delivery.scopePath(),
                selection.subscriptionContractKeys(
                        interval, selectorTypes),
                false);
        EffectiveContractSnapshot contract =
                bundle.effectiveContractSnapshot(delivery.channelKey());
        if (contract == null
                || !EffectiveContractSnapshotConstants
                .Role.EXTERNAL_CHANNEL.equals(contract.role())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery channel is absent or not external at "
                            + delivery.scopePath() + "/"
                            + delivery.channelKey());
        }
        if (!delivery.effectiveTypeBlueId().equals(
                contract.effectiveTypeBlueId())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery effective type mismatch at "
                            + delivery.scopePath() + "/"
                            + delivery.channelKey());
        }
        if (delivery.order() != contract.order()) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery order mismatch at "
                            + delivery.scopePath() + "/"
                            + delivery.channelKey());
        }
        if (!delivery.sourceContributionNodeBlueIds().equals(
                contract.sourceContributionNodeBlueIds())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery ordered Source contributions mismatch at "
                            + delivery.scopePath() + "/"
                            + delivery.channelKey());
        }
        FrozenNode effectiveContract =
                bundle.contractNode(delivery.channelKey());
        if (effectiveContract == null) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery effective contract content is absent at "
                            + delivery.scopePath() + "/"
                            + delivery.channelKey());
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
            String selectedChild = null;
            int selectedDepth = -1;
            for (String embedded : bundle.embeddedPaths()) {
                String candidate = PointerUtils.resolvePointer(
                        current, embedded);
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
}
