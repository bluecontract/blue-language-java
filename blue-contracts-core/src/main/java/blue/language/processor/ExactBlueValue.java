package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.BlueIds;

import java.util.Objects;

/**
 * Immutable exact Blue value admitted by a processor-owned semantic boundary.
 *
 * <p>The frozen value and BlueId are inseparable. An internal owner token
 * prevents an admission from being replayed as already-metered work in a
 * different invocation; public accessors expose no token or mutable node.</p>
 */
public final class ExactBlueValue {

    private final FrozenNode value;
    private final String blueId;
    private final Object admissionOwner;

    ExactBlueValue(FrozenNode value, String blueId) {
        this(value, blueId, null);
    }

    ExactBlueValue(FrozenNode value,
                   String blueId,
                   Object admissionOwner) {
        this.value = Objects.requireNonNull(value, BlueLanguageConstants.OBJECT_VALUE);
        this.blueId = Objects.requireNonNull(blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
        this.admissionOwner = admissionOwner;
    }

    /**
     * Returns the immutable exact value without materializing a mutable tree.
     *
     * @return invocation-admitted frozen value
     */
    public FrozenNode frozenValue() {
        return value;
    }

    /**
     * Materializes a detached mutable copy of the admitted value.
     *
     * @return newly materialized node
     */
    public Node toNode() {
        return value.toNode();
    }

    /**
     * Returns the identity proved at admission time.
     *
     * @return exact BlueId of the value
     */
    public String blueId() {
        return blueId;
    }

    /**
     * Reports whether the identity names a member of a cyclic BlueId set.
     *
     * @return {@code true} when the BlueId includes a cyclic-member fragment
     */
    public boolean isCyclicMember() {
        return BlueIds.hasCyclicMemberSeparator(blueId);
    }

    boolean belongsTo(Object owner) {
        return admissionOwner != null
                && admissionOwner == owner;
    }
}
