package blue.language.processor;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.Contract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.JsonPointer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Finite validator for the core and fixture External Channel laws.
 *
 * <p>The validator examines only contract/type dependencies and embedded
 * branches affected by the committed paths. The production instance resolves
 * effective contracts and obtains additional channel-type functions from the
 * processor's fixed runtime registry.</p>
 */
public final class DirectSubscriptionSurfaceValidator
        implements SubscriptionSurfaceValidator {

    public static final DirectSubscriptionSurfaceValidator INSTANCE =
            new DirectSubscriptionSurfaceValidator();

    private final ContractLoader contractLoader;
    private final ProcessingSnapshotManager snapshotManager;
    private final ContractProcessorRegistry registry;
    private final NodeToObjectConverter converter;

    private DirectSubscriptionSurfaceValidator() {
        this(null, null, null, null);
    }

    private DirectSubscriptionSurfaceValidator(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter) {
        this.contractLoader = contractLoader;
        this.snapshotManager = snapshotManager;
        this.registry = registry;
        this.converter = converter;
    }

    static DirectSubscriptionSurfaceValidator configured(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter) {
        return new DirectSubscriptionSurfaceValidator(
                contractLoader, snapshotManager, registry, converter);
    }

    @Override
    public SubscriptionDelta validate(Node inputRoot,
                                      Node tentativeRoot,
                                      Set<String> changedPaths,
                                      GasSchedule schedule) {
        return validate(SubscriptionSurfaceValidationContext.builder(
                        inputRoot,
                        tentativeRoot,
                        changedPaths != null
                                ? changedPaths
                                : Collections.<String>emptySet(),
                        schedule)
                .build());
    }

    @Override
    public SubscriptionDelta validate(
            SubscriptionSurfaceValidationContext context) {
        if (context.changedPaths().isEmpty()) {
            return SubscriptionDelta.empty();
        }
        try {
            Set<String> normalized =
                    normalizeChanges(context.changedPaths());
            Map<String, SubscriptionDelta.Entry> before =
                    context.hasActiveSubscriptionIntervals()
                            ? retainedChangedSurface(
                            context.inputRoot(),
                            context.tentativeRoot(),
                            context.activeSubscriptionIntervals(),
                            normalized)
                            : surface(
                            context.inputRoot(),
                            context.inputSnapshot(),
                            context.gasSchedule(),
                            normalized);
            Map<String, SubscriptionDelta.Entry> after =
                    surface(
                            context.tentativeRoot(),
                            context.tentativeSnapshot(),
                            context.gasSchedule(),
                            normalized);
            List<SubscriptionDelta.Entry> removed = new ArrayList<>();
            List<SubscriptionDelta.Entry> added = new ArrayList<>();
            for (Map.Entry<String, SubscriptionDelta.Entry> entry
                    : before.entrySet()) {
                SubscriptionDelta.Entry replacement =
                        after.get(entry.getKey());
                if (!entry.getValue().sameSubscriptionSnapshot(
                        replacement)) {
                    removed.add(retired(
                            entry.getValue(), context));
                }
            }
            for (Map.Entry<String, SubscriptionDelta.Entry> entry
                    : after.entrySet()) {
                SubscriptionDelta.Entry previous =
                        before.get(entry.getKey());
                if (!entry.getValue().sameSubscriptionSnapshot(
                        previous)) {
                    added.add(activated(
                            entry.getValue(), context));
                }
            }
            return new SubscriptionDelta(added, removed);
        } catch (SubscriptionSurfaceInvalidException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid(
                    "Subscription surface derivation failed: "
                            + ProcessorEngine.deterministicMessage(
                            exception, "invalid changed surface"),
                    "/",
                    null);
        }
    }

    private Map<String, SubscriptionDelta.Entry> retainedChangedSurface(
            Node inputRoot,
            Node tentativeRoot,
            List<SubscriptionDelta.Entry> retainedIntervals,
            Set<String> changedPaths) {
        Map<String, SubscriptionDelta.Entry> result =
                new LinkedHashMap<>();
        for (SubscriptionDelta.Entry interval : retainedIntervals) {
            if (retainedOccurrenceAffected(
                    interval,
                    changedPaths,
                    inputRoot,
                    tentativeRoot)) {
                result.put(interval.occurrenceKey(), interval);
            }
        }
        return result;
    }

    private boolean retainedOccurrenceAffected(
            SubscriptionDelta.Entry interval,
            Set<String> changedPaths,
            Node inputRoot,
            Node tentativeRoot) {
        String scopePath =
                PointerUtils.normalizeScope(interval.scopePath());
        String contractPath = PointerUtils.resolvePointer(
                scopePath,
                "/contracts/"
                        + JsonPointer.escape(interval.channelKey()));
        if (dependencyAffected(
                scopePath, contractPath, changedPaths)) {
            return true;
        }
        for (String changed : changedPaths) {
            /*
             * Replacing/removing an ancestor branch changes reachability of
             * every retained occurrence below it. Ordinary descendant payload
             * writes do not.
             */
            if (PointerUtils.descendantOrEqual(
                    scopePath, changed)) {
                return true;
            }
        }
        for (String ancestor : ancestorScopes(scopePath)) {
            String typePath = PointerUtils.resolvePointer(
                    ancestor, "/type");
            String terminationPath = PointerUtils.resolvePointer(
                    ancestor, "/contracts/terminated");
            String contractsPath = PointerUtils.resolvePointer(
                    ancestor, "/contracts");
            for (String changed : changedPaths) {
                if (overlaps(changed, typePath)
                        || overlaps(changed, terminationPath)
                        || changed.equals(contractsPath)
                        || processEmbeddedPathsChanged(
                        contractsPath, changed)
                        || processEmbeddedContractChanged(
                        ancestor,
                        contractsPath,
                        changed,
                        inputRoot,
                        tentativeRoot)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> ancestorScopes(String scopePath) {
        List<String> ancestors = new ArrayList<>();
        String current = "/";
        ancestors.add(current);
        List<String> segments = JsonPointer.split(scopePath);
        for (int index = 0;
             index + 1 < segments.size();
             index++) {
            current = PointerUtils.appendPointer(
                    current, segments.get(index));
            ancestors.add(current);
        }
        return ancestors;
    }

    private boolean processEmbeddedPathsChanged(
            String contractsPath,
            String changedPath) {
        if (!PointerUtils.descendantOrEqual(
                changedPath, contractsPath)
                || changedPath.equals(contractsPath)) {
            return false;
        }
        List<String> relative = JsonPointer.split(
                PointerUtils.relativizePointer(
                        contractsPath, changedPath));
        return relative.size() >= 2
                && "paths".equals(relative.get(1));
    }

    private boolean processEmbeddedContractChanged(
            String scopePath,
            String contractsPath,
            String changedPath,
            Node inputRoot,
            Node tentativeRoot) {
        if (!PointerUtils.descendantOrEqual(
                changedPath, contractsPath)
                || changedPath.equals(contractsPath)) {
            return false;
        }
        List<String> relative = JsonPointer.split(
                PointerUtils.relativizePointer(
                        contractsPath, changedPath));
        if (relative.isEmpty()) {
            return false;
        }
        String contractKey = relative.get(0);
        return isDirectProcessEmbeddedContract(
                inputRoot, scopePath, contractKey)
                || isDirectProcessEmbeddedContract(
                tentativeRoot, scopePath, contractKey);
    }

    private boolean isDirectProcessEmbeddedContract(
            Node root,
            String scopePath,
            String contractKey) {
        Node scope = nodeAtRoot(root, scopePath);
        Node contracts =
                scope != null ? scope.getContracts() : null;
        Node contract = contracts != null
                && contracts.getProperties() != null
                ? contracts.getProperties().get(contractKey)
                : null;
        return contract != null
                && RuntimeBlueIds.PROCESS_EMBEDDED.equals(
                recognizedType(contract));
    }

    private SubscriptionDelta.Entry activated(
            SubscriptionDelta.Entry entry,
            SubscriptionSurfaceValidationContext context) {
        return hasCommittingInterval(context)
                ? entry.activatedAt(
                context.committingRootRevision(),
                context.currentEventOrderKey())
                : entry;
    }

    private SubscriptionDelta.Entry retired(
            SubscriptionDelta.Entry entry,
            SubscriptionSurfaceValidationContext context) {
        return hasCommittingInterval(context)
                ? entry.retiredAt(context.committingRootRevision())
                : entry;
    }

    private boolean hasCommittingInterval(
            SubscriptionSurfaceValidationContext context) {
        return context.committingRootRevision() != null
                && context.currentEventOrderKey() != null;
    }

    private Map<String, SubscriptionDelta.Entry> surface(
            Node root,
            ResolvedSnapshot suppliedSnapshot,
            GasSchedule schedule,
            Set<String> changedPaths) {
        if (contractLoader != null && registry != null) {
            return effectiveSurface(
                    root, suppliedSnapshot, schedule, changedPaths);
        }
        if (!isConcrete(root)) {
            throw invalid("Root subscription scope must be concrete",
                    "/", null);
        }
        Map<String, SubscriptionDelta.Entry> result =
                new LinkedHashMap<>();
        collect(
                root,
                "/",
                result,
                new LinkedHashSet<String>(),
                new IdentityHashMap<Node, String>(),
                new LinkedHashMap<String, String>(),
                schedule,
                changedPaths,
                0);
        return result;
    }

    private Map<String, SubscriptionDelta.Entry> effectiveSurface(
            Node root,
            ResolvedSnapshot suppliedSnapshot,
            GasSchedule schedule,
            Set<String> changedPaths) {
        EffectiveResolution resolution =
                new EffectiveResolution(root, suppliedSnapshot);
        ScopeView rootScope = resolution.scopeAt("/");
        if (rootScope == null || !isConcrete(rootScope.effective)) {
            throw invalid("Root subscription scope must be concrete",
                    "/", null);
        }
        Map<String, SubscriptionDelta.Entry> result =
                new LinkedHashMap<>();
        collectEffective(
                resolution,
                rootScope,
                "/",
                result,
                new LinkedHashSet<String>(),
                new IdentityHashMap<Node, String>(),
                new LinkedHashMap<String, String>(),
                schedule,
                changedPaths,
                0);
        return result;
    }

    private void collectEffective(
            EffectiveResolution resolution,
            ScopeView scope,
            String scopePath,
            Map<String, SubscriptionDelta.Entry> result,
            Set<String> visitedPaths,
            IdentityHashMap<Node, String> activeScopes,
            Map<String, String> activeExactScopes,
            GasSchedule schedule,
            Set<String> changedPaths,
            int depth) {
        requireLimit(
                "embeddedDepth",
                depth,
                schedule.portableLimit("embeddedDepth"),
                scopePath,
                null);
        if (!visitedPaths.add(scopePath)) {
            throw invalid(
                    "Duplicate or ambiguous embedded route to " + scopePath,
                    scopePath,
                    null);
        }
        Node identityNode =
                scope.selected != null ? scope.selected : scope.effective;
        String activeAt = activeScopes.put(identityNode, scopePath);
        if (activeAt != null) {
            throw invalid(
                    "Declared embedded ancestry cycle between "
                            + activeAt + " and " + scopePath,
                    scopePath,
                    null);
        }
        String exactScopeIdentity =
                declaredExactIdentity(identityNode);
        if (exactScopeIdentity != null) {
            String sameExactScopeAt =
                    activeExactScopes.put(
                            exactScopeIdentity, scopePath);
            if (sameExactScopeAt != null) {
                activeScopes.remove(identityNode);
                throw invalid(
                        "Declared embedded ancestry revisits exact node "
                                + exactScopeIdentity + " at "
                                + sameExactScopeAt + " and " + scopePath,
                        scopePath,
                        null);
            }
        }
        try {
            requireObjectLimits(
                    scope.effective, schedule, scopePath, null);
            if (directTerminated(scope.selected)) {
                return;
            }
            ContractBundle bundle = scope.bundle;
            List<EffectiveContractSnapshot> contracts =
                    bundle.effectiveContractSnapshots();
            requireLimit(
                    "effectiveContractsPerParticipatingScope",
                    contracts.size(),
                    schedule.portableLimit(
                            "effectiveContractsPerParticipatingScope"),
                    scopePath,
                    null);

            int externalCount = 0;
            List<EmbeddedRoute> embeddedRoutes =
                    Collections.emptyList();
            String embeddedKey = null;
            for (EffectiveContractSnapshot contract : contracts) {
                validateContractKey(
                        contract.key(), schedule, scopePath);
                String contractPath = PointerUtils.resolvePointer(
                        scopePath,
                        "/contracts/"
                                + JsonPointer.escape(contract.key()));
                if ("external-channel".equals(contract.role())) {
                    externalCount++;
                    requireLimit(
                            "externalChannelsPerScope",
                            externalCount,
                            schedule.portableLimit(
                                    "externalChannelsPerScope"),
                            scopePath,
                            contract.key());
                    if (dependencyAffected(
                            scopePath, contractPath, changedPaths)) {
                        SubscriptionDelta.Entry descriptor =
                                effectiveExternalDescriptor(
                                        bundle,
                                        contract,
                                        scopePath,
                                        schedule);
                        if (result.put(
                                descriptor.occurrenceKey(),
                                descriptor) != null) {
                            throw invalid(
                                    "Duplicate external subscription occurrence",
                                    scopePath,
                                    contract.key());
                        }
                    }
                } else if ("process-embedded".equals(contract.role())) {
                    if (embeddedKey != null) {
                        throw invalid(
                                "Multiple effective Process Embedded contracts",
                                scopePath,
                                contract.key());
                    }
                    embeddedKey = contract.key();
                    embeddedRoutes = embeddedRoutes(
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
                    "/contracts/" + JsonPointer.escape(embeddedKey));
            boolean routeDependencyChanged = dependencyAffected(
                    scopePath, embeddedContractPath, changedPaths);
            for (EmbeddedRoute route : embeddedRoutes) {
                if (!routeDependencyChanged
                        && !branchAffected(
                        route.targetScope, changedPaths)) {
                    continue;
                }
                ScopeView child =
                        resolution.scopeAt(route.targetScope);
                if (child == null || child.effective == null) {
                    /*
                     * A declaration may reserve a future occurrence. Missing
                     * children contribute no active subscription scope.
                     */
                    continue;
                }
                if (!isObject(child.effective)) {
                    throw invalid(
                            "Declared embedded child is not an object: "
                                    + route.targetScope,
                            scopePath,
                            embeddedKey);
                }
                collectEffective(
                        resolution,
                        child,
                        route.targetScope,
                        result,
                        visitedPaths,
                        activeScopes,
                        activeExactScopes,
                        schedule,
                        routeDependencyChanged
                                ? Collections.singleton(route.targetScope)
                                : changedPaths,
                        depth + 1);
            }
        } finally {
            activeScopes.remove(identityNode);
            if (exactScopeIdentity != null) {
                activeExactScopes.remove(exactScopeIdentity);
            }
        }
    }

    private SubscriptionDelta.Entry effectiveExternalDescriptor(
            ContractBundle bundle,
            EffectiveContractSnapshot contract,
            String scopePath,
            GasSchedule schedule) {
        FrozenNode frozen = bundle.contractNode(contract.key());
        if (frozen == null) {
            throw invalid(
                    "Effective External Channel content is unavailable",
                    scopePath,
                    contract.key());
        }
        Node channelNode = frozen.toNode();
        requireObjectLimits(
                channelNode, schedule, scopePath, contract.key());
        RegisteredSubscriptionHeader first =
                registeredSubscriptionHeader(
                        contract, channelNode, scopePath);
        /*
         * Invoke the immutable functions against an independent conversion.
         * This catches stateful function implementations without letting a
         * mutating function corrupt the ContractLoader's cached binding.
         */
        RegisteredSubscriptionHeader second =
                registeredSubscriptionHeader(
                        contract, channelNode, scopePath);
        if (!first.equals(second)) {
            throw invalid(
                    "External Channel subscription functions are not "
                            + "deterministic over an immutable snapshot",
                    scopePath,
                    contract.key());
        }
        validateSubscriptionKeys(
                first.keys, schedule, scopePath, contract.key());
        String domain = CheckpointDomain.derive(
                contract.effectiveTypeBlueId(),
                contract.sourceContributionNodeBlueIds(),
                first.checkpointDomainDiscriminator);
        return new SubscriptionDelta.Entry(
                scopePath,
                contract.key(),
                contract.effectiveTypeBlueId(),
                contract.sourceContributionNodeBlueIds(),
                contract.order(),
                first.keys,
                domain,
                null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RegisteredSubscriptionHeader registeredSubscriptionHeader(
            EffectiveContractSnapshot snapshot,
            Node channelNode,
            String scopePath) {
        Contract converted = converter != null
                ? converter.convertWithType(
                channelNode.clone(), Contract.class, false)
                : null;
        if (!(converted instanceof ChannelContract)) {
            throw invalid(
                    "Effective External Channel could not be converted",
                    scopePath,
                    snapshot.key());
        }
        ChannelContract channel = (ChannelContract) converted;
        channel.setKey(snapshot.key());
        channel.setTypeBlueId(snapshot.effectiveTypeBlueId());
        ChannelProcessor processor = registry.lookupChannel(channel)
                .orElse(null);
        ExternalChannelSubscriptionFunctions functions =
                processor != null
                        ? processor.externalSubscriptionFunctions()
                        : null;
        if (functions == null) {
            throw invalid(
                    "External Channel runtime type does not expose supported "
                            + "immutable subscription functions: "
                            + snapshot.effectiveTypeBlueId(),
                    scopePath,
                    snapshot.key());
        }
        List<String> suppliedKeys =
                functions.channelKeys(channel);
        List<String> keys = suppliedKeys != null
                ? new ArrayList<>(suppliedKeys)
                : null;
        String discriminator =
                functions.checkpointDomainDiscriminator(channel);
        return new RegisteredSubscriptionHeader(keys, discriminator);
    }

    private void validateSubscriptionKeys(
            List<String> keys,
            GasSchedule schedule,
            String scopePath,
            String key) {
        if (keys == null) {
            throw invalid(
                    "External Channel subscription functions returned no "
                            + "finite key set",
                    scopePath,
                    key);
        }
        requireLimit(
                "subscriptionKeysPerChannel",
                keys.size(),
                schedule.portableLimit("subscriptionKeysPerChannel"),
                scopePath,
                key);
        Set<String> unique = new LinkedHashSet<>();
        for (String subscriptionKey : keys) {
            if (subscriptionKey == null
                    || subscriptionKey.isEmpty()
                    || !unique.add(subscriptionKey)) {
                throw invalid(
                        "Subscription keys must be unique non-empty Text",
                        scopePath,
                        key);
            }
        }
        if (keys.isEmpty()) {
            throw invalid(
                    "External Channel must have a finite non-empty "
                            + "subscription key set",
                    scopePath,
                    key);
        }
    }

    private void collect(Node scope,
                         String scopePath,
                         Map<String, SubscriptionDelta.Entry> result,
                         Set<String> visitedPaths,
                         IdentityHashMap<Node, String> activeScopes,
                         Map<String, String> activeExactScopes,
                         GasSchedule schedule,
                         Set<String> changedPaths,
                         int depth) {
        requireLimit(
                "embeddedDepth",
                depth,
                schedule.portableLimit("embeddedDepth"),
                scopePath,
                null);
        if (!visitedPaths.add(scopePath)) {
            throw invalid(
                    "Duplicate or ambiguous embedded route to " + scopePath,
                    scopePath,
                    null);
        }
        String activeAt = activeScopes.put(scope, scopePath);
        if (activeAt != null) {
            throw invalid(
                    "Declared embedded ancestry cycle between "
                            + activeAt + " and " + scopePath,
                    scopePath,
                    null);
        }
        String exactScopeIdentity =
                declaredExactIdentity(scope);
        if (exactScopeIdentity != null) {
            String sameExactScopeAt =
                    activeExactScopes.put(
                            exactScopeIdentity, scopePath);
            if (sameExactScopeAt != null) {
                activeScopes.remove(scope);
                throw invalid(
                        "Declared embedded ancestry revisits exact node "
                                + exactScopeIdentity + " at "
                                + sameExactScopeAt + " and " + scopePath,
                        scopePath,
                        null);
            }
        }
        try {
            requireObjectLimits(scope, schedule, scopePath, null);
            if (directTerminated(scope)) {
                return;
            }
            Node contracts = scope.getContracts();
            if (contracts == null) {
                return;
            }
            if (!isObject(contracts)) {
                throw invalid("contracts must be a direct object map",
                        scopePath, null);
            }
            requireObjectLimits(contracts, schedule, scopePath, null);
            Map<String, Node> entries = contracts.getProperties() != null
                    ? contracts.getProperties()
                    : Collections.<String, Node>emptyMap();
            requireLimit(
                    "effectiveContractsPerParticipatingScope",
                    entries.size(),
                    schedule.portableLimit(
                            "effectiveContractsPerParticipatingScope"),
                    scopePath,
                    null);

            int externalCount = 0;
            List<EmbeddedRoute> embeddedRoutes = new ArrayList<>();
            String embeddedKey = null;
            for (Map.Entry<String, Node> contract : entries.entrySet()) {
                validateContractKey(
                        contract.getKey(), schedule, scopePath);
                String typeBlueId = recognizedType(contract.getValue());
                String contractPath = PointerUtils.resolvePointer(
                        scopePath,
                        "/contracts/"
                                + JsonPointer.escape(contract.getKey()));
                if (isKnownExternalType(typeBlueId)) {
                    externalCount++;
                    requireLimit(
                            "externalChannelsPerScope",
                            externalCount,
                            schedule.portableLimit(
                                    "externalChannelsPerScope"),
                            scopePath,
                            contract.getKey());
                    if (dependencyAffected(
                            scopePath, contractPath, changedPaths)) {
                        SubscriptionDelta.Entry descriptor =
                                externalDescriptor(
                                        contract.getValue(),
                                        typeBlueId,
                                        scopePath,
                                        contract.getKey(),
                                        schedule);
                        if (result.put(
                                descriptor.occurrenceKey(),
                                descriptor) != null) {
                            throw invalid(
                                    "Duplicate external subscription occurrence",
                                    scopePath,
                                    contract.getKey());
                        }
                    }
                }
                if (RuntimeBlueIds.PROCESS_EMBEDDED.equals(typeBlueId)) {
                    if (embeddedKey != null) {
                        throw invalid(
                                "Multiple effective Process Embedded contracts",
                                scopePath,
                                contract.getKey());
                    }
                    embeddedKey = contract.getKey();
                    embeddedRoutes = embeddedRoutes(
                            contract.getValue(),
                            scopePath,
                            contract.getKey(),
                            schedule);
                }
            }

            if (embeddedKey == null) {
                return;
            }
            String embeddedContractPath = PointerUtils.resolvePointer(
                    scopePath,
                    "/contracts/" + JsonPointer.escape(embeddedKey));
            boolean routeDependencyChanged = dependencyAffected(
                    scopePath, embeddedContractPath, changedPaths);
            for (EmbeddedRoute route : embeddedRoutes) {
                if (!routeDependencyChanged
                        && !branchAffected(
                        route.targetScope, changedPaths)) {
                    continue;
                }
                Node child = nodeAt(
                        scope, scopePath, route.targetScope);
                if (child == null) {
                    /*
                     * A declaration may reserve a future occurrence. Missing
                     * children contribute no active subscription scope.
                     */
                    continue;
                }
                if (!isObject(child)) {
                    throw invalid(
                            "Declared embedded child is not an object: "
                                    + route.targetScope,
                            scopePath,
                            embeddedKey);
                }
                collect(
                        child,
                        route.targetScope,
                        result,
                        visitedPaths,
                        activeScopes,
                        activeExactScopes,
                        schedule,
                        routeDependencyChanged
                                ? Collections.singleton(route.targetScope)
                                : changedPaths,
                        depth + 1);
            }
        } finally {
            activeScopes.remove(scope);
            if (exactScopeIdentity != null) {
                activeExactScopes.remove(exactScopeIdentity);
            }
        }
    }

    private SubscriptionDelta.Entry externalDescriptor(
            Node channel,
            String effectiveTypeBlueId,
            String scopePath,
            String key,
            GasSchedule schedule) {
        requireObjectLimits(channel, schedule, scopePath, key);
        List<String> keys = subscriptionKeys(
                channel, scopePath, key);
        requireLimit(
                "subscriptionKeysPerChannel",
                keys.size(),
                schedule.portableLimit("subscriptionKeysPerChannel"),
                scopePath,
                key);
        if (keys.isEmpty()) {
            throw invalid(
                    "External Channel must have a finite non-empty "
                            + "subscription key set",
                    scopePath,
                    key);
        }
        String contribution = exactIdentity(channel);
        String domain = CheckpointDomain.derive(
                effectiveTypeBlueId,
                Collections.singletonList(contribution),
                textField(channel, "checkpointDomain"));
        return new SubscriptionDelta.Entry(
                scopePath,
                key,
                effectiveTypeBlueId,
                Collections.singletonList(contribution),
                integerField(channel, "order", 0, scopePath, key),
                keys,
                domain,
                null);
    }

    private List<EmbeddedRoute> embeddedRoutes(
            Node embedded,
            String scopePath,
            String key,
            GasSchedule schedule) {
        Node paths = property(embedded, "paths");
        if (paths == null || paths.getItems() == null) {
            throw invalid(
                    "Process Embedded paths must be a finite List",
                    scopePath,
                    key);
        }
        requireLimit(
                "processEmbeddedPathsPerScope",
                paths.getItems().size(),
                schedule.portableLimit(
                        "processEmbeddedPathsPerScope"),
                scopePath,
                key);
        List<EmbeddedRoute> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (Node item : paths.getItems()) {
            Object value = item != null ? item.getValue() : null;
            if (!(value instanceof String)) {
                throw invalid(
                        "Process Embedded path must be Text",
                        scopePath,
                        key);
            }
            String relative;
            try {
                relative = PointerUtils.assertValidRuntimePointer(
                        (String) value);
            } catch (IllegalArgumentException exception) {
                throw invalid(
                        "Invalid Process Embedded path: " + value,
                        scopePath,
                        key);
            }
            String target = PointerUtils.resolvePointer(
                    scopePath, relative);
            if (target.equals(scopePath) || !unique.add(target)) {
                throw invalid(
                        "Duplicate or cyclic Process Embedded path: "
                                + value,
                        scopePath,
                        key);
            }
            for (EmbeddedRoute prior : result) {
                if (PointerUtils.descendantOrEqual(
                        target, prior.targetScope)
                        || PointerUtils.descendantOrEqual(
                        prior.targetScope, target)) {
                    throw invalid(
                            "Ambiguous Process Embedded paths: "
                                    + prior.targetScope + " and " + target,
                            scopePath,
                            key);
                }
            }
            result.add(new EmbeddedRoute(target));
        }
        return result;
    }

    private List<EmbeddedRoute> embeddedRoutes(
            List<String> paths,
            String scopePath,
            String key,
            GasSchedule schedule) {
        if (paths == null) {
            throw invalid(
                    "Process Embedded paths must be a finite List",
                    scopePath,
                    key);
        }
        requireLimit(
                "processEmbeddedPathsPerScope",
                paths.size(),
                schedule.portableLimit(
                        "processEmbeddedPathsPerScope"),
                scopePath,
                key);
        List<EmbeddedRoute> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String value : paths) {
            if (value == null) {
                throw invalid(
                        "Process Embedded path must be Text",
                        scopePath,
                        key);
            }
            String relative;
            try {
                relative = PointerUtils.assertValidRuntimePointer(value);
            } catch (IllegalArgumentException exception) {
                throw invalid(
                        "Invalid Process Embedded path: " + value,
                        scopePath,
                        key);
            }
            String target = PointerUtils.resolvePointer(
                    scopePath, relative);
            if (target.equals(scopePath) || !unique.add(target)) {
                throw invalid(
                        "Duplicate or cyclic Process Embedded path: "
                                + value,
                        scopePath,
                        key);
            }
            for (EmbeddedRoute prior : result) {
                if (PointerUtils.descendantOrEqual(
                        target, prior.targetScope)
                        || PointerUtils.descendantOrEqual(
                        prior.targetScope, target)) {
                    throw invalid(
                            "Ambiguous Process Embedded paths: "
                                    + prior.targetScope + " and " + target,
                            scopePath,
                            key);
                }
            }
            result.add(new EmbeddedRoute(target));
        }
        return result;
    }

    private Set<String> normalizeChanges(Set<String> changes) {
        Set<String> result = new LinkedHashSet<>();
        for (String path : changes) {
            try {
                result.add(PointerUtils.assertValidRuntimePointer(path));
            } catch (RuntimeException exception) {
                throw invalid(
                        "Invalid changed path: " + path, "/", null);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private boolean dependencyAffected(String scopePath,
                                       String dependencyPath,
                                       Set<String> changes) {
        String typePath = PointerUtils.resolvePointer(scopePath, "/type");
        String terminationPath = PointerUtils.resolvePointer(
                scopePath, "/contracts/terminated");
        for (String changed : changes) {
            if (overlaps(changed, dependencyPath)
                    || overlaps(changed, typePath)
                    || overlaps(changed, terminationPath)
                    || "/".equals(changed)) {
                return true;
            }
        }
        return false;
    }

    private boolean branchAffected(String branch,
                                   Set<String> changes) {
        for (String changed : changes) {
            if (overlaps(changed, branch)) {
                return true;
            }
        }
        return false;
    }

    private boolean overlaps(String left, String right) {
        return PointerUtils.descendantOrEqual(left, right)
                || PointerUtils.descendantOrEqual(right, left);
    }

    private List<String> subscriptionKeys(Node channel,
                                          String scopePath,
                                          String key) {
        Node plural = property(channel, "subscriptionKeys");
        List<String> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        if (plural != null) {
            if (plural.getItems() == null) {
                throw invalid(
                        "subscriptionKeys must be a List",
                        scopePath,
                        key);
            }
            for (Node item : plural.getItems()) {
                Object value = item != null ? item.getValue() : null;
                if (!(value instanceof String)
                        || ((String) value).isEmpty()
                        || !unique.add((String) value)) {
                    throw invalid(
                            "Subscription keys must be unique non-empty Text",
                            scopePath,
                            key);
                }
                result.add((String) value);
            }
            return result;
        }
        String singular = textField(channel, "subscriptionKey");
        if (singular != null && !singular.isEmpty()) {
            result.add(singular);
        }
        return result;
    }

    private String recognizedType(Node contract) {
        Node type = contract != null ? contract.getType() : null;
        Set<String> visited = new LinkedHashSet<>();
        while (type != null) {
            String blueId = type.getBlueId() != null
                    ? type.getBlueId()
                    : BlueIdCalculator.calculateBlueId(type);
            if (!visited.add(blueId)) {
                throw new IllegalArgumentException(
                        "Cyclic effective contract type");
            }
            if (isKnownExternalType(blueId)
                    || RuntimeBlueIds.PROCESS_EMBEDDED.equals(blueId)) {
                return blueId;
            }
            if (type.isReferenceOnly()) {
                return blueId;
            }
            type = type.getType();
        }
        return null;
    }

    private boolean isKnownExternalType(String blueId) {
        return RuntimeBlueIds.EXTERNAL_CHANNEL.equals(blueId)
                || RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL.equals(blueId);
    }

    private boolean directTerminated(Node scope) {
        Node contracts = scope != null ? scope.getContracts() : null;
        Node marker = contracts != null && contracts.getProperties() != null
                ? contracts.getProperties().get("terminated")
                : null;
        return marker != null
                && RuntimeBlueIds.PROCESSING_TERMINATED_MARKER.equals(
                recognizedType(marker));
    }

    private void validateContractKey(String key,
                                     GasSchedule schedule,
                                     String scopePath) {
        if (key == null || key.isEmpty()) {
            throw invalid("Contract key must be non-empty",
                    scopePath, key);
        }
        requireLimit(
                "contractKeyCodePoints",
                key.codePointCount(0, key.length()),
                schedule.portableLimit("contractKeyCodePoints"),
                scopePath,
                key);
        requireLimit(
                "contractKeyUtf8Bytes",
                key.getBytes(StandardCharsets.UTF_8).length,
                schedule.portableLimit("contractKeyUtf8Bytes"),
                scopePath,
                key);
    }

    private void requireObjectLimits(Node node,
                                     GasSchedule schedule,
                                     String scopePath,
                                     String key) {
        if (node == null) {
            return;
        }
        int entries = node.getProperties() != null
                ? node.getProperties().size() : 0;
        requireLimit(
                "directObjectEntriesMaterializedOrRebuilt",
                entries,
                schedule.portableLimit(
                        "directObjectEntriesMaterializedOrRebuilt"),
                scopePath,
                key);
        int items = node.getItems() != null
                ? node.getItems().size() : 0;
        requireLimit(
                "directListItemsMaterializedOrRebuilt",
                items,
                schedule.portableLimit(
                        "directListItemsMaterializedOrRebuilt"),
                scopePath,
                key);
    }

    private void requireLimit(String name,
                              long actual,
                              long limit,
                              String scopePath,
                              String key) {
        if (actual > limit) {
            throw invalid(
                    name + " exceeds portable limit "
                            + limit + ": " + actual,
                    scopePath,
                    key);
        }
    }

    private int integerField(Node node,
                             String key,
                             int defaultValue,
                             String scopePath,
                             String contractKey) {
        Node field = property(node, key);
        Object value = field != null ? field.getValue() : null;
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number)) {
            throw invalid(key + " must be an Integer",
                    scopePath, contractKey);
        }
        long result = ((Number) value).longValue();
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw invalid(key + " is outside Integer range",
                    scopePath, contractKey);
        }
        return (int) result;
    }

    private Node nodeAt(Node currentScope,
                        String currentScopePath,
                        String target) {
        String relative = PointerUtils.relativizePointer(
                currentScopePath, target);
        Node current = currentScope;
        for (String segment : JsonPointer.split(relative)) {
            if (current == null || current.getProperties() == null) {
                return null;
            }
            current = current.getProperties().get(segment);
        }
        return current;
    }

    private String exactIdentity(Node node) {
        return node.getBlueId() != null
                ? node.getBlueId()
                : BlueIdCalculator.calculateBlueId(node);
    }

    /**
     * Uses an already retained exact scope identity for ancestry checks. It
     * deliberately does not recursively hash an otherwise unrelated scope:
     * object-identity ancestry still detects in-memory cycles, while reference
     * backed/reused exact scopes carry their BlueId explicitly.
     */
    private String declaredExactIdentity(Node node) {
        return node != null ? node.getBlueId() : null;
    }

    private String textField(Node node, String key) {
        Node field = property(node, key);
        Object value = field != null ? field.getValue() : null;
        return value instanceof String ? (String) value : null;
    }

    private Node property(Node node, String key) {
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    private boolean isObject(Node node) {
        return node != null
                && node.getValue() == null
                && node.getItems() == null
                && !node.isReferenceOnly();
    }

    private boolean isConcrete(Node node) {
        return node != null && !node.isReferenceOnly();
    }

    private SubscriptionSurfaceInvalidException invalid(
            String message,
            String scopePath,
            String key) {
        return new SubscriptionSurfaceInvalidException(
                message, scopePath, key);
    }

    private final class EffectiveResolution {
        private final Node root;
        private final ResolvedSnapshot snapshot;
        private final Map<String, ScopeView> scopes =
                new LinkedHashMap<>();
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
            String normalized =
                    PointerUtils.normalizeScope(scopePath);
            ScopeView cached = scopes.get(normalized);
            if (cached != null || absent.contains(normalized)) {
                return cached;
            }
            Node selected;
            Node effective;
            if (snapshot != null) {
                selected = "/".equals(normalized)
                        ? snapshot.canonicalRoot()
                        : snapshot.canonicalNodeAt(normalized);
                effective = "/".equals(normalized)
                        ? snapshot.resolvedRoot()
                        : snapshot.resolvedNodeAt(normalized);
            } else {
                selected = nodeAtRoot(root, normalized);
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
            ScopeView created =
                    new ScopeView(selected, effective, bundle);
            scopes.put(normalized, created);
            return created;
        }
    }

    private Node nodeAtRoot(Node root, String pointer) {
        if ("/".equals(pointer)) {
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

    private static final class RegisteredSubscriptionHeader {
        private final List<String> keys;
        private final String checkpointDomainDiscriminator;

        private RegisteredSubscriptionHeader(
                List<String> keys,
                String checkpointDomainDiscriminator) {
            this.keys = keys != null
                    ? Collections.unmodifiableList(
                    new ArrayList<>(keys))
                    : null;
            this.checkpointDomainDiscriminator =
                    checkpointDomainDiscriminator;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof RegisteredSubscriptionHeader)) {
                return false;
            }
            RegisteredSubscriptionHeader header =
                    (RegisteredSubscriptionHeader) other;
            return Objects.equals(keys, header.keys)
                    && Objects.equals(
                    checkpointDomainDiscriminator,
                    header.checkpointDomainDiscriminator);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    keys, checkpointDomainDiscriminator);
        }
    }

    private static final class EmbeddedRoute {
        private final String targetScope;

        private EmbeddedRoute(String targetScope) {
            this.targetScope = targetScope;
        }
    }
}
