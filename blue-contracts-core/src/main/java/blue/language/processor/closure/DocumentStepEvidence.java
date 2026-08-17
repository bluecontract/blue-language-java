package blue.language.processor.closure;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Exact conformance projection proving one work used one isolated Root. */
public final class DocumentStepEvidence {

    private final long stepOrdinal;
    private final long workOrdinal;
    private final DocumentId targetDocumentId;

    /**
     * Creates the canonical evidence projection from an admitted input.
     *
     * @param input exact isolated document-step input
     */
    public DocumentStepEvidence(DocumentStepInput input) {
        DocumentStepInput admitted = Objects.requireNonNull(input, "input");
        this.stepOrdinal = admitted.stepOrdinal();
        this.workOrdinal = admitted.work().ordinal();
        this.targetDocumentId = admitted.work().targetDocumentId();
    }

    /** Returns the contiguous step ordinal.
     * @return step ordinal */
    public long stepOrdinal() { return stepOrdinal; }

    /** Returns the owning accepted-work ordinal.
     * @return work ordinal */
    public long workOrdinal() { return workOrdinal; }

    /** Returns the selected managed document.
     * @return document identity */
    public DocumentId targetDocumentId() { return targetDocumentId; }

    /** Returns the one execution Root.
     * @return target document identity */
    public DocumentId executionRootDocumentId() { return targetDocumentId; }

    /** Returns the closure execution scope.
     * @return exact Root path */
    public String scopePath() { return "/"; }

    /** Returns the stable evidence discriminator.
     * @return execution mode */
    public String executionMode() { return "ISOLATED_DOCUMENT"; }

    /**
     * Returns no reverse-containment values.
     * @return an empty immutable list
     */
    public List<DocumentId> ambientContainingDocumentIds() {
        return Collections.emptyList();
    }
}
