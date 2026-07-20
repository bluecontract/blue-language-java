package blue.language.processor;

import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.ParsedJsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static blue.language.utils.Properties.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.utils.Properties.DOUBLE_TYPE_BLUE_ID;
import static blue.language.utils.Properties.INTEGER_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;

/** Immutable evidence describing which semantic region one patch can affect. */
final class PatchImpact {

    enum Kind {
        VALUE_ONLY,
        OBJECT_MEMBER_VALUE,
        COLLECTION_SHAPE,
        TYPE_METADATA,
        SCHEMA_METADATA,
        REFERENCE_OR_BLUE_ID,
        MERGE_POLICY,
        PROCESSOR_MANAGED_STATE,
        CONTRACT_OR_PROCESSING_STRUCTURE,
        ROOT_REPLACEMENT,
        UNKNOWN
    }

    enum Shape {
        MISSING,
        SCALAR,
        OBJECT,
        LIST,
        REFERENCE,
        METADATA_OR_MIXED
    }

    enum FallbackReason {
        CUSTOM_MERGING_PROCESSOR,
        UNKNOWN_PROCESSOR_CAPABILITY,
        ROOT_REPLACEMENT,
        CONTRACTS_CHANGED,
        TYPE_GRAPH_CHANGED,
        SCHEMA_GRAPH_CHANGED,
        MERGE_POLICY_CHANGED,
        REFERENCE_CYCLE_CHANGED,
        UNBOUNDED_SIBLING_DEPENDENCY,
        LIST_CONTROL_STRUCTURE_CHANGED,
        STRICT_VERIFICATION_EVIDENCE_UNAVAILABLE,
        DEPENDENCY_INDEX_MISSING_OR_STALE,
        CUSTOM_CONFORMANCE_PLANNER,
        NON_REPLACE_OPERATION,
        NON_LEAF_VALUE,
        RESOLVED_VALUE_DIFFERS,
        UNKNOWN
    }

    private final Kind kind;
    private final ParsedJsonPointer path;
    private final JsonPatch.Op operation;
    private final Shape beforeShape;
    private final Shape afterShape;
    private final String changedSubtreeRoot;
    private final List<String> ancestorChain;
    private final List<String> affectedTypedBoundaries;
    private final String nearestCollectionBoundary;
    private final boolean typeDependency;
    private final boolean schemaDependency;
    private final boolean referenceDependency;
    private final boolean siblingValuesInfluenceValidation;
    private final boolean listPositionOrIdentityCanChange;
    private final boolean contractsOrProcessorRoutingCanChange;
    private final boolean localResolutionProvenSafe;
    private final boolean resolvedScalarMetadataPreservationRequired;
    private final FallbackReason fallbackReason;

    PatchImpact(Kind kind,
                ParsedJsonPointer path,
                JsonPatch.Op operation,
                FrozenNode before,
                FrozenNode after,
                String changedSubtreeRoot,
                List<String> ancestorChain,
                List<String> affectedTypedBoundaries,
                String nearestCollectionBoundary,
                boolean typeDependency,
                boolean schemaDependency,
                boolean referenceDependency,
                boolean siblingValuesInfluenceValidation,
                boolean listPositionOrIdentityCanChange,
                boolean contractsOrProcessorRoutingCanChange,
                boolean localResolutionProvenSafe,
                boolean resolvedScalarMetadataPreservationRequired,
                FallbackReason fallbackReason) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.path = Objects.requireNonNull(path, "path");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.beforeShape = shapeOf(before);
        this.afterShape = shapeOf(after);
        this.changedSubtreeRoot = Objects.requireNonNull(changedSubtreeRoot, "changedSubtreeRoot");
        this.ancestorChain = immutableCopy(ancestorChain);
        this.affectedTypedBoundaries = immutableCopy(affectedTypedBoundaries);
        this.nearestCollectionBoundary = nearestCollectionBoundary;
        this.typeDependency = typeDependency;
        this.schemaDependency = schemaDependency;
        this.referenceDependency = referenceDependency;
        this.siblingValuesInfluenceValidation = siblingValuesInfluenceValidation;
        this.listPositionOrIdentityCanChange = listPositionOrIdentityCanChange;
        this.contractsOrProcessorRoutingCanChange = contractsOrProcessorRoutingCanChange;
        this.localResolutionProvenSafe = localResolutionProvenSafe;
        this.resolvedScalarMetadataPreservationRequired =
                resolvedScalarMetadataPreservationRequired;
        this.fallbackReason = localResolutionProvenSafe
                ? null
                : Objects.requireNonNull(fallbackReason, "fallbackReason");
    }

    private static List<String> immutableCopy(List<String> source) {
        return Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(source, "source")));
    }

    private static Shape shapeOf(FrozenNode node) {
        if (node == null) {
            return Shape.MISSING;
        }
        if (node.isReferenceOnly()) {
            return Shape.REFERENCE;
        }
        if (node.hasItems()) {
            return Shape.LIST;
        }
        if (isScalarPayload(node)) {
            return Shape.SCALAR;
        }
        if (node.hasProperties() && !hasMetadata(node)) {
            return Shape.OBJECT;
        }
        return Shape.METADATA_OR_MIXED;
    }

    private static boolean hasMetadata(FrozenNode node) {
        return node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getContracts() != null
                || node.getReferenceBlueId() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null
                || node.getBlue() != null
                || node.isInlineValue();
    }

    /** Accepts either a raw scalar or its exact canonical inferred-basic-type wrapper. */
    static boolean isValueOnlyScalar(FrozenNode node) {
        if (node == null || node.getValue() == null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getContracts() != null
                || node.getReferenceBlueId() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null
                || node.getBlue() != null
                || node.isInlineValue()
                || node.hasItems()
                || node.hasProperties()
                || node.getName() != null
                || node.getDescription() != null) {
            return false;
        }
        FrozenNode type = node.getType();
        if (type == null) {
            return true;
        }
        String expected = inferredTypeBlueId(node.getValue());
        return expected != null
                && type.isReferenceOnly()
                && expected.equals(type.getReferenceBlueId());
    }

    static boolean isScalarPayload(FrozenNode node) {
        return node != null
                && node.getValue() != null
                && !node.hasItems()
                && !node.hasProperties()
                && !node.isReferenceOnly();
    }

    static String inferredTypeBlueId(Object value) {
        if (value instanceof String) {
            return TEXT_TYPE_BLUE_ID;
        }
        if (value instanceof java.math.BigInteger) {
            return INTEGER_TYPE_BLUE_ID;
        }
        if (value instanceof java.math.BigDecimal) {
            return DOUBLE_TYPE_BLUE_ID;
        }
        if (value instanceof Boolean) {
            return BOOLEAN_TYPE_BLUE_ID;
        }
        return null;
    }

    Kind kind() {
        return kind;
    }

    ParsedJsonPointer path() {
        return path;
    }

    JsonPatch.Op operation() {
        return operation;
    }

    Shape beforeShape() {
        return beforeShape;
    }

    Shape afterShape() {
        return afterShape;
    }

    String changedSubtreeRoot() {
        return changedSubtreeRoot;
    }

    List<String> ancestorChain() {
        return ancestorChain;
    }

    List<String> affectedTypedBoundaries() {
        return affectedTypedBoundaries;
    }

    String nearestCollectionBoundary() {
        return nearestCollectionBoundary;
    }

    boolean typeDependency() {
        return typeDependency;
    }

    boolean schemaDependency() {
        return schemaDependency;
    }

    boolean referenceDependency() {
        return referenceDependency;
    }

    boolean siblingValuesInfluenceValidation() {
        return siblingValuesInfluenceValidation;
    }

    boolean listPositionOrIdentityCanChange() {
        return listPositionOrIdentityCanChange;
    }

    boolean contractsOrProcessorRoutingCanChange() {
        return contractsOrProcessorRoutingCanChange;
    }

    boolean localResolutionProvenSafe() {
        return localResolutionProvenSafe;
    }

    boolean resolvedScalarMetadataPreservationRequired() {
        return resolvedScalarMetadataPreservationRequired;
    }

    FallbackReason fallbackReason() {
        return fallbackReason;
    }
}
