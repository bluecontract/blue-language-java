package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projects the changed subscription surface directly from materialized nodes.
 *
 * <p>This fallback path is used by the stateless public validator. It preserves
 * the finite direct-contract behavior while keeping traversal state separate
 * from orchestration and delta construction.</p>
 */
final class DirectSubscriptionSurfaceProjector {

    private static final String KEY_CHECKPOINT_DOMAIN = "checkpointDomain";
    private static final String KEY_ORDER = "order";

    private final SubscriptionSurfaceRules rules;
    private final EmbeddedSubscriptionRouteProjector routes;

    DirectSubscriptionSurfaceProjector(SubscriptionSurfaceRules rules) {
        this.rules = rules;
        this.routes = new EmbeddedSubscriptionRouteProjector(rules);
    }

    /** Projects only occurrences whose dependencies overlap changed paths. */
    Map<String, SubscriptionDelta.Entry> project(
            Node root,
            GasSchedule schedule,
            Set<String> changedPaths,
            SubscriptionSurfaceValidationContext validationContext,
            SubscriptionSurfaceProjector.EmbeddedMembership membership) {
        if (!rules.isConcrete(root)) {
            throw rules.invalid(
                    "Root subscription scope must be concrete",
                    JsonPointer.ROOT,
                    null);
        }
        Map<String, SubscriptionDelta.Entry> result = new LinkedHashMap<>();
        collect(
                root,
                JsonPointer.ROOT,
                result,
                new LinkedHashSet<String>(),
                new IdentityHashMap<Node, String>(),
                new LinkedHashMap<String, String>(),
                schedule,
                changedPaths,
                0,
                validationContext,
                membership);
        return result;
    }

    private void collect(Node scope,
                         String scopePath,
                         Map<String, SubscriptionDelta.Entry> result,
                         Set<String> visitedPaths,
                         IdentityHashMap<Node, String> activeScopes,
                         Map<String, String> activeExactScopes,
                         GasSchedule schedule,
                         Set<String> changedPaths,
                         int depth,
                         SubscriptionSurfaceValidationContext
                                 validationContext,
                         SubscriptionSurfaceProjector.EmbeddedMembership
                                 membership) {
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
        String activeAt = activeScopes.put(scope, scopePath);
        if (activeAt != null) {
            throw rules.invalid(
                    "Declared embedded ancestry cycle between "
                            + activeAt + " and " + scopePath,
                    scopePath,
                    null);
        }
        String exactScopeIdentity = rules.declaredExactIdentity(scope);
        if (exactScopeIdentity != null) {
            String sameExactScopeAt =
                    activeExactScopes.put(exactScopeIdentity, scopePath);
            if (sameExactScopeAt != null) {
                activeScopes.remove(scope);
                throw rules.invalid(
                        "Declared embedded ancestry revisits exact node "
                                + exactScopeIdentity + " at "
                                + sameExactScopeAt + " and " + scopePath,
                        scopePath,
                        null);
            }
        }
        try {
            rules.requireObjectLimits(scope, schedule, scopePath, null);
            if (rules.directTerminated(scope)) {
                return;
            }
            Node contracts = scope.getContracts();
            if (contracts == null) {
                return;
            }
            if (!rules.isObject(contracts)) {
                throw rules.invalid(
                        "contracts must be a direct object map",
                        scopePath,
                        null);
            }
            rules.requireObjectLimits(contracts, schedule, scopePath, null);
            Map<String, Node> entries = contracts.getProperties() != null
                    ? contracts.getProperties()
                    : Collections.<String, Node>emptyMap();
            rules.requireLimit(
                    GasScheduleConstants.PortableLimit
                            .EFFECTIVE_CONTRACTS_PER_SCOPE,
                    entries.size(),
                    schedule.portableLimit(
                            GasScheduleConstants.PortableLimit
                                    .EFFECTIVE_CONTRACTS_PER_SCOPE),
                    scopePath,
                    null);

            int externalCount = 0;
            List<String> embeddedRoutes = new ArrayList<>();
            String embeddedKey = null;
            for (Map.Entry<String, Node> contract : entries.entrySet()) {
                rules.validateContractKey(
                        contract.getKey(), schedule, scopePath);
                String typeBlueId = rules.recognizedType(contract.getValue());
                String contractPath = PointerUtils.resolvePointer(
                        scopePath,
                        ProcessorPointerConstants.relativeContractsEntry(
                                contract.getKey()));
                if (rules.isKnownExternalType(typeBlueId)) {
                    externalCount++;
                    rules.requireLimit(
                            GasScheduleConstants.PortableLimit
                                    .EXTERNAL_CHANNELS_PER_SCOPE,
                            externalCount,
                            schedule.portableLimit(
                                    GasScheduleConstants.PortableLimit
                                            .EXTERNAL_CHANNELS_PER_SCOPE),
                            scopePath,
                            contract.getKey());
                    if (rules.dependencyAffected(
                            scopePath, contractPath, changedPaths)
                            || rules.sameScopeContractsAffected(
                                    scopePath, changedPaths)) {
                        SubscriptionDelta.Entry descriptor = descriptor(
                                contract.getValue(),
                                typeBlueId,
                                scopePath,
                                contract.getKey(),
                                schedule);
                        if (result.put(
                                descriptor.occurrenceKey(), descriptor)
                                != null) {
                            throw rules.invalid(
                                    "Duplicate external subscription occurrence",
                                    scopePath,
                                    contract.getKey());
                        }
                    }
                }
                if (RuntimeBlueIds.PROCESS_EMBEDDED.equals(typeBlueId)) {
                    if (embeddedKey != null) {
                        throw rules.invalid(
                                "Multiple effective Process Embedded contracts",
                                scopePath,
                                contract.getKey());
                    }
                    embeddedKey = contract.getKey();
                    EmbeddedScopePlan entryPlan =
                            membership
                                    == SubscriptionSurfaceProjector
                                            .EmbeddedMembership.ENTRY
                                    && validationContext
                                            .hasEntryEmbeddedScopePlan(
                                                    scopePath)
                            ? validationContext.entryEmbeddedScopePlan(
                                    scopePath)
                            : null;
                    embeddedRoutes = routes.projectScope(
                            scope,
                            routes.declaration(
                                    contract.getValue(),
                                    scopePath,
                                    contract.getKey()),
                            entryPlan,
                            scopePath,
                            schedule,
                            new EmbeddedScopePlanner());
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
                        .forMaterialized(scope)
                        .validateProcessEmbeddedTraversalPath(
                                PointerUtils.relativizePointer(
                                        scopePath, targetScope));
                if (!routeDependencyChanged
                        && !rules.branchAffected(
                                targetScope, changedPaths)) {
                    continue;
                }
                Node child = rules.nodeAt(scope, scopePath, targetScope);
                if (child == null) {
                    // A declaration may reserve a future occurrence.
                    continue;
                }
                if (!rules.isObject(child)) {
                    throw rules.invalid(
                            "Declared embedded child is not an object: "
                                    + targetScope,
                            scopePath,
                            embeddedKey);
                }
                collect(
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
                        validationContext,
                        membership);
            }
        } finally {
            activeScopes.remove(scope);
            if (exactScopeIdentity != null) {
                activeExactScopes.remove(exactScopeIdentity);
            }
        }
    }

    private SubscriptionDelta.Entry descriptor(
            Node channel,
            String effectiveTypeBlueId,
            String scopePath,
            String key,
            GasSchedule schedule) {
        rules.requireObjectLimits(channel, schedule, scopePath, key);
        List<String> keys = rules.subscriptionKeys(channel, scopePath, key);
        rules.requireLimit(
                GasScheduleConstants.PortableLimit
                        .SUBSCRIPTION_KEYS_PER_CHANNEL,
                keys.size(),
                schedule.portableLimit(
                        GasScheduleConstants.PortableLimit
                                .SUBSCRIPTION_KEYS_PER_CHANNEL),
                scopePath,
                key);
        if (keys.isEmpty()) {
            throw rules.invalid(
                    "External Channel must have a finite non-empty "
                            + "subscription key set",
                    scopePath,
                    key);
        }
        String contribution = rules.exactIdentity(channel);
        String domain = CheckpointDomain.derive(
                effectiveTypeBlueId,
                Collections.singletonList(contribution),
                rules.textField(channel, KEY_CHECKPOINT_DOMAIN));
        return new SubscriptionDelta.Entry(
                scopePath,
                key,
                effectiveTypeBlueId,
                Collections.singletonList(contribution),
                rules.integerField(
                        channel, KEY_ORDER, 0, scopePath, key),
                keys,
                domain,
                null);
    }
}
