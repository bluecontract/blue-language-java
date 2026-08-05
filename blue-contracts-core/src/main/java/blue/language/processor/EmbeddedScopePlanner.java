package blue.language.processor;

import blue.language.identity.BlueIds;
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

    private final ExactReferenceMaterializer referenceMaterializer;

    /** Creates a planner that suspends when verified reference content is needed. */
    EmbeddedScopePlanner() {
        this(null);
    }

    /**
     * Creates a planner with an invocation-owned verified exact-reference
     * boundary.
     *
     * @param referenceMaterializer materializer, or {@code null} to suspend
     */
    EmbeddedScopePlanner(
            ExactReferenceMaterializer referenceMaterializer) {
        this.referenceMaterializer = referenceMaterializer;
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
                true);
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
                true);
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
                true);
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
                false);
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
                false);
    }

    private EmbeddedScopePlan plan(
            FrozenNode effectiveScope,
            String scopePath,
            List<String> explicitPaths,
            List<String> collectionPaths,
            GasSchedule schedule,
            GasMeter meter,
            boolean verifyDirectExplicitReferences) {
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
        validateDeclarationOverlap(
                explicitDeclarations, collectionDeclarations,
                normalizedScope);

        List<EmbeddedConcretePath> concrete = new ArrayList<>();
        for (String declaration : explicitDeclarations) {
            FrozenNode target = select(
                    effectiveScope,
                    declaration,
                    DeclarationKind.EXPLICIT,
                    normalizedScope);
            if (target == null) {
                continue;
            }
            if (target.isReferenceOnly()
                    && !verifyDirectExplicitReferences) {
                rejectCyclicMember(
                        target, normalizedScope, declaration, null);
            } else {
                target = materialize(
                        target, normalizedScope, declaration);
            }
            if (!target.isReferenceOnly()
                    && !isScopeObjectCompatible(target)) {
                throw invalid(
                        ProcessorErrorCategory.EmbeddedScopeNotObject,
                        "Process Embedded path must select an object: "
                                + declaration,
                        normalizedScope);
            }
            concrete.add(new EmbeddedConcretePath(
                    PointerUtils.resolvePointer(normalizedScope, declaration),
                    EmbeddedPathOrigin.EXPLICIT,
                    declaration,
                    null));
        }

        Map<String, List<String>> memberKeysByDeclaration =
                new LinkedHashMap<>();
        Map<String, String> collectionDeclarationByIdentity =
                new LinkedHashMap<>();
        for (String declaration : collectionDeclarations) {
            List<String> memberKeys = projectCollection(
                    effectiveScope,
                    declaration,
                    normalizedScope,
                    schedule,
                    meter,
                    concrete,
                    collectionDeclarationByIdentity);
            memberKeysByDeclaration.put(declaration, memberKeys);
        }

        requireLimit(
                GasScheduleConstants.PortableLimit
                        .PROCESS_EMBEDDED_PATHS_PER_SCOPE,
                concrete.size(),
                schedule);
        rejectConcreteOverlap(concrete, normalizedScope);
        List<EmbeddedConcretePath> orderedConcrete =
                sortConcrete(concrete, normalizedScope, meter);
        return new EmbeddedScopePlan(
                normalizedScope,
                explicitDeclarations,
                collectionDeclarations,
                memberKeysByDeclaration,
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

    private List<String> projectCollection(
            FrozenNode scope,
            String declaration,
            String scopePath,
            GasSchedule schedule,
            GasMeter meter,
            List<EmbeddedConcretePath> concrete,
            Map<String, String> collectionDeclarationByIdentity) {
        FrozenNode collection = select(
                scope,
                declaration,
                DeclarationKind.COLLECTION,
                scopePath);
        if (collection == null) {
            return Collections.emptyList();
        }
        collection = materialize(collection, scopePath, declaration);
        if (!isCollectionObject(collection)) {
            throw invalid(
                    ProcessorErrorCategory.EmbeddedCollectionMustBeObject,
                    "Embedded collection must be an object: " + declaration,
                    scopePath);
        }
        String collectionIdentity = collection.blueId();
        String previousDeclaration = collectionDeclarationByIdentity.put(
                collectionIdentity, declaration);
        if (previousDeclaration != null) {
            throw overlap(
                    "Graph-equivalent collection declarations: "
                            + previousDeclaration + " and " + declaration,
                    scopePath);
        }
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

        for (String key : orderedKeys) {
            requireLimit(
                    GasScheduleConstants.PortableLimit
                            .DIRECT_OBJECT_KEY_CODE_POINTS,
                    key.codePointCount(0, key.length()),
                    schedule);
            FrozenNode member = properties.get(key);
            rejectCyclicMember(member, scopePath, declaration, key);
            member = materialize(member, scopePath,
                    PointerUtils.appendPointer(declaration, key));
            if (!isScopeObjectCompatible(member)) {
                throw invalid(
                        ProcessorErrorCategory
                                .EmbeddedCollectionMemberMustBeObject,
                        "Embedded collection member must be an object: "
                                + declaration + "/"
                                + PointerUtils.escapeSegment(key),
                        scopePath);
            }
            String generatedDeclaration =
                    PointerUtils.appendPointer(declaration, key);
            validateGeneratedPath(
                    generatedDeclaration, scopePath, schedule, meter);
            concrete.add(new EmbeddedConcretePath(
                    PointerUtils.resolvePointer(
                            scopePath, generatedDeclaration),
                    EmbeddedPathOrigin.COLLECTION_MEMBER,
                    declaration,
                    key));
        }
        return Collections.unmodifiableList(new ArrayList<>(orderedKeys));
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

    private FrozenNode select(
            FrozenNode scope,
            String declaration,
            DeclarationKind kind,
            String scopePath) {
        FrozenNode current = scope;
        for (String segment : JsonPointer.split(declaration)) {
            current = materialize(current, scopePath, declaration);
            if (current.hasItems() || current.getValue() != null
                    || current.isPreviousOnly()) {
                throw invalid(
                        kind.invalidPathCategory(),
                        "Process Embedded declaration cannot traverse a non-object: "
                                + declaration,
                        scopePath);
            }
            current = current.property(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
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

    private void validateDeclarationOverlap(
            List<String> explicit,
            List<String> collections,
            String scopePath) {
        List<String> all = new ArrayList<>(explicit.size() + collections.size());
        all.addAll(explicit);
        all.addAll(collections);
        Map<String, String> declarationsByPath = new LinkedHashMap<>();
        for (String declaration : all) {
            String duplicate = declarationsByPath.put(
                    declaration, declaration);
            if (duplicate != null) {
                throw overlap(
                        "Overlapping Process Embedded declarations: "
                                + duplicate + " and " + declaration,
                        scopePath);
            }
        }
        for (String declaration : all) {
            String ancestor = strictAncestorIn(
                    declarationsByPath, declaration);
            if (ancestor != null) {
                throw overlap(
                        "Overlapping Process Embedded declarations: "
                                + ancestor + " and " + declaration,
                        scopePath);
            }
        }
    }

    /** Rejects duplicate and ancestor-related concrete paths in bounded time. */
    void rejectConcreteOverlap(
            List<EmbeddedConcretePath> concrete,
            String scopePath) {
        Map<String, EmbeddedConcretePath> concreteByPath =
                new LinkedHashMap<>();
        for (EmbeddedConcretePath candidate : concrete) {
            EmbeddedConcretePath duplicate = concreteByPath.put(
                    candidate.absolutePath(), candidate);
            if (duplicate != null) {
                throw concreteOverlap(
                        duplicate.absolutePath(),
                        candidate.absolutePath(),
                        scopePath);
            }
        }
        for (EmbeddedConcretePath candidate : concrete) {
            String ancestor = strictAncestorIn(
                    concreteByPath, candidate.absolutePath());
            if (ancestor != null) {
                throw concreteOverlap(
                        ancestor,
                        candidate.absolutePath(),
                        scopePath);
            }
        }
    }

    private SubscriptionSurfaceInvalidException concreteOverlap(
            String left,
            String right,
            String scopePath) {
        return overlap(
                "Overlapping concrete embedded paths: "
                        + left + " and " + right,
                scopePath);
    }

    /**
     * Finds a strict segment ancestor using a complete-path index. Pointer
     * depth and bytes are already portable-bounded, so this is linear in the
     * indexed path count rather than quadratic in sibling count.
     */
    private String strictAncestorIn(
            Map<String, ?> pathsByPointer,
            String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        for (int length = segments.size() - 1; length > 0; length--) {
            String ancestor = JsonPointer.toPointer(
                    segments.subList(0, length));
            if (pathsByPointer.containsKey(ancestor)) {
                return ancestor;
            }
        }
        return null;
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

    private boolean isCollectionObject(FrozenNode node) {
        return node != null
                && node.getValue() == null
                && !node.hasItems()
                && !node.isReferenceOnly()
                && !node.isPreviousOnly();
    }

    /**
     * A processing scope may carry a scalar payload when it also carries a
     * direct Contracts envelope. A bare scalar remains a non-object collection
     * member, while the envelope keeps values such as {@code value: 0} plus
     * local Channels processable as one owned occurrence.
     */
    private boolean isScopeObjectCompatible(FrozenNode node) {
        return node != null
                && !node.hasItems()
                && !node.isReferenceOnly()
                && !node.isPreviousOnly()
                && (node.getValue() == null
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
