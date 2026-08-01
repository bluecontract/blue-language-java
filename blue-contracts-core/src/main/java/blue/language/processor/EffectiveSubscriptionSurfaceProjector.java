package blue.language.processor;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Projects changed subscription occurrences from effective contract bundles.
 *
 * <p>Reference resolution, inherited contracts, and custom immutable External
 * Channel functions live on this production path. Function results and their
 * staged gas traces are evaluated twice before becoming projection values.</p>
 */
final class EffectiveSubscriptionSurfaceProjector {

    private final ContractLoader contractLoader;
    private final ProcessingSnapshotManager snapshotManager;
    private final ContractProcessorRegistry registry;
    private final NodeToObjectConverter converter;
    private final SubscriptionSurfaceRules rules;
    private final EmbeddedSubscriptionRouteProjector routes;

    EffectiveSubscriptionSurfaceProjector(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            SubscriptionSurfaceRules rules) {
        this.contractLoader = Objects.requireNonNull(
                contractLoader, "contractLoader");
        this.snapshotManager = snapshotManager;
        this.registry = Objects.requireNonNull(registry, "registry");
        this.converter = converter;
        this.rules = Objects.requireNonNull(rules, "rules");
        this.routes = new EmbeddedSubscriptionRouteProjector(rules);
    }

    /** Projects only occurrences whose effective dependencies changed. */
    Map<String, SubscriptionDelta.Entry> project(
            Node root,
            ResolvedSnapshot suppliedSnapshot,
            GasSchedule schedule,
            Set<String> changedPaths,
            SubscriptionSurfaceValidationContext validationContext) {
        EffectiveResolution resolution =
                new EffectiveResolution(root, suppliedSnapshot);
        ScopeView rootScope = resolution.scopeAt(JsonPointer.ROOT);
        if (rootScope == null || !rules.isConcrete(rootScope.effective)) {
            throw rules.invalid(
                    "Root subscription scope must be concrete",
                    JsonPointer.ROOT,
                    null);
        }
        Map<String, SubscriptionDelta.Entry> result = new LinkedHashMap<>();
        collect(
                resolution,
                rootScope,
                JsonPointer.ROOT,
                result,
                new LinkedHashSet<String>(),
                new IdentityHashMap<Node, String>(),
                new LinkedHashMap<String, String>(),
                schedule,
                changedPaths,
                0,
                validationContext);
        return result;
    }

    private void collect(
            EffectiveResolution resolution,
            ScopeView scope,
            String scopePath,
            Map<String, SubscriptionDelta.Entry> result,
            Set<String> visitedPaths,
            IdentityHashMap<Node, String> activeScopes,
            Map<String, String> activeExactScopes,
            GasSchedule schedule,
            Set<String> changedPaths,
            int depth,
            SubscriptionSurfaceValidationContext validationContext) {
        rules.requireLimit(
                GasScheduleConstants.PortableLimit.EMBEDDED_DEPTH,
                depth,
                schedule.portableLimit(
                        GasScheduleConstants.PortableLimit.EMBEDDED_DEPTH),
                scopePath,
                null);
        if (!visitedPaths.add(scopePath)) {
            throw rules.invalid(
                    "Duplicate or ambiguous embedded route to " + scopePath,
                    scopePath,
                    null);
        }
        Node identityNode =
                scope.selected != null ? scope.selected : scope.effective;
        String activeAt = activeScopes.put(identityNode, scopePath);
        if (activeAt != null) {
            throw rules.invalid(
                    "Declared embedded ancestry cycle between "
                            + activeAt + " and " + scopePath,
                    scopePath,
                    null);
        }
        String exactScopeIdentity =
                rules.declaredExactIdentity(identityNode);
        if (exactScopeIdentity != null) {
            String sameExactScopeAt =
                    activeExactScopes.put(exactScopeIdentity, scopePath);
            if (sameExactScopeAt != null) {
                activeScopes.remove(identityNode);
                throw rules.invalid(
                        "Declared embedded ancestry revisits exact node "
                                + exactScopeIdentity + " at "
                                + sameExactScopeAt + " and " + scopePath,
                        scopePath,
                        null);
            }
        }
        try {
            rules.requireObjectLimits(
                    scope.effective, schedule, scopePath, null);
            if (rules.directTerminated(scope.selected)) {
                return;
            }
            ContractBundle bundle = scope.bundle;
            List<EffectiveContractSnapshot> contracts =
                    bundle.effectiveContractSnapshots();
            rules.requireLimit(
                    GasScheduleConstants.PortableLimit
                            .EFFECTIVE_CONTRACTS_PER_SCOPE,
                    contracts.size(),
                    schedule.portableLimit(
                            GasScheduleConstants.PortableLimit
                                    .EFFECTIVE_CONTRACTS_PER_SCOPE),
                    scopePath,
                    null);

            int externalCount = 0;
            List<String> embeddedRoutes = Collections.emptyList();
            String embeddedKey = null;
            for (EffectiveContractSnapshot contract : contracts) {
                rules.validateContractKey(
                        contract.key(), schedule, scopePath);
                String contractPath = PointerUtils.resolvePointer(
                        scopePath,
                        ProcessorPointerConstants.relativeContractsEntry(
                                contract.key()));
                if (EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL
                        .equals(contract.role())) {
                    externalCount++;
                    rules.requireLimit(
                            GasScheduleConstants.PortableLimit
                                    .EXTERNAL_CHANNELS_PER_SCOPE,
                            externalCount,
                            schedule.portableLimit(
                                    GasScheduleConstants.PortableLimit
                                            .EXTERNAL_CHANNELS_PER_SCOPE),
                            scopePath,
                            contract.key());
                    if (rules.dependencyAffected(
                            scopePath, contractPath, changedPaths)
                            || rules.sameScopeContractsAffected(
                                    scopePath, changedPaths)) {
                        SubscriptionDelta.Entry descriptor = descriptor(
                                bundle,
                                contract,
                                scopePath,
                                schedule,
                                validationContext);
                        if (result.put(
                                descriptor.occurrenceKey(), descriptor)
                                != null) {
                            throw rules.invalid(
                                    "Duplicate external subscription occurrence",
                                    scopePath,
                                    contract.key());
                        }
                    }
                } else if (EffectiveContractSnapshotConstants.Role
                        .PROCESS_EMBEDDED.equals(contract.role())) {
                    if (embeddedKey != null) {
                        throw rules.invalid(
                                "Multiple effective Process Embedded contracts",
                                scopePath,
                                contract.key());
                    }
                    embeddedKey = contract.key();
                    embeddedRoutes = routes.project(
                            bundle.embeddedPaths(),
                            scopePath,
                            contract.key(),
                            schedule);
                }
            }

            if (embeddedKey == null) {
                return;
            }
            String embeddedContractPath = PointerUtils.resolvePointer(
                    scopePath,
                    ProcessorPointerConstants.relativeContractsEntry(
                            embeddedKey));
            boolean routeDependencyChanged = rules.dependencyAffected(
                    scopePath, embeddedContractPath, changedPaths);
            for (String targetScope : embeddedRoutes) {
                ImmutablePatchPlanner
                        .forMaterialized(resolution.root)
                        .validateProcessEmbeddedTraversalPath(targetScope);
                if (!routeDependencyChanged
                        && !rules.branchAffected(
                                targetScope, changedPaths)) {
                    continue;
                }
                ScopeView child = resolution.scopeAt(targetScope);
                if (child == null || child.effective == null) {
                    // A declaration may reserve a future occurrence.
                    continue;
                }
                if (!rules.isObject(child.effective)) {
                    throw rules.invalid(
                            "Declared embedded child is not an object: "
                                    + targetScope,
                            scopePath,
                            embeddedKey);
                }
                collect(
                        resolution,
                        child,
                        targetScope,
                        result,
                        visitedPaths,
                        activeScopes,
                        activeExactScopes,
                        schedule,
                        routeDependencyChanged
                                ? Collections.singleton(targetScope)
                                : changedPaths,
                        depth + 1,
                        validationContext);
            }
        } finally {
            activeScopes.remove(identityNode);
            if (exactScopeIdentity != null) {
                activeExactScopes.remove(exactScopeIdentity);
            }
        }
    }

    private SubscriptionDelta.Entry descriptor(
            ContractBundle bundle,
            EffectiveContractSnapshot contract,
            String scopePath,
            GasSchedule schedule,
            SubscriptionSurfaceValidationContext validationContext) {
        FrozenNode frozen = bundle.contractNode(contract.key());
        if (frozen == null) {
            throw rules.invalid(
                    "Effective External Channel content is unavailable",
                    scopePath,
                    contract.key());
        }
        Node channelNode = frozen.toNode();
        rules.requireObjectLimits(
                channelNode, schedule, scopePath, contract.key());
        RuntimeWorkSession authoritative =
                validationContext.newRuntimeWorkSession();
        RuntimeWorkSession comparison = authoritative.diagnosticTwin();
        final ExternalChannelFunctionResolver.Header first;
        final ExternalChannelFunctionResolver.Header second;
        try {
            first = resolveHeader(bundle, contract, authoritative);
            second = resolveHeader(bundle, contract, comparison);
            if (!first.sameResult(second)
                    || !sameRuntimeTrace(
                            authoritative.stagedTrace(),
                            comparison.stagedTrace())) {
                authoritative.failDeterministically();
                comparison.suspend();
                throw rules.invalid(
                        "External Channel subscription functions are not "
                                + "deterministic over an immutable snapshot",
                        scopePath,
                        contract.key());
            }
            authoritative.complete();
            comparison.suspend();
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            suspendIfOpen(authoritative);
            suspendIfOpen(comparison);
            throw unavailable;
        } catch (RuntimeException | Error failure) {
            failIfOpen(authoritative);
            suspendIfOpen(comparison);
            throw failure;
        }
        validateSubscriptionKeys(
                first.channelKeys(), schedule, scopePath, contract.key());
        return new SubscriptionDelta.Entry(
                scopePath,
                contract.key(),
                contract.effectiveTypeBlueId(),
                contract.sourceContributionNodeBlueIds(),
                contract.order(),
                first.channelKeys(),
                first.checkpointDomainBlueId(),
                first.dependencies(),
                null,
                null,
                null);
    }

    private ExternalChannelFunctionResolver.Header resolveHeader(
            ContractBundle bundle,
            EffectiveContractSnapshot contract,
            RuntimeWorkSession runtimeWorkSession) {
        ExternalChannelFunctionEvaluation.MatcherSession matcher =
                ExternalChannelFunctionEvaluation
                        .verifiedMatcherSessions(snapshotManager)
                        .open();
        try {
            return new ExternalChannelFunctionResolver(
                    registry,
                    converter,
                    matcher,
                    bundle,
                    null,
                    runtimeWorkSession)
                    .header(contract);
        } finally {
            matcher.close();
        }
    }

    private void validateSubscriptionKeys(
            List<String> keys,
            GasSchedule schedule,
            String scopePath,
            String key) {
        if (keys == null) {
            throw rules.invalid(
                    "External Channel subscription functions returned no "
                            + "finite key set",
                    scopePath,
                    key);
        }
        rules.requireLimit(
                GasScheduleConstants.PortableLimit
                        .SUBSCRIPTION_KEYS_PER_CHANNEL,
                keys.size(),
                schedule.portableLimit(
                        GasScheduleConstants.PortableLimit
                                .SUBSCRIPTION_KEYS_PER_CHANNEL),
                scopePath,
                key);
        Set<String> unique = new LinkedHashSet<>();
        for (String subscriptionKey : keys) {
            if (subscriptionKey == null
                    || subscriptionKey.isEmpty()
                    || !unique.add(subscriptionKey)) {
                throw rules.invalid(
                        "Subscription keys must be unique non-empty Text",
                        scopePath,
                        key);
            }
        }
        if (keys.isEmpty()) {
            throw rules.invalid(
                    "External Channel must have a finite non-empty "
                            + "subscription key set",
                    scopePath,
                    key);
        }
    }

    private static void failIfOpen(RuntimeWorkSession session) {
        if (session.isOpen()) {
            session.failDeterministically();
        }
    }

    private static void suspendIfOpen(RuntimeWorkSession session) {
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
                    || !Objects.equals(a.scopePath(), b.scopePath())
                    || !Objects.equals(a.contractKey(), b.contractKey())
                    || !Objects.equals(a.logicalPath(), b.logicalPath())
                    || !Objects.equals(a.reason(), b.reason())) {
                return false;
            }
        }
        return true;
    }

    /** Resolves and caches effective scope views for one projection. */
    private final class EffectiveResolution {
        private final Node root;
        private final ResolvedSnapshot snapshot;
        private final Map<String, ScopeView> scopes = new LinkedHashMap<>();
        private final Set<String> absent = new LinkedHashSet<>();

        private EffectiveResolution(
                Node root,
                ResolvedSnapshot suppliedSnapshot) {
            this.root = Objects.requireNonNull(root, "root");
            this.snapshot = suppliedSnapshot != null
                    ? suppliedSnapshot
                    : snapshotManager != null
                    ? snapshotManager.fromDocumentTransient(root.clone())
                    : null;
        }

        private ScopeView scopeAt(String scopePath) {
            String normalized = PointerUtils.normalizeScope(scopePath);
            ScopeView cached = scopes.get(normalized);
            if (cached != null || absent.contains(normalized)) {
                return cached;
            }
            Node selected;
            Node effective;
            if (snapshot != null) {
                selected = JsonPointer.ROOT.equals(normalized)
                        ? snapshot.canonicalRoot()
                        : snapshot.canonicalNodeAt(normalized);
                effective = JsonPointer.ROOT.equals(normalized)
                        ? snapshot.resolvedRoot()
                        : snapshot.resolvedNodeAt(normalized);
            } else {
                selected = rules.nodeAtRoot(root, normalized);
                effective = selected;
            }
            if (effective == null) {
                absent.add(normalized);
                return null;
            }
            ContractBundle bundle;
            if (snapshot != null) {
                bundle = contractLoader.load(snapshot, normalized);
            } else {
                FrozenNode selectedFrozen = selected != null
                        ? FrozenNode.fromResolvedNode(selected)
                        : null;
                FrozenNode effectiveFrozen =
                        FrozenNode.fromResolvedNode(effective);
                bundle = contractLoader.load(
                        selectedFrozen,
                        effectiveFrozen,
                        normalized);
            }
            ScopeView created = new ScopeView(selected, effective, bundle);
            scopes.put(normalized, created);
            return created;
        }
    }

    /** Immutable selected/effective view of one subscription scope. */
    private static final class ScopeView {
        private final Node selected;
        private final Node effective;
        private final ContractBundle bundle;

        private ScopeView(
                Node selected,
                Node effective,
                ContractBundle bundle) {
            this.selected = selected;
            this.effective = effective;
            this.bundle = Objects.requireNonNull(bundle, "bundle");
        }
    }
}
