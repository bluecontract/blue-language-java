package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.identity.DirectBlueIdCalculator;

/**
 * Domain-bound checkpoint entry for one raw External Channel key.
 *
 * <p>The domain is stored as an exact node or BlueId reference. The subject is an
 * exact checkpoint-subject node and may be either a pure reference or inline
 * content such as a minimal Timeline ordering tuple. Accessors defensively
 * copy both values, and their BlueId accessors return exact identity in
 * either representation.</p>
 *
 * <p>The entry itself is mutable. Domain and subject inputs are cloned on
 * assignment, and getters return fresh clones, so subsequent node mutation
 * cannot alter the stored checkpoint.</p>
 */
@TypeBlueId(RuntimeBlueIds.CHECKPOINT_ENTRY)
public final class CheckpointEntry {

    private Node domain;
    private Node subject;

    /** Creates an empty checkpoint entry. */
    public CheckpointEntry() {
    }

    /**
     * Returns a defensive copy of the exact checkpoint-domain node.
     *
     * @return copied domain node, or {@code null} when absent
     */
    public Node getDomain() {
        return domain != null ? domain.clone() : null;
    }

    /**
     * Stores a defensive copy of the checkpoint-domain node.
     *
     * @param domain exact domain node or reference to copy, or {@code null} to clear it
     * @return this entry
     */
    public CheckpointEntry domain(Node domain) {
        this.domain = domain != null ? domain.clone() : null;
        return this;
    }

    /**
     * Returns a defensive copy of the exact checkpoint subject.
     *
     * @return copied subject node, or {@code null} when absent
     */
    public Node getSubject() {
        return subject != null ? subject.clone() : null;
    }

    /**
     * Stores a defensive copy of the exact checkpoint subject.
     *
     * @param subject checkpoint subject to copy, or {@code null} to clear it
     * @return this entry
     */
    public CheckpointEntry subject(Node subject) {
        this.subject = subject != null ? subject.clone() : null;
        return this;
    }

    /**
     * Calculates the exact identity of the stored domain in either representation.
     *
     * @return domain BlueId, or {@code null} when no domain is stored
     */
    public String domainBlueId() {
        return domain != null
                ? DirectBlueIdCalculator.calculateBlueId(domain)
                : null;
    }

    /**
     * Calculates the exact identity of the stored subject.
     *
     * @return subject BlueId, or {@code null} when no subject is stored
     */
    public String subjectBlueId() {
        return subject != null
                ? DirectBlueIdCalculator.calculateBlueId(
                        subject)
                : null;
    }
}
