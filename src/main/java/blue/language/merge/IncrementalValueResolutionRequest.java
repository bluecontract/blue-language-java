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

    public String originScope() {
        return originScope;
    }

    public String changedPath() {
        return changedPath;
    }

    public String operation() {
        return operation;
    }

    public FrozenNode canonicalBefore() {
        return canonicalBefore;
    }

    public FrozenNode canonicalAfter() {
        return canonicalAfter;
    }

    public FrozenNode resolvedBefore() {
        return resolvedBefore;
    }

    public FrozenNode resolvedAfter() {
        return resolvedAfter;
    }

    public List<String> affectedTypedBoundaries() {
        return affectedTypedBoundaries;
    }

    public boolean typeMetadataChange() {
        return typeMetadataChange;
    }

    public boolean schemaMetadataChange() {
        return schemaMetadataChange;
    }

    public boolean referenceChange() {
        return referenceChange;
    }

    public boolean listShapeChange() {
        return listShapeChange;
    }

    public boolean contractsOrProcessingChange() {
        return contractsOrProcessingChange;
    }
}
