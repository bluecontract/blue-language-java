package blue.language.processor;

import blue.language.identity.BlueIds;
import blue.language.processor.util.PointerUtils;

import java.util.Objects;

/**
 * Immutable processor-owned evidence for one generated generalization
 * metadata write.
 *
 * <p>This is presentation evidence only. It reports a write already planned
 * and committed by the processor; it neither authorizes a write nor
 * participates in document, invocation, closure, or result identity.</p>
 */
public final class ManagedGeneralizationWrite {

    private final String path;
    private final String valueBlueId;
    private final int requiringPatchIndex;

    ManagedGeneralizationWrite(
            String path,
            String valueBlueId,
            int requiringPatchIndex) {
        this.path = PointerUtils.assertValidRuntimePointer(path);
        this.valueBlueId = BlueIds.requirePlainBlueId(
                Objects.requireNonNull(valueBlueId, "valueBlueId"),
                "/valueBlueId");
        if (requiringPatchIndex < 0) {
            throw new IllegalArgumentException(
                    "requiringPatchIndex must be non-negative");
        }
        this.requiringPatchIndex = requiringPatchIndex;
    }

    /**
     * Returns the exact generated metadata path.
     *
     * @return canonical absolute JSON Pointer
     */
    public String path() {
        return path;
    }

    /**
     * Returns the exact BlueId of the generated metadata value.
     *
     * @return plain BlueId of the committed value
     */
    public String valueBlueId() {
        return valueBlueId;
    }

    /**
     * Returns the authored patch index that required this generated write.
     *
     * @return zero-based authored patch index
     */
    public int requiringPatchIndex() {
        return requiringPatchIndex;
    }

}
