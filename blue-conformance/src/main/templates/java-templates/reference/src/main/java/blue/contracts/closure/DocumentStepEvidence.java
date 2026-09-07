package blue.contracts.closure;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Exact conformance projection proving one work item used one isolated Root. */
public final class DocumentStepEvidence {
    private final long stepOrdinal;
    private final long workOrdinal;
    private final DocumentId targetDocumentId;
    private final DocumentId executionRootDocumentId;
    private final String scopePath;
    private final String executionMode;
    private final List<DocumentId> ambientContainingDocumentIds;

    public DocumentStepEvidence(DocumentStepInput input) {
        Objects.requireNonNull(input, "input");
        this.stepOrdinal = input.stepOrdinal();
        this.workOrdinal = input.workOrdinal();
        this.targetDocumentId = input.targetDocumentId();
        this.executionRootDocumentId = input.targetDocumentId();
        this.scopePath = input.scopePath();
        this.executionMode = "ISOLATED_DOCUMENT";
        this.ambientContainingDocumentIds = Collections.emptyList();
    }

    public long stepOrdinal() { return stepOrdinal; }
    public long workOrdinal() { return workOrdinal; }
    public DocumentId targetDocumentId() { return targetDocumentId; }
    public DocumentId executionRootDocumentId() {
        return executionRootDocumentId;
    }
    public String scopePath() { return scopePath; }
    public String executionMode() { return executionMode; }
    public List<DocumentId> ambientContainingDocumentIds() {
        return ambientContainingDocumentIds;
    }
}
