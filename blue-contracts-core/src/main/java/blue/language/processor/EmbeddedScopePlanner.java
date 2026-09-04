package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.model.Node;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds the immutable, deterministic immediate-child plan for one effective
 * Process Embedded declaration.
 *
 * <p>The planner reads only the supplied effective scope and exact references
 * demanded by declaration traversal. It does not discover descendants or
 * executable bodies. A caller that can open verified exact references may
 * supply an {@link ExactReferenceMaterializer}; without one, encountering a
 * plain pure reference is reported as retryable evidence rather than being
 * mistaken for an absent path.</p>
 */
final class EmbeddedScopePlanner {

    private final EmbeddedScopeValueResolver valueResolver;

    /** Creates a planner that suspends when verified reference content is needed. */
    EmbeddedScopePlanner() {
        this(
                null,
                EmbeddedScopeValueResolver::unavailableTypeEvidence,
                CanonicalTypeIdentityLookup.incomplete());
    }

    /**
     * Creates a planner with an invocation-owned verified exact-reference
     * boundary.
     *
     * @param referenceMaterializer materializer, or {@code null} to suspend
     */
    EmbeddedScopePlanner(
            ExactReferenceMaterializer referenceMaterializer) {
        this(
                referenceMaterializer,
                EmbeddedScopeValueResolver::unavailableTypeEvidence,
                CanonicalTypeIdentityLookup.incomplete());
    }

    /**
     * Creates a planner bound atomically to one snapshot manager and the
     * canonical type identities of the exact effective scope being planned.
     */
    EmbeddedScopePlanner(
            ProcessingSnapshotManager snapshotManager,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        this(
                Objects.requireNonNull(snapshotManager, "snapshotManager")
                        ::materializeVerifiedExactReference,
                reference -> EmbeddedScopeValueResolver
                        .materializeVerifiedTypeEvidence(
                                snapshotManager, reference),
                canonicalTypeIdentities);
    }

    /**
     * Creates a planner with a caller-specific exact-content boundary and
     * type evidence supplied by the same invocation's snapshot manager.
     */
    EmbeddedScopePlanner(
            ExactReferenceMaterializer referenceMaterializer,
            ProcessingSnapshotManager snapshotManager,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        this(
                referenceMaterializer,
                reference -> EmbeddedScopeValueResolver
                        .materializeVerifiedTypeEvidence(
                        Objects.requireNonNull(
                                snapshotManager, "snapshotManager"),
                        reference),
                canonicalTypeIdentities);
    }

    /**
     * Creates a planner confined to one resolver invocation's verified type
     * materialization and canonical identity evidence.
     *
     * @param referenceMaterializer exact content materializer, or
     *         {@code null} to suspend
     * @param verifiedTypeMaterializer verified exact type materializer
     * @param canonicalTypeIdentities resolver-issued identities for completed
     *         inline types already present in the effective scope
     */
    EmbeddedScopePlanner(
            ExactReferenceMaterializer referenceMaterializer,
            Function<FrozenNode, TypeEvidenceResolution>
                    verifiedTypeMaterializer,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        this.valueResolver = new EmbeddedScopeValueResolver(
                referenceMaterializer,
                verifiedTypeMaterializer,
                canonicalTypeIdentities);
    }

    /** Builds an unmetered plan after defensively freezing a mutable scope. */
    EmbeddedScopePlan plan(
            Node effectiveScope,
            String scopePath,
            List<String> explicitPaths,
            List<String> collectionPaths,
            GasSchedule schedule) {
        Objects.requireNonNull(effectiveScope, "effectiveScope");
        return plan(
                FrozenNode.fromResolvedNode(effectiveScope),
                scopePath,
                explicitPaths,
                collectionPaths,
                schedule,
                null,
                TargetPolicy.FULL,
                Collections.<String, String>emptyMap());
    }

    /** Builds an unmetered plan from one immutable effective scope. */
    EmbeddedScopePlan plan(
            FrozenNode effectiveScope,
            String scopePath,
            List<String> explicitPaths,
            List<String> collectionPaths,
            GasSchedule schedule) {
        return plan(
                effectiveScope,
                scopePath,
                explicitPaths,
                collectionPaths,
                schedule,
                null,
                TargetPolicy.FULL,
                Collections.<String, String>emptyMap());
    }

    /**
     * Builds a plan while admitting every normative logical gas charge before
     * its corresponding work.
     */
    EmbeddedScopePlan plan(
            FrozenNode effectiveScope,
            String scopePath,
            List<String> explicitPaths,
            List<String> collectionPaths,
            GasMeter meter) {
        Objects.requireNonNull(meter, "meter");
        return plan(
                effectiveScope,
                scopePath,
                explicitPaths,
                collectionPaths,
                meter.schedule(),
                meter,
                TargetPolicy.FULL,
                Collections.<String, String>emptyMap());
    }

    /**
     * Builds the current-event plan at the revision-bound feeder trust
     * boundary. A direct explicit child that is already represented by one
     * exact BlueId remains opaque until that branch participates; collection
     * containers and members still use the strict projection rules because
     * their direct key set must be known to construct the concrete paths.
     */
    EmbeddedScopePlan planForRevisionBoundEvent(
            Node effectiveScope,
            String scopePath,
            List<String> explicitPaths,
            List<String> collectionPaths,
            GasSchedule schedule) {
        Objects.requireNonNull(effectiveScope, "effectiveScope");
        return planForRevisionBoundEvent(
                FrozenNode.fromResolvedNode(effectiveScope),
                scopePath,
                explicitPaths,
                collectionPaths,
                schedule);
    }

    /** Builds a revision-bound plan from one immutable effective scope. */
    EmbeddedScopePlan planForRevisionBoundEvent(
            FrozenNode effectiveScope,
            String scopePath,
            List<String> explicitPaths,
            List<String> collectionPaths,
            GasSchedule schedule) {
        return plan(
                effectiveScope,
                scopePath,
                explicitPaths,
                collectionPaths,
                schedule,
                null,
                TargetPolicy.REVISION_BOUND,
                Collections.<String, String>emptyMap());
    }

    /**
     * Builds the declaration-complete concrete surface used by managed graph
     * reconciliation.
     *
     * <p>Direct selected children and direct collection members remain opaque
     * when represented by exact references, including cyclic-member
     * references. The closure reconciler owns their exact target verification.
     * Collection containers still open strictly because their direct key set
     * is required to derive the concrete occurrence paths.</p>
     */
    EmbeddedScopePlan planForManagedReconciliation(
            FrozenNode effectiveScope,
            String scopePath,
            List<String> explicitPaths,
            List<String> collectionPaths,
            GasSchedule schedule) {
        return plan(
                effectiveScope,
                scopePath,
                explicitPaths,
                collectionPaths,
                schedule,
                null,
                TargetPolicy.MANAGED_RECONCILIATION,
                Collections.<String, String>emptyMap());
    }

    /**
     * Metered counterpart of {@link #planForRevisionBoundEvent(FrozenNode,
     * String, List, List, GasSchedule)}.
     */
    EmbeddedScopePlan planForRevisionBoundEvent(
            FrozenNode effectiveScope,
            String scopePath,
            List<String> explicitPaths,
            List<String> collectionPaths,
            GasMeter meter) {
        Objects.requireNonNull(meter, "meter");
        return plan(
                effectiveScope,
                scopePath,
                explicitPaths,
                collectionPaths,
                meter.schedule(),
                meter,
                TargetPolicy.REVISION_BOUND,
                Collections.<String, String>emptyMap());
    }

    /**
     * Builds a declaration-complete plan whose selected managed children stay
     * opaque and must exactly match the supplied closure occurrence paths.
     */
    EmbeddedScopePlan planForOpaqueManagedRoot(
            FrozenNode effectiveScope,
            String scopePath,
            List<String> explicitPaths,
            List<String> collectionPaths,
            Map<String, String> expectedManagedBlueIdsByPath,
            GasMeter meter) {
        Objects.requireNonNull(meter, "meter");
        String normalizedScope = normalizedScope(scopePath);
        LinkedHashMap<String, String> expected =
                validatedOpaqueExpectations(
                        normalizedScope,
                        expectedManagedBlueIdsByPath);
        return plan(
                effectiveScope,
                normalizedScope,
                explicitPaths,
                collectionPaths,
                meter.schedule(),
                meter,
                TargetPolicy.OPAQUE_MANAGED,
                expected);
    }

    /** Unmetered counterpart for closure-side mutation reclassification. */
    EmbeddedScopePlan planForOpaqueManagedRoot(
            FrozenNode effectiveScope,
            String scopePath,
            List<String> explicitPaths,
            List<String> collectionPaths,
            Map<String, String> expectedManagedBlueIdsByPath,
            GasSchedule schedule) {
        Objects.requireNonNull(schedule, "schedule");
        String normalizedScope = normalizedScope(scopePath);
        LinkedHashMap<String, String> expected =
                validatedOpaqueExpectations(
                        normalizedScope,
                        expectedManagedBlueIdsByPath);
        return plan(
                effectiveScope,
                normalizedScope,
                explicitPaths,
                collectionPaths,
                schedule,
                null,
                TargetPolicy.OPAQUE_MANAGED,
                expected);
    }

    private LinkedHashMap<String, String> validatedOpaqueExpectations(
            String normalizedScope,
            Map<String, String> expectedManagedBlueIdsByPath) {
        LinkedHashMap<String, String> expected =
                new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : Objects.requireNonNull(
                expectedManagedBlueIdsByPath,
                "expectedManagedBlueIdsByPath").entrySet()) {
            String path = Objects.requireNonNull(
                    entry.getKey(), "managed embedded path");
            String normalized = PointerUtils.assertValidRuntimePointer(path);
            if (!normalized.equals(path)
                    || JsonPointer.ROOT.equals(normalized)
                    || !isWithinScope(normalizedScope, normalized)) {
                throw invalid(
                        ProcessorErrorCategory.SubscriptionSurfaceInvalid,
                        "Managed embedded path is outside its declaring Root: "
                                + path,
                        normalizedScope);
            }
            if (expected.put(normalized, Objects.requireNonNull(
                    entry.getValue(), "expected managed BlueId")) != null) {
                throw overlap(
                        "Duplicate managed embedded path: " + normalized,
                        normalizedScope);
            }
        }
        return expected;
    }

    private EmbeddedScopePlan plan(
            FrozenNode effectiveScope,
            String scopePath,
            List<String> explicitPaths,
            List<String> collectionPaths,
            GasSchedule schedule,
            GasMeter meter,
            TargetPolicy targetPolicy,
            Map<String, String> expectedManagedBlueIdsByPath) {
        Objects.requireNonNull(effectiveScope, "effectiveScope");
        Objects.requireNonNull(schedule, "schedule");
        String normalizedScope = normalizedScope(scopePath);
        List<String> explicitInput = pathsOrEmpty(explicitPaths);
        List<String> collectionInput = pathsOrEmpty(collectionPaths);
        if (explicitInput.isEmpty() && collectionInput.isEmpty()) {
            throw invalid(
                    ProcessorErrorCategory.SubscriptionSurfaceInvalid,
                    "Process Embedded requires at least one non-empty "
                            + "paths or collectionPaths list",
                    normalizedScope);
        }
        requireLimit(
                GasScheduleConstants.PortableLimit
                        .PROCESS_EMBEDDED_PATHS_PER_SCOPE,
                (long) explicitInput.size() + collectionInput.size(),
                schedule);

        List<String> explicitDeclarations = validateDeclarations(
                explicitInput,
                normalizedScope,
                DeclarationKind.EXPLICIT,
                schedule,
                meter);
        List<String> collectionDeclarations = validateDeclarations(
                collectionInput,
                normalizedScope,
                DeclarationKind.COLLECTION,
                schedule,
                meter);
        EmbeddedScopeOverlapValidator.validateDeclarations(
                explicitDeclarations, collectionDeclarations,
                normalizedScope);

        List<EmbeddedConcretePath> concrete = new ArrayList<>();
        for (String declaration : explicitDeclarations) {
            EmbeddedScopeValueResolver.SelectedValue selected =
                    valueResolver.select(
                            effectiveScope,
                            declaration,
                            DeclarationKind.EXPLICIT.invalidPathCategory(),
                            normalizedScope,
                            schedule);
            if (!valueResolver.isSemanticallyPresent(
                    selected.node(), selected.presentByReference())) {
                continue;
            }
            FrozenNode target = selected.node();
            String absolutePath = PointerUtils.resolvePointer(
                    normalizedScope, declaration);
            if (targetPolicy == TargetPolicy.OPAQUE_MANAGED) {
                valueResolver.requireOpaqueManagedTarget(
                        target,
                        absolutePath,
                        expectedManagedBlueIdsByPath,
                        normalizedScope);
            } else if (target.isReferenceOnly()
                    && (targetPolicy == TargetPolicy.REVISION_BOUND
                    || targetPolicy
                    == TargetPolicy.MANAGED_RECONCILIATION)) {
                if (targetPolicy == TargetPolicy.REVISION_BOUND) {
                    valueResolver.rejectCyclicMember(
                            target, normalizedScope, declaration, null);
                }
            } else {
                target = valueResolver.materialize(
                        target, normalizedScope, declaration);
            }
            if (!target.isReferenceOnly()
                    && !valueResolver.isSelectedScopeRootObject(
                            target,
                            declaration,
                            normalizedScope,
                            schedule)) {
                throw invalid(
                        ProcessorErrorCategory.EmbeddedScopeNotObject,
                        "Process Embedded path must select an object: "
                                + declaration,
                        normalizedScope);
            }
            concrete.add(new EmbeddedConcretePath(
                    absolutePath,
                    EmbeddedPathOrigin.EXPLICIT,
                    declaration,
                    null));
        }

        Map<String, List<String>> memberKeysByDeclaration =
                new LinkedHashMap<>();
        Map<String, EmbeddedCollectionState> statesByDeclaration =
                new LinkedHashMap<>();
        for (String declaration : collectionDeclarations) {
            CollectionProjection projection = projectCollection(
                    effectiveScope,
                    declaration,
                    normalizedScope,
                    schedule,
                    meter,
                    concrete,
                    targetPolicy,
                    expectedManagedBlueIdsByPath);
            memberKeysByDeclaration.put(declaration, projection.memberKeys);
            statesByDeclaration.put(declaration, projection.state);
        }

        requireLimit(
                GasScheduleConstants.PortableLimit
                        .PROCESS_EMBEDDED_PATHS_PER_SCOPE,
                concrete.size(),
                schedule);
        rejectConcreteOverlap(concrete, normalizedScope);
        List<EmbeddedConcretePath> orderedConcrete =
                sortConcrete(concrete, normalizedScope, meter);
        if (targetPolicy == TargetPolicy.OPAQUE_MANAGED) {
            LinkedHashSet<String> planned = new LinkedHashSet<String>();
            for (EmbeddedConcretePath path : orderedConcrete) {
                planned.add(path.absolutePath());
            }
            if (!planned.equals(
                    expectedManagedBlueIdsByPath.keySet())) {
                throw invalid(
                        ProcessorErrorCategory.SubscriptionSurfaceInvalid,
                        "Managed occurrence paths do not exactly match the "
                                + "effective Process Embedded declaration",
                        normalizedScope);
            }
        }
        return new EmbeddedScopePlan(
                normalizedScope,
                explicitDeclarations,
                collectionDeclarations,
                memberKeysByDeclaration,
                statesByDeclaration,
                orderedConcrete);
    }

    private List<String> validateDeclarations(
            List<String> declarations,
            String scopePath,
            DeclarationKind kind,
            GasSchedule schedule,
            GasMeter meter) {
        List<String> normalized = new ArrayList<>(declarations.size());
        Set<String> unique = new LinkedHashSet<>();
        for (String declaration : declarations) {
            String logicalPath = declaration != null
                    ? logicalPath(scopePath, declaration)
                    : null;
            if (meter != null) {
                meter.chargeEmbeddedPathEntryRead(scopePath, logicalPath);
                meter.chargeEmbeddedPathSegmentsValidated(
                        scopePath,
                        logicalPath,
                        uncheckedSegmentCount(declaration));
            }
            String path = validateDeclaration(
                    declaration, scopePath, kind, schedule);
            if (!unique.add(path)) {
                throw overlap(
                        "Duplicate Process Embedded declaration: " + path,
                        scopePath);
            }
            normalized.add(path);
        }
        return Collections.unmodifiableList(normalized);
    }

    private String validateDeclaration(
            String declaration,
            String scopePath,
            DeclarationKind kind,
            GasSchedule schedule) {
        final String normalized;
        try {
            normalized = PointerUtils.assertValidRuntimePointer(declaration);
        } catch (IllegalArgumentException failure) {
            throw invalid(
                    kind.invalidPathCategory(),
                    "Invalid Process Embedded declaration: " + declaration,
                    scopePath);
        }
        if (!normalized.equals(declaration) || JsonPointer.ROOT.equals(normalized)) {
            throw invalid(
                    kind.invalidPathCategory(),
                    "Process Embedded declaration must be a normalized non-root Runtime Pointer: "
                            + declaration,
                    scopePath);
        }
        List<String> segments = JsonPointer.split(normalized);
        requireLimit(
                GasScheduleConstants.PortableLimit.RUNTIME_POINTER_SEGMENTS,
                segments.size(),
                schedule);
        requireLimit(
                GasScheduleConstants.PortableLimit.RUNTIME_POINTER_UTF8_BYTES,
                normalized.getBytes(StandardCharsets.UTF_8).length,
                schedule);
        for (String segment : segments) {
            if (isSelector(segment)) {
                throw invalid(
                        ProcessorErrorCategory.EmbeddedPathSelectorUnsupported,
                        "Process Embedded selectors are unsupported: "
                                + declaration,
                        scopePath);
            }
            if (BlueLanguageConstants.isLanguageReservedField(segment)) {
                throw invalid(
                        kind.invalidPathCategory(),
                        "Process Embedded declaration traverses Language-reserved field '"
                                + segment + "': " + declaration,
                        scopePath);
            }
        }
        return normalized;
    }

    private CollectionProjection projectCollection(
            FrozenNode scope,
            String declaration,
            String scopePath,
            GasSchedule schedule,
            GasMeter meter,
            List<EmbeddedConcretePath> concrete,
            TargetPolicy targetPolicy,
            Map<String, String> expectedManagedBlueIdsByPath) {
        EmbeddedScopeValueResolver.SelectedValue selected =
                valueResolver.select(
                        scope,
                        declaration,
                        DeclarationKind.COLLECTION.invalidPathCategory(),
                        scopePath,
                        schedule);
        if (!valueResolver.isSemanticallyPresent(
                selected.node(), selected.presentByReference())) {
            return CollectionProjection.absent();
        }
        FrozenNode collection = selected.node();
        collection = valueResolver.materialize(
                collection, scopePath, declaration);
        if (!valueResolver.isCollectionObject(
                collection,
                declaration,
                scopePath,
                schedule)) {
            throw invalid(
                    ProcessorErrorCategory.EmbeddedCollectionMustBeObject,
                    "Embedded collection must be an object: " + declaration,
                    scopePath);
        }
        String collectionIdentity = collection.blueId();
        Map<String, FrozenNode> properties = collection.getProperties();
        List<String> keys = properties != null
                ? new ArrayList<>(properties.keySet())
                : new ArrayList<>();
        requireLimit(
                GasScheduleConstants.PortableLimit.DIRECT_OBJECT_ENTRIES,
                keys.size(),
                schedule);
        if (meter != null) {
            GasChargeContext context = routeContext(
                    scopePath,
                    PointerUtils.resolvePointer(scopePath, declaration));
            meter.semantic().openNodeManifest(collectionIdentity, context);
            meter.semantic().objectMembersRead(keys.size(), context);
        }
        keys.sort(ExternalOrderKey::compareTextCodePoints);
        List<String> orderedKeys = meter != null
                ? meter.semantic().stableBottomUpSort(
                        keys,
                        (left, right) -> meter.semantic().compareText(
                                left, right, routeContext(scopePath, declaration)),
                        routeContext(scopePath, declaration))
                : Collections.unmodifiableList(keys);

        List<String> presentKeys = new ArrayList<>();
        for (String key : orderedKeys) {
            requireLimit(
                    GasScheduleConstants.PortableLimit
                            .DIRECT_OBJECT_KEY_CODE_POINTS,
                    key.codePointCount(0, key.length()),
                    schedule);
            FrozenNode member = properties.get(key);
            String generatedDeclaration =
                    PointerUtils.appendPointer(declaration, key);
            boolean presentByReference = member != null
                    && member.getReferenceBlueId() != null;
            if (!valueResolver.isSemanticallyPresent(
                    member, presentByReference)) {
                continue;
            }
            presentKeys.add(key);
            String absolutePath = PointerUtils.resolvePointer(
                    scopePath, generatedDeclaration);
            if (targetPolicy == TargetPolicy.OPAQUE_MANAGED) {
                valueResolver.requireOpaqueManagedTarget(
                        member,
                        absolutePath,
                        expectedManagedBlueIdsByPath,
                        scopePath);
                if (!member.isReferenceOnly()
                        && !valueResolver.isSelectedScopeRootObject(
                                member,
                                generatedDeclaration,
                                scopePath,
                                schedule)) {
                    throw invalidCollectionMember(
                            declaration, key, scopePath);
                }
            } else if (targetPolicy
                    == TargetPolicy.MANAGED_RECONCILIATION
                    && member != null
                    && member.isReferenceOnly()) {
                /*
                 * The closure reconciler verifies the exact managed target.
                 * This planner needs only the already-open container's keys.
                 */
            } else {
                valueResolver.rejectCyclicMember(
                        member, scopePath, declaration, key);
                member = valueResolver.materialize(member, scopePath,
                        generatedDeclaration);
                if (!valueResolver.isSelectedScopeRootObject(
                        member,
                        generatedDeclaration,
                        scopePath,
                        schedule)) {
                    throw invalidCollectionMember(
                            declaration, key, scopePath);
                }
            }
            validateGeneratedPath(
                    generatedDeclaration, scopePath, schedule, meter);
            concrete.add(new EmbeddedConcretePath(
                    absolutePath,
                    EmbeddedPathOrigin.COLLECTION_MEMBER,
                    declaration,
                    key));
        }
        return CollectionProjection.present(presentKeys);
    }

    private SubscriptionSurfaceInvalidException invalidCollectionMember(
            String declaration,
            String key,
            String scopePath) {
        return invalid(
                ProcessorErrorCategory.EmbeddedCollectionMemberMustBeObject,
                "Embedded collection member must be an object: "
                        + declaration + "/"
                        + PointerUtils.escapeSegment(key),
                scopePath);
    }

    private void validateGeneratedPath(
            String generatedPath,
            String scopePath,
            GasSchedule schedule,
            GasMeter meter) {
        List<String> segments = JsonPointer.split(generatedPath);
        requireLimit(
                GasScheduleConstants.PortableLimit.RUNTIME_POINTER_SEGMENTS,
                segments.size(),
                schedule);
        requireLimit(
                GasScheduleConstants.PortableLimit.RUNTIME_POINTER_UTF8_BYTES,
                generatedPath.getBytes(StandardCharsets.UTF_8).length,
                schedule);
        if (meter != null) {
            String logicalPath = PointerUtils.resolvePointer(
                    scopePath, generatedPath);
            meter.chargeEmbeddedPathEntryRead(scopePath, logicalPath);
            meter.chargeEmbeddedPathSegmentsValidated(
                    scopePath, logicalPath, segments.size());
        }
    }

    /** Rejects duplicate and ancestor-related concrete paths in bounded time. */
    void rejectConcreteOverlap(
            List<EmbeddedConcretePath> concrete,
            String scopePath) {
        EmbeddedScopeOverlapValidator.rejectConcreteOverlap(
                concrete, scopePath);
    }

    private List<EmbeddedConcretePath> sortConcrete(
            List<EmbeddedConcretePath> concrete,
            String scopePath,
            GasMeter meter) {
        List<EmbeddedConcretePath> canonicalInput = new ArrayList<>(concrete);
        canonicalInput.sort(Comparator.comparing(
                EmbeddedConcretePath::absolutePath,
                ExternalOrderKey::compareTextCodePoints));
        if (meter == null) {
            return Collections.unmodifiableList(canonicalInput);
        }
        GasChargeContext context = routeContext(scopePath, scopePath);
        return meter.semantic().stableBottomUpSort(
                canonicalInput,
                (left, right) -> meter.semantic().compareText(
                        left.absolutePath(), right.absolutePath(), context),
                context);
    }

    private static final class CollectionProjection {
        private final EmbeddedCollectionState state;
        private final List<String> memberKeys;

        private CollectionProjection(
                EmbeddedCollectionState state,
                List<String> memberKeys) {
            this.state = Objects.requireNonNull(state, "state");
            this.memberKeys = Collections.unmodifiableList(
                    new ArrayList<>(Objects.requireNonNull(
                            memberKeys, "memberKeys")));
        }

        private static CollectionProjection absent() {
            return new CollectionProjection(
                    EmbeddedCollectionState.ABSENT_ZERO_OCCURRENCES,
                    Collections.<String>emptyList());
        }

        private static CollectionProjection present(List<String> memberKeys) {
            return new CollectionProjection(
                    EmbeddedCollectionState.PRESENT_COLLECTION,
                    memberKeys);
        }
    }

    private boolean isSelector(String segment) {
        return "*".equals(segment)
                || "**".equals(segment)
                || enclosedBy(segment, '[', ']')
                || enclosedBy(segment, '{', '}')
                || (!segment.isEmpty() && segment.charAt(0) == '?');
    }

    private boolean enclosedBy(
            String value,
            char opening,
            char closing) {
        return value.length() >= 2
                && value.charAt(0) == opening
                && value.charAt(value.length() - 1) == closing;
    }

    private String normalizedScope(String scopePath) {
        try {
            return PointerUtils.assertValidRuntimePointer(
                    PointerUtils.normalizeScope(scopePath));
        } catch (RuntimeException invalidScope) {
            throw new IllegalArgumentException(
                    "scopePath must be a valid absolute Runtime Pointer",
                    invalidScope);
        }
    }

    private boolean isWithinScope(
            String normalizedScope,
            String absolutePath) {
        return PointerUtils.strictlyInside(
                absolutePath,
                normalizedScope);
    }

    private List<String> pathsOrEmpty(List<String> paths) {
        return paths != null ? paths : Collections.emptyList();
    }

    private String logicalPath(String scopePath, String declaration) {
        try {
            return PointerUtils.resolvePointer(scopePath, declaration);
        } catch (RuntimeException ignored) {
            return declaration;
        }
    }

    private long uncheckedSegmentCount(String path) {
        if (path == null || path.isEmpty()) {
            return 1L;
        }
        long count = 0L;
        for (int index = 0; index < path.length(); index++) {
            if (path.charAt(index) == '/') {
                count++;
            }
        }
        return Math.max(1L, count);
    }

    private GasChargeContext routeContext(
            String scopePath,
            String logicalPath) {
        return GasChargeContext.of(
                scopePath,
                ProcessorContractConstants.KEY_EMBEDDED,
                logicalPath,
                GasScheduleConstants.ChargeReason.ROUTE);
    }

    private void requireLimit(
            String name,
            long observed,
            GasSchedule schedule) {
        long limit = schedule.portableLimit(name);
        if (observed > limit) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory.SubscriptionSurfaceInvalid,
                    name,
                    observed,
                    limit);
        }
    }

    private SubscriptionSurfaceInvalidException overlap(
            String message,
            String scopePath) {
        return invalid(
                ProcessorErrorCategory.OverlappingEmbeddedDeclaration,
                message,
                scopePath);
    }

    private SubscriptionSurfaceInvalidException invalid(
            ProcessorErrorCategory category,
            String message,
            String scopePath) {
        return new SubscriptionSurfaceInvalidException(
                message,
                scopePath,
                ProcessorContractConstants.KEY_EMBEDDED,
                category);
    }

    /**
     * Opens exact content through an invocation-owned provider-verification
     * boundary. Implementations must preserve unavailable and invalid-evidence
     * exceptions rather than returning a fabricated node.
     */
    @FunctionalInterface
    interface ExactReferenceMaterializer {
        /** Returns verified exact content, or {@code null} only for not-found. */
        FrozenNode materialize(FrozenNode reference);
    }

    private enum TargetPolicy {
        FULL,
        REVISION_BOUND,
        OPAQUE_MANAGED,
        MANAGED_RECONCILIATION
    }

    private enum DeclarationKind {
        EXPLICIT(ProcessorErrorCategory.InvalidRuntimePointer),
        COLLECTION(ProcessorErrorCategory.InvalidEmbeddedCollectionPath);

        private final ProcessorErrorCategory invalidPathCategory;

        DeclarationKind(ProcessorErrorCategory invalidPathCategory) {
            this.invalidPathCategory = invalidPathCategory;
        }

        ProcessorErrorCategory invalidPathCategory() {
            return invalidPathCategory;
        }
    }
}
