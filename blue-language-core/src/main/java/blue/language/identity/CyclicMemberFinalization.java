package blue.language.identity;

import blue.language.model.Node;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable finalization evidence for one member of a cyclic document set.
 *
 * <p>The input index identifies the caller-supplied member without becoming
 * part of Blue identity. The canonical index is the suffix used by the final
 * {@code MASTER#index} BlueId. The retained member body contains the canonical
 * {@code this#n} remapping used to calculate the master and is returned only as
 * a defensive copy.</p>
 */
public final class CyclicMemberFinalization {

    private final int inputIndex;
    private final int canonicalIndex;
    private final String preliminaryBlueId;
    private final String finalBlueId;
    private final byte[] preliminaryCanonicalInputBytes;
    private final Node canonicalMemberBody;

    CyclicMemberFinalization(
            int inputIndex,
            int canonicalIndex,
            String preliminaryBlueId,
            String finalBlueId,
            byte[] preliminaryCanonicalInputBytes,
            Node canonicalMemberBody) {
        if (inputIndex < 0) {
            throw new IllegalArgumentException(
                    "inputIndex must be non-negative");
        }
        if (canonicalIndex < 0) {
            throw new IllegalArgumentException(
                    "canonicalIndex must be non-negative");
        }
        this.inputIndex = inputIndex;
        this.canonicalIndex = canonicalIndex;
        this.preliminaryBlueId = Objects.requireNonNull(
                preliminaryBlueId, "preliminaryBlueId");
        this.finalBlueId = Objects.requireNonNull(
                finalBlueId, "finalBlueId");
        this.preliminaryCanonicalInputBytes = Objects.requireNonNull(
                preliminaryCanonicalInputBytes,
                "preliminaryCanonicalInputBytes").clone();
        this.canonicalMemberBody = Objects.requireNonNull(
                canonicalMemberBody, "canonicalMemberBody").clone();
    }

    /**
     * Returns the zero-based position of this member in the supplied input.
     *
     * @return caller input index; never identity-bearing
     */
    public int inputIndex() {
        return inputIndex;
    }

    /**
     * Returns the member's zero-based position in canonical cyclic-set order.
     *
     * @return final {@code MASTER#index} suffix
     */
    public int canonicalIndex() {
        return canonicalIndex;
    }

    /**
     * Returns the preliminary ZERO-placeholder BlueId used for ordering.
     *
     * @return preliminary member BlueId
     */
    public String preliminaryBlueId() {
        return preliminaryBlueId;
    }

    /**
     * Returns this member's final {@code MASTER#index} BlueId.
     *
     * @return final member BlueId
     */
    public String finalBlueId() {
        return finalBlueId;
    }

    /**
     * Returns the exact canonical bytes used as the preliminary tie-break.
     *
     * @return defensive byte copy
     */
    public byte[] preliminaryCanonicalInputBytes() {
        return preliminaryCanonicalInputBytes.clone();
    }

    /**
     * Returns the canonical member body with remapped {@code this#n} edges.
     *
     * @return defensive member-body copy
     */
    public Node canonicalMemberBody() {
        return canonicalMemberBody.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CyclicMemberFinalization)) {
            return false;
        }
        CyclicMemberFinalization that =
                (CyclicMemberFinalization) other;
        return inputIndex == that.inputIndex
                && canonicalIndex == that.canonicalIndex
                && preliminaryBlueId.equals(that.preliminaryBlueId)
                && finalBlueId.equals(that.finalBlueId)
                && Arrays.equals(
                        preliminaryCanonicalInputBytes,
                        that.preliminaryCanonicalInputBytes)
                && Arrays.equals(
                        canonicalBodyBytes(),
                        that.canonicalBodyBytes());
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                inputIndex,
                canonicalIndex,
                preliminaryBlueId,
                finalBlueId);
        result = 31 * result
                + Arrays.hashCode(preliminaryCanonicalInputBytes);
        result = 31 * result
                + Arrays.hashCode(canonicalBodyBytes());
        return result;
    }

    private byte[] canonicalBodyBytes() {
        return CanonicalJsonValueWriter.write(
                new BlueIdInputNormalizer()
                        .normalizeAllowingCyclicPlaceholders(
                                canonicalMemberBody));
    }
}
