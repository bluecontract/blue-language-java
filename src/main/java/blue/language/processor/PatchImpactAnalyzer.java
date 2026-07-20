package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;
import blue.language.utils.ParsedJsonPointer;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static blue.language.utils.Properties.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.utils.Properties.DOUBLE_TYPE_BLUE_ID;
import static blue.language.utils.Properties.INTEGER_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;

/**
 * Conservative dependency analysis for exact-replacement processor patches.
 *
 * <p>The analyzer proves locality for plain scalar members and for the narrow
 * case where every observing type contributes only matching, value-free basic
 * type metadata. Every typed boundary is checked along the relative changed
 * path. Unknown processor behavior and every context-sensitive construct use
 * an explicit authoritative fallback.</p>
 */
final class PatchImpactAnalyzer {

    private final ConformanceEngine conformanceEngine;
    private final ConformancePlannerOverride conformancePlannerOverride;
    private final ProcessingSnapshotManager snapshotManager;
    private final ProcessingMetricsSink metrics;

    PatchImpactAnalyzer(ConformanceEngine conformanceEngine,
                        ConformancePlannerOverride conformancePlannerOverride,
                        ProcessingSnapshotManager snapshotManager,
                        ProcessingMetricsSink metrics) {
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.snapshotManager = snapshotManager;
        this.metrics = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
    }

    PatchImpact analyze(boolean exactReplacement,
                        FrozenNode canonicalRoot,
                        FrozenNode resolvedRoot,
                        ImmutablePatchPlanner.PatchPlan canonicalPlan,
                        ImmutablePatchPlanner.PatchPlan resolvedPlan,
                        ImmutableJsonPatch patch) {
        Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        Objects.requireNonNull(canonicalPlan, "canonicalPlan");
        Objects.requireNonNull(resolvedPlan, "resolvedPlan");
        Objects.requireNonNull(patch, "patch");

        metrics.incrementPatchImpactAnalyses();
        ParsedJsonPointer path = patch.path();
        List<String> ancestors = ancestorPaths(path);
        List<String> typedBoundaries = new ArrayList<>();
        String collectionBoundary = null;
        boolean schemaDependency = false;
        boolean referenceDependency = false;
        boolean siblingDependency = false;
        boolean typeDependency = false;
        boolean allObservedTypeDependenciesAreBasicMetadata = true;
        String observedBasicTypeBlueId = null;
        boolean emptyContractsNormalizationDependency = false;
        boolean hasResolutionContext = false;

        for (int depth = 0; depth < path.depth(); depth++) {
            String ancestorPath = pointer(path.segments(), depth);
            FrozenNode canonicalAncestor = read(canonicalRoot, ancestorPath);
            FrozenNode resolvedAncestor = read(resolvedRoot, ancestorPath);
            FrozenNode effective = resolvedAncestor != null ? resolvedAncestor : canonicalAncestor;
            if (effective == null) {
                continue;
            }
            if (hasResolutionMetadata(canonicalAncestor) || hasResolutionMetadata(resolvedAncestor)) {
                hasResolutionContext = true;
            }
            if (hasTypeMetadata(effective)) {
                typedBoundaries.add(ancestorPath);
                List<String> relative = path.segments().subList(depth, path.depth());
                TypeObservation observation = observeType(effective, relative);
                if (observation.observes) {
                    typeDependency = true;
                    schemaDependency |= observation.schemaDependency;
                    siblingDependency |= observation.siblingDependency;
                    if (!observation.valueFreeBasicMetadata
                            || observedBasicTypeBlueId != null
                            && !observedBasicTypeBlueId.equals(observation.basicTypeBlueId)) {
                        allObservedTypeDependenciesAreBasicMetadata = false;
                    } else if (observedBasicTypeBlueId == null) {
                        observedBasicTypeBlueId = observation.basicTypeBlueId;
                    }
                }
            }
            if (hasSchemaDependency(canonicalAncestor) || hasSchemaDependency(resolvedAncestor)) {
                schemaDependency = true;
                siblingDependency = true;
            }
            if (hasReferenceDependency(canonicalAncestor) || hasReferenceDependency(resolvedAncestor)) {
                referenceDependency = true;
            }
            if (hasEmptyContractsNormalizationDependency(canonicalAncestor)
                    || hasEmptyContractsNormalizationDependency(resolvedAncestor)) {
                emptyContractsNormalizationDependency = true;
                siblingDependency = true;
            }
            if (isCollectionBoundary(effective)) {
                if (collectionBoundary == null) {
                    collectionBoundary = ancestorPath;
                }
                siblingDependency = true;
            }
        }

        boolean collectionChange = collectionBoundary != null
                || parentIsList(canonicalRoot, path)
                || parentIsList(resolvedRoot, path);
        boolean contractsChange = containsSegment(path, "contracts");
        boolean typeChange = containsAnySegment(path, "type", "itemType", "keyType", "valueType");
        boolean schemaChange = containsSegment(path, "schema");
        boolean referenceChange = containsAnySegment(path, "blueId", "blue", "$previous", "$pos");
        boolean mergePolicyChange = containsSegment(path, "mergePolicy");
        boolean listIdentityChange = collectionChange
                || patch.op() != JsonPatch.Op.REPLACE && path.hasArrayIndexLeaf();
        boolean safeBasicTypeDependency = typeDependency
                && allObservedTypeDependenciesAreBasicMetadata
                && observedBasicTypeBlueId != null
                && observedBasicTypeBlueId.equals(
                PatchImpact.inferredTypeBlueId(canonicalPlan.after() != null
                        ? canonicalPlan.after().getValue()
                        : null));

        PatchImpact.Kind kind = classify(path,
                patch,
                canonicalPlan,
                resolvedPlan,
                collectionChange,
                contractsChange,
                typeChange,
                schemaChange,
                referenceChange,
                mergePolicyChange);
        recordKind(kind);
        metrics.addConformanceTypedBoundariesConsidered(typedBoundaries.size());

        boolean legacyRequiresAuthoritative = hasResolutionContext
                || !sameStructure(canonicalPlan.before(), resolvedPlan.before())
                || patch.op() != JsonPatch.Op.REMOVE
                && !isPlainValue(patch.resolvedValue(), new IdentityHashMap<FrozenNode, Boolean>());

        Decision decision;
        if (!exactReplacement) {
            decision = Decision.fallback(PatchImpact.FallbackReason.UNKNOWN);
        } else if (!legacyRequiresAuthoritative) {
            // Preserve rc.11's already-safe direct path for untyped/plain data.
            decision = Decision.local();
        } else {
            decision = decideTypedLocality(path,
                    patch,
                    canonicalPlan,
                    resolvedPlan,
                    kind,
                    typeDependency,
                    safeBasicTypeDependency,
                    observedBasicTypeBlueId,
                    schemaDependency,
                    referenceDependency,
                    siblingDependency,
                    collectionChange,
                    contractsChange,
                    typeChange,
                    schemaChange,
                    referenceChange,
                    mergePolicyChange);
        }

        return new PatchImpact(kind,
                path,
                patch.op(),
                resolvedPlan.before(),
                resolvedPlan.after(),
                path.pointer(),
                ancestors,
                typedBoundaries,
                collectionBoundary,
                typeDependency,
                schemaDependency,
                referenceDependency,
                siblingDependency,
                listIdentityChange,
                contractsChange || emptyContractsNormalizationDependency,
                decision.local,
                decision.local && safeBasicTypeDependency,
                decision.reason);
    }

    private Decision decideTypedLocality(ParsedJsonPointer path,
                                         ImmutableJsonPatch patch,
                                         ImmutablePatchPlanner.PatchPlan canonicalPlan,
                                         ImmutablePatchPlanner.PatchPlan resolvedPlan,
                                         PatchImpact.Kind kind,
                                         boolean typeDependency,
                                         boolean safeBasicTypeDependency,
                                         String observedBasicTypeBlueId,
                                         boolean schemaDependency,
                                         boolean referenceDependency,
                                         boolean siblingDependency,
                                         boolean collectionChange,
                                         boolean contractsChange,
                                         boolean typeChange,
                                         boolean schemaChange,
                                         boolean referenceChange,
                                         boolean mergePolicyChange) {
        if (path.isRoot()) {
            return Decision.fallback(PatchImpact.FallbackReason.ROOT_REPLACEMENT);
        }
        if (contractsChange) {
            return Decision.fallback(PatchImpact.FallbackReason.CONTRACTS_CHANGED);
        }
        if (schemaChange || schemaDependency) {
            return Decision.fallback(PatchImpact.FallbackReason.SCHEMA_GRAPH_CHANGED);
        }
        if (typeChange || typeDependency && !safeBasicTypeDependency) {
            return Decision.fallback(PatchImpact.FallbackReason.TYPE_GRAPH_CHANGED);
        }
        if (mergePolicyChange) {
            return Decision.fallback(PatchImpact.FallbackReason.MERGE_POLICY_CHANGED);
        }
        if (referenceChange || referenceDependency) {
            return Decision.fallback(PatchImpact.FallbackReason.REFERENCE_CYCLE_CHANGED);
        }
        if (collectionChange) {
            return Decision.fallback(PatchImpact.FallbackReason.LIST_CONTROL_STRUCTURE_CHANGED);
        }
        if (siblingDependency) {
            return Decision.fallback(PatchImpact.FallbackReason.UNBOUNDED_SIBLING_DEPENDENCY);
        }
        if (patch.op() != JsonPatch.Op.REPLACE) {
            return Decision.fallback(PatchImpact.FallbackReason.NON_REPLACE_OPERATION);
        }
        if (kind != PatchImpact.Kind.VALUE_ONLY
                || !PatchImpact.isValueOnlyScalar(canonicalPlan.before())
                || !PatchImpact.isValueOnlyScalar(canonicalPlan.after())
                || !PatchImpact.isValueOnlyScalar(resolvedPlan.before())
                || !PatchImpact.isValueOnlyScalar(resolvedPlan.after())) {
            return Decision.fallback(PatchImpact.FallbackReason.NON_LEAF_VALUE);
        }
        if (safeBasicTypeDependency
                ? !sameBasicScalar(canonicalPlan.before(), resolvedPlan.before(), observedBasicTypeBlueId)
                || !sameBasicScalar(canonicalPlan.after(), resolvedPlan.after(), observedBasicTypeBlueId)
                : !sameStructure(canonicalPlan.before(), resolvedPlan.before())
                || !sameStructure(canonicalPlan.after(), resolvedPlan.after())) {
            return Decision.fallback(PatchImpact.FallbackReason.RESOLVED_VALUE_DIFFERS);
        }
        if (conformancePlannerOverride != null && conformancePlannerOverride.applies()) {
            return Decision.fallback(PatchImpact.FallbackReason.CUSTOM_CONFORMANCE_PLANNER);
        }
        if (conformanceEngine == null || !conformanceEngine.supportsIncrementalValueResolution()) {
            return Decision.fallback(PatchImpact.FallbackReason.CUSTOM_MERGING_PROCESSOR);
        }
        if (snapshotManager == null || !snapshotManager.supportsIncrementalValueResolution()) {
            return Decision.fallback(PatchImpact.FallbackReason.UNKNOWN_PROCESSOR_CAPABILITY);
        }
        return Decision.local();
    }

    private TypeObservation observeType(FrozenNode boundary, List<String> relativePath) {
        TypeObservation aggregate = TypeObservation.none();
        FrozenNode[] declaredTypes = {
                boundary.getType(), boundary.getItemType(), boundary.getKeyType(), boundary.getValueType()
        };
        for (FrozenNode declaredType : declaredTypes) {
            if (declaredType != null) {
                aggregate = aggregate.merge(observeTypePath(declaredType, relativePath));
            }
        }
        return aggregate;
    }

    private TypeObservation observeTypePath(FrozenNode type, List<String> relativePath) {
        FrozenNode current = type;
        for (String segment : relativePath) {
            if (current == null) {
                return TypeObservation.none();
            }
            if (hasIntermediatePayloadDependency(current)) {
                return TypeObservation.unsafe(hasSchemaDependency(current), true);
            }
            current = current.property(segment);
        }
        if (current == null) {
            return TypeObservation.none();
        }
        String basicTypeBlueId = valueFreeBasicTypeContribution(current);
        if (basicTypeBlueId != null) {
            return TypeObservation.safeBasic(basicTypeBlueId);
        }
        return TypeObservation.unsafe(hasSchemaDependency(current), hasSiblingPayloadDependency(current));
    }

    private boolean hasIntermediatePayloadDependency(FrozenNode node) {
        return node != null
                && (node.getSchema() != null
                || node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getMergePolicy() != null
                || node.getContracts() != null
                || node.isReferenceOnly()
                || node.getPreviousBlueId() != null
                || node.getPosition() != null
                || node.getBlue() != null
                || node.isInlineValue()
                || node.getValue() != null
                || node.hasItems());
    }

    private String valueFreeBasicTypeContribution(FrozenNode node) {
        if (node == null
                || node.getName() != null
                || node.getDescription() != null
                || node.getValue() != null
                || node.hasItems()
                || node.hasProperties()
                || node.getContracts() != null
                || node.getReferenceBlueId() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null
                || node.getBlue() != null
                || node.isInlineValue()
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null) {
            return null;
        }
        FrozenNode type = node.getType();
        if (type == null || !type.isReferenceOnly()) {
            return null;
        }
        String blueId = type.getReferenceBlueId();
        return isBasicTypeBlueId(blueId) ? blueId : null;
    }

    private boolean isBasicTypeBlueId(String blueId) {
        return TEXT_TYPE_BLUE_ID.equals(blueId)
                || INTEGER_TYPE_BLUE_ID.equals(blueId)
                || DOUBLE_TYPE_BLUE_ID.equals(blueId)
                || BOOLEAN_TYPE_BLUE_ID.equals(blueId);
    }

    private boolean hasSiblingPayloadDependency(FrozenNode node) {
        return node != null
                && (node.hasItems()
                || node.hasProperties()
                || node.getContracts() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getMergePolicy() != null);
    }

    private PatchImpact.Kind classify(ParsedJsonPointer path,
                                      ImmutableJsonPatch patch,
                                      ImmutablePatchPlanner.PatchPlan canonicalPlan,
                                      ImmutablePatchPlanner.PatchPlan resolvedPlan,
                                      boolean collectionChange,
                                      boolean contractsChange,
                                      boolean typeChange,
                                      boolean schemaChange,
                                      boolean referenceChange,
                                      boolean mergePolicyChange) {
        if (path.isRoot()) {
            return PatchImpact.Kind.ROOT_REPLACEMENT;
        }
        if (contractsChange) {
            return PatchImpact.Kind.CONTRACT_OR_PROCESSING_STRUCTURE;
        }
        if (typeChange) {
            return PatchImpact.Kind.TYPE_METADATA;
        }
        if (schemaChange) {
            return PatchImpact.Kind.SCHEMA_METADATA;
        }
        if (referenceChange) {
            return PatchImpact.Kind.REFERENCE_OR_BLUE_ID;
        }
        if (mergePolicyChange) {
            return PatchImpact.Kind.MERGE_POLICY;
        }
        if (collectionChange) {
            return PatchImpact.Kind.COLLECTION_SHAPE;
        }
        if (patch.op() == JsonPatch.Op.REPLACE
                && PatchImpact.isScalarPayload(canonicalPlan.after())
                && PatchImpact.isScalarPayload(resolvedPlan.before())
                && PatchImpact.isScalarPayload(resolvedPlan.after())
                && (canonicalPlan.before() == null
                || PatchImpact.isScalarPayload(canonicalPlan.before()))) {
            return PatchImpact.Kind.VALUE_ONLY;
        }
        if (patch.op() != JsonPatch.Op.REMOVE
                && canonicalPlan.after() != null
                && !path.isRoot()) {
            return PatchImpact.Kind.OBJECT_MEMBER_VALUE;
        }
        return PatchImpact.Kind.UNKNOWN;
    }

    private void recordKind(PatchImpact.Kind kind) {
        switch (kind) {
            case VALUE_ONLY:
                metrics.incrementPatchImpactValueOnly();
                break;
            case OBJECT_MEMBER_VALUE:
                metrics.incrementPatchImpactObjectMemberValue();
                break;
            case COLLECTION_SHAPE:
                metrics.incrementPatchImpactCollectionShape();
                break;
            case TYPE_METADATA:
                metrics.incrementPatchImpactTypeMetadata();
                break;
            case SCHEMA_METADATA:
                metrics.incrementPatchImpactSchemaMetadata();
                break;
            case REFERENCE_OR_BLUE_ID:
                metrics.incrementPatchImpactReference();
                break;
            case MERGE_POLICY:
                metrics.incrementPatchImpactMergePolicy();
                break;
            case CONTRACT_OR_PROCESSING_STRUCTURE:
                metrics.incrementPatchImpactContractsOrProcessing();
                break;
            case ROOT_REPLACEMENT:
                metrics.incrementPatchImpactRootReplacement();
                break;
            case UNKNOWN:
            default:
                metrics.incrementPatchImpactUnknown();
                break;
        }
    }

    private List<String> ancestorPaths(ParsedJsonPointer path) {
        List<String> paths = new ArrayList<>(path.depth());
        for (int depth = 0; depth < path.depth(); depth++) {
            paths.add(pointer(path.segments(), depth));
        }
        return paths;
    }

    private String pointer(List<String> segments, int depth) {
        return JsonPointer.toPointer(segments.subList(0, depth));
    }

    private FrozenNode read(FrozenNode root, String path) {
        return root == null ? null : ImmutablePatchPlanner.forFrozen(root).read(path);
    }

    private boolean parentIsList(FrozenNode root, ParsedJsonPointer path) {
        if (root == null || path.isRoot()) {
            return false;
        }
        FrozenNode parent = read(root, path.parent().pointer());
        return parent != null && parent.hasItems();
    }

    private boolean hasTypeMetadata(FrozenNode node) {
        return node != null
                && (node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null);
    }

    private boolean hasResolutionMetadata(FrozenNode node) {
        return node != null
                && (hasTypeMetadata(node)
                || node.getSchema() != null
                || node.getMergePolicy() != null
                || node.getReferenceBlueId() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null
                || node.getBlue() != null
                || node.isInlineValue());
    }

    private boolean hasSchemaDependency(FrozenNode node) {
        if (node == null) {
            return false;
        }
        if (node.getSchema() != null) {
            return true;
        }
        FrozenNode type = node.getType();
        return type != null && type.getSchema() != null;
    }

    private boolean hasReferenceDependency(FrozenNode node) {
        return node != null
                && (node.isReferenceOnly()
                || node.getPreviousBlueId() != null
                || node.getBlue() != null);
    }

    private boolean hasEmptyContractsNormalizationDependency(FrozenNode node) {
        return node != null
                && node.getContracts() != null
                && node.getContracts().isEmptyNode();
    }

    private boolean isCollectionBoundary(FrozenNode node) {
        return node != null
                && (node.hasItems()
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getMergePolicy() != null);
    }

    private boolean sameStructure(FrozenNode left, FrozenNode right) {
        return left == right || left != null && left.sameResolvedStructure(right);
    }

    private boolean sameBasicScalar(FrozenNode left,
                                    FrozenNode right,
                                    String expectedBasicTypeBlueId) {
        return PatchImpact.isValueOnlyScalar(left)
                && PatchImpact.isValueOnlyScalar(right)
                && Objects.equals(left.getValue(), right.getValue())
                && expectedBasicTypeBlueId.equals(effectiveBasicTypeBlueId(left))
                && expectedBasicTypeBlueId.equals(effectiveBasicTypeBlueId(right));
    }

    private String effectiveBasicTypeBlueId(FrozenNode node) {
        FrozenNode type = node.getType();
        return type != null ? type.getReferenceBlueId() : PatchImpact.inferredTypeBlueId(node.getValue());
    }

    private boolean isPlainValue(FrozenNode node, Map<FrozenNode, Boolean> visited) {
        if (node == null || visited.put(node, Boolean.TRUE) != null) {
            return false;
        }
        if (node.getBlue() != null
                || node.getReferenceBlueId() != null
                || node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null
                || node.getName() != null
                || node.getDescription() != null
                || node.getSchema() != null
                || node.getContracts() != null
                || node.getMergePolicy() != null
                || node.isInlineValue()) {
            return false;
        }
        if (node.getItems() != null) {
            for (FrozenNode item : node.getItems()) {
                if (!isPlainValue(item, visited)) {
                    return false;
                }
            }
        }
        if (node.getProperties() != null) {
            for (FrozenNode property : node.getProperties().values()) {
                if (!isPlainValue(property, visited)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean containsSegment(ParsedJsonPointer path, String segment) {
        return path.segments().contains(segment);
    }

    private boolean containsAnySegment(ParsedJsonPointer path, String... segments) {
        for (String segment : segments) {
            if (containsSegment(path, segment)) {
                return true;
            }
        }
        return false;
    }

    private static final class Decision {
        private final boolean local;
        private final PatchImpact.FallbackReason reason;

        private Decision(boolean local, PatchImpact.FallbackReason reason) {
            this.local = local;
            this.reason = reason;
        }

        private static Decision local() {
            return new Decision(true, null);
        }

        private static Decision fallback(PatchImpact.FallbackReason reason) {
            return new Decision(false, Objects.requireNonNull(reason, "reason"));
        }
    }

    private static final class TypeObservation {
        private final boolean observes;
        private final boolean valueFreeBasicMetadata;
        private final String basicTypeBlueId;
        private final boolean schemaDependency;
        private final boolean siblingDependency;

        private TypeObservation(boolean observes,
                                boolean valueFreeBasicMetadata,
                                String basicTypeBlueId,
                                boolean schemaDependency,
                                boolean siblingDependency) {
            this.observes = observes;
            this.valueFreeBasicMetadata = valueFreeBasicMetadata;
            this.basicTypeBlueId = basicTypeBlueId;
            this.schemaDependency = schemaDependency;
            this.siblingDependency = siblingDependency;
        }

        private static TypeObservation none() {
            return new TypeObservation(false, false, null, false, false);
        }

        private static TypeObservation safeBasic(String basicTypeBlueId) {
            return new TypeObservation(true,
                    true,
                    Objects.requireNonNull(basicTypeBlueId, "basicTypeBlueId"),
                    false,
                    false);
        }

        private static TypeObservation unsafe(boolean schemaDependency,
                                              boolean siblingDependency) {
            return new TypeObservation(true, false, null, schemaDependency, siblingDependency);
        }

        private TypeObservation merge(TypeObservation other) {
            if (!observes) {
                return other;
            }
            if (!other.observes) {
                return this;
            }
            boolean sameBasicType = valueFreeBasicMetadata
                    && other.valueFreeBasicMetadata
                    && basicTypeBlueId.equals(other.basicTypeBlueId);
            return new TypeObservation(true,
                    sameBasicType,
                    sameBasicType ? basicTypeBlueId : null,
                    schemaDependency || other.schemaDependency,
                    siblingDependency || other.siblingDependency);
        }
    }
}
