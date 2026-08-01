package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Exact source provenance for one effective executable-contract body.
 *
 * <p>The descriptor is deliberately out of band: it preserves the effective
 * body's exact identity and the Source contribution that owns it without
 * manufacturing an identity for the merged effective contract. The source
 * pointer is an RFC 6901 JSON Pointer relative to the owning contribution.</p>
 */
public final class ExecutableBodySourceDescriptor {

    private final String scopePath;
    private final String contractKey;
    private final String effectiveTypeBlueId;
    private final String bodyField;
    private final String bodyNodeBlueId;
    private final List<String> sourceContributionNodeBlueIds;
    private final String owningSourceContributionNodeBlueId;
    private final String sourcePointer;
    private final boolean pureReference;

    ExecutableBodySourceDescriptor(
            String scopePath,
            String contractKey,
            String effectiveTypeBlueId,
            String bodyField,
            String bodyNodeBlueId,
            List<String> sourceContributionNodeBlueIds,
            String owningSourceContributionNodeBlueId,
            String sourcePointer,
            boolean pureReference) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.contractKey = Objects.requireNonNull(contractKey, "contractKey");
        this.effectiveTypeBlueId =
                Objects.requireNonNull(effectiveTypeBlueId, "effectiveTypeBlueId");
        this.bodyField = Objects.requireNonNull(bodyField, "bodyField");
        this.bodyNodeBlueId =
                Objects.requireNonNull(bodyNodeBlueId, "bodyNodeBlueId");
        this.sourceContributionNodeBlueIds =
                Collections.unmodifiableList(
                        new ArrayList<>(
                                Objects.requireNonNull(
                                        sourceContributionNodeBlueIds,
                                        "sourceContributionNodeBlueIds")));
        this.owningSourceContributionNodeBlueId =
                Objects.requireNonNull(
                        owningSourceContributionNodeBlueId,
                        "owningSourceContributionNodeBlueId");
        this.sourcePointer =
                Objects.requireNonNull(sourcePointer, "sourcePointer");
        this.pureReference = pureReference;
    }

    /**
     * Returns the absolute processing scope that owns the contract.
     *
     * @return normalized scope path
     */
    public String scopePath() {
        return scopePath;
    }

    /**
     * Returns the raw contract key within the owning scope.
     *
     * @return contract key
     */
    public String contractKey() {
        return contractKey;
    }

    /**
     * Returns the exact effective runtime type used for dispatch.
     *
     * @return effective type BlueId
     */
    public String effectiveTypeBlueId() {
        return effectiveTypeBlueId;
    }

    /**
     * Returns the direct contract field selected as executable content.
     *
     * @return executable-body field name
     */
    public String bodyField() {
        return bodyField;
    }

    /**
     * Exact identity retained by the effective executable body.
     *
     * @return exact body BlueId
     */
    public String bodyNodeBlueId() {
        return bodyNodeBlueId;
    }

    /**
     * Ancestor-to-descendant Source identities for the effective contract.
     *
     * @return immutable ordered Source contribution identities
     */
    public List<String> sourceContributionNodeBlueIds() {
        return sourceContributionNodeBlueIds;
    }

    /**
     * Exact Source contribution whose field supplies the effective body.
     *
     * @return owning Source contribution BlueId
     */
    public String owningSourceContributionNodeBlueId() {
        return owningSourceContributionNodeBlueId;
    }

    /**
     * RFC 6901 pointer to the body inside the owning contribution.
     *
     * @return source-relative body pointer
     */
    public String sourcePointer() {
        return sourcePointer;
    }

    /**
     * Whether the owning contribution stores the body as a pure reference.
     *
     * @return {@code true} for a pure-reference body field
     */
    public boolean pureReference() {
        return pureReference;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExecutableBodySourceDescriptor)) {
            return false;
        }
        ExecutableBodySourceDescriptor that =
                (ExecutableBodySourceDescriptor) other;
        return pureReference == that.pureReference
                && scopePath.equals(that.scopePath)
                && contractKey.equals(that.contractKey)
                && effectiveTypeBlueId.equals(
                        that.effectiveTypeBlueId)
                && bodyField.equals(that.bodyField)
                && bodyNodeBlueId.equals(
                        that.bodyNodeBlueId)
                && sourceContributionNodeBlueIds.equals(
                        that.sourceContributionNodeBlueIds)
                && owningSourceContributionNodeBlueId.equals(
                        that.owningSourceContributionNodeBlueId)
                && sourcePointer.equals(that.sourcePointer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                scopePath,
                contractKey,
                effectiveTypeBlueId,
                bodyField,
                bodyNodeBlueId,
                sourceContributionNodeBlueIds,
                owningSourceContributionNodeBlueId,
                sourcePointer,
                pureReference);
    }
}
