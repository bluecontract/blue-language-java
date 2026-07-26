package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

/**
 * Domain-bound checkpoint entry for one raw External Channel key.
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
        return subject != null ? subject.getBlueId() : null;
    }
}
