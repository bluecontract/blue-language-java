package blue.language.merge;

import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, bounded evidence for one proposed incremental value resolution.
 *
 * <p>The request deliberately exposes frozen before/after views and fixed
 * structural flags, never mutable {@code Node} graphs or processor-specific
 * implementation details.</p>
 */
public final class IncrementalValueResolutionRequest {

    private final String originScope;
    private final String changedPath;
    private final String operation;
    private final FrozenNode canonicalBefore;
    private final FrozenNode canonicalAfter;
    private final FrozenNode resolvedBefore;
    private final FrozenNode resolvedAfter;
    private final List<String> affectedTypedBoundaries;
    private final boolean typeMetadataChange;
    private final boolean schemaMetadataChange;
    private final boolean referenceChange;
    private final boolean listShapeChange;
    private final boolean contractsOrProcessingChange;

    /**
     * Creates the immutable evidence for one proposed incremental resolution.
     *
     * @param originScope absolute scope in which the change originated
     * @param changedPath canonical path changed within the origin scope
     * @param operation patch operation that produced the change
     * @param canonicalBefore canonical value before the change, or {@code null}
     * @param canonicalAfter canonical value after the change, or {@code null}
     * @param resolvedBefore resolved value before the change, or {@code null}
     * @param resolvedAfter resolved value after the change, or {@code null}
     * @param affectedTypedBoundaries ordered typed boundaries affected by the change
     * @param typeMetadataChange whether type metadata changed
     * @param schemaMetadataChange whether schema metadata changed
     * @param referenceChange whether reference identity or structure changed
     * @param listShapeChange whether list shape changed
     * @param contractsOrProcessingChange whether contracts or processing metadata changed
     */
    public IncrementalValueResolutionRequest(String originScope,
                                             String changedPath,
                                             String operation,
                                             FrozenNode canonicalBefore,
                                             FrozenNode canonicalAfter,
                                             FrozenNode resolvedBefore,
                                             FrozenNode resolvedAfter,
                                             List<String> affectedTypedBoundaries,
                                             boolean typeMetadataChange,
                                             boolean schemaMetadataChange,
                                             boolean referenceChange,
                                             boolean listShapeChange,
                                             boolean contractsOrProcessingChange) {
        this.originScope = Objects.requireNonNull(originScope, "originScope");
        this.changedPath = Objects.requireNonNull(changedPath, "changedPath");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.canonicalBefore = canonicalBefore;
        this.canonicalAfter = canonicalAfter;
        this.resolvedBefore = resolvedBefore;
        this.resolvedAfter = resolvedAfter;
        this.affectedTypedBoundaries = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(affectedTypedBoundaries, "affectedTypedBoundaries")));
        this.typeMetadataChange = typeMetadataChange;
        this.schemaMetadataChange = schemaMetadataChange;
        this.referenceChange = referenceChange;
        this.listShapeChange = listShapeChange;
        this.contractsOrProcessingChange = contractsOrProcessingChange;
    }

    /**
     * Returns the absolute scope in which the change originated.
     *
     * @return non-null origin scope
     */
    public String originScope() {
        return originScope;
    }

    /**
     * Returns the canonical path changed within the origin scope.
     *
     * @return non-null changed path
     */
    public String changedPath() {
        return changedPath;
    }

    /**
     * Returns the patch operation that produced the change.
     *
     * @return non-null operation name
     */
    public String operation() {
        return operation;
    }

    /**
     * Returns the canonical value before the change.
     *
     * @return immutable prior canonical value, or {@code null}
     */
    public FrozenNode canonicalBefore() {
        return canonicalBefore;
    }

    /**
     * Returns the canonical value after the change.
     *
     * @return immutable resulting canonical value, or {@code null}
     */
    public FrozenNode canonicalAfter() {
        return canonicalAfter;
    }

    /**
     * Returns the resolved value before the change.
     *
     * @return immutable prior resolved value, or {@code null}
     */
    public FrozenNode resolvedBefore() {
        return resolvedBefore;
    }

    /**
     * Returns the resolved value after the change.
     *
     * @return immutable resulting resolved value, or {@code null}
     */
    public FrozenNode resolvedAfter() {
        return resolvedAfter;
    }

    /**
     * Returns the typed boundaries affected by the change.
     *
     * @return immutable ordered boundary paths
     */
    public List<String> affectedTypedBoundaries() {
        return affectedTypedBoundaries;
    }

    /**
     * Reports whether the change modifies type metadata.
     *
     * @return {@code true} when type metadata changes
     */
    public boolean typeMetadataChange() {
        return typeMetadataChange;
    }

    /**
     * Reports whether the change modifies schema metadata.
     *
     * @return {@code true} when schema metadata changes
     */
    public boolean schemaMetadataChange() {
        return schemaMetadataChange;
    }

    /**
     * Reports whether the change modifies reference identity or structure.
     *
     * @return {@code true} when a reference changes
     */
    public boolean referenceChange() {
        return referenceChange;
    }

    /**
     * Reports whether the change modifies list shape.
     *
     * @return {@code true} when list shape changes
     */
    public boolean listShapeChange() {
        return listShapeChange;
    }

    /**
     * Reports whether the change modifies contracts or processing metadata.
     *
     * @return {@code true} when contracts or processing metadata changes
     */
    public boolean contractsOrProcessingChange() {
        return contractsOrProcessingChange;
    }
}
