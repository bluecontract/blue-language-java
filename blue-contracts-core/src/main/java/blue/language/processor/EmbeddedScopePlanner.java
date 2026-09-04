package blue.language.processor;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.identity.BlueIds;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.matching.FrozenTypeMatcher;
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

    private static final FrozenNode DICTIONARY_TYPE_REFERENCE =
            FrozenNode.fromResolvedNode(new Node().blueId(
                    BlueLanguageConstants.DICTIONARY_TYPE_BLUE_ID));

    private final ExactReferenceMaterializer referenceMaterializer;
    private final CanonicalTypeIdentityLookup canonicalTypeIdentities;
    private final FrozenTypeMatcher typeMatcher;

    /** Creates a planner that suspends when verified reference content is needed. */
    EmbeddedScopePlanner() {
        this(
                null,
                EmbeddedScopePlanner::unavailableTypeEvidence,
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
                EmbeddedScopePlanner::unavailableTypeEvidence,
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
                reference -> materializeVerifiedTypeEvidence(
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
                reference -> materializeVerifiedTypeEvidence(
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
        this.referenceMaterializer = referenceMaterializer;
        this.canonicalTypeIdentities = Objects.requireNonNull(
                canonicalTypeIdentities, "canonicalTypeIdentities");
        this.typeMatcher = FrozenTypeMatcher.withVerifiedTypeEvidence(
                Objects.requireNonNull(
                        verifiedTypeMaterializer,
                        "verifiedTypeMaterializer"),
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
            SelectedNode selected = select(
                    effectiveScope,
                    declaration,
                    DeclarationKind.EXPLICIT,
                    normalizedScope,
                    schedule);
            if (!isSemanticallyPresent(
                    selected.node, selected.presentByReference)) {
                continue;
            }
            FrozenNode target = selected.node;
            String absolutePath = PointerUtils.resolvePointer(
                    normalizedScope, declaration);
            if (targetPolicy == TargetPolicy.OPAQUE_MANAGED) {
                requireOpaqueManagedTarget(
                        target,
                        absolutePath,
                        expectedManagedBlueIdsByPath,
                        normalizedScope);
            } else if (target.isReferenceOnly()
                    && (targetPolicy == TargetPolicy.REVISION_BOUND
                    || targetPolicy
                    == TargetPolicy.MANAGED_RECONCILIATION)) {
                if (targetPolicy == TargetPolicy.REVISION_BOUND) {
                    rejectCyclicMember(
                            target, normalizedScope, declaration, null);
                }
            } else {
                target = materialize(
                        target, normalizedScope, declaration);
            }
            if (!target.isReferenceOnly()
                    && !isSelectedScopeRootObject(
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
        SelectedNode selected = select(
                scope,
                declaration,
                DeclarationKind.COLLECTION,
                scopePath,
                schedule);
        if (!isSemanticallyPresent(
                selected.node, selected.presentByReference)) {
            return CollectionProjection.absent();
        }
        FrozenNode collection = selected.node;
        collection = materialize(collection, scopePath, declaration);
        if (!isCollectionObject(
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
            if (!isSemanticallyPresent(member, presentByReference)) {
                continue;
            }
            presentKeys.add(key);
            String absolutePath = PointerUtils.resolvePointer(
                    scopePath, generatedDeclaration);
            if (targetPolicy == TargetPolicy.OPAQUE_MANAGED) {
                requireOpaqueManagedTarget(
                        member,
                        absolutePath,
                        expectedManagedBlueIdsByPath,
                        scopePath);
                if (!member.isReferenceOnly()
                        && !isSelectedScopeRootObject(
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
                rejectCyclicMember(member, scopePath, declaration, key);
                member = materialize(member, scopePath,
                        generatedDeclaration);
                if (!isSelectedScopeRootObject(
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

    private void requireOpaqueManagedTarget(
            FrozenNode target,
            String absolutePath,
            Map<String, String> expectedManagedBlueIdsByPath,
            String scopePath) {
        String expectedBlueId = expectedManagedBlueIdsByPath.get(
                absolutePath);
        if (expectedBlueId == null) {
            throw invalid(
                    ProcessorErrorCategory.SubscriptionSurfaceInvalid,
                    "Process Embedded selected a child without managed "
                            + "occurrence evidence: " + absolutePath,
                    scopePath);
        }
        if (target == null) {
            throw invalid(
                    ProcessorErrorCategory.InvalidProcessingDocument,
                    "Managed embedded occurrence is absent at "
                            + absolutePath,
                    scopePath);
        }
        if (!target.isReferenceOnly()
                && BlueIds.hasCyclicMemberSeparator(expectedBlueId)) {
            throw new InvalidExecutionEvidenceException(
                    "Materialized cyclic managed content requires complete "
                            + "owning cyclic-set proof at " + absolutePath,
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        String actualBlueId = target.isReferenceOnly()
                ? target.getReferenceBlueId()
                : target.blueId();
        try {
            BlueIds.requireBlueIdOrCyclicMember(
                    actualBlueId,
                    "managed embedded occurrence");
        } catch (IllegalArgumentException invalidIdentity) {
            throw new InvalidExecutionEvidenceException(
                    "Invalid managed embedded identity at " + absolutePath,
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        if (!expectedBlueId.equals(actualBlueId)) {
            throw new InvalidExecutionEvidenceException(
                    "Managed embedded reference disagrees with admitted "
                            + "occurrence evidence at " + absolutePath
                            + ": expected " + expectedBlueId
                            + " but found " + actualBlueId,
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
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

    private SelectedNode select(
            FrozenNode scope,
            String declaration,
            DeclarationKind kind,
            String scopePath,
            GasSchedule schedule) {
        FrozenNode current = scope;
        boolean presentByReference = current.getReferenceBlueId() != null;
        boolean scopeRoot = true;
        for (String segment : JsonPointer.split(declaration)) {
            if (!scopeRoot
                    && !isSemanticallyPresent(
                            current, presentByReference)) {
                return SelectedNode.absent();
            }
            current = materialize(current, scopePath, declaration);
            boolean traversable = scopeRoot
                    ? isAdmittedScopeRootObject(current)
                    : isTraversalObject(
                            current,
                            declaration,
                            scopePath,
                            schedule);
            if (!traversable) {
                throw invalid(
                        kind.invalidPathCategory(),
                        "Process Embedded declaration cannot traverse a non-object: "
                                + declaration,
                        scopePath);
            }
            current = current.property(segment);
            if (current == null) {
                return SelectedNode.absent();
            }
            presentByReference = current.getReferenceBlueId() != null;
            scopeRoot = false;
        }
        return new SelectedNode(current, presentByReference);
    }

    private FrozenNode materialize(
            FrozenNode node,
            String scopePath,
            String logicalPath) {
        if (node == null || !node.isReferenceOnly()) {
            return node;
        }
        rejectCyclicMember(node, scopePath, logicalPath, null);
        String blueId = node.getReferenceBlueId();
        if (referenceMaterializer == null) {
            throw new ExecutionEvidenceUnavailableException(
                    "Verified exact content is required for embedded path "
                            + logicalPath,
                    Collections.singletonList(blueId));
        }
        FrozenNode materialized = referenceMaterializer.materialize(node);
        if (materialized == null || materialized.isReferenceOnly()) {
            throw new InvalidExecutionEvidenceException(
                    "Verified exact content was not found for embedded path "
                            + logicalPath,
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        return materialized;
    }

    private void rejectCyclicMember(
            FrozenNode node,
            String scopePath,
            String declaration,
            String memberKey) {
        if (node == null || !node.isReferenceOnly()) {
            return;
        }
        String blueId = node.getReferenceBlueId();
        if (!BlueIds.hasCyclicMemberSeparator(blueId)) {
            return;
        }
        try {
            BlueIds.requireBlueIdOrCyclicMember(
                    blueId, "embedded collection member");
        } catch (IllegalArgumentException invalidIdentity) {
            throw new InvalidExecutionEvidenceException(
                    "Invalid cyclic-member identity at embedded path "
                            + declaration,
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        String suffix = memberKey != null
                ? "/" + PointerUtils.escapeSegment(memberKey)
                : "";
        throw invalid(
                ProcessorErrorCategory
                        .CyclicSetEmbeddedBoundaryUnsupported,
                "Process Embedded cannot cross cyclic-set member boundary: "
                        + declaration + suffix,
                scopePath);
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

    private boolean isCollectionObject(
            FrozenNode node,
            String declaration,
            String scopePath,
            GasSchedule schedule) {
        if (node == null
                || node.getValue() != null
                || node.hasItems()
                || node.isReferenceOnly()
                || node.isPreviousOnly()) {
            return false;
        }
        if (!node.hasProperties()
                && node.getType() == null) {
            return false;
        }
        return hasObjectCompatibleDeclaredType(
                node, declaration, scopePath, schedule);
    }

    /** Returns whether a present, materialized intermediate is traversable. */
    private boolean isTraversalObject(
            FrozenNode node,
            String declaration,
            String scopePath,
            GasSchedule schedule) {
        return isScopeObjectCompatible(
                node,
                declaration,
                scopePath,
                schedule);
    }

    /**
     * Returns whether an already-admitted processing scope can be traversed
     * as an object container.
     *
     * <p>The processor admits the effective scope before invoking this
     * planner. Its application-specific nominal type therefore need not
     * derive from the Language Dictionary type. Descendants remain subject
     * to {@link #isTraversalObject(FrozenNode, String, String, GasSchedule)}
     * so a declaration still cannot cross a list, reference shell, previous
     * shell, or scalar without a direct Contracts envelope.</p>
     */
    private boolean isAdmittedScopeRootObject(FrozenNode node) {
        if (node == null
                || node.hasItems()
                || node.isReferenceOnly()
                || node.isPreviousOnly()) {
            return false;
        }
        return node.getValue() == null || node.getContracts() != null;
    }

    /**
     * Verifies a selected value that will become an independently processed
     * embedded scope.
     *
     * <p>A non-empty resolved object envelope is positive value evidence even
     * when its application-specific type does not derive from Dictionary.
     * Exact empty values have no such structural proof, so a declared type on
     * them must still establish Dictionary compatibility. This rejects, for
     * example, an exact empty object declared as Text or List.</p>
     */
    private boolean isSelectedScopeRootObject(
            FrozenNode node,
            String declaration,
            String scopePath,
            GasSchedule schedule) {
        if (!isAdmittedScopeRootObject(node)) {
            return false;
        }
        if (node.getValue() != null || node.getContracts() != null) {
            return true;
        }
        Map<String, FrozenNode> properties = node.getProperties();
        if (properties != null && !properties.isEmpty()) {
            return true;
        }
        return hasObjectCompatibleDeclaredType(
                node, declaration, scopePath, schedule);
    }

    /** Verifies the nominal Dictionary lineage of a declared object type. */
    private boolean hasObjectCompatibleDeclaredType(
            FrozenNode node,
            String declaration,
            String scopePath,
            GasSchedule schedule) {
        FrozenNode declaredType = node.getType();
        if (declaredType == null) {
            return true;
        }
        requireInlineTypeIdentityEvidence(
                declaredType, declaration, scopePath);
        try {
            return typeMatcher.isSubtypeOrSame(
                    declaredType,
                    DICTIONARY_TYPE_REFERENCE,
                    schedule.portableLimit(
                            GasScheduleConstants.PortableLimit
                                    .TYPE_CHAIN_EDGES));
        } catch (ExecutionEvidenceUnavailableException
                | InvalidExecutionEvidenceException failure) {
            throw failure;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw invalidTypeEvidence(
                    declaration, scopePath, failure);
        }
    }

    private void requireInlineTypeIdentityEvidence(
            FrozenNode declaredType,
            String declaration,
            String scopePath) {
        if (declaredType.isReferenceOnly()) {
            return;
        }
        final boolean covered;
        try {
            covered = canonicalTypeIdentities.findCanonicalTypeBlueId(
                    declaredType.toNode()).isPresent();
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw invalidTypeEvidence(
                    declaration, scopePath, failure);
        }
        if (!covered) {
            throw new ExecutionEvidenceUnavailableException(
                    "Resolver-issued canonical type identity evidence is "
                            + "required for embedded object "
                            + logicalPath(scopePath, declaration));
        }
    }

    private InvalidExecutionEvidenceException invalidTypeEvidence(
            String declaration,
            String scopePath,
            RuntimeException failure) {
        String detail = failure.getMessage();
        return new InvalidExecutionEvidenceException(
                "Invalid embedded object type evidence at "
                        + logicalPath(scopePath, declaration)
                        + (detail != null && !detail.isEmpty()
                                ? ": " + detail
                                : ""),
                ProcessorErrorCategory.InvalidProcessingDocument);
    }

    private static TypeEvidenceResolution unavailableTypeEvidence(
            FrozenNode reference) {
        Objects.requireNonNull(reference, "reference");
        String blueId = reference.getReferenceBlueId();
        throw new ExecutionEvidenceUnavailableException(
                "Verified exact type evidence is required for embedded "
                        + "object type " + blueId,
                Collections.singletonList(blueId));
    }

    private static TypeEvidenceResolution materializeVerifiedTypeEvidence(
            ProcessingSnapshotManager snapshotManager,
            FrozenNode reference) {
        String blueId = Objects.requireNonNull(
                reference, "reference").getReferenceBlueId();
        try {
            TypeEvidenceResolution materialization = snapshotManager
                    .materializeVerifiedTypeReference(reference);
            if (materialization == null
                    || materialization.resolvedRoot().isReferenceOnly()) {
                throw new ExecutionEvidenceUnavailableException(
                        "Verified exact type evidence is unavailable for "
                                + blueId,
                        Collections.singletonList(blueId));
            }
            return materialization;
        } catch (ExecutionEvidenceUnavailableException
                | InvalidExecutionEvidenceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            if (BlueLanguageErrorClassifier.classify(failure)
                    == BlueLanguageErrorCategory.ProviderUnavailable
                    || failure instanceof UnsupportedOperationException) {
                throw new ExecutionEvidenceUnavailableException(
                        "Verified exact type evidence is unavailable for "
                                + blueId,
                        Collections.singletonList(blueId));
            }
            throw failure;
        }
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

    /** A selected node plus source-surface presence retained across opening. */
    private static final class SelectedNode {
        private final FrozenNode node;
        private final boolean presentByReference;

        private SelectedNode(
                FrozenNode node,
                boolean presentByReference) {
            this.node = node;
            this.presentByReference = presentByReference;
        }

        private static SelectedNode absent() {
            return new SelectedNode(null, false);
        }
    }

    /**
     * A processing scope may carry a scalar payload when it also carries a
     * direct Contracts envelope. A bare scalar remains a non-object collection
     * member, while the envelope keeps values such as {@code value: 0} plus
     * local Channels processable as one owned occurrence.
     */
    private boolean isScopeObjectCompatible(
            FrozenNode node,
            String declaration,
            String scopePath,
            GasSchedule schedule) {
        if (node == null
                || node.hasItems()
                || node.isReferenceOnly()
                || node.isPreviousOnly()) {
            return false;
        }
        if (node.getValue() != null) {
            return node.getContracts() != null;
        }
        if (!node.hasProperties()
                && node.getType() == null) {
            /*
             * The already-admitted scope Root is allowed to consist solely
             * of its Contracts envelope. Selected descendants still pass the
             * semantic-presence gate first, where reserved metadata alone is
             * absent and therefore never becomes an embedded occurrence.
             */
            return node.getContracts() != null;
        }
        return hasObjectCompatibleDeclaredType(
                node, declaration, scopePath, schedule);
    }

    /**
     * Applies Language semantic presence before interpreting a node's kind.
     * Reserved metadata alone is a declaration, not a value. A retained exact
     * reference is present even when opening it produces metadata-only content.
     */
    private boolean isSemanticallyPresent(
            FrozenNode node,
            boolean presentByReference) {
        return node != null
                && (presentByReference
                        || node.getReferenceBlueId() != null
                        || node.getValue() != null
                        || node.hasItems()
                        || node.hasProperties()
                        || node.getContracts() != null);
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
