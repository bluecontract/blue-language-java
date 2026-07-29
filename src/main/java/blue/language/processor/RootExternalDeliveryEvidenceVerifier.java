package blue.language.processor;

import blue.language.BlueLanguageErrorCategory;
import blue.language.BlueLanguageErrorClassifier;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.JsonPointer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Complete core verifier for revision-bound External Channel preselection.
 *
 * <p>The environmental deriver establishes the exact occurrence set,
 * checkpoint subjects, and activation intervals.  This verifier independently
 * resolves the effective Contracts surface and binds every occurrence to its
 * ordered Source contributions, type, order, subscription keys, and checkpoint
 * domain.</p>
 */
public final class RootExternalDeliveryEvidenceVerifier
        implements ExternalDeliveryEvidenceVerifier {

    /**
     * Standalone verification has no provider/resolver or environmental
     * subscription state and therefore fails closed.
     */
    public static final RootExternalDeliveryEvidenceVerifier INSTANCE =
            new RootExternalDeliveryEvidenceVerifier(
                    null,
                    null,
                    null,
                    null,
                    ExternalDeliveryPlanDeriver.unavailable());

    private final ContractLoader contractLoader;
    private final ProcessingSnapshotManager snapshotManager;
    private final ContractProcessorRegistry registry;
    private final NodeToObjectConverter converter;
    private final ExternalDeliveryPlanDeriver planDeriver;

    private RootExternalDeliveryEvidenceVerifier(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            ExternalDeliveryPlanDeriver planDeriver) {
        this.contractLoader = contractLoader;
        this.snapshotManager = snapshotManager;
        this.registry = registry;
        this.converter = converter;
        this.planDeriver = Objects.requireNonNull(
                planDeriver, "planDeriver");
    }

    static RootExternalDeliveryEvidenceVerifier configured(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            ExternalDeliveryPlanDeriver planDeriver) {
        return new RootExternalDeliveryEvidenceVerifier(
                Objects.requireNonNull(contractLoader, "contractLoader"),
                snapshotManager,
                Objects.requireNonNull(registry, "registry"),
                Objects.requireNonNull(converter, "converter"),
                Objects.requireNonNull(planDeriver, "planDeriver"));
    }

    VerifiedExecutionEvidence deriveAndVerify(
            Node root,
            Node event,
            String runtimeRegistryIdentity) {
        ExternalDeliveryPlan plan = derivePlan(root, event);
        VerifiedExecutionEvidence evidence =
                plan.bind(root, event, runtimeRegistryIdentity);
        evidence.revalidateDerived(
                root,
                event,
                runtimeRegistryIdentity,
                this,
                plan);
        return evidence;
    }

    @Override
    public void verify(Node root,
                       Node event,
                       VerifiedExecutionEvidence evidence) {
        verifyAgainstPlan(
                root,
                event,
                evidence,
                derivePlan(root, event));
    }

    @Override
    public void verifyDerived(Node root,
                              Node event,
                              VerifiedExecutionEvidence evidence,
                              ExternalDeliveryPlan derivedPlan) {
        verifyAgainstPlan(root, event, evidence, derivedPlan);
    }

    ExternalDeliveryPlan derivePlan(Node root, Node event) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(event, "event");
        try {
            ExternalDeliveryPlan plan;
            if (planDeriver == ExternalDeliveryPlanDeriver.UNAVAILABLE) {
                plan = deriveProvablyEmptyPlan(root);
            } else {
                plan = planDeriver.derive(root.clone(), event.clone());
            }
            if (plan == null) {
                throw invalid(
                        "External delivery plan deriver returned no plan");
            }
            if (!plan.exactRuntimeState()) {
                throw invalid(
                        "External delivery plan is not certified complete");
            }
            return plan;
        } catch (ExecutionEvidenceUnavailableException exception) {
            throw exception;
        } catch (InvalidExecutionEvidenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (BlueLanguageErrorClassifier.classify(exception)
                    == BlueLanguageErrorCategory.ProviderUnavailable) {
                throw unavailable(
                        "External delivery plan acquisition failed: "
                                + ProcessorEngine.deterministicMessage(
                                exception, "provider unavailable"),
                        referencedBlueIds(root, event));
            }
            throw invalid("External delivery plan derivation failed: "
                    + ProcessorEngine.deterministicMessage(
                    exception, "environmental state unavailable"));
        }
    }

    /**
     * The default can prove only a genuinely empty effective External Channel
     * surface.  It never guesses subscription or activation state.
     */
    private ExternalDeliveryPlan deriveProvablyEmptyPlan(Node root) {
        try (Resolution resolution = resolution(root)) {
            Deque<String> pending = new ArrayDeque<>();
            Set<String> visited = new LinkedHashSet<>();
            pending.add(JsonPointer.ROOT);
            while (!pending.isEmpty()) {
                String scopePath = pending.removeFirst();
                if (!visited.add(scopePath)) {
                    throw invalid(
                            "Process Embedded surface contains a repeated scope: "
                                    + scopePath);
                }
                Node selectedScope = resolution.selectedNodeAt(scopePath);
                Node effectiveScope = resolution.effectiveNodeAt(scopePath);
                if (!isValidScope(
                        scopePath, selectedScope)
                        || !isValidScope(
                        scopePath, effectiveScope)) {
                    throw invalid(
                            "Process Embedded scope is absent or not an object: "
                                    + scopePath);
                }
                if (hasDirectTerminatedMarker(selectedScope)) {
                    continue;
                }
                ContractBundle bundle =
                        resolution.subscriptionBundleAt(scopePath);
                for (EffectiveContractSnapshot snapshot
                        : bundle.effectiveContractSnapshots()) {
                    if (EffectiveContractSnapshotConstants
                            .Role.EXTERNAL_CHANNEL.equals(
                            snapshot.role())) {
                        throw unavailable(
                                "Exact external delivery subscription and "
                                        + "activation state is unavailable",
                                referencedBlueIds(root));
                    }
                }
                for (String embedded : bundle.embeddedPaths()) {
                    String child = PointerUtils.resolvePointer(
                            scopePath, embedded);
                    if (child.equals(scopePath)
                            || !PointerUtils.descendantOrEqual(
                            child, scopePath)) {
                        throw invalid(
                                "Process Embedded path escapes its scope at "
                                        + scopePath + ": " + embedded);
                    }
                    if (visited.contains(child) || pending.contains(child)) {
                        throw invalid(
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

    private void verifyAgainstPlan(
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence,
            ExternalDeliveryPlan plan) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(plan, "plan");
        if (!plan.exactRuntimeState()) {
            throw invalid(
                    "External delivery plan is not certified complete");
        }
        if (evidence.managedRootRevision()
                != plan.managedRootRevision()
                || evidence.indexedRootRevision()
                != plan.indexedRootRevision()) {
            throw invalid(
                    "External delivery plan revision mismatch");
        }
        if (!evidence.eventOrderKey().equals(
                plan.eventOrderKey())) {
            throw invalid(
                    "External delivery event order mismatch");
        }
        if (!evidence.availableExactNodeBlueIds().equals(
                plan.availableExactNodeBlueIds())
                || !evidence.requiredExactNodeBlueIds().equals(
                plan.requiredExactNodeBlueIds())) {
            throw invalid(
                    "External delivery resource closure mismatch");
        }
        if (evidence.hasActiveSubscriptionIntervals()
                != plan.hasActiveSubscriptionIntervals()
                || !evidence.activeSubscriptionIntervals().equals(
                plan.activeSubscriptionIntervals())) {
            throw invalid(
                    "External delivery active subscription interval "
                            + "surface mismatch");
        }
        verifyExactDeliveries(
                evidence.deliveries(), plan.deliveries());

        /*
         * A deriver's "exact" bit is only a claim. The retained,
         * revision-complete active index is the independent completeness
         * companion; re-run registered PRESELECTS/ACCEPTS only for those exact
         * indexed occurrences.
         */
        verifyCompletePreselection(root, event, evidence);
    }

    private void verifyCompletePreselection(
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence) {
        if (registry == null || converter == null) {
            throw invalid(
                    "Registered External Channel subscription functions are "
                            + "unavailable");
        }
        if (!evidence.hasActiveSubscriptionIntervals()) {
            throw unavailable(
                    "Complete retained external subscription and activation "
                            + "evidence is unavailable",
                    referencedBlueIds(root, event));
        }
        Map<String, ExternalDeliverySnapshot> remaining =
                new LinkedHashMap<>();
        for (ExternalDeliverySnapshot delivery
                : evidence.deliveries()) {
            remaining.put(occurrenceKey(
                    delivery.scopePath(), delivery.channelKey()), delivery);
        }
        SubscriptionIndexProjection projected =
                subscriptionIndexProjection(
                root, evidence.activeSubscriptionIntervals());
        try (Resolution resolution =
                     subscriptionResolution(projected)) {
            for (SubscriptionDelta.Entry activeInterval
                    : evidence.activeSubscriptionIntervals()) {
                String scopePath = PointerUtils.normalizeScope(
                        activeInterval.scopePath());
                Node selected = resolution.selectedNodeAt(scopePath);
                Node effective = resolution.effectiveNodeAt(scopePath);
                if (selected == null || effective == null) {
                    throw invalid(
                            "Retained active subscription scope is absent: "
                                    + scopePath);
                }
                if (!isValidScope(scopePath, selected)
                        || !isValidScope(scopePath, effective)) {
                    throw invalid(
                            "Process Embedded scope is not an object: "
                                    + scopePath);
                }
                if (hasDirectTerminatedMarker(selected)) {
                    throw invalid(
                            "Retained active subscription is under a direct "
                                    + "terminated scope: " + scopePath + "/"
                                    + activeInterval.channelKey());
                }
                if (!reachableScope(resolution, scopePath)) {
                    throw invalid(
                            "Retained active subscription scope is not "
                                    + "reachable through Process Embedded: "
                                    + scopePath);
                }
                Map<String, String> selectorTypes =
                        hasEnumerationSelector(activeInterval)
                                ? projected.selectorTypes(
                                scopePath)
                                : null;
                ContractBundle bundle =
                        resolution.subscriptionBundleAt(
                                scopePath,
                                subscriptionContractKeys(
                                        activeInterval,
                                        selectorTypes),
                                false);
                EffectiveContractSnapshot snapshot =
                        bundle.effectiveContractSnapshot(
                                activeInterval.channelKey());
                if (snapshot == null
                        || !EffectiveContractSnapshotConstants
                        .Role.EXTERNAL_CHANNEL.equals(
                        snapshot.role())) {
                    throw invalid(
                            "Retained active subscription channel is absent "
                                    + "or not external at " + scopePath + "/"
                                    + activeInterval.channelKey());
                }
                SubscriptionEvaluation evaluation =
                        evaluateSubscription(
                                bundle,
                                snapshot,
                                event,
                                activeInterval.dependencies()
                                        .wholeSameScopeChannelCatalog()
                                        ? projected.contractKeys(
                                        scopePath)
                                        : null);
                if (evaluation.accepts
                        && !evaluation.preselects) {
                    throw invalid(
                            "External subscription law violated "
                                    + "(ACCEPTS => PRESELECTS) at "
                                    + scopePath + "/"
                                    + snapshot.key());
                }
                if (evaluation.preselects
                        && !intersects(
                        evaluation.channelKeys,
                        evaluation.eventKeys)) {
                    throw invalid(
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
                String key = occurrenceKey(
                        scopePath, snapshot.key());
                ExternalDeliverySnapshot delivery =
                        remaining.remove(key);
                boolean eligibleAtEvent =
                        activeInterval.startAfterExternalOrderKey() == null
                                || evidence.eventOrderKey().compareTo(
                                activeInterval
                                        .startAfterExternalOrderKey()) > 0;
                boolean expected =
                        eligibleAtEvent && evaluation.preselects;
                if (expected != (delivery != null)) {
                    throw invalid(
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
                            snapshot,
                            delivery,
                            evaluation,
                            scopePath);
                    verifyDeliveryActivation(
                            activeInterval, delivery);
                    verifyDelivery(
                            resolution,
                            delivery,
                            activeInterval);
                }
            }
        } catch (ExecutionEvidenceUnavailableException exception) {
            throw exception;
        } catch (InvalidExecutionEvidenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (BlueLanguageErrorClassifier.classify(exception)
                    == BlueLanguageErrorCategory.ProviderUnavailable) {
                throw unavailable(
                        "External subscription surface acquisition failed: "
                                + ProcessorEngine.deterministicMessage(
                                exception, "provider unavailable"),
                        referencedBlueIds(root, event));
            }
            throw invalid(
                    "External subscription surface verification failed: "
                            + ProcessorEngine.deterministicMessage(
                            exception, "invalid subscription surface"));
        }
        if (!remaining.isEmpty()) {
            throw invalid(
                    "External delivery plan contains an occurrence outside "
                            + "the retained active subscription surface");
        }
    }

    private SubscriptionEvaluation evaluateSubscription(
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot,
            Node event) {
        return evaluateSubscription(
                bundle,
                snapshot,
                event,
                null);
    }

    private SubscriptionEvaluation evaluateSubscription(
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot,
            Node event,
            List<String> effectiveContractKeys) {
        ExternalChannelFunctionEvaluation evaluation =
                ExternalChannelFunctionEvaluation.evaluate(
                        registry,
                        converter,
                        ExternalChannelFunctionEvaluation
                                .verifiedMatcherSessions(
                                        snapshotManager),
                        bundle,
                        snapshot,
                        event,
                        effectiveContractKeys);
        return new SubscriptionEvaluation(
                evaluation.channelKeys(),
                evaluation.eventKeys(),
                evaluation.preselects(),
                evaluation.accepts(),
                evaluation.checkpointDomainBlueId(),
                evaluation.checkpointSubjectBlueId(),
                evaluation.dependencies());
    }

    private boolean intersects(
            List<String> left,
            List<String> right) {
        Set<String> rightSet = new LinkedHashSet<>(right);
        for (String value : left) {
            if (rightSet.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private void verifySubscriptionHeader(
            EffectiveContractSnapshot snapshot,
            ExternalDeliverySnapshot delivery,
            SubscriptionEvaluation evaluation,
            String scopePath) {
        if (!evaluation.channelKeys.equals(
                delivery.subscriptionKeys())) {
            throw invalid(
                    "External delivery subscription keys mismatch at "
                            + scopePath + "/" + snapshot.key());
        }
        if (!evaluation.checkpointDomainBlueId.equals(
                delivery.checkpointDomainBlueId())) {
            throw invalid(
                    "External delivery checkpoint domain mismatch at "
                            + scopePath + "/" + snapshot.key());
        }
        if (evaluation.accepts
                && !evaluation.checkpointSubjectBlueId.equals(
                delivery.checkpointSubjectBlueId())) {
            throw invalid(
                    "External delivery checkpoint subject mismatch at "
                            + scopePath + "/" + snapshot.key());
        }
    }

    private void verifyActiveInterval(
            EffectiveContractSnapshot snapshot,
            SubscriptionDelta.Entry interval,
            SubscriptionEvaluation evaluation,
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
            throw invalid(
                    "Retained active subscription interval header mismatch "
                            + "at " + scopePath + "/" + snapshot.key());
        }
        if (interval.activationRootRevision() == null
                || interval.activationRootRevision()
                > indexedRootRevision
                || interval.endAtRootRevision() != null) {
            throw invalid(
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
            throw invalid(
                    "External delivery activation interval mismatch at "
                            + delivery.scopePath() + "/"
                            + delivery.channelKey());
        }
    }

    private String occurrenceKey(
            String scopePath,
            String channelKey) {
        return PointerUtils.normalizeScope(scopePath)
                + ProcessorIdentityConstants.SELECTOR_COMPONENT_DELIMITER
                + channelKey;
    }

    private Set<String> subscriptionContractKeys(
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

    private boolean isExternalChannelType(String typeBlueId) {
        ChannelProcessor<?> processor = typeBlueId != null
                ? registry.lookupChannel(typeBlueId).orElse(null)
                : null;
        if (processor == null) {
            return false;
        }
        Class<?> contractType = processor.contractType();
        for (Class<?> managed
                : ProcessorContractConstants
                .PROCESSOR_MANAGED_CHANNEL_TYPES) {
            if (managed.isAssignableFrom(contractType)) {
                return false;
            }
        }
        return true;
    }

    private boolean isChannelType(String typeBlueId) {
        return typeBlueId != null
                && registry.lookupChannel(typeBlueId).isPresent();
    }

    private boolean hasEnumerationSelector(
            SubscriptionDelta.Entry interval) {
        return interval.dependencies()
                .wholeSameScopeExternalSurface()
                || interval.dependencies()
                .wholeSameScopeChannelCatalog()
                || !interval.dependencies().typeFamilies().isEmpty();
    }

    /**
     * Resolves only the contract headers that the exact occurrence set can
     * semantically demand: its channels, Process Embedded routing, and direct
     * processor state. Unsupported contracts elsewhere remain for the
     * processor's complete participating-closure preflight.
     */
    private SubscriptionIndexProjection subscriptionIndexProjection(
            Node root,
            List<SubscriptionDelta.Entry> activeIntervals) {
        Map<String, Set<String>> subscriptionKeys =
                new LinkedHashMap<>();
        Map<String, Map<String, String>> selectorTypesByScope =
                new LinkedHashMap<>();
        Set<String> selectorScopes = new LinkedHashSet<>();
        Set<String> channelCatalogScopes = new LinkedHashSet<>();
        for (SubscriptionDelta.Entry interval : activeIntervals) {
            String scopePath = PointerUtils.normalizeScope(
                    interval.scopePath());
            subscriptionKeys.computeIfAbsent(
                            scopePath,
                            ignored -> new LinkedHashSet<>())
                    .addAll(subscriptionContractKeys(
                            interval, null));
            if (hasEnumerationSelector(interval)) {
                selectorScopes.add(scopePath);
            }
            if (interval.dependencies()
                    .wholeSameScopeChannelCatalog()) {
                channelCatalogScopes.add(scopePath);
            }
        }
        if (!selectorScopes.isEmpty()) {
            /*
             * Enumeration selectors are absence proofs. First build a
             * scope-spine projection containing all contract headers only at
             * selector scopes. Resolve that projection with every discovered
             * Handler body deferred, then expand selector keys from its full
             * same-scope External Channel header catalog. The unprojected Root
             * is never resolved here.
             */
            Node selectorProjection =
                    selectorCatalogProjection(root, selectorScopes);
            try (Resolution selectorResolution =
                         selectorResolution(
                                 selectorProjection,
                                 selectorScopes,
                                 channelCatalogScopes)) {
                for (SubscriptionDelta.Entry interval
                        : activeIntervals) {
                    if (!hasEnumerationSelector(interval)) {
                        continue;
                    }
                    String scopePath = PointerUtils.normalizeScope(
                            interval.scopePath());
                    Map<String, String> selectorTypes =
                            selectorTypesByScope.get(scopePath);
                    if (selectorTypes == null) {
                        selectorTypes =
                                selectorEffectiveContractTypes(
                                        selectorResolution,
                                        scopePath);
                        selectorTypesByScope.put(
                                scopePath, selectorTypes);
                    }
                    subscriptionKeys.get(scopePath).addAll(
                            subscriptionContractKeys(
                                    interval,
                                    selectorTypes));
                }
            }
        }
        Node projected = copySubscriptionSpine(
                root, JsonPointer.ROOT, subscriptionKeys);
        if (projected == null) {
            throw invalid(
                    "Retained active subscription scope is absent");
        }
        clearMaterializationProvenance(
                projected, new IdentityHashMap<Node, Boolean>());
        return new SubscriptionIndexProjection(
                projected,
                subscriptionKeys,
                selectorTypesByScope);
    }

    private Node selectorCatalogProjection(
            Node root,
            Set<String> selectorScopes) {
        Node projected = copySelectorCatalogSpine(
                root, JsonPointer.ROOT, selectorScopes);
        if (projected == null) {
            throw invalid(
                    "Enumeration-selector scope is absent");
        }
        clearMaterializationProvenance(
                projected, new IdentityHashMap<Node, Boolean>());
        return projected;
    }

    /**
     * Copies only branches leading to enumeration-selector scopes. At the
     * selected scope it retains direct contract declarations so the effective
     * header map can prove additions; at ancestors it retains only processor
     * state and Process Embedded routing. Declared scope types remain attached
     * so inherited headers are still observable.
     */
    private Node copySelectorCatalogSpine(
            Node source,
            String path,
            Set<String> selectorScopes) {
        if (source == null || source.isReferenceOnly()) {
            return source != null ? source.clone() : null;
        }
        String normalized = PointerUtils.normalizeScope(path);
        boolean selected = selectorScopes.contains(normalized);
        boolean includeRouting =
                requiresEmbeddedRouting(path, selectorScopes);
        Node projected = copyNodeHeader(source);
        Node contracts = selected
                ? cloneNullable(source.getContracts())
                : copySubscriptionContracts(
                        source.getContracts(),
                        Collections.<String>emptySet(),
                        includeRouting);
        if (contracts != null) {
            projected.contracts(contracts);
        }
        if (source.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : source.getProperties().entrySet()) {
                String childPath = PointerUtils.appendPointer(
                        path, entry.getKey());
                if (!requestedBranch(
                        childPath, selectorScopes)) {
                    continue;
                }
                Node child = copySelectorCatalogSpine(
                        entry.getValue(),
                        childPath,
                        selectorScopes);
                if (child != null) {
                    projected.properties(entry.getKey(), child);
                }
            }
        }
        return projected;
    }

    private Resolution selectorResolution(
            Node selectorProjection,
            Set<String> selectorScopes,
            Set<String> channelCatalogScopes) {
        if (snapshotManager == null) {
            return resolution(selectorProjection);
        }
        Set<String> preserved =
                selectorDeferredContractPaths(
                        selectorProjection,
                        selectorScopes,
                        channelCatalogScopes);
        ResolvedSnapshot snapshot = preserved.isEmpty()
                ? snapshotManager.fromDocumentTransient(
                selectorProjection.clone())
                : snapshotManager
                .fromDocumentTransientPreservingPaths(
                        selectorProjection.clone(),
                        preserved);
        return new Resolution(selectorProjection, snapshot);
    }

    private Resolution subscriptionResolution(
            SubscriptionIndexProjection projection) {
        if (snapshotManager == null) {
            return resolution(projection.root);
        }
        Set<String> preserved =
                unrequestedContractPaths(projection);
        ResolvedSnapshot snapshot = preserved.isEmpty()
                ? snapshotManager.fromDocumentTransient(
                projection.root.clone())
                : snapshotManager
                .fromDocumentTransientPreservingPaths(
                        projection.root.clone(),
                        preserved);
        return new Resolution(projection.root, snapshot);
    }

    /**
     * The final sparse projection may retain a nominal scope type because one
     * requested Channel is inherited from it. Defer every other inherited
     * contract subtree before resolving that projection; otherwise an
     * unrelated Handler/extension body in the same type could become a
     * provider demand before it is filtered from the subscription bundle.
     */
    private Set<String> unrequestedContractPaths(
            SubscriptionIndexProjection projection) {
        Set<String> paths = new LinkedHashSet<>();
        Set<String> openedScopes = openedScopeAncestors(
                projection.requestedKeys.keySet());
        for (String scopePath : openedScopes) {
            Set<String> requested =
                    projection.requestedKeys.getOrDefault(
                            scopePath,
                            Collections.<String>emptySet());
            boolean includeRouting =
                    requiresEmbeddedRouting(
                            scopePath,
                            projection.requestedKeys.keySet());
            Map<String, String> types =
                    exactContractTypes(
                            exactScopeContributionsAt(
                                    projection.root,
                                    scopePath));
            for (Map.Entry<String, String> entry
                    : types.entrySet()) {
                if (requested.contains(entry.getKey())
                        || isSubscriptionProcessorStateKey(
                        entry.getKey())
                        || includeRouting
                        && RuntimeBlueIds.PROCESS_EMBEDDED.equals(
                        entry.getValue())) {
                    continue;
                }
                paths.add(contractPath(
                        scopePath, entry.getKey()));
            }
        }
        return paths;
    }

    private Set<String> openedScopeAncestors(
            Iterable<String> scopes) {
        Set<String> opened = new LinkedHashSet<>();
        opened.add(JsonPointer.ROOT);
        for (String scope : scopes) {
            String current = JsonPointer.ROOT;
            for (String segment : JsonPointer.split(scope)) {
                current = PointerUtils.appendPointer(
                        current, segment);
                opened.add(current);
            }
        }
        return opened;
    }

    private String contractPath(
            String scopePath,
            String contractKey) {
        List<String> segments =
                new ArrayList<>(
                        JsonPointer.split(scopePath));
        segments.add(ProcessorContractConstants.KEY_CONTRACTS);
        segments.add(contractKey);
        return JsonPointer.toPointer(segments);
    }

    /**
     * Defers every contract outside the exact selector family as one exact
     * subtree. This protects registered Handler bodies and unknown extension
     * content alike. Whole External selectors retain only External Channel
     * headers; whole Channel-catalog selectors also retain processor-managed
     * Channel headers. A reference-only contribution is materialized exactly
     * only to inspect its declared type; nested body references are never
     * opened.
     */
    private Set<String> selectorDeferredContractPaths(
            Node selectorProjection,
            Set<String> selectorScopes,
            Set<String> channelCatalogScopes) {
        Set<String> paths = new LinkedHashSet<>();
        Set<String> openedScopes =
                openedScopeAncestors(selectorScopes);
        for (String scopePath : openedScopes) {
            boolean includeAllChannels =
                    channelCatalogScopes.contains(
                            PointerUtils.normalizeScope(
                                    scopePath));
            List<Node> contributions =
                    exactScopeContributionsAt(
                            selectorProjection, scopePath);
            Map<String, String> types =
                    exactContractTypes(contributions);
            for (Map.Entry<String, String> entry
                    : types.entrySet()) {
                if (isExternalChannelType(entry.getValue())
                        || includeAllChannels
                        && isChannelType(entry.getValue())) {
                    continue;
                }
                paths.add(contractPath(
                        scopePath, entry.getKey()));
            }
        }
        return paths;
    }

    private Map<String, String> selectorEffectiveContractTypes(
            Resolution resolution,
            String scopePath) {
        Node effective = resolution.effectiveNodeAt(scopePath);
        Node contracts = effective != null
                ? effective.getContracts()
                : null;
        Map<String, String> result = new LinkedHashMap<>();
        if (contracts == null
                || contracts.getProperties() == null) {
            return result;
        }
        for (Map.Entry<String, Node> entry
                : contracts.getProperties().entrySet()) {
            result.put(
                    entry.getKey(),
                    exactTypeBlueId(entry.getValue()));
        }
        return result;
    }

    private List<Node> exactScopeContributionsAt(
            Node root,
            String scopePath) {
        List<Node> current = new ArrayList<>();
        Node exactRoot = exactHeaderNode(root);
        if (exactRoot != null) {
            current.add(exactRoot);
        }
        for (String segment : JsonPointer.split(scopePath)) {
            List<Node> next = new ArrayList<>();
            Set<String> identities = new LinkedHashSet<>();
            for (Node contribution : current) {
                for (Node source : exactNodeAndTypeLineage(
                        contribution)) {
                    Node child = source.getProperties() != null
                            ? source.getProperties().get(segment)
                            : null;
                    Node exactChild = exactHeaderNode(child);
                    if (exactChild == null) {
                        continue;
                    }
                    String identity =
                            BlueIdCalculator.calculateBlueId(
                                    exactChild);
                    if (identities.add(identity)) {
                        next.add(exactChild);
                    }
                }
            }
            current = next;
            if (current.isEmpty()) {
                break;
            }
        }
        return current;
    }

    private List<Node> exactNodeAndTypeLineage(Node node) {
        List<Node> result = new ArrayList<>();
        collectExactTypeLineage(
                exactHeaderNode(node),
                result,
                new LinkedHashSet<String>(),
                0);
        return result;
    }

    private void collectExactTypeLineage(
            Node node,
            List<Node> result,
            Set<String> active,
            int depth) {
        if (node == null) {
            return;
        }
        long limit = GasSchedule.contracts10()
                .portableLimit(GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES);
        if (depth > limit) {
            throw invalid(
                    "Enumeration-selector type hierarchy exceeds "
                            + limit);
        }
        Node exact = exactHeaderNode(node);
        if (exact == null) {
            return;
        }
        String identity =
                BlueIdCalculator.calculateBlueId(exact);
        if (!active.add(identity)) {
            throw invalid(
                    "Cyclic type hierarchy in enumeration-selector "
                            + "header catalog");
        }
        collectExactTypeLineage(
                exact.getType(),
                result,
                active,
                depth + 1);
        result.add(exact);
        active.remove(identity);
    }

    private Node exactHeaderNode(Node node) {
        if (node == null || !node.isReferenceOnly()) {
            return node;
        }
        if (snapshotManager == null) {
            throw invalid(
                    "Enumeration-selector exact header materialization "
                            + "is unavailable");
        }
        return snapshotManager
                .materializeVerifiedExactReference(
                        FrozenNode.fromNode(node))
                .toNode();
    }

    private Map<String, String> exactContractTypes(
            List<Node> scopeContributions) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Node scopeContribution : scopeContributions) {
            for (Node source : exactNodeAndTypeLineage(
                    scopeContribution)) {
                Node contracts = exactHeaderNode(
                        source.getContracts());
                if (contracts == null
                        || contracts.getProperties() == null) {
                    continue;
                }
                for (Map.Entry<String, Node> entry
                        : contracts.getProperties().entrySet()) {
                    Node contract =
                            exactHeaderNode(entry.getValue());
                    String typeBlueId =
                            exactTypeBlueId(contract);
                    if (!result.containsKey(entry.getKey())
                            || typeBlueId != null) {
                        result.put(
                                entry.getKey(),
                                typeBlueId);
                    }
                }
            }
        }
        return result;
    }

    private String exactTypeBlueId(Node contract) {
        Node type = contract != null
                ? contract.getType()
                : null;
        if (type == null) {
            return null;
        }
        return type.getBlueId() != null
                ? type.getBlueId()
                : BlueIdCalculator.calculateBlueId(type);
    }

    /**
     * Builds an owned Source projection by walking only ancestor spines named
     * by the retained active index. Unrelated application branches are never
     * cloned or traversed.
     */
    private Node copySubscriptionSpine(
            Node source,
            String path,
            Map<String, Set<String>> subscriptionKeys) {
        if (source == null || source.isReferenceOnly()) {
            return source != null ? source.clone() : null;
        }
        Set<String> requestedKeys =
                subscriptionKeys.getOrDefault(
                        PointerUtils.normalizeScope(path),
                        Collections.emptySet());
        boolean includeProcessEmbedded =
                requiresEmbeddedRouting(
                        path, subscriptionKeys.keySet());
        Node projected = copyNodeHeader(source);
        if (!typeContributesToSubscriptionSurface(
                snapshotManager,
                source.getType(),
                requestedKeys,
                includeProcessEmbedded,
                new LinkedHashSet<String>())) {
            /*
             * The sparse subscription projection must not resolve unrelated
             * contracts inherited from the scope type. Exact type-source
             * inspection above proves that removing this nominal type cannot
             * change any retained Channel header or Process Embedded route;
             * the complete participating-closure preflight still resolves
             * the original type later.
             */
            projected.type((Node) null);
        }
        Node contracts = copySubscriptionContracts(
                source.getContracts(),
                requestedKeys,
                includeProcessEmbedded);
        if (contracts != null) {
            projected.contracts(contracts);
        }
        if (source.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : source.getProperties().entrySet()) {
                String childPath = PointerUtils.appendPointer(
                        path, entry.getKey());
                if (!requestedBranch(
                        childPath, subscriptionKeys.keySet())) {
                    continue;
                }
                Node child = copySubscriptionSpine(
                        entry.getValue(),
                        childPath,
                        subscriptionKeys);
                if (child != null) {
                    projected.properties(entry.getKey(), child);
                }
            }
        }
        return projected;
    }

    static boolean typeContributesToSubscriptionSurface(
            ProcessingSnapshotManager snapshotManager,
            Node declaredType,
            Set<String> requestedChannelKeys,
            boolean includeProcessEmbedded,
            Set<String> visited) {
        if (declaredType == null) {
            return false;
        }
        if (requestedChannelKeys.isEmpty()
                && !includeProcessEmbedded) {
            return false;
        }
        if (snapshotManager == null) {
            return true;
        }
        FrozenNode exactType;
        if (declaredType.isReferenceOnly()) {
            exactType = snapshotManager
                    .materializeVerifiedExactReference(
                            FrozenNode.fromNode(declaredType));
        } else {
            exactType = FrozenNode.fromNode(
                    declaredType.clone());
        }
        String identity = declaredType.getBlueId() != null
                ? declaredType.getBlueId()
                : exactType.blueId();
        if (!visited.add(identity)) {
            throw new InvalidExecutionEvidenceException(
                    "Cyclic scope type hierarchy in subscription surface: "
                            + identity);
        }

        FrozenNode contracts = exactType.getContracts();
        if (contracts != null && contracts.isReferenceOnly()) {
            contracts = snapshotManager
                    .materializeVerifiedExactReference(contracts);
        }
        if (contracts != null
                && contracts.getProperties() != null) {
            for (Map.Entry<String, FrozenNode> entry
                    : contracts.getProperties().entrySet()) {
                if (requestedChannelKeys.contains(
                        entry.getKey())) {
                    return true;
                }
                if (includeProcessEmbedded
                        && isExactProcessEmbeddedContract(
                        snapshotManager,
                        entry.getValue())) {
                    return true;
                }
            }
        }
        FrozenNode parent = exactType.getType();
        return parent != null
                && typeContributesToSubscriptionSurface(
                        snapshotManager,
                        parent.toNode(),
                        requestedChannelKeys,
                        includeProcessEmbedded,
                        visited);
    }

    private static boolean isExactProcessEmbeddedContract(
            ProcessingSnapshotManager snapshotManager,
            FrozenNode contract) {
        FrozenNode exact = contract;
        if (exact != null && exact.isReferenceOnly()) {
            exact = snapshotManager
                    .materializeVerifiedExactReference(exact);
        }
        FrozenNode type = exact != null
                ? exact.getType()
                : null;
        return type != null
                && RuntimeBlueIds.PROCESS_EMBEDDED.equals(
                type.getReferenceBlueId() != null
                        ? type.getReferenceBlueId()
                        : type.blueId());
    }

    private Node copySubscriptionContracts(
            Node sourceContracts,
            Set<String> requestedKeys,
            boolean includeProcessEmbedded) {
        if (sourceContracts == null) {
            return null;
        }
        if (sourceContracts.isReferenceOnly()) {
            return sourceContracts.clone();
        }
        Node projected = copyNodeHeader(sourceContracts);
        if (sourceContracts.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : sourceContracts.getProperties().entrySet()) {
                if (requestedKeys.contains(entry.getKey())
                        || isSubscriptionProcessorStateKey(entry.getKey())
                        || includeProcessEmbedded
                        && isDirectProcessEmbeddedContract(
                        entry.getValue())) {
                    projected.properties(
                            entry.getKey(), entry.getValue().clone());
                }
            }
        }
        return projected;
    }

    /**
     * Copies only a node's own semantic header. Child properties, list items,
     * and Contracts are supplied by the sparse projection builder.
     */
    private Node copyNodeHeader(Node source) {
        Node copy = new Node()
                .name(source.getName())
                .description(source.getDescription())
                .value(source.getRawValue())
                .type(cloneNullable(source.getType()))
                .itemType(cloneNullable(source.getItemType()))
                .keyType(cloneNullable(source.getKeyType()))
                .valueType(cloneNullable(source.getValueType()))
                .schema(source.getSchema() != null
                        ? source.getSchema().clone()
                        : null)
                .mergePolicy(source.getMergePolicy())
                .previousBlueId(source.getPreviousBlueId())
                .position(source.getPosition())
                .blue(cloneNullable(source.getBlue()))
                .inlineValue(source.isInlineValue());
        if (source.getBlueId() != null) {
            copy.blueId(source.getBlueId());
        }
        return copy;
    }

    private Node cloneNullable(Node source) {
        return source != null ? source.clone() : null;
    }

    private boolean requiresEmbeddedRouting(
            String path,
            Set<String> requestedScopes) {
        String normalized = PointerUtils.normalizeScope(path);
        for (String requestedScope : requestedScopes) {
            String requested =
                    PointerUtils.normalizeScope(requestedScope);
            if (!requested.equals(normalized)
                    && PointerUtils.descendantOrEqual(
                    requested, normalized)) {
                return true;
            }
        }
        return false;
    }

    private void clearMaterializationProvenance(
            Node node,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (node.isReferenceOnly()) {
            return;
        }
        if (node.getBlueId() != null) {
            node.blueId(null);
        }
        node.type(nominalReference(node.getType()));
        node.itemType(nominalReference(node.getItemType()));
        node.keyType(nominalReference(node.getKeyType()));
        node.valueType(nominalReference(node.getValueType()));
        clearMaterializationProvenance(node.getType(), visited);
        clearMaterializationProvenance(node.getItemType(), visited);
        clearMaterializationProvenance(node.getKeyType(), visited);
        clearMaterializationProvenance(node.getValueType(), visited);
        clearMaterializationProvenance(node.getBlue(), visited);
        clearSchemaMaterializationProvenance(
                node.getSchema(), visited);
        clearMaterializationProvenance(node.getContracts(), visited);
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                clearMaterializationProvenance(child, visited);
            }
        }
        if (node.getItems() != null) {
            for (Node child : node.getItems()) {
                clearMaterializationProvenance(child, visited);
            }
        }
    }

    /**
     * A resolved nominal type may carry both its published identity and its
     * materialized body. The Source projection must preserve the published
     * nominal identity, so collapse that representation back to a pure
     * reference instead of recomputing an identity from resolved content.
     */
    private Node nominalReference(Node type) {
        if (type == null
                || type.getBlueId() == null
                || type.isReferenceOnly()) {
            return type;
        }
        return new Node().blueId(type.getBlueId());
    }

    private void clearSchemaMaterializationProvenance(
            Schema schema,
            IdentityHashMap<Node, Boolean> visited) {
        if (schema == null || schema.isReferenceOnly()) {
            return;
        }
        if (schema.getBlueId() != null) {
            schema.blueId(null);
        }
        clearMaterializationProvenance(schema.getRequired(), visited);
        clearMaterializationProvenance(schema.getMinLength(), visited);
        clearMaterializationProvenance(schema.getMaxLength(), visited);
        clearMaterializationProvenance(schema.getMinimum(), visited);
        clearMaterializationProvenance(schema.getMaximum(), visited);
        clearMaterializationProvenance(
                schema.getExclusiveMinimum(), visited);
        clearMaterializationProvenance(
                schema.getExclusiveMaximum(), visited);
        clearMaterializationProvenance(schema.getMultipleOf(), visited);
        clearMaterializationProvenance(schema.getMinItems(), visited);
        clearMaterializationProvenance(schema.getMaxItems(), visited);
        clearMaterializationProvenance(schema.getUniqueItems(), visited);
        clearMaterializationProvenance(schema.getMinFields(), visited);
        clearMaterializationProvenance(schema.getMaxFields(), visited);
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                clearMaterializationProvenance(value, visited);
            }
        }
    }

    /**
     * Keeps only headers needed to derive feeder subscriptions. Unsupported or
     * malformed application contracts outside that header surface remain for
     * accepted-new must-understand preflight and cannot change no-match/stale
     * precedence.
     */
    private FrozenNode subscriptionProjection(Node effectiveScope) {
        return subscriptionProjection(
                effectiveScope, null, true);
    }

    private FrozenNode subscriptionProjection(
            Node effectiveScope,
            Set<String> retainedChannelKeys,
            boolean includeProcessEmbedded) {
        Node projected = effectiveScope.clone();
        Node contracts = projected.getContracts();
        if (contracts != null
                && contracts.getProperties() != null) {
            contracts.getProperties().entrySet().removeIf(entry ->
                    !isSubscriptionProcessorStateKey(entry.getKey())
                            && !(retainedChannelKeys != null
                            ? retainedChannelKeys.contains(entry.getKey())
                            : isSubscriptionContract(entry.getValue()))
                            && !(includeProcessEmbedded
                            && isDirectProcessEmbeddedContract(
                            entry.getValue())));
            if (contracts.getProperties().isEmpty()) {
                projected.contracts(null);
            }
        }
        clearMaterializationProvenance(
                projected, new IdentityHashMap<Node, Boolean>());
        return FrozenNode.fromResolvedNode(projected);
    }

    private boolean isSubscriptionContract(Node contract) {
        if (contract == null) {
            return false;
        }
        if (isDirectProcessEmbeddedContract(contract)) {
            return true;
        }
        Node type = contract.getType();
        if (type == null) {
            return false;
        }
        String typeBlueId = type.getBlueId() != null
                ? type.getBlueId()
                : BlueIdCalculator.calculateBlueId(type);
        return registry.lookupChannel(typeBlueId).isPresent();
    }

    /**
     * Process Embedded is a core nominal header. Inspecting that direct header
     * avoids freezing or resolving unrelated contracts merely to decide
     * whether they belong in the subscription projection.
     */
    private boolean isDirectProcessEmbeddedContract(Node contract) {
        Node type = contract != null ? contract.getType() : null;
        return type != null
                && RuntimeBlueIds.PROCESS_EMBEDDED.equals(
                type.getBlueId());
    }

    private boolean requestedBranch(
            String candidate,
            Set<String> requestedScopes) {
        String normalized =
                PointerUtils.normalizeScope(candidate);
        for (String scope : requestedScopes) {
            if (PointerUtils.descendantOrEqual(
                    scope, normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSubscriptionProcessorStateKey(String key) {
        /*
         * Initialization state does not affect feeder preselection. Keeping
         * its exact document payload in this sparse projection would resolve
         * unrelated content and make inline/collapsed marker forms observably
         * different. Termination and checkpoint state are the only direct
         * processor state needed by this phase.
         */
        return ProcessorContractConstants.KEY_TERMINATED
                .equals(key)
                || ProcessorContractConstants.KEY_CHECKPOINT
                .equals(key);
    }

    private void verifyExactDeliveries(
            List<ExternalDeliverySnapshot> actual,
            List<ExternalDeliverySnapshot> expected) {
        if (actual.size() != expected.size()) {
            throw invalid(
                    "External delivery occurrence set is incomplete or has "
                            + "extra entries");
        }
        Set<String> occurrences = new LinkedHashSet<>();
        ExternalDeliverySnapshot previous = null;
        for (int index = 0; index < actual.size(); index++) {
            ExternalDeliverySnapshot delivery = actual.get(index);
            if (!sameDelivery(delivery, expected.get(index))) {
                throw invalid(
                        "External delivery occurrence mismatch at index "
                                + index);
            }
            if (previous != null
                    && compareDeliveries(previous, delivery) > 0) {
                throw invalid(
                        "External delivery snapshot is not in canonical order");
            }
            String occurrence = delivery.scopePath()
                    + ProcessorIdentityConstants.SELECTOR_COMPONENT_DELIMITER
                    + delivery.channelKey();
            if (!occurrences.add(occurrence)) {
                throw invalid(
                        "Duplicate External Channel occurrence at "
                                + delivery.scopePath() + "/"
                                + delivery.channelKey());
            }
            previous = delivery;
        }
    }

    private boolean sameDelivery(ExternalDeliverySnapshot left,
                                 ExternalDeliverySnapshot right) {
        return left.scopePath().equals(right.scopePath())
                && left.channelKey().equals(right.channelKey())
                && left.order() == right.order()
                && left.sourceContributionNodeBlueIds().equals(
                right.sourceContributionNodeBlueIds())
                && left.effectiveTypeBlueId().equals(
                right.effectiveTypeBlueId())
                && left.subscriptionKeys().equals(
                right.subscriptionKeys())
                && left.checkpointDomainBlueId().equals(
                right.checkpointDomainBlueId())
                && left.checkpointSubjectBlueId().equals(
                right.checkpointSubjectBlueId())
                && Objects.equals(
                left.activationStartExclusive(),
                right.activationStartExclusive())
                && Objects.equals(
                left.activationEndInclusive(),
                right.activationEndInclusive());
    }

    private void verifyDelivery(
            Resolution resolution,
            ExternalDeliverySnapshot delivery,
            SubscriptionDelta.Entry interval) {
        if (!reachableScope(resolution, delivery.scopePath())) {
            throw invalid(
                    "External delivery scope is not reachable through the "
                            + "effective Process Embedded surface: "
                            + delivery.scopePath());
        }
        Node selectedScope =
                resolution.selectedNodeAt(delivery.scopePath());
        Node effectiveScope =
                resolution.effectiveNodeAt(delivery.scopePath());
        if (!isValidScope(
                delivery.scopePath(), selectedScope)
                || !isValidScope(
                delivery.scopePath(), effectiveScope)) {
            throw invalid(
                    "External delivery scope is absent or not an object: "
                            + delivery.scopePath());
        }
        if (hasDirectTerminatedMarker(selectedScope)) {
            throw invalid(
                    "External delivery scope is directly terminated: "
                            + delivery.scopePath());
        }
        Map<String, String> selectorTypes =
                hasEnumerationSelector(interval)
                        ? selectorEffectiveContractTypes(
                        resolution,
                        delivery.scopePath())
                        : null;
        ContractBundle bundle =
                resolution.subscriptionBundleAt(
                        delivery.scopePath(),
                        subscriptionContractKeys(
                                interval, selectorTypes),
                        false);
        EffectiveContractSnapshot contract =
                bundle.effectiveContractSnapshot(
                        delivery.channelKey());
        if (contract == null
                || !EffectiveContractSnapshotConstants
                .Role.EXTERNAL_CHANNEL.equals(
                contract.role())) {
            throw invalid(
                    "External delivery channel is absent or not external at "
                            + delivery.scopePath() + "/"
                            + delivery.channelKey());
        }
        if (!delivery.effectiveTypeBlueId().equals(
                contract.effectiveTypeBlueId())) {
            throw invalid(
                    "External delivery effective type mismatch at "
                            + delivery.scopePath() + "/"
                            + delivery.channelKey());
        }
        if (delivery.order() != contract.order()) {
            throw invalid(
                    "External delivery order mismatch at "
                            + delivery.scopePath() + "/"
                            + delivery.channelKey());
        }
        if (!delivery.sourceContributionNodeBlueIds().equals(
                contract.sourceContributionNodeBlueIds())) {
            throw invalid(
                    "External delivery ordered Source contributions mismatch at "
                            + delivery.scopePath() + "/"
                            + delivery.channelKey());
        }
        FrozenNode effectiveContract =
                bundle.contractNode(delivery.channelKey());
        if (effectiveContract == null) {
            throw invalid(
                    "External delivery effective contract content is absent at "
                            + delivery.scopePath() + "/"
                            + delivery.channelKey());
        }
    }

    private boolean reachableScope(Resolution resolution,
                                   String targetPath) {
        String target = PointerUtils.normalizeScope(targetPath);
        String current = JsonPointer.ROOT;
        Set<String> visited = new LinkedHashSet<>();
        while (!current.equals(target)) {
            if (!visited.add(current)) {
                return false;
            }
            Node selected = resolution.selectedNodeAt(current);
            if (hasDirectTerminatedMarker(selected)) {
                return false;
            }
            ContractBundle bundle =
                    resolution.subscriptionBundleAt(
                            current, (String) null, true);
            String selectedChild = null;
            int selectedDepth = -1;
            for (String embedded : bundle.embeddedPaths()) {
                String candidate =
                        PointerUtils.resolvePointer(current, embedded);
                if (candidate.equals(current)
                        || !PointerUtils.descendantOrEqual(
                        target, candidate)) {
                    continue;
                }
                int depth = depth(candidate);
                if (depth > selectedDepth) {
                    selectedChild = candidate;
                    selectedDepth = depth;
                } else if (depth == selectedDepth
                        && !candidate.equals(selectedChild)) {
                    throw invalid(
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

    private boolean hasDirectTerminatedMarker(Node scope) {
        Node contracts = scope != null ? scope.getContracts() : null;
        Node marker = contracts != null
                && contracts.getProperties() != null
                ? contracts.getProperties().get(
                ProcessorContractConstants.KEY_TERMINATED)
                : null;
        if (marker == null) {
            return false;
        }
        try {
            ProcessorEngine.validateTerminationMarker(
                    marker,
                    PointerUtils.resolvePointer(
                            JsonPointer.ROOT,
                            ProcessorPointerConstants.RELATIVE_TERMINATED));
            return true;
        } catch (RuntimeException exception) {
            throw invalid(
                    "Invalid direct terminated marker");
        }
    }

    private Node nodeAt(Node root, String pointer) {
        if (JsonPointer.ROOT.equals(pointer)) {
            return root;
        }
        Node current = root;
        for (String segment : JsonPointer.split(pointer)) {
            if (current == null
                    || current.getProperties() == null) {
                return null;
            }
            current = current.getProperties().get(segment);
        }
        return current;
    }

    private boolean isValidScope(String scopePath, Node node) {
        if (node == null || node.isReferenceOnly()) {
            return false;
        }
        if (JsonPointer.ROOT.equals(PointerUtils.normalizeScope(
                scopePath))) {
            return true;
        }
        return node.getValue() == null
                && node.getItems() == null;
    }

    private int compareDeliveries(
            ExternalDeliverySnapshot left,
            ExternalDeliverySnapshot right) {
        int comparison = Integer.compare(
                depth(right.scopePath()),
                depth(left.scopePath()));
        if (comparison != 0) {
            return comparison;
        }
        comparison = ExternalOrderKey.compareTextCodePoints(
                left.scopePath(), right.scopePath());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(
                left.order(), right.order());
        if (comparison != 0) {
            return comparison;
        }
        comparison = ExternalOrderKey.compareTextCodePoints(
                left.channelKey(), right.channelKey());
        return comparison != 0
                ? comparison
                : ExternalOrderKey.compareTextCodePoints(
                left.effectiveTypeBlueId(),
                right.effectiveTypeBlueId());
    }

    private int depth(String scopePath) {
        return JsonPointer.split(scopePath).size();
    }

    private Resolution resolution(Node root) {
        if (contractLoader == null) {
            throw invalid(
                    "Effective-contract resolver is unavailable");
        }
        ResolvedSnapshot snapshot = snapshotManager != null
                ? snapshotManager.fromDocumentTransient(root.clone())
                : null;
        return new Resolution(root, snapshot);
    }

    private InvalidExecutionEvidenceException invalid(String message) {
        return new InvalidExecutionEvidenceException(message);
    }

    private ExecutionEvidenceUnavailableException unavailable(
            String message,
            Set<String> requiredExactBlueIds) {
        return new ExecutionEvidenceUnavailableException(
                message, requiredExactBlueIds);
    }

    private Set<String> referencedBlueIds(Node... roots) {
        Set<String> result = new LinkedHashSet<>();
        IdentityHashMap<Node, Boolean> visited =
                new IdentityHashMap<>();
        if (roots != null) {
            for (Node root : roots) {
                collectReferencedBlueIds(root, result, visited);
            }
        }
        return result;
    }

    private void collectReferencedBlueIds(
            Node node,
            Set<String> result,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (node.isReferenceOnly()) {
            if (node.getBlueId() != null
                    && !node.getBlueId().isEmpty()) {
                result.add(node.getBlueId());
            }
            return;
        }
        collectReferencedBlueIds(node.getType(), result, visited);
        collectReferencedBlueIds(node.getSchema(), result, visited);
        collectReferencedBlueIds(node.getContracts(), result, visited);
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                collectReferencedBlueIds(child, result, visited);
            }
        }
        if (node.getItems() != null) {
            for (Node child : node.getItems()) {
                collectReferencedBlueIds(child, result, visited);
            }
        }
    }

    private void collectReferencedBlueIds(
            Schema schema,
            Set<String> result,
            IdentityHashMap<Node, Boolean> visited) {
        if (schema == null) {
            return;
        }
        if (schema.isReferenceOnly()) {
            if (schema.getBlueId() != null
                    && !schema.getBlueId().isEmpty()) {
                result.add(schema.getBlueId());
            }
            return;
        }
        collectReferencedBlueIds(schema.getRequired(), result, visited);
        collectReferencedBlueIds(schema.getMinLength(), result, visited);
        collectReferencedBlueIds(schema.getMaxLength(), result, visited);
        collectReferencedBlueIds(schema.getMinimum(), result, visited);
        collectReferencedBlueIds(schema.getMaximum(), result, visited);
        collectReferencedBlueIds(
                schema.getExclusiveMinimum(), result, visited);
        collectReferencedBlueIds(
                schema.getExclusiveMaximum(), result, visited);
        collectReferencedBlueIds(schema.getMultipleOf(), result, visited);
        collectReferencedBlueIds(schema.getMinItems(), result, visited);
        collectReferencedBlueIds(schema.getMaxItems(), result, visited);
        collectReferencedBlueIds(schema.getUniqueItems(), result, visited);
        collectReferencedBlueIds(schema.getMinFields(), result, visited);
        collectReferencedBlueIds(schema.getMaxFields(), result, visited);
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                collectReferencedBlueIds(value, result, visited);
            }
        }
    }

    private static final class SubscriptionIndexProjection {
        private final Node root;
        private final Map<String, Set<String>> requestedKeys;
        private final Map<String, Map<String, String>>
                selectorTypesByScope;

        private SubscriptionIndexProjection(
                Node root,
                Map<String, Set<String>> requestedKeys,
                Map<String, Map<String, String>>
                        selectorTypesByScope) {
            this.root = Objects.requireNonNull(root, "root");
            Map<String, Set<String>> copy =
                    new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry
                    : requestedKeys.entrySet()) {
                copy.put(
                        entry.getKey(),
                        Collections.unmodifiableSet(
                                new LinkedHashSet<>(
                                        entry.getValue())));
            }
            this.requestedKeys =
                    Collections.unmodifiableMap(copy);
            Map<String, Map<String, String>> typesCopy =
                    new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, String>> entry
                    : selectorTypesByScope.entrySet()) {
                typesCopy.put(
                        entry.getKey(),
                        Collections.unmodifiableMap(
                                new LinkedHashMap<>(
                                        entry.getValue())));
            }
            this.selectorTypesByScope =
                    Collections.unmodifiableMap(typesCopy);
        }

        private Map<String, String> selectorTypes(
                String scopePath) {
            return selectorTypesByScope.get(
                    PointerUtils.normalizeScope(
                            scopePath));
        }

        private List<String> contractKeys(
                String scopePath) {
            Map<String, String> types =
                    selectorTypes(scopePath);
            if (types == null || types.isEmpty()) {
                return Collections.emptyList();
            }
            List<String> keys = new ArrayList<>();
            for (String key : types.keySet()) {
                if (!ProcessorContractConstants.KEY_INITIALIZED
                        .equals(key)
                        && !ProcessorContractConstants.KEY_TERMINATED
                        .equals(key)
                        && !ProcessorContractConstants.KEY_CHECKPOINT
                        .equals(key)) {
                    keys.add(key);
                }
            }
            keys.sort(
                    ExternalOrderKey::compareTextCodePoints);
            return Collections.unmodifiableList(keys);
        }
    }

    private static final class SubscriptionEvaluation {
        private final List<String> channelKeys;
        private final List<String> eventKeys;
        private final boolean preselects;
        private final boolean accepts;
        private final String checkpointDomainBlueId;
        private final String checkpointSubjectBlueId;
        private final ExternalChannelDependencySnapshot dependencies;

        private SubscriptionEvaluation(
                List<String> channelKeys,
                List<String> eventKeys,
                boolean preselects,
                boolean accepts,
                String checkpointDomainBlueId,
                String checkpointSubjectBlueId,
                ExternalChannelDependencySnapshot dependencies) {
            this.channelKeys = channelKeys;
            this.eventKeys = eventKeys;
            this.preselects = preselects;
            this.accepts = accepts;
            this.checkpointDomainBlueId =
                    Objects.requireNonNull(
                            checkpointDomainBlueId,
                            "checkpointDomainBlueId");
            this.checkpointSubjectBlueId =
                    checkpointSubjectBlueId;
            this.dependencies = Objects.requireNonNull(
                    dependencies, "dependencies");
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof SubscriptionEvaluation)) {
                return false;
            }
            SubscriptionEvaluation evaluation =
                    (SubscriptionEvaluation) other;
            return channelKeys.equals(evaluation.channelKeys)
                    && eventKeys.equals(evaluation.eventKeys)
                    && preselects == evaluation.preselects
                    && accepts == evaluation.accepts
                    && checkpointDomainBlueId.equals(
                    evaluation.checkpointDomainBlueId)
                    && Objects.equals(
                    checkpointSubjectBlueId,
                    evaluation.checkpointSubjectBlueId)
                    && dependencies.equals(
                    evaluation.dependencies);
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
                    dependencies);
        }
    }

    private final class Resolution implements AutoCloseable {
        private final Node root;
        private final ResolvedSnapshot snapshot;

        private Resolution(Node root, ResolvedSnapshot snapshot) {
            this.root = root;
            this.snapshot = snapshot;
        }

        private Node selectedNodeAt(String scopePath) {
            if (snapshot != null) {
                if (JsonPointer.ROOT.equals(
                        PointerUtils.normalizeScope(
                        scopePath))) {
                    return snapshot.canonicalRoot();
                }
                Node selected = snapshot.canonicalNodeAt(scopePath);
                return selected != null ? selected : null;
            }
            return nodeAt(root, scopePath);
        }

        private Node effectiveNodeAt(String scopePath) {
            if (snapshot != null) {
                if (JsonPointer.ROOT.equals(
                        PointerUtils.normalizeScope(
                        scopePath))) {
                    return snapshot.resolvedRoot();
                }
                return snapshot.resolvedNodeAt(scopePath);
            }
            Node selected = nodeAt(root, scopePath);
            if (selected != null && selected.getType() != null) {
                throw invalid(
                        "Inherited effective scope resolution requires a "
                                + "configured ProcessingSnapshotManager at "
                                + scopePath);
            }
            return selected;
        }

        private ContractBundle bundleAt(String scopePath) {
            if (snapshot != null) {
                return contractLoader.load(snapshot, scopePath);
            }
            Node selected = effectiveNodeAt(scopePath);
            if (selected == null) {
                throw invalid(
                        "Scope is absent: " + scopePath);
            }
            return contractLoader.load(
                    FrozenNode.fromResolvedNode(selected),
                    scopePath);
        }

        private ContractBundle subscriptionBundleAt(
                String scopePath) {
            return subscriptionBundleAt(
                    scopePath, (Set<String>) null, true);
        }

        private ContractBundle subscriptionBundleAt(
                String scopePath,
                String retainedChannelKey,
                boolean includeProcessEmbedded) {
            return subscriptionBundleAt(
                    scopePath,
                    retainedChannelKey != null
                            ? Collections.singleton(
                            retainedChannelKey)
                            : Collections.emptySet(),
                    includeProcessEmbedded);
        }

        private ContractBundle subscriptionBundleAt(
                String scopePath,
                Set<String> retainedChannelKeys,
                boolean includeProcessEmbedded) {
            Node selected = selectedNodeAt(scopePath);
            Node effective = effectiveNodeAt(scopePath);
            if (selected == null || effective == null) {
                throw invalid(
                        "Scope is absent: " + scopePath);
            }
            FrozenNode selectedFrozen = snapshot != null
                    ? snapshot.canonicalAt(scopePath)
                    : FrozenNode.fromResolvedNode(selected);
            return contractLoader.load(
                    selectedFrozen,
                    subscriptionProjection(
                            effective,
                            retainedChannelKeys,
                            includeProcessEmbedded),
                    scopePath);
        }

        @Override
        public void close() {
            // The configured manager is processor-owned and remains reusable.
        }
    }
}
