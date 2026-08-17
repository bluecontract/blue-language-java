package blue.language.processor.closure;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Tentative local effects of one isolated document step.
 *
 * <p>This value intentionally has no after-BlueId accessor or constructor
 * argument. Only the closure orchestrator may calculate an ordinary identity
 * or complete-set cyclic {@code MASTER#index} identities after applying this
 * local body.</p>
 */
public final class LocalDocumentStepResult {

    private final DocumentId documentId;
    private final String workOccurrenceIdentity;
    private final String beforeBlueId;
    private final Node resultingBody;
    private final List<Node> emittedEvents;
    private final long gasBefore;
    private final long gasAfter;
    private final boolean identityAffecting;

    /**
     * Creates one pre-finalization local result.
     *
     * @param documentId target managed document
     * @param workOccurrenceIdentity exact owning work identity
     * @param beforeBlueId exact target identity before execution
     * @param resultingBody resulting local body before identity finalization
     * @param emittedEvents ordered locally emitted events
     * @param gasBefore shared gas total before execution
     * @param gasAfter shared gas total after execution
     * @param identityAffecting whether identity reconciliation is required
     */
    public LocalDocumentStepResult(
            DocumentId documentId,
            String workOccurrenceIdentity,
            String beforeBlueId,
            Node resultingBody,
            List<Node> emittedEvents,
            long gasBefore,
            long gasAfter,
            boolean identityAffecting) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.workOccurrenceIdentity =
                ClosureValueSupport.requireSha256Identity(
                        workOccurrenceIdentity,
                        "workOccurrenceIdentity");
        this.beforeBlueId = ClosureValueSupport.requireBlueId(
                beforeBlueId, "beforeBlueId");
        this.resultingBody = Objects.requireNonNull(
                resultingBody, "resultingBody").clone();
        this.emittedEvents = immutableNodes(emittedEvents);
        this.gasBefore = ClosureValueSupport.requireSafeInteger(
                gasBefore, "gasBefore");
        this.gasAfter = ClosureValueSupport.requireSafeInteger(
                gasAfter, "gasAfter");
        if (this.gasAfter < this.gasBefore) {
            throw new IllegalArgumentException("gasAfter precedes gasBefore");
        }
        this.identityAffecting = identityAffecting;
    }

    /** Returns the target managed document.
     * @return document identity */
    public DocumentId documentId() { return documentId; }

    /** Returns the exact owning work identity.
     * @return work identity */
    public String workOccurrenceIdentity() { return workOccurrenceIdentity; }

    /** Returns the exact pre-step identity.
     * @return before BlueId */
    public String beforeBlueId() { return beforeBlueId; }

    /** Returns a defensive local body copy.
     * @return resulting body */
    public Node resultingBody() { return resultingBody.clone(); }

    /** Returns defensive emitted-event copies.
     * @return ordered events */
    public List<Node> emittedEvents() { return immutableNodes(emittedEvents); }

    /** Returns the pre-step shared gas total.
     * @return admitted gas before */
    public long gasBefore() { return gasBefore; }

    /** Returns the post-step shared gas total.
     * @return admitted gas after */
    public long gasAfter() { return gasAfter; }

    /** Reports whether identity reconciliation is required.
     * @return flag */
    public boolean identityAffecting() { return identityAffecting; }

    private static List<Node> immutableNodes(List<Node> values) {
        ArrayList<Node> copy = new ArrayList<Node>();
        for (Node value : Objects.requireNonNull(values, "emittedEvents")) {
            copy.add(Objects.requireNonNull(value, "emitted event").clone());
        }
        return Collections.unmodifiableList(copy);
    }
}
