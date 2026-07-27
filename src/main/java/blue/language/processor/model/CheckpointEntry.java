package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.utils.BlueIdCalculator;

/**
 * Domain-bound checkpoint entry for one raw External Channel key.
 *
 * <p>The domain is stored as its exact BlueId reference. The subject is an
 * exact checkpoint-subject node and may be either a pure reference or inline
 * content such as a minimal Timeline ordering tuple. Accessors defensively
 * copy the subject, and {@link #subjectBlueId()} returns its exact identity in
 * either representation.</p>
 */
@TypeBlueId(RuntimeBlueIds.CHECKPOINT_ENTRY)
public final class CheckpointEntry {

    private Node domain;
    private Node subject;

    public Node getDomain() {
        return domain != null ? domain.clone() : null;
    }

    public CheckpointEntry domain(Node domain) {
        this.domain = domain != null ? domain.clone() : null;
        return this;
    }

    public Node getSubject() {
        return subject != null ? subject.clone() : null;
    }

    public CheckpointEntry subject(Node subject) {
        this.subject = subject != null ? subject.clone() : null;
        return this;
    }

    public String domainBlueId() {
        return domain != null ? domain.getBlueId() : null;
    }

    public String subjectBlueId() {
        return subject != null
                ? BlueIdCalculator.calculateBlueId(
                        subject)
                : null;
    }
}
